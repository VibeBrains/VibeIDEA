// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.http

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Everything the incoming HTTP API decides BEFORE touching the IDE — routing, authentication,
 * anti-rebinding, size and body validation — as one pure function over a described request.
 *
 * Pure on purpose: this is the security surface of a feature that runs code on the owner's machine,
 * and a security surface that can only be tested by opening a socket ends up untested. The socket
 * layer ([VibeHttpApi]) is left with transport and nothing else.
 *
 * Contract is VibeIDE's `httpApiSpec.md`, carried over verbatim so scripts written for VibeIDE keep
 * working: paths `/health` and `/run`, Bearer token, the same status codes.
 */
object HttpApiPolicy {
  const val PROTOCOL_VERSION = 1
  const val MAX_BODY_BYTES = 1_000_000

  /** What the transport layer must tell the policy about a request. */
  data class Request(
    val method: String,
    val path: String,
    val authorization: String?,
    val host: String?,
    val remoteIsLoopback: Boolean,
    val bodyLength: Int,
    val body: String,
    /** `Mcp-Method`, required by MCP 2026-07-28 on POST so gateways route without reading bodies. */
    val mcpMethod: String? = null,
  )

  sealed interface Decision {
    /** `GET /health` — answer immediately, no IDE involved. */
    data object Health : Decision

    /** `POST /run` — hand [task] to the agent; [sessionId] continues an existing conversation. */
    data class Run(
      val task: String,
      val sessionId: String?,
      val wait: Boolean,
      /**
       * Optional caller key: the same key while a run is still going returns THAT run instead of
       * starting a second one. A webhook delivered twice is the ordinary case, not the exotic one.
       */
      val idempotencyKey: String? = null,
    ) : Decision

    /**
     * `POST /mcp` — one JSON-RPC message for the MCP server.
     *
     * A separate decision rather than a variant of [Run]: MCP decides for itself what a message
     * means, and folding it into the run path would put a protocol parser inside the route that
     * exists to be boring.
     */
    data class Mcp(val body: String, val mcpMethod: String?) : Decision

    /** Anything refused: [code] is the HTTP status, [message] goes into the JSON error body. */
    data class Refuse(val code: Int, val message: String) : Decision
  }

  private val json = Json { ignoreUnknownKeys = true }

  /**
   * @param token the configured API token, or null when the API is on but no token is stored (503).
   */
  fun decide(request: Request, token: String?): Decision {
    // 1. Origin. The listener is bound to loopback, but a bind is one line away from being changed;
    //    checking the peer as well means the mistake would have to be made twice.
    if (!request.remoteIsLoopback) return Decision.Refuse(403, "запросы принимаются только с этой машины")

    // 2. Host. A page open in the owner's browser can resolve its own domain to 127.0.0.1 and post
    //    to this port (DNS rebinding); the one thing it cannot forge is the Host header.
    if (!isLocalHost(request.host)) return Decision.Refuse(403, "недопустимый Host: ${request.host}")

    // 3. Token. 503 before 401: "no token configured" is our problem, not the caller's.
    if (token.isNullOrEmpty()) return Decision.Refuse(503, "токен HTTP API не настроен")
    if (!isAuthorized(request.authorization, token)) return Decision.Refuse(401, "нужен заголовок Authorization: Bearer <токен>")

    // 4. Size, before parsing: a refusal must not require reading a gigabyte first.
    if (request.bodyLength > MAX_BODY_BYTES) return Decision.Refuse(413, "тело больше $MAX_BODY_BYTES байт")

    return when {
      request.method == "GET" && request.path == "/health" -> Decision.Health
      request.method == "POST" && request.path == "/run" -> parseRun(request.body)
      // The MCP endpoint deliberately lives beside /run rather than replacing it: the VibeIDE
      // contract for /health and /run is carried over verbatim and scripts depend on it.
      request.method == "POST" && request.path == "/mcp" -> Decision.Mcp(request.body, request.mcpMethod)
      else -> Decision.Refuse(404, "известны только GET /health, POST /run и POST /mcp")
    }
  }

  private fun parseRun(body: String): Decision {
    val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
                ?: return Decision.Refuse(400, "тело должно быть JSON-объектом")
    val task = obj["task"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    if (task.isEmpty()) return Decision.Refuse(400, "поле task обязательно и не может быть пустым")
    val sessionId = obj["sessionId"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    val wait = obj["wait"]?.jsonPrimitive?.booleanOrNull ?: false
    val idempotencyKey = obj["idempotencyKey"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    return Decision.Run(task = task, sessionId = sessionId, wait = wait, idempotencyKey = idempotencyKey)
  }

  /** `Bearer` is case-insensitive, the token is not; comparison is constant-time. */
  fun isAuthorized(header: String?, token: String): Boolean {
    val value = header?.trim() ?: return false
    val space = value.indexOf(' ')
    if (space <= 0) return false
    if (!value.substring(0, space).equals("Bearer", ignoreCase = true)) return false
    val presented = value.substring(space + 1).trim()
    return MessageDigest.isEqual(
      presented.toByteArray(StandardCharsets.UTF_8),
      token.toByteArray(StandardCharsets.UTF_8),
    )
  }

  /** Accepts `localhost`, `127.0.0.1`, `[::1]` — with or without a port; anything else is refused. */
  fun isLocalHost(host: String?): Boolean {
    val value = host?.trim()?.lowercase() ?: return false
    if (value.isEmpty()) return false
    val name = when {
      value.startsWith("[") -> value.substringAfter('[').substringBefore(']')  // [::1] or [::1]:7391
      else -> value.substringBefore(':')
    }
    return name in LOCAL_NAMES
  }

  private val LOCAL_NAMES = setOf("localhost", "127.0.0.1", "::1", "0:0:0:0:0:0:0:1")
}
