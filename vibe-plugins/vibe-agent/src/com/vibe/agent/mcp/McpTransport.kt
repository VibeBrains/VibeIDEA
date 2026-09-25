// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * How JSON-RPC messages reach one MCP server and come back; the protocol on top is [McpClient]
 */
interface McpTransport : AutoCloseable {
  val isAlive: Boolean

  /**
   * Sends a request and returns the server's whole answer to it: a message with `result` or `error`
   * @throws McpClient.McpException when no answer came, [McpClient.Unauthorized] when the credentials were refused
   */
  fun exchange(message: JsonObject, id: Long, timeoutMs: Long): JsonObject

  /** Sends a notification; no answer is expected */
  fun notify(message: JsonObject)

  /** The protocol revision the handshake settled on: a transport that carries it outside the body keeps it */
  fun agreed(version: String) {}
}

/**
 * Newline-delimited JSON-RPC 2.0 over a process's stdin and stdout
 *
 * A request from the server (`roots/list`, sampling) is answered «method not found» rather than left hanging:
 * a server waiting for our answer stops answering us
 *
 * Streams, not a process, in the constructor: the protocol is testable against a pipe, and [start] is the one place
 * that knows about processes
 */
class McpStdioTransport(
  private val input: InputStream,
  private val output: OutputStream,
  private val onClose: () -> Unit = {},
) : McpTransport {
  private val json = Json { ignoreUnknownKeys = true }
  private val pending = ConcurrentHashMap<Long, CompletableFuture<JsonObject>>()
  @Volatile private var closed = false

  override val isAlive: Boolean get() = !closed

  private val reader = Thread({ readLoop(input) }, "vibe-mcp-stdio").apply { isDaemon = true }

  init {
    reader.start()
  }

  private fun readLoop(input: InputStream) {
    try {
      input.bufferedReader(Charsets.UTF_8).forEachLine { line ->
        if (line.isNotBlank()) runCatching { dispatch(json.parseToJsonElement(line).jsonObject) }
      }
    }
    catch (_: java.io.IOException) {
      // The process went away; close() below fails whatever still waits.
    }
    finally {
      close()
    }
  }

  private fun dispatch(message: JsonObject) {
    val id = message["id"]?.jsonPrimitive?.longOrNull
    if (message["method"] != null) {
      if (id != null) send(buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("error", buildJsonObject {
          put("code", McpProtocol.Error.METHOD_NOT_FOUND)
          put("message", "not supported by this client")
        })
      })
      return
    }
    id?.let { pending.remove(it) }?.complete(message)
  }

  private fun send(message: JsonObject) {
    synchronized(output) {
      output.write((message.toString() + "\n").toByteArray(Charsets.UTF_8))
      output.flush()
    }
  }

  override fun exchange(message: JsonObject, id: Long, timeoutMs: Long): JsonObject {
    if (closed) throw McpClient.McpException("server is not running")
    val waiter = CompletableFuture<JsonObject>()
    pending[id] = waiter
    try {
      send(message)
      return waiter.get(timeoutMs, TimeUnit.MILLISECONDS)
    }
    catch (e: java.util.concurrent.ExecutionException) {
      throw e.cause ?: e
    }
    catch (e: java.util.concurrent.TimeoutException) {
      throw McpClient.McpException("${message["method"]?.jsonPrimitive?.contentOrNull}: no answer in $timeoutMs ms")
    }
    finally {
      pending.remove(id)
    }
  }

  override fun notify(message: JsonObject) = send(message)

  override fun close() {
    if (closed) return
    closed = true
    pending.values.forEach { it.completeExceptionally(McpClient.McpException("server stopped")) }
    pending.clear()
    runCatching { output.close() }
    onClose()
    // The reader blocks in read(): closing the input is what wakes it. Without this a stopped server left a
    // thread per chat panel waiting on a stream nobody would ever write to again (caught by the leak check).
    runCatching { input.close() }
    if (Thread.currentThread() !== reader) reader.join(READER_JOIN_MS)
  }

  companion object {
    /** A piped stream notices its close within a second; waiting a little longer covers a slow machine. */
    private const val READER_JOIN_MS = 2_000L

    /** Starts the server process; its stderr is discarded — it is a log, not a protocol. */
    fun start(command: String, args: List<String>, workingDir: Path?, env: Map<String, String> = emptyMap()): McpStdioTransport {
      val process = ProcessBuilder(listOf(command) + args)
        .apply { workingDir?.let { directory(it.toFile()) } }
        // Переменные окружения нужны чужим серверам: почти каждый просит ключ именно так. Своё
        // окружение процесса IDE при этом СОХРАНЯЕТСЯ — сервер без PATH не найдёт даже node.
        .apply { if (env.isNotEmpty()) environment().putAll(env) }
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
      return McpStdioTransport(process.inputStream, process.outputStream) { process.destroy() }
    }
  }
}
