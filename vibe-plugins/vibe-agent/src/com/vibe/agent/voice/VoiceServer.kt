// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.voice

import com.intellij.openapi.application.ApplicationManager
import com.vibe.agent.watch.WatchTools
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * A resident `whisper-server` (whisper.cpp) on 127.0.0.1, so a dictation is transcribed while it is
 * being recorded (decision №84).
 *
 * A live transcript needs a model that stays loaded and takes the audio in pieces. Re-running a
 * one-shot transcriber every few seconds loads the model anew each time; `whisper-stream` opens the
 * microphone itself, around decision №42. `whisper-server` keeps the model in memory and takes OUR
 * capture over HTTP — measured on this machine with ggml-base-q5_1: ready in about half a second,
 * a 4.5-second Russian phrase answered in 0.66 s.
 *
 * The process and its protocol, with no IDE types, so the real server can be driven from a test.
 * One per IDE lives in [VoiceServerService], which also stops it with the IDE: the model is hundreds
 * of megabytes, and two projects dictating do not need two copies. Started on the first recording,
 * restarted when the model or the language changes. Listens on the loopback interface only.
 */
class VoiceServer {
  /** What the server runs with; a different config means a restart. */
  data class Config(val binary: String, val model: String, val language: String)

  private var process: Process? = null
  private var port = 0
  private var running: Config? = null

  /**
   * The transcript of [wav], starting or restarting the server when needed. Blocking, one request
   * at a time: the live transcript skips a beat rather than queueing behind itself.
   */
  @Synchronized
  fun transcribe(wav: ByteArray, config: Config, timeoutMs: Long): String? {
    if (running != config || process?.isAlive != true) start(config)
    return post(port, wav, timeoutMs)
  }

  @Synchronized
  private fun start(config: Config) {
    stop()
    val chosen = freePort()
    val started = ProcessBuilder(command(config, chosen))
      .redirectErrorStream(true)
      .redirectOutput(ProcessBuilder.Redirect.DISCARD)
      .start()
    val deadline = System.currentTimeMillis() + READY_TIMEOUT_MS
    while (System.currentTimeMillis() < deadline) {
      if (!started.isAlive) throw IllegalStateException("whisper-server exited with code ${started.exitValue()}")
      if (answers(chosen)) {
        process = started
        port = chosen
        running = config
        return
      }
      Thread.sleep(READY_POLL_MS)
    }
    started.destroyForcibly()
    throw IllegalStateException("whisper-server did not answer in ${READY_TIMEOUT_MS / 1000} s")
  }

  @Synchronized
  fun stop() {
    process?.let { p ->
      p.destroy()
      if (!p.waitFor(STOP_WAIT_MS, TimeUnit.MILLISECONDS)) p.destroyForcibly()
    }
    process = null
    running = null
  }

  companion object {
    const val BINARY = "whisper-server"
    private const val HOST = "127.0.0.1"

    /** How often the live transcript asks; a beat shorter makes the preview flicker, longer — lag. */
    const val PREVIEW_INTERVAL_MS = 2_500L

    /**
     * Past this length of a recording the live transcript stops: each preview transcribes the whole
     * recording again, and the final one comes at «стоп» anyway.
     */
    const val PREVIEW_MAX_MS = 90_000L

    /** A preview answer that takes longer is no longer a preview. */
    const val PREVIEW_TIMEOUT_MS = 15_000L

    /** The final transcript of a five-minute note on a large model is allowed its time. */
    const val FINAL_TIMEOUT_MS = 120_000L

    /** A large model loads for seconds; the server answers only after that. */
    private const val READY_TIMEOUT_MS = 30_000L
    private const val READY_POLL_MS = 200L
    private const val STOP_WAIT_MS = 2_000L
    private const val PROBE_TIMEOUT_MS = 1_000L

    private val json = Json { ignoreUnknownKeys = true }

    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(PROBE_TIMEOUT_MS)).build()

    /** The one server of this IDE, held by [VoiceServerService]. */
    fun getInstance(): VoiceServer = ApplicationManager.getApplication().getService(VoiceServerService::class.java).server

    /** The config a live transcript can run with, or null: no `whisper-server`, or no model file set. */
    fun configOf(
      modelPath: String?,
      language: String?,
      lookup: (String) -> String? = WatchTools::find,
      hasFile: (String) -> Boolean = { File(it).isFile },
    ): Config? {
      val model = modelPath?.trim()?.takeIf { it.isNotEmpty() && hasFile(it) } ?: return null
      val binary = lookup(BINARY) ?: return null
      return Config(binary, model, language?.trim()?.takeIf { it.isNotEmpty() } ?: VoiceTranscription.AUTO_LANGUAGE)
    }

    /** Loopback only: a microphone transcript is nobody else's business. */
    fun command(config: Config, port: Int): List<String> = listOf(
      config.binary, "-m", config.model, "--host", HOST, "--port", port.toString(), "-nt", "-l", config.language,
    )

    /** The `/inference` form: the WAV as `file`, JSON back, deterministic decoding. */
    fun multipart(boundary: String, wav: ByteArray): ByteArray {
      val out = ByteArrayOutputStream()
      fun text(value: String) = out.write(value.toByteArray(Charsets.UTF_8))
      for ((name, value) in listOf("response_format" to "json", "temperature" to "0.0")) {
        text("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n")
      }
      text("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"voice.wav\"\r\nContent-Type: audio/wav\r\n\r\n")
      out.write(wav)
      text("\r\n--$boundary--\r\n")
      return out.toByteArray()
    }

    /** `{"text": " …\n"}` → the text; null when the answer is not that. */
    fun textOf(body: String): String? =
      runCatching { ((json.parseToJsonElement(body) as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull?.trim() }.getOrNull()

    /** One transcription request to a server on [port]. Throws on anything but a 200. */
    fun post(port: Int, wav: ByteArray, timeoutMs: Long): String? {
      val boundary = "vibe-" + UUID.randomUUID()
      val request = HttpRequest.newBuilder(URI.create("http://$HOST:$port/inference"))
        .timeout(Duration.ofMillis(timeoutMs))
        .header("Content-Type", "multipart/form-data; boundary=$boundary")
        .POST(HttpRequest.BodyPublishers.ofByteArray(multipart(boundary, wav)))
        .build()
      val response = http.send(request, HttpResponse.BodyHandlers.ofString())
      if (response.statusCode() != HTTP_OK) throw IllegalStateException("whisper-server answered ${response.statusCode()}: ${response.body().take(ERROR_CHARS)}")
      return textOf(response.body())
    }

    /** The server serves a page at `/` once the model is loaded — there is no health endpoint. */
    fun answers(port: Int): Boolean = runCatching {
      val request = HttpRequest.newBuilder(URI.create("http://$HOST:$port/")).timeout(Duration.ofMillis(PROBE_TIMEOUT_MS)).GET().build()
      http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == HTTP_OK
    }.getOrDefault(false)

    private fun freePort(): Int = ServerSocket(0, 0, InetAddress.getLoopbackAddress()).use { it.localPort }

    private const val HTTP_OK = 200
    private const val ERROR_CHARS = 200
  }
}
