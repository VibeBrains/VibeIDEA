// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.acp

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end for [AcpClient]: a real subprocess ([FakeAcpAgent]), real pipes, real JSON-RPC.
 *
 * Everything here was previously verified only by the owner clicking through a live Claude Code
 * session, which means every regression in framing, request correlation, reverse calls or shutdown
 * was invisible until someone noticed the chat had gone quiet.
 */
@Timeout(60)
class AcpClientE2ETest {
  private val updates = ConcurrentLinkedQueue<JsonObject>()
  private val protocolLog = ConcurrentLinkedQueue<String>()
  private val modeChanges = ConcurrentLinkedQueue<String>()
  private val exits = ConcurrentLinkedQueue<Int>()
  private var client: AcpClient? = null

  @AfterEach fun tearDown() { client?.stop() }

  // --- harness ---

  private inner class TestHandler(
    val permission: (JsonObject) -> JsonElement = { allow() },
    val readFile: (JsonObject) -> JsonElement = { buildJsonObject { put("content", "старое содержимое") } },
    val writeFile: (JsonObject) -> JsonElement = { JsonObject(emptyMap()) },
    val createTerminal: ((JsonObject) -> JsonElement)? = null,
  ) : AcpClient.Handler {
    val permissionCalls = ConcurrentLinkedQueue<JsonObject>()
    val writes = ConcurrentLinkedQueue<JsonObject>()
    override fun onSessionUpdate(update: JsonObject) { updates += update }
    override fun onModeChanged(modeId: String) { modeChanges += modeId }
    override fun onRequestPermission(params: JsonObject): JsonElement { permissionCalls += params; return permission(params) }
    override fun onReadTextFile(params: JsonObject): JsonElement = readFile(params)
    override fun onWriteTextFile(params: JsonObject): JsonElement { writes += params; return writeFile(params) }
    override fun onCreateTerminal(params: JsonObject): JsonElement =
      createTerminal?.invoke(params) ?: super.onCreateTerminal(params)
    override fun onProtocolLog(line: String) { protocolLog += line }
    override fun onProcessExit(client: AcpClient, code: Int) { exits += code }
  }

  private fun allow(): JsonElement = buildJsonObject {
    put("outcome", buildJsonObject { put("outcome", "selected"); put("optionId", "allow") })
  }

  /** Starts the client against the fake agent running the given scenario. */
  private fun start(scenario: String, handler: AcpClient.Handler, terminal: Boolean = false): AcpClient {
    val java = System.getProperty("java.home") + "/bin/java"
    val config = AgentServerConfig(
      name = "fake",
      command = java,
      args = listOf("-cp", System.getProperty("java.class.path"), FakeAcpAgent::class.java.name, scenario),
      env = emptyMap(),
    )
    return AcpClient(config, workingDir = null, handler = handler, advertiseTerminalExec = terminal)
      .also { client = it; it.start() }
  }

  private fun texts(): List<String> = updates.mapNotNull { p ->
    p["update"]?.jsonObject?.get("content")?.jsonObject?.get("text")?.jsonPrimitive?.content
  }

  private fun await(condition: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
    while (System.nanoTime() < deadline) {
      if (condition()) return
      Thread.sleep(20)
    }
    throw AssertionError("условие не наступило за 20 с; лог протокола: ${protocolLog.toList()}")
  }

  // --- handshake ---

  @Test
  fun `initialize parses capabilities and session_new parses modes`() {
    val c = start("basic", TestHandler())
    val sessionId = c.initializeAndOpenSession().get(30, TimeUnit.SECONDS)

    assertEquals(FakeAcpAgent.SESSION_ID, sessionId)
    assertEquals(AgentCapabilities(image = true, embeddedContext = true), c.capabilities)
    val modes = assertNotNull(c.modes)
    assertEquals("default", modes.currentModeId)
    assertEquals(listOf("default", "plan"), modes.available.map { it.id })
    assertEquals("только чтение", modes.available.last().description)
  }

  @Test
  fun `the client announces fs access and the claude terminal_output meta, terminal exec only when allowed`() {
    // The Claude adapter streams Bash output through `_meta.terminal_output`; losing that key in a
    // refactor would silently kill the live terminal view, and nothing else would notice.
    val c = start("capabilities", TestHandler(), terminal = false)
    c.initializeAndOpenSession().get(30, TimeUnit.SECONDS)
    c.prompt("что ты обо мне знаешь").get(30, TimeUnit.SECONDS)
    await { texts().any { it.startsWith("caps=") } }
    val announced = texts().first { it.startsWith("caps=") }

    assertTrue(announced.contains("\"readTextFile\":true"), announced)
    assertTrue(announced.contains("\"writeTextFile\":true"), announced)
    assertTrue(announced.contains("\"terminal_output\":true"), announced)
    assertFalse(announced.contains("\"terminal\":true"), "терминал не разрешён — не анонсируем: $announced")
  }

  @Test
  fun `terminal execution is announced when the user allowed it`() {
    val c = start("capabilities", TestHandler(), terminal = true)
    c.initializeAndOpenSession().get(30, TimeUnit.SECONDS)
    c.prompt("что ты обо мне знаешь").get(30, TimeUnit.SECONDS)
    await { texts().any { it.startsWith("caps=") } }

    assertTrue(texts().first { it.startsWith("caps=") }.contains("\"terminal\":true"))
  }

  // --- streaming ---

  @Test
  fun `session updates arrive in order and none are lost`() {
    val c = start("basic", TestHandler())
    c.initializeAndOpenSession().get(30, TimeUnit.SECONDS)
    val result = c.prompt("привет").get(30, TimeUnit.SECONDS)

    assertEquals("end_turn", result.jsonObject["stopReason"]?.jsonPrimitive?.content)
    await { texts().size == 3 }
    assertEquals(listOf("часть 0", "часть 1", "часть 2"), texts())
  }

  @Test
  fun `a malformed frame is skipped and the reader survives it`() {
    val c = start("garbage", TestHandler())
    c.initializeAndOpenSession().get(30, TimeUnit.SECONDS)
    c.prompt("мусор").get(30, TimeUnit.SECONDS)

    await { texts().contains("после мусора") }
    assertTrue(protocolLog.any { it.contains("кадр пропущен") || it.contains("не-JSON") },
               "битый кадр должен попасть в лог протокола: ${protocolLog.toList()}")
    assertTrue(c.isAlive, "процесс агента должен пережить битый кадр")
  }

  // --- reverse calls ---

  @Test
  fun `permission request reaches the handler and its answer reaches the agent`() {
    val handler = TestHandler()
    val c = start("permission", handler)
    c.initializeAndOpenSession().get(30, TimeUnit.SECONDS)
    c.prompt("удали файл").get(30, TimeUnit.SECONDS)

    assertEquals(1, handler.permissionCalls.size)
    await { texts().any { it.startsWith("решение:") } }
    assertTrue(texts().first { it.startsWith("решение:") }.contains("allow"))
  }

  @Test
  fun `a refusal is delivered as an answer, never as silence`() {
    // A closed dialog is a refusal: the agent must get a response, otherwise the turn hangs forever.
    val handler = TestHandler(permission = {
      buildJsonObject { put("outcome", buildJsonObject { put("outcome", "cancelled") }) }
    })
    val c = start("permission", handler)
    c.initializeAndOpenSession().get(30, TimeUnit.SECONDS)
    c.prompt("удали файл").get(30, TimeUnit.SECONDS)

    await { texts().any { it.startsWith("решение:") } }
    assertTrue(texts().first { it.startsWith("решение:") }.contains("cancelled"))
  }

  @Test
  fun `a handler that throws answers with a JSON-RPC error instead of hanging`() {
    val handler = TestHandler(permission = { throw IllegalStateException("диалог сломался") })
    val c = start("permission", handler)
    c.initializeAndOpenSession().get(30, TimeUnit.SECONDS)
    c.prompt("удали файл").get(30, TimeUnit.SECONDS)

    await { texts().any { it.startsWith("решение:") } }
    assertTrue(texts().first { it.startsWith("решение:") }.contains("диалог сломался"))
  }

  @Test
  fun `fs read and write round-trip through the handler`() {
    val handler = TestHandler()
    val c = start("fs", handler)
    c.initializeAndOpenSession().get(30, TimeUnit.SECONDS)
    c.prompt("почини файл").get(30, TimeUnit.SECONDS)

    await { texts().any { it.startsWith("read=") } }
    val line = texts().first { it.startsWith("read=") }
    assertTrue(line.contains("старое содержимое"), line)
    assertEquals(1, handler.writes.size)
    assertEquals("новое содержимое", handler.writes.first()["content"]?.jsonPrimitive?.content)
  }

  // --- terminal capability ---

  @Test
  fun `terminal calls are refused with an error when execution is not advertised`() {
    val c = start("terminal", TestHandler(), terminal = false)
    c.initializeAndOpenSession().get(30, TimeUnit.SECONDS)
    c.prompt("собери проект").get(30, TimeUnit.SECONDS)

    await { texts().any { it.startsWith("terminal=") } }
    // The agent must learn it cannot delegate execution — an unanswered request would hang the turn.
    assertTrue(texts().first { it.startsWith("terminal=") }.contains("ошибка"))
  }

  @Test
  fun `terminal calls reach the handler when execution is advertised`() {
    val handler = TestHandler(createTerminal = { buildJsonObject { put("terminalId", "t1") } })
    val c = start("terminal", handler, terminal = true)
    c.initializeAndOpenSession().get(30, TimeUnit.SECONDS)
    c.prompt("собери проект").get(30, TimeUnit.SECONDS)

    await { texts().any { it.startsWith("terminal=") } }
    assertTrue(texts().first { it.startsWith("terminal=") }.contains("t1"))
  }

  @Test
  fun `an unknown method from the agent is answered with an error, not ignored`() {
    val c = start("unknown", TestHandler())
    c.initializeAndOpenSession().get(30, TimeUnit.SECONDS)
    c.prompt("сделай что-нибудь").get(30, TimeUnit.SECONDS)

    await { texts().any { it.startsWith("ответ на неизвестный метод:") } }
    val line = texts().first { it.startsWith("ответ на неизвестный метод:") }
    assertFalse(line.contains("НЕТ ОШИБКИ"), line)
    assertTrue(line.contains("-32601"), line)
  }

  // --- session control ---

  @Test
  fun `set_mode is acknowledged and the mode update reaches the handler`() {
    val c = start("basic", TestHandler())
    c.initializeAndOpenSession().get(30, TimeUnit.SECONDS)
    c.setMode("plan").get(30, TimeUnit.SECONDS)

    await { modeChanges.contains("plan") }
    assertEquals("plan", c.modes?.currentModeId)
  }

  @Test
  fun `cancel stops the turn and leaves the agent process alive`() {
    val c = start("cancel", TestHandler())
    c.initializeAndOpenSession().get(30, TimeUnit.SECONDS)
    val turn = c.prompt("долгая задача")
    await { texts().contains("работаю…") }

    c.cancel()
    val result = turn.get(30, TimeUnit.SECONDS)

    assertEquals("cancelled", result.jsonObject["stopReason"]?.jsonPrimitive?.content)
    assertTrue(c.isAlive, "Стоп отменяет ход, но не убивает агента")
    assertTrue(exits.isEmpty(), "отмена хода — не выход процесса")
  }

  @Test
  fun `responses are matched by id even when they come back out of order`() {
    val c = start("outOfOrder", TestHandler())
    c.initializeAndOpenSession().get(30, TimeUnit.SECONDS)
    val turn = c.prompt("первый запрос")          // fake answers this one last, after a delay
    val mode = c.setMode("plan")                  // answered immediately, out of order

    mode.get(30, TimeUnit.SECONDS)
    assertFalse(turn.isDone, "ответ на set_mode не должен завершать ход")
    assertEquals("end_turn", turn.get(30, TimeUnit.SECONDS).jsonObject["stopReason"]?.jsonPrimitive?.content)
  }

  // --- lifecycle ---

  @Test
  fun `an agent that dies reports its exit code and fails the in-flight call`() {
    val exited = CountDownLatch(1)
    val handler = object : AcpClient.Handler by TestHandler() {
      override fun onProcessExit(client: AcpClient, code: Int) { exits += code; exited.countDown() }
    }
    val c = start("crash", handler)
    val handshake = runCatching { c.initializeAndOpenSession().get(30, TimeUnit.SECONDS) }

    assertTrue(exited.await(30, TimeUnit.SECONDS), "выход процесса должен быть замечен")
    assertEquals(FakeAcpAgent.CRASH_EXIT_CODE, exits.first())
    assertTrue(handshake.isFailure, "висящий запрос обязан провалиться, а не ждать вечно")
  }

  @Test
  fun `a deliberate stop is silent and unblocks callers`() {
    val c = start("cancel", TestHandler())
    c.initializeAndOpenSession().get(30, TimeUnit.SECONDS)
    val turn = c.prompt("долгая задача")

    c.stop()

    assertTrue(runCatching { turn.get(30, TimeUnit.SECONDS) }.isFailure, "запрос должен провалиться, а не висеть")
    Thread.sleep(300)
    assertTrue(exits.isEmpty(), "наш собственный stop() не должен репортить как падение агента")
    assertFalse(c.isAlive)
  }
}
