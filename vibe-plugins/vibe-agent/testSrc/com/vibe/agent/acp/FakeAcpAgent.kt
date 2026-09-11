// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.acp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess

/**
 * A stand-in ACP agent: NDJSON over stdio, driven by a scenario name.
 *
 * Exists so the real [AcpClient] can be exercised end to end — process, pipes, framing, request
 * correlation, reverse calls, cancellation, death — without Claude Code installed and without the
 * network. Deliberately a plain JVM main rather than a node script: the test must be hermetic, and
 * Bazel already has a JVM.
 *
 * Contract: stdout carries protocol frames ONLY. Anything diagnostic goes to stderr, which the
 * client forwards to its protocol log.
 */
object FakeAcpAgent {
  private val json = Json { ignoreUnknownKeys = true }
  private val out = System.out.bufferedWriter()
  private val agentRequests = ConcurrentHashMap<Long, LinkedBlockingQueue<JsonObject>>()
  private var nextAgentId = 1000L
  private val cancelled = CountDownLatch(1)

  /** Sessions opened so far: the first is [SESSION_ID], every next one a new id, as a real agent does. */
  private val sessionsOpened = AtomicInteger(0)

  /** The session a `session/cancel` named — so a test can see which turn the client stopped. */
  @Volatile private var cancelledSession: String? = null

  /** What the client announced about itself in `initialize` — replayed on demand so tests can assert it. */
  @Volatile private var clientCapabilities: JsonObject = JsonObject(emptyMap())

  @JvmStatic
  fun main(args: Array<String>) {
    val scenario = args.firstOrNull() ?: "basic"
    val reader = System.`in`.bufferedReader()
    while (true) {
      val line = reader.readLine() ?: break
      if (line.isBlank()) continue
      val msg = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
      val id = (msg["id"] as? JsonPrimitive)?.longOrNull
      val method = (msg["method"] as? JsonPrimitive)?.contentOrNull
      val params = msg["params"] as? JsonObject ?: JsonObject(emptyMap())
      when {
        // A response to something WE asked the client.
        method == null && id != null -> agentRequests[id]?.offer(msg)
        method == "session/cancel" -> {
          cancelledSession = (params["sessionId"] as? JsonPrimitive)?.contentOrNull
          cancelled.countDown()
        }
        method != null && id != null -> handleRequest(scenario, id, method, params)
        else -> {} // other notifications are of no interest to the fake
      }
    }
  }

  const val AGENT_LOGIN = "agent-login"
  const val TERMINAL_LOGIN = "terminal-login"

  /** Scenario `auth`: whether the client signed in — `session/new` asks for it until then, `logout` undoes it. */
  @Volatile private var loggedIn = false

  private fun handleRequest(scenario: String, id: Long, method: String, params: JsonObject) {
    when (method) {
      "initialize" -> {
        clientCapabilities = params["clientCapabilities"] as? JsonObject ?: JsonObject(emptyMap())
        if (scenario == "crash") { send(result(id, JsonObject(emptyMap()))); flushAndDie() }
        send(result(id, buildJsonObject {
          put("protocolVersion", 1)
          put("agentCapabilities", buildJsonObject {
            put("promptCapabilities", buildJsonObject {
              put("image", true)
              put("embeddedContext", true)
            })
            if (scenario == "auth") put("auth", buildJsonObject { put("logout", buildJsonObject { }) })
          })
          // Both kinds the protocol defines, and one it may define later: the client names that one, never acts on it.
          if (scenario == "auth") put("authMethods", JsonArray(listOf(
            buildJsonObject { put("id", AGENT_LOGIN); put("name", "Войти через агента"); put("description", "поток входа самого агента") },
            buildJsonObject {
              put("id", TERMINAL_LOGIN); put("name", "Войти в терминале"); put("type", "terminal")
              put("args", JsonArray(listOf(JsonPrimitive("--login"))))
              put("env", buildJsonObject { put("ACP_INTERACTIVE_LOGIN", "1") })
            },
            buildJsonObject { put("id", "future-login"); put("name", "Способ из будущего"); put("type", "env_var") },
          )))
          // Echoed back so the test can assert what the client announced about itself.
          put("_echoClientCapabilities", params["clientCapabilities"] ?: JsonObject(emptyMap()))
        }))
      }
      "session/new" -> {
        // What an agent that needs a login answers until one of its methods is done. The code is the
        // protocol SDK's `RequestError.authRequired`, spelled out here rather than taken from the
        // client: a wrong constant there must fail the test, not agree with itself.
        if (scenario == "auth" && !loggedIn) return send(error(id, -32000, "Authentication required"))
        val opened = sessionsOpened.incrementAndGet()
        send(result(id, buildJsonObject {
          put("sessionId", if (opened == 1) SESSION_ID else "fake-session-$opened")
          put("modes", buildJsonObject {
            put("currentModeId", "default")
            put("availableModes", JsonArray(listOf(
              buildJsonObject { put("id", "default"); put("name", "Обычный") },
              buildJsonObject { put("id", "plan"); put("name", "План"); put("description", "только чтение") },
            )))
          })
          put("_echoCwd", params["cwd"] ?: JsonPrimitive(""))
        }))
      }
      "authenticate" -> {
        // Only a protocol-driven method belongs here; a terminal one reaching the agent is a client bug.
        val methodId = (params["methodId"] as? JsonPrimitive)?.contentOrNull
        if (methodId == AGENT_LOGIN) { loggedIn = true; send(result(id, JsonObject(emptyMap()))) }
        else send(error(id, -32602, "not an agent method: $methodId"))
      }
      "logout" -> {
        loggedIn = false
        send(result(id, JsonObject(emptyMap())))
      }
      "session/set_mode" -> {
        send(result(id, JsonObject(emptyMap())))
        // The agent confirms the switch the way a real one does — through the update stream.
        notifyUpdate(buildJsonObject {
          put("sessionUpdate", "current_mode_update")
          put("currentModeId", params["modeId"] ?: JsonPrimitive("default"))
        })
      }
      "session/prompt" -> {
        val session = (params["sessionId"] as? JsonPrimitive)?.contentOrNull ?: SESSION_ID
        Thread { runPrompt(scenario, id, session) }.start()
      }
      else -> send(error(id, -32601, "fake agent does not know $method"))
    }
  }

  private fun runPrompt(scenario: String, id: Long, session: String) {
    when (scenario) {
      "basic" -> {
        repeat(3) { i -> notifyUpdate(chunk("часть $i")) }
        send(result(id, stop("end_turn")))
      }
      "capabilities" -> {
        notifyUpdate(chunk("caps=" + clientCapabilities.toString()))
        send(result(id, stop("end_turn")))
      }
      "garbage" -> {
        // A frame that is not JSON at all, then a frame that is JSON but not an object.
        writeRaw("{ это не json")
        writeRaw("\"строка вместо объекта\"")
        notifyUpdate(chunk("после мусора"))
        send(result(id, stop("end_turn")))
      }
      "permission" -> {
        val answer = ask("session/request_permission", buildJsonObject {
          put("sessionId", SESSION_ID)
          put("toolCall", buildJsonObject { put("title", "rm -rf /tmp/x"); put("kind", "execute") })
          put("options", JsonArray(listOf(
            buildJsonObject { put("optionId", "allow"); put("name", "Разрешить"); put("kind", "allow_once") },
            buildJsonObject { put("optionId", "reject"); put("name", "Отклонить"); put("kind", "reject_once") },
          )))
        })
        notifyUpdate(chunk("решение: " + (answer["result"]?.toString() ?: answer["error"].toString())))
        send(result(id, stop("end_turn")))
      }
      "fs" -> {
        val read = ask("fs/read_text_file", buildJsonObject {
          put("sessionId", SESSION_ID); put("path", "/tmp/vibe-fake.txt")
        })
        val write = ask("fs/write_text_file", buildJsonObject {
          put("sessionId", SESSION_ID); put("path", "/tmp/vibe-fake.txt"); put("content", "новое содержимое")
        })
        notifyUpdate(chunk("read=" + read["result"]?.toString() + " write=" + write["result"]?.toString()))
        send(result(id, stop("end_turn")))
      }
      "terminal" -> {
        val created = ask("terminal/create", buildJsonObject {
          put("sessionId", SESSION_ID); put("command", "echo"); put("args", JsonArray(listOf(JsonPrimitive("привет"))))
        })
        notifyUpdate(chunk("terminal=" + (created["result"]?.toString() ?: "ошибка: " + created["error"].toString())))
        send(result(id, stop("end_turn")))
      }
      "unknown" -> {
        val answer = ask("vibe/no-such-method", JsonObject(emptyMap()))
        notifyUpdate(chunk("ответ на неизвестный метод: " + (answer["error"]?.toString() ?: "НЕТ ОШИБКИ")))
        send(result(id, stop("end_turn")))
      }
      "cancel" -> {
        notifyUpdate(chunk("работаю…"))
        val got = cancelled.await(10, TimeUnit.SECONDS)
        send(result(id, stop(if (got) "cancelled" else "end_turn")))
      }
      "sessions" -> {
        // Says which session the prompt reached; a session that is not the first one also switches
        // its own mode, which must stay its own business.
        notifyUpdate(chunk("session=$session"), session)
        if (session != SESSION_ID) {
          notifyUpdate(buildJsonObject {
            put("sessionUpdate", "current_mode_update")
            put("currentModeId", "plan")
          }, session)
        }
        send(result(id, stop("end_turn")))
      }
      "cancelSession" -> {
        notifyUpdate(chunk("работаю…"), session)
        val got = cancelled.await(10, TimeUnit.SECONDS)
        notifyUpdate(chunk("отменена сессия $cancelledSession"), session)
        send(result(id, stop(if (got) "cancelled" else "end_turn")))
      }
      "authStatus" -> {
        // The Claude adapter's extension notification: how the turn is billed, account included.
        send(buildJsonObject {
          put("jsonrpc", "2.0")
          put("method", "_auth/status_update")
          put("params", buildJsonObject {
            put("authStatus", buildJsonObject {
              put("kind", "subscription")
              put("label", "Claude Max")
              put("detail", "claude.ai")
              put("account", buildJsonObject { put("email", "me@example.com"); put("organization", "Организация") })
            })
          })
        })
        notifyUpdate(chunk("статус отправлен"))
        send(result(id, stop("end_turn")))
      }
      "outOfOrder" -> {
        // Answers the prompt LAST, after the client's later set_mode call has been served —
        // the client must still route each response to its own future.
        Thread.sleep(300)
        send(result(id, stop("end_turn")))
      }
      else -> send(result(id, stop("end_turn")))
    }
  }

  /** Sends a request to the CLIENT and blocks until its response arrives. */
  private fun ask(method: String, params: JsonObject): JsonObject {
    val id = nextAgentId++
    val queue = LinkedBlockingQueue<JsonObject>()
    agentRequests[id] = queue
    send(buildJsonObject {
      put("jsonrpc", "2.0"); put("id", id); put("method", method); put("params", params)
    })
    return queue.poll(15, TimeUnit.SECONDS) ?: buildJsonObject { put("error", "клиент не ответил") }
  }

  private fun chunk(text: String): JsonObject = buildJsonObject {
    put("sessionUpdate", "agent_message_chunk")
    put("content", buildJsonObject { put("type", "text"); put("text", text) })
  }

  private fun stop(reason: String): JsonObject = buildJsonObject { put("stopReason", reason) }

  private fun notifyUpdate(update: JsonObject, session: String = SESSION_ID) = send(buildJsonObject {
    put("jsonrpc", "2.0")
    put("method", "session/update")
    put("params", buildJsonObject { put("sessionId", session); put("update", update) })
  })

  private fun result(id: Long, value: JsonElement) = buildJsonObject {
    put("jsonrpc", "2.0"); put("id", id); put("result", value)
  }

  private fun error(id: Long, code: Int, message: String) = buildJsonObject {
    put("jsonrpc", "2.0"); put("id", id)
    put("error", buildJsonObject { put("code", code); put("message", message) })
  }

  @Synchronized private fun send(message: JsonObject) = writeRaw(message.toString())

  @Synchronized private fun writeRaw(line: String) {
    out.write(line); out.write("\n"); out.flush()
  }

  private fun flushAndDie() {
    out.flush()
    exitProcess(CRASH_EXIT_CODE)
  }

  const val SESSION_ID = "fake-session-1"
  const val CRASH_EXIT_CODE = 3
}
