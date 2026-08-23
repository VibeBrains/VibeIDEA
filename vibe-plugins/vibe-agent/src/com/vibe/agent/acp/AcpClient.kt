// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.acp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
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
    /** Called on the reader thread; must return the permission outcome (closed dialog = refusal). */
    fun onRequestPermission(params: JsonObject): JsonElement
    fun onReadTextFile(params: JsonObject): JsonElement
    fun onWriteTextFile(params: JsonObject): JsonElement
    fun onProtocolLog(line: String)
    fun onProcessExit(code: Int)
  }

  private val json = Json { ignoreUnknownKeys = true }
  private var process: Process? = null
  private var writer: BufferedWriter? = null
  private val nextId = AtomicLong(1)
  private val pending = ConcurrentHashMap<Long, CompletableFuture<JsonElement>>()

  @Volatile var sessionId: String? = null
    private set

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
    Thread({ handler.onProcessExit(p.waitFor()) }, "vibe-acp-exit").apply { isDaemon = true }.start()
  }

  fun stop() {
    process?.destroy()
    process = null
    sessionId = null
    pending.values.forEach { it.completeExceptionally(IllegalStateException("agent stopped")) }
    pending.clear()
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
    return request("initialize", init).thenCompose {
      request("session/new", buildJsonObject {
        put("cwd", workingDir ?: System.getProperty("user.home"))
        put("mcpServers", kotlinx.serialization.json.JsonArray(emptyList()))
      })
    }.thenApply { result ->
      val id = result.jsonObject.getValue("sessionId").jsonPrimitive.content
      sessionId = id
      id
    }
  }

  fun prompt(text: String): CompletableFuture<JsonElement> {
    val sid = checkNotNull(sessionId) { "no session" }
    return request("session/prompt", buildJsonObject {
      put("sessionId", sid)
      put("prompt", kotlinx.serialization.json.JsonArray(listOf(buildJsonObject {
        put("type", "text")
        put("text", text)
      })))
    })
  }

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
    w.write(message.toString())
    w.write("\n")
    w.flush()
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
          method != null -> if (method == "session/update") handler.onSessionUpdate(msg["params"]!!.jsonObject)
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
