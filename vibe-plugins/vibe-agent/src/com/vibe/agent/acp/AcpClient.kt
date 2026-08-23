// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.acp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Minimal Agent Client Protocol client: JSON-RPC 2.0, newline-delimited JSON over stdio.
 * The agent runs as a local subprocess started WITHOUT a shell (command + args list) —
 * VibeIDE contract. Incoming traffic is data, never instructions for the IDE itself.
 */
class AcpClient(
  private val config: AgentServerConfig,
  private val workingDir: String?,
  private val handler: Handler,
) {
  interface Handler {
    fun onSessionUpdate(update: JsonObject)
    /** Agent switched the session mode (`current_mode_update`); called on the reader thread before [onSessionUpdate]. */
    fun onModeChanged(modeId: String) {}
    /** Called on the reader thread; must return the permission outcome (closed dialog = refusal). */
    fun onRequestPermission(params: JsonObject): JsonElement
    fun onReadTextFile(params: JsonObject): JsonElement
    fun onWriteTextFile(params: JsonObject): JsonElement
    fun onProtocolLog(line: String)
    /** The process of [client] ended on its own; deliberate [stop] calls do not report. */
    fun onProcessExit(client: AcpClient, code: Int)
  }

  private val json = Json { ignoreUnknownKeys = true }
  private var process: Process? = null
  private var writer: BufferedWriter? = null
  private val nextId = AtomicLong(1)
  private val pending = ConcurrentHashMap<Long, CompletableFuture<JsonElement>>()

  @Volatile var sessionId: String? = null
    private set

  /** Parsed from the `initialize` result; null until initialized or after [stop]. */
  @Volatile var capabilities: AgentCapabilities? = null
    private set

  /** Parsed from the `session/new` result; null when the agent reports no modes or after [stop]. */
  @Volatile var modes: SessionModes? = null
    private set

  /** Set by [stop] before the process is destroyed so the exit thread stays quiet. */
  @Volatile private var stopped = false

  val isAlive: Boolean get() = process?.isAlive == true

  fun start() {
    check(process == null) { "already started" }
    val cmd = ArrayList<String>()
    cmd.add(resolveBinary(config.command))
    cmd.addAll(config.args)
    val pb = ProcessBuilder(cmd)
    workingDir?.let { pb.directory(File(it)) }
    // GUI apps on macOS do not inherit the shell PATH — extend it with well-known dirs.
    val path = (pb.environment()["PATH"] ?: "") + File.pathSeparator + EXTRA_PATH
    pb.environment()["PATH"] = path
    pb.environment().putAll(config.env)
    val p = pb.start()
    process = p
    writer = p.outputStream.bufferedWriter()
    Thread({ readLoop(p.inputStream.bufferedReader()) }, "vibe-acp-reader").apply { isDaemon = true }.start()
    Thread({ p.errorStream.bufferedReader().forEachLine { handler.onProtocolLog("[stderr] $it") } }, "vibe-acp-stderr")
      .apply { isDaemon = true }.start()
    Thread({
      val code = p.waitFor()
      // Nobody will answer the in-flight requests any more: fail them so callers unblock.
      failPending("agent process exited (code $code)")
      if (!stopped) handler.onProcessExit(this, code)
    }, "vibe-acp-exit").apply { isDaemon = true }.start()
  }

  fun stop() {
    stopped = true
    process?.destroy()
    process = null
    sessionId = null
    capabilities = null
    modes = null
    failPending("agent stopped")
  }

  private fun failPending(reason: String) {
    val stale = pending.values.toList()
    pending.clear()
    stale.forEach { it.completeExceptionally(IllegalStateException(reason)) }
  }

  fun initializeAndOpenSession(): CompletableFuture<String> {
    val init = buildJsonObject {
      put("protocolVersion", 1)
      put("clientCapabilities", buildJsonObject {
        put("fs", buildJsonObject {
          put("readTextFile", true)
          put("writeTextFile", true)
        })
      })
    }
    return request("initialize", init).thenCompose { initResult ->
      capabilities = parseCapabilities(initResult)
      request("session/new", buildJsonObject {
        put("cwd", workingDir ?: System.getProperty("user.home"))
        put("mcpServers", JsonArray(emptyList()))
      })
    }.thenApply { result ->
      val obj = result.jsonObject
      val id = obj.getValue("sessionId").jsonPrimitive.content
      modes = parseModes(obj)
      sessionId = id
      id
    }
  }

  fun prompt(text: String): CompletableFuture<JsonElement> = prompt(listOf(ContentBlock.Text(text)))

  fun prompt(blocks: List<ContentBlock>): CompletableFuture<JsonElement> {
    val sid = checkNotNull(sessionId) { "no session" }
    return request("session/prompt", buildJsonObject {
      put("sessionId", sid)
      put("prompt", JsonArray(blocks.map { it.toJson() }))
    })
  }

  /** Switches the session mode; [modes] is updated once the agent acknowledges. */
  fun setMode(modeId: String): CompletableFuture<Unit> {
    val sid = checkNotNull(sessionId) { "no session" }
    return request("session/set_mode", buildJsonObject {
      put("sessionId", sid)
      put("modeId", modeId)
    }).thenApply {
      modes = modes?.copy(currentModeId = modeId)
      Unit
    }
  }

  // Lenient parsing: a missing or malformed field never fails the handshake, it just reads as "unsupported".
  private fun parseCapabilities(initResult: JsonElement): AgentCapabilities {
    val obj = initResult as? JsonObject ?: return AgentCapabilities(image = false, embeddedContext = false)
    val prompt = (obj["agentCapabilities"] as? JsonObject)?.get("promptCapabilities") as? JsonObject
    return AgentCapabilities(
      image = prompt?.get("image").booleanOrFalse(),
      embeddedContext = prompt?.get("embeddedContext").booleanOrFalse(),
    )
  }

  private fun parseModes(sessionResult: JsonObject): SessionModes? {
    val modesObj = sessionResult["modes"] as? JsonObject ?: return null
    val current = modesObj["currentModeId"]?.stringOrNull() ?: return null
    val available = (modesObj["availableModes"] as? JsonArray).orEmpty().mapNotNull { entry ->
      val mode = entry as? JsonObject ?: return@mapNotNull null
      val id = mode["id"]?.stringOrNull() ?: return@mapNotNull null
      SessionMode(
        id = id,
        name = mode["name"]?.stringOrNull() ?: id,
        description = mode["description"]?.stringOrNull(),
      )
    }
    return SessionModes(currentModeId = current, available = available)
  }

  private fun JsonElement.stringOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull

  private fun JsonElement?.booleanOrFalse(): Boolean = (this as? JsonPrimitive)?.booleanOrNull ?: false

  fun cancel() {
    val sid = sessionId ?: return
    notify("session/cancel", buildJsonObject { put("sessionId", sid) })
  }

  private fun request(method: String, params: JsonObject): CompletableFuture<JsonElement> {
    val id = nextId.getAndIncrement()
    val future = CompletableFuture<JsonElement>()
    pending[id] = future
    send(buildJsonObject {
      put("jsonrpc", "2.0")
      put("id", id)
      put("method", method)
      put("params", params)
    })
    return future
  }

  private fun notify(method: String, params: JsonObject) {
    send(buildJsonObject {
      put("jsonrpc", "2.0")
      put("method", method)
      put("params", params)
    })
  }

  @Synchronized
  private fun send(message: JsonObject) {
    val w = writer ?: return
    try {
      w.write(message.toString())
      w.write("\n")
      w.flush()
    }
    catch (e: java.io.IOException) {
      // A dead pipe must not surface as an IDE error from whichever thread wrote last.
      handler.onProtocolLog("[acp] запись агенту не удалась: ${e.message}")
      failPending("agent pipe closed: ${e.message}")
    }
  }

  private fun readLoop(reader: BufferedReader) {
    try {
      reader.forEachLine { line ->
        if (line.isBlank()) return@forEachLine
        val msg = try { json.parseToJsonElement(line).jsonObject }
        catch (e: Exception) {
          handler.onProtocolLog("[protocol] не-JSON строка: ${line.take(200)}")
          return@forEachLine
        }
        val id = msg["id"]?.jsonPrimitive?.longOrNull
        val method = msg["method"]?.jsonPrimitive?.content
        when {
          method != null && id != null -> respond(id, method, msg["params"]?.jsonObject ?: JsonObject(emptyMap()))
          method != null -> if (method == "session/update") onSessionUpdateNotification(msg["params"]!!.jsonObject)
                            else handler.onProtocolLog("[protocol] уведомление $method")
          id != null -> {
            val future = pending.remove(id) ?: return@forEachLine
            val error = msg["error"]
            if (error != null && error != JsonNull) future.completeExceptionally(RuntimeException(error.toString()))
            else future.complete(msg["result"] ?: JsonNull)
          }
        }
      }
    }
    catch (e: Exception) {
      handler.onProtocolLog("[protocol] reader завершён: ${e.message}")
    }
  }

  private fun onSessionUpdateNotification(params: JsonObject) {
    val update = params["update"] as? JsonObject
    if (update?.get("sessionUpdate")?.stringOrNull() == UPDATE_CURRENT_MODE) {
      val modeId = update["currentModeId"]?.stringOrNull()
      if (modeId != null) {
        modes = modes?.copy(currentModeId = modeId)
        handler.onModeChanged(modeId)
      }
    }
    handler.onSessionUpdate(params)
  }

  private fun respond(id: Long, method: String, params: JsonObject) {
    val result: JsonElement = try {
      when (method) {
        "session/request_permission" -> handler.onRequestPermission(params)
        "fs/read_text_file" -> handler.onReadTextFile(params)
        "fs/write_text_file" -> handler.onWriteTextFile(params)
        else -> {
          sendError(id, -32601, "Method not supported by this client: $method")
          return
        }
      }
    }
    catch (e: Exception) {
      sendError(id, -32603, e.message ?: e.javaClass.simpleName)
      return
    }
    send(buildJsonObject {
      put("jsonrpc", "2.0")
      put("id", id)
      put("result", result)
    })
  }

  private fun sendError(id: Long, code: Int, message: String) {
    send(buildJsonObject {
      put("jsonrpc", "2.0")
      put("id", id)
      put("error", buildJsonObject {
        put("code", code)
        put("message", message)
      })
    })
  }

  companion object {
    private const val UPDATE_CURRENT_MODE = "current_mode_update"

    private val EXTRA_PATH: String = listOf(
      System.getProperty("user.home") + "/.local/bin",
      System.getProperty("user.home") + "/.npm-global/bin",
      "/opt/homebrew/bin",
      "/usr/local/bin",
    ).joinToString(File.pathSeparator)

    internal fun resolveBinary(binary: String): String {
      if (binary.contains('/')) return binary
      val dirs = (System.getenv("PATH")?.split(File.pathSeparator).orEmpty()) + EXTRA_PATH.split(File.pathSeparator)
      return dirs.asSequence().map { Path.of(it, binary) }.firstOrNull { Files.isExecutable(it) }?.toString() ?: binary
    }
  }
}
