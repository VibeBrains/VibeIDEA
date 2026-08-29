// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.http

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/**
 * Incoming HTTP API: "poke the agent from CI, a bot or a cron job".
 *
 * Transport only — every decision about who may ask what lives in [HttpApiPolicy], which is pure
 * and therefore actually tested. The socket is bound to the loopback address and there is no
 * setting to bind anywhere else: that setting would turn running code on a personal machine into a
 * network service.
 *
 * The runner is injected rather than reached for, so tests drive the whole server without an IDE.
 */
class VibeHttpApi(
  private val tokenProvider: () -> String?,
  private val runner: Runner,
) {
  /** What actually performs a task; the production implementation talks to the agent tool window. */
  interface Runner {
    /** @return the session id to continue with, or throws to report a failed run. */
    fun run(task: String, sessionId: String?, wait: Boolean): String
  }

  private var server: HttpServer? = null

  /** Bound port, or -1 when stopped. `0` as [port] means "any free port" — the pick is reported here. */
  @Volatile var boundPort: Int = -1
    private set

  @Synchronized
  fun start(port: Int) {
    if (server != null) return
    val address = InetSocketAddress(InetAddress.getLoopbackAddress(), port)
    val http = HttpServer.create(address, BACKLOG)
    http.createContext("/") { exchange -> handle(exchange) }
    // A small pool: the API is a control plane, not a load balancer, and unbounded threads would be
    // a denial-of-service knob for anything that can reach loopback.
    http.executor = Executors.newFixedThreadPool(THREADS) { r ->
      Thread(r, "vibe-http-api").apply { isDaemon = true }
    }
    http.start()
    server = http
    boundPort = http.address.port
  }

  @Synchronized
  fun stop() {
    server?.stop(0)
    server = null
    boundPort = -1
  }

  val isRunning: Boolean get() = server != null

  private fun handle(exchange: HttpExchange) {
    try {
      val body = readBody(exchange)
      val request = HttpApiPolicy.Request(
        method = exchange.requestMethod.uppercase(),
        path = exchange.requestURI.path.trimEnd('/').ifEmpty { "/" },
        authorization = exchange.requestHeaders.getFirst("Authorization"),
        host = exchange.requestHeaders.getFirst("Host"),
        remoteIsLoopback = exchange.remoteAddress?.address?.isLoopbackAddress == true,
        bodyLength = body.size,
        body = String(body, StandardCharsets.UTF_8),
      )
      when (val decision = HttpApiPolicy.decide(request, tokenProvider())) {
        is HttpApiPolicy.Decision.Health -> respond(exchange, 200, buildJsonObject {
          put("ok", true)
          put("version", HttpApiPolicy.PROTOCOL_VERSION)
        })
        is HttpApiPolicy.Decision.Refuse -> respond(exchange, decision.code, buildJsonObject {
          put("ok", false)
          put("error", decision.message)
        })
        is HttpApiPolicy.Decision.Run -> {
          val session = try {
            runner.run(decision.task, decision.sessionId, decision.wait)
          }
          catch (e: Exception) {
            respond(exchange, 500, buildJsonObject {
              put("sessionId", decision.sessionId ?: "")
              put("status", "failed")
              put("error", e.message ?: e.javaClass.simpleName)
            })
            return
          }
          respond(exchange, 200, buildJsonObject {
            put("sessionId", session)
            put("status", if (decision.wait) "completed" else "started")
          })
        }
      }
    }
    catch (e: Exception) {
      // Never leak a stack trace to a caller; the IDE log keeps the details.
      runCatching { respond(exchange, 500, buildJsonObject { put("ok", false); put("error", "внутренняя ошибка") }) }
      throw e
    }
    finally {
      exchange.close()
    }
  }

  /** Reads at most one byte past the cap: enough to refuse with 413, not enough to be a memory sink. */
  private fun readBody(exchange: HttpExchange): ByteArray {
    val limit = HttpApiPolicy.MAX_BODY_BYTES + 1
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    exchange.requestBody.use { input ->
      while (out.size() < limit) {
        val read = try { input.read(buffer) } catch (e: IOException) { break }
        if (read <= 0) break
        out.write(buffer, 0, minOf(read, limit - out.size()))
      }
    }
    return out.toByteArray()
  }

  private fun respond(exchange: HttpExchange, code: Int, body: JsonObject) {
    val bytes = body.toString().toByteArray(StandardCharsets.UTF_8)
    exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
    // The API is not for browsers: no CORS headers, ever — a page must not be able to read answers.
    exchange.sendResponseHeaders(code, bytes.size.toLong())
    exchange.responseBody.use { it.write(bytes) }
  }

  private companion object {
    const val BACKLOG = 8
    const val THREADS = 4
  }
}
