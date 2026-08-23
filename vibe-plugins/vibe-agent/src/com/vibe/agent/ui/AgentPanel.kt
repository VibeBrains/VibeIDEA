// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.vibe.agent.acp.AcpClient
import com.vibe.agent.acp.AcpConfig
import com.vibe.agent.acp.AgentServerConfig
import com.vibe.agent.acp.IdeFileOps
import com.vibe.agent.providers.ChatMessage
import com.vibe.agent.providers.LlmClient
import com.vibe.agent.providers.ModelEntry
import com.vibe.agent.providers.ProviderGuard
import com.vibe.agent.providers.ProviderEntry
import com.vibe.agent.providers.ProvidersService
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.awt.BorderLayout
import java.awt.Font
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.SwingUtilities

/**
 * MVP agent chat: agent picker, streamed transcript, prompt input.
 * Permission requests pop a modal dialog; a closed dialog is a refusal
 * (VibeIDE contract), file operations are mapped through [IdeFileOps].
 */
class AgentPanel(private val project: Project) : JPanel(BorderLayout()), AcpClient.Handler {
  private val transcript = JTextArea().apply {
    isEditable = false
    lineWrap = true
    wrapStyleWord = true
    font = Font(Font.MONOSPACED, Font.PLAIN, 12)
  }
  private val input = JBTextField()
  private sealed interface Target { val label: String }
  private data class AcpTarget(val config: AgentServerConfig) : Target {
    override val label: String get() = "Агент: ${config.name}"
  }
  private data class LlmTarget(val provider: ProviderEntry, val model: ModelEntry) : Target {
    override val label: String get() = "LLM: ${provider.name} · ${model.name}"
  }

  private val agents: List<AgentServerConfig> = AcpConfig.load { appendLine("[конфиг] $it") }
  private val providers: List<ProviderEntry> = ProvidersService.load(project.basePath) { appendLine("[providers] $it") }
  private val targets: List<Target> = buildList<Target> {
    agents.forEach { add(AcpTarget(it)) }
    providers.forEach { p ->
      p.models.filter { it.active }
        .sortedWith(compareByDescending<ModelEntry> { it.default }.thenByDescending { it.pinned }.thenBy { it.name })
        .forEach { m -> add(LlmTarget(p, m)) }
    }
  }
  private val agentCombo = JComboBox(DefaultComboBoxModel(targets.map { it.label }.toTypedArray()))
  private val llmClient = LlmClient()
  private val llmHistory = ArrayList<ChatMessage>()
  private val sendButton = JButton("Отправить")
  private val stopButton = JButton("Стоп")
  private val fileOps = IdeFileOps(project)
  private var client: AcpClient? = null

  init {
    border = JBUI.Borders.empty(4)
    val top = JPanel(BorderLayout()).apply {
      add(agentCombo, BorderLayout.CENTER)
      add(stopButton, BorderLayout.EAST)
    }
    val bottom = JPanel(BorderLayout()).apply {
      add(input, BorderLayout.CENTER)
      add(sendButton, BorderLayout.EAST)
    }
    add(top, BorderLayout.NORTH)
    add(JBScrollPane(transcript), BorderLayout.CENTER)
    add(bottom, BorderLayout.SOUTH)
    sendButton.addActionListener { send() }
    input.addActionListener { send() }
    stopButton.addActionListener { stopAgent() }
    appendLine("Vibe Agent готов. Агенты: ${agents.joinToString { it.name }} (реестр: ${AcpConfig.configPath()}).")
    if (providers.isNotEmpty()) {
      appendLine("Провайдеры (.vibe/providers.json): " + providers.joinToString { it.name })
      ProviderGuard.scan(providers).forEach { f -> appendLine("[guard:${f.severity}] ${f.message}") }
    }
  }

  private fun send() {
    val text = input.text.trim()
    if (text.isEmpty()) return
    input.text = ""
    appendLine("\n▶ Вы: $text")
    when (val target = targets.getOrNull(agentCombo.selectedIndex.coerceAtLeast(0))) {
      is LlmTarget -> sendToLlm(target, text)
      is AcpTarget, null -> ApplicationManager.getApplication().executeOnPooledThread {
        try {
          ensureClient().prompt(text).whenComplete { result, error ->
            if (error != null) appendLine("[ошибка] ${error.message}")
            else {
              val stop = result?.jsonObject?.get("stopReason")?.jsonPrimitive?.contentOrNull
              appendLine("\n■ Ход завершён${if (stop != null) " ($stop)" else ""}")
            }
          }
        }
        catch (e: Exception) {
          appendLine("[ошибка] ${e.message}")
        }
      }
    }
  }

  private fun sendToLlm(target: LlmTarget, text: String) {
    ApplicationManager.getApplication().executeOnPooledThread {
      try {
        val resolved = ProvidersService.resolve(target.provider, project.basePath) { appendLine("[providers] $it") }
        if (resolved == null) return@executeOnPooledThread
        if (resolved.apiKey == null && !resolved.isLocal) {
          appendLine("[providers] нет ключа для '${target.provider.id}': задайте apiKeyEnv в providers.json и значение в .vibe/.env (или переменной окружения)")
          return@executeOnPooledThread
        }
        if (resolved.isLocal) appendLine("[локальная модель]")
        llmHistory.add(ChatMessage("user", text))
        val answer = StringBuilder()
        llmClient.chat(resolved, target.model, llmHistory) { delta ->
          answer.append(delta)
          SwingUtilities.invokeLater {
            transcript.append(delta)
            transcript.caretPosition = transcript.document.length
          }
        }
        llmHistory.add(ChatMessage("assistant", answer.toString()))
        appendLine("\n■ Ответ завершён (${target.model.id})")
      }
      catch (e: Exception) {
        appendLine("[ошибка] ${e.message}")
      }
    }
  }

  private fun ensureClient(): AcpClient {
    val existing = client
    if (existing != null && existing.isAlive && existing.sessionId != null) return existing
    val config = (targets.getOrNull(agentCombo.selectedIndex.coerceAtLeast(0)) as? AcpTarget)?.config ?: agents.first()
    appendLine("[агент] запускаю: ${config.command} ${config.args.joinToString(" ")}")
    val fresh = AcpClient(config, project.basePath, this)
    fresh.start()
    client = fresh
    fresh.initializeAndOpenSession().get()
    appendLine("[агент] сессия открыта")
    return fresh
  }

  private fun stopAgent() {
    client?.stop()
    client = null
    appendLine("[агент] остановлен")
  }

  private fun appendLine(text: String) {
    SwingUtilities.invokeLater {
      transcript.append(text + "\n")
      transcript.caretPosition = transcript.document.length
    }
  }

  // --- AcpClient.Handler (reader thread) ---

  override fun onSessionUpdate(update: JsonObject) {
    val u = update["update"]?.jsonObject ?: return
    when (u["sessionUpdate"]?.jsonPrimitive?.contentOrNull) {
      "agent_message_chunk" -> appendChunk(u)
      "agent_thought_chunk" -> {}
      "tool_call" -> appendLine("⚙ ${u["title"]?.jsonPrimitive?.contentOrNull ?: u["kind"]?.jsonPrimitive?.contentOrNull ?: "инструмент"}")
      "tool_call_update" -> {}
      "plan" -> appendLine("🗺 план обновлён")
      else -> {}
    }
  }

  private fun appendChunk(u: JsonObject) {
    val text = u["content"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull ?: return
    SwingUtilities.invokeLater {
      transcript.append(text)
      transcript.caretPosition = transcript.document.length
    }
  }

  override fun onRequestPermission(params: JsonObject): JsonElement {
    val title = params["toolCall"]?.jsonObject?.get("title")?.jsonPrimitive?.contentOrNull ?: "Действие агента"
    val options = params["options"]?.jsonArray?.map { it.jsonObject } ?: emptyList()
    var selected: String? = null
    ApplicationManager.getApplication().invokeAndWait {
      val names = options.map { it["name"]?.jsonPrimitive?.contentOrNull ?: it.getValue("optionId").jsonPrimitive.content }
      val choice = Messages.showDialog(project, title, "Vibe Agent: разрешение", names.toTypedArray(), 0, Messages.getQuestionIcon())
      if (choice >= 0) selected = options[choice].getValue("optionId").jsonPrimitive.content
    }
    val chosen = selected
    return buildJsonObject {
      put("outcome", buildJsonObject {
        if (chosen != null) {
          put("outcome", "selected")
          put("optionId", chosen)
        }
        else {
          // Closed dialog = refusal, never a silent allow.
          put("outcome", "cancelled")
        }
      })
    }
  }

  override fun onReadTextFile(params: JsonObject): JsonElement = fileOps.readTextFile(params)

  override fun onWriteTextFile(params: JsonObject): JsonElement = fileOps.writeTextFile(params)

  override fun onProtocolLog(line: String) = appendLine(line)

  override fun onProcessExit(code: Int) {
    appendLine("[агент] процесс завершился (код $code)")
    client = null
  }
}
