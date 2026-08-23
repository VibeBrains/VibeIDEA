// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.vibe.agent.acp.AcpClient
import com.vibe.agent.acp.AcpConfig
import com.vibe.agent.acp.AgentServerConfig
import com.vibe.agent.acp.IdeFileOps
import com.vibe.agent.design.DesignContextFile
import com.vibe.agent.pipelines.PipelinesFile
import com.vibe.agent.providers.ChatMessage
import com.vibe.agent.providers.LlmClient
import com.vibe.agent.providers.ModelEntry
import com.vibe.agent.providers.ProviderEntry
import com.vibe.agent.providers.ProviderGuard
import com.vibe.agent.providers.ProvidersService
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Font
import java.awt.datatransfer.DataFlavor
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import javax.swing.TransferHandler

/**
 * VibeIDE-style chat: user bubbles on the RIGHT, agent bubbles on the LEFT,
 * each with a time stamp; agent bubbles get the response duration on finish.
 * Selection is two-step like VibeIDE: pick an agent/provider, then a model.
 * Files dragged from the project tree drop into the input as relative paths.
 */
class AgentPanel(private val project: Project) : JPanel(BorderLayout()), AcpClient.Handler {
  private sealed interface Target { val label: String }
  private data class AcpTarget(val config: AgentServerConfig) : Target {
    override val label: String get() = "Агент: ${config.name}"
  }
  private data class LlmTarget(val provider: ProviderEntry) : Target {
    override val label: String get() = "LLM: ${provider.name}"
  }

  private val messages = JPanel().apply {
    layout = BoxLayout(this, BoxLayout.Y_AXIS)
    border = JBUI.Borders.empty(6)
    background = CHAT_BG
  }
  private val scroll = JBScrollPane(messages)
  private val input = JBTextField()
  private val agents: List<AgentServerConfig> = AcpConfig.load { systemLine("[конфиг] $it") }
  @Volatile private var providers: List<ProviderEntry> = ProvidersService.load(project.basePath) { systemLine("[providers] $it") }
  private var targets: List<Target> = buildTargets()
  private val targetCombo = JComboBox(DefaultComboBoxModel(targets.map { it.label }.toTypedArray()))
  private val modelCombo = JComboBox<String>()
  private val llmClient = LlmClient()
  private val llmHistory = ArrayList<ChatMessage>()
  private val fileOps = IdeFileOps(project)
  private var client: AcpClient? = null
  @Volatile private var stepBuffer: StringBuilder? = null
  @Volatile private var currentAgentBubble: Bubble? = null
  private val changedPaths = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

  init {
    border = JBUI.Borders.empty(4)
    val pipelineButton = JButton("Пайплайн…").apply { addActionListener { choosePipeline() } }
    val stopButton = JButton("Стоп").apply { addActionListener { stopAgent() } }
    val sendButton = JButton("Отправить").apply { addActionListener { send() } }
    modelCombo.isEnabled = false
    targetCombo.addActionListener { refreshModelCombo() }
    val combos = JPanel().apply {
      layout = BoxLayout(this, BoxLayout.X_AXIS)
      add(targetCombo)
      add(Box.createHorizontalStrut(4))
      add(modelCombo)
    }
    val top = JPanel(BorderLayout()).apply {
      add(pipelineButton, BorderLayout.WEST)
      add(combos, BorderLayout.CENTER)
      add(stopButton, BorderLayout.EAST)
    }
    val bottom = JPanel(BorderLayout()).apply {
      add(input, BorderLayout.CENTER)
      add(sendButton, BorderLayout.EAST)
    }
    add(top, BorderLayout.NORTH)
    add(scroll, BorderLayout.CENTER)
    add(bottom, BorderLayout.SOUTH)
    input.addActionListener { send() }
    installFileDrop()
    systemLine("Vibe Agent готов. Агенты: ${agents.joinToString { it.name }}; провайдеры: ${providers.joinToString { it.name }.ifEmpty { "нет" }}.")
    systemLine("Ключи провайдеров: Settings → Tools → Vibe Providers (или .vibe/.env). Реестры: ${AcpConfig.configPath()}, ~/.vibe/providers.json.")
    ProviderGuard.scan(providers).forEach { f -> systemLine("[guard:${f.severity}] ${f.message}") }
    refreshModelCombo()
    fetchProviderModels()
  }

  private fun buildTargets(): List<Target> = buildList<Target> {
    agents.forEach { add(AcpTarget(it)) }
    providers.forEach { add(LlmTarget(it)) }
  }

  private fun selectedTarget(): Target? = targets.getOrNull(targetCombo.selectedIndex.coerceAtLeast(0))

  private fun refreshModelCombo() {
    val t = selectedTarget()
    if (t is LlmTarget) {
      val models = t.provider.models.filter { it.active }
        .sortedWith(compareByDescending<ModelEntry> { it.default }.thenByDescending { it.pinned }.thenBy { it.name })
      modelCombo.model = DefaultComboBoxModel(models.map { it.name }.toTypedArray())
      modelCombo.isEnabled = models.isNotEmpty()
    }
    else {
      modelCombo.model = DefaultComboBoxModel(arrayOf("— модель агента —"))
      modelCombo.isEnabled = false
    }
  }

  private fun selectedModel(provider: ProviderEntry): ModelEntry? {
    val models = provider.models.filter { it.active }
      .sortedWith(compareByDescending<ModelEntry> { it.default }.thenByDescending { it.pinned }.thenBy { it.name })
    return models.getOrNull(modelCombo.selectedIndex.coerceAtLeast(0))
  }

  // --- отправка ---

  private fun send() {
    val text = input.text.trim()
    if (text.isEmpty()) return
    input.text = ""
    userBubble(text)
    val startedAt = System.currentTimeMillis()
    when (val target = selectedTarget()) {
      is LlmTarget -> sendToLlm(target, text, startedAt)
      is AcpTarget -> sendToAcp(text, startedAt)
      null -> systemLine("[ошибка] цель не выбрана")
    }
  }

  private fun sendToAcp(text: String, startedAt: Long) {
    ApplicationManager.getApplication().executeOnPooledThread {
      try {
        val design = DesignContextFile.load(project.basePath)
        val fullPrompt = if (design != null) DesignContextFile.promptBlock(design) + "\n" + text else text
        ensureClient().prompt(fullPrompt).whenComplete { result, error ->
          val secs = (System.currentTimeMillis() - startedAt) / 1000.0
          if (error != null) systemLine("[ошибка] ${error.message}")
          else {
            val stop = result?.jsonObject?.get("stopReason")?.jsonPrimitive?.contentOrNull
            finishAgentBubble(secs, stop)
          }
        }
      }
      catch (e: Exception) {
        systemLine("[ошибка] ${e.message}")
      }
    }
  }

  private fun sendToLlm(target: LlmTarget, text: String, startedAt: Long) {
    ApplicationManager.getApplication().executeOnPooledThread {
      try {
        val resolved = ProvidersService.resolve(target.provider, project.basePath) { systemLine("[providers] $it") } ?: return@executeOnPooledThread
        val model = selectedModel(target.provider) ?: run { systemLine("[providers] у '${target.provider.id}' нет активных моделей"); return@executeOnPooledThread }
        if (resolved.apiKey == null && !resolved.isLocal) {
          systemLine("[providers] нет ключа для '${target.provider.id}': Settings → Tools → Vibe Providers, либо .vibe/.env")
          return@executeOnPooledThread
        }
        if (resolved.isLocal) systemLine("[локальная модель]")
        llmHistory.add(ChatMessage("user", text))
        val answer = StringBuilder()
        llmClient.chat(resolved, model, llmHistory) { delta ->
          answer.append(delta)
          appendAgentText(delta)
        }
        llmHistory.add(ChatMessage("assistant", answer.toString()))
        finishAgentBubble((System.currentTimeMillis() - startedAt) / 1000.0, model.id)
      }
      catch (e: Exception) {
        systemLine("[ошибка] ${e.message}")
      }
    }
  }

  private fun ensureClient(): AcpClient {
    val existing = client
    if (existing != null && existing.isAlive && existing.sessionId != null) return existing
    val config = (selectedTarget() as? AcpTarget)?.config ?: agents.first()
    systemLine("[агент] запускаю: ${config.command} ${config.args.joinToString(" ")}")
    val fresh = AcpClient(config, project.basePath, this)
    fresh.start()
    client = fresh
    fresh.initializeAndOpenSession().get()
    systemLine("[агент] сессия открыта")
    return fresh
  }

  private fun stopAgent() {
    client?.stop()
    client = null
    systemLine("[агент] остановлен")
  }

  // --- пайплайны ---

  private fun choosePipeline() {
    val pipelines = PipelinesFile.load(project.basePath) { systemLine("[pipelines] $it") }
    if (pipelines.isEmpty()) {
      systemLine("[pipelines] нет пайплайнов: создайте .vibe/pipelines.json (спека — docs/vibe/manuals/pipelinesSpec.md)")
      return
    }
    val names = pipelines.map { "${it.name} (${it.steps.size} шагов)" }
    val choice = Messages.showDialog(project, "Какой пайплайн запустить?", "Vibe Agent: пайплайны", names.toTypedArray(), 0, Messages.getQuestionIcon())
    if (choice >= 0) runPipeline(pipelines[choice])
  }

  private fun runPipeline(pipeline: com.vibe.agent.pipelines.Pipeline) {
    systemLine("═══ Пайплайн «${pipeline.name}» — ${pipeline.steps.size} шагов ═══")
    ApplicationManager.getApplication().executeOnPooledThread {
      val artifacts = LinkedHashSet<String>()
      var lastSummary: String? = null
      var failed = false
      pipeline.steps.forEachIndexed { i, step ->
        val header = "— Шаг ${i + 1}/${pipeline.steps.size} [${step.role}]"
        if (failed && !step.continueOnFailure) {
          systemLine("$header — пропущен (предыдущий шаг провалился)")
          return@forEachIndexed
        }
        systemLine("$header ${step.task.take(80)}")
        val prompt = buildString {
          appendLine(PipelinesFile.rolePreamble(step.role))
          appendLine("Задача: ${step.task}")
          step.acceptance?.let { appendLine("Критерий готовности: $it") }
          if (!step.ignorePreviousArtifacts) {
            if (artifacts.isNotEmpty()) appendLine("Файлы, которых касались предыдущие шаги (прочитай нужные сам): ${artifacts.joinToString()}")
            lastSummary?.let { appendLine("Резюме предыдущего шага: $it") }
          }
        }
        try {
          val c = ensureClient()
          changedPaths.clear()
          stepBuffer = StringBuilder()
          val startedAt = System.currentTimeMillis()
          c.prompt(prompt).get()
          finishAgentBubble((System.currentTimeMillis() - startedAt) / 1000.0, "шаг ${i + 1}")
          val summaryText = stepBuffer?.toString().orEmpty()
          lastSummary = summaryText.takeLast(2000).ifBlank { "(шаг не оставил текста)" }
          artifacts.addAll(changedPaths)
          systemLine("$header завершён; изменённых файлов: ${changedPaths.size}")
        }
        catch (e: Exception) {
          failed = true
          systemLine("$header ПРОВАЛЕН: ${e.message}")
        }
        finally {
          stepBuffer = null
        }
      }
      systemLine("═══ Пайплайн «${pipeline.name}» ${if (failed) "остановлен с провалом" else "завершён"} ═══")
    }
  }

  // --- models.fetch ---

  private fun fetchProviderModels() {
    ApplicationManager.getApplication().executeOnPooledThread {
      var changed = false
      val updated = providers.map { p ->
        if (p.modelsFetch == null) return@map p
        val resolved = ProvidersService.resolve(p, project.basePath) { } ?: return@map p
        try {
          val ids = llmClient.listModels(resolved, p.modelsFetch.ifBlank { null })
          val known = p.models.map { it.id }.toSet()
          val extra = ids.filter { it !in known }.map { ModelEntry(id = it) }
          if (extra.isNotEmpty()) { changed = true; p.copy(models = p.models + extra) } else p
        }
        catch (e: Exception) {
          systemLine("[providers] '${p.id}': каталог моделей не получен (${e.message}) — работаю по static")
          p
        }
      }
      if (changed) {
        SwingUtilities.invokeLater {
          providers = updated
          val selected = targetCombo.selectedIndex
          targets = buildTargets()
          targetCombo.model = DefaultComboBoxModel(targets.map { it.label }.toTypedArray())
          if (selected in targets.indices) targetCombo.selectedIndex = selected
          refreshModelCombo()
          systemLine("[providers] каталоги моделей подтянуты")
        }
      }
    }
  }

  // --- drag-n-drop из дерева проекта ---

  private fun installFileDrop() {
    val handler = object : TransferHandler() {
      override fun canImport(support: TransferSupport): Boolean =
        support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)

      override fun importData(support: TransferSupport): Boolean {
        if (!canImport(support)) return false
        @Suppress("UNCHECKED_CAST")
        val files = support.transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>
        val base = project.basePath
        val paths = files.joinToString(" ") { f ->
          val p = f.absolutePath
          if (base != null && p.startsWith(base)) p.removePrefix(base).removePrefix("/") else p
        }
        input.text = (input.text + " " + paths).trim() + " "
        input.requestFocusInWindow()
        return true
      }
    }
    input.transferHandler = handler
    messages.transferHandler = handler
    transferHandler = handler
  }

  // --- пузыри ---

  private inner class Bubble(role: String, meta: String, right: Boolean) {
    val header = JLabel(meta).apply {
      font = font.deriveFont(Font.PLAIN, 10f)
      foreground = META_FG
    }
    val text = JTextArea().apply {
      isEditable = false
      lineWrap = true
      wrapStyleWord = true
      font = Font(Font.MONOSPACED, Font.PLAIN, 12)
      background = if (right) USER_BUBBLE else AGENT_BUBBLE
      border = JBUI.Borders.empty(6, 8)
    }
    val row = JPanel(BorderLayout()).apply {
      isOpaque = false
      border = JBUI.Borders.emptyBottom(6)
      val inner = JPanel(BorderLayout(0, 2)).apply {
        isOpaque = false
        add(header, BorderLayout.NORTH)
        add(text, BorderLayout.CENTER)
      }
      add(inner, BorderLayout.CENTER)
      add(Box.createHorizontalStrut(60), if (right) BorderLayout.WEST else BorderLayout.EAST)
      alignmentX = Component.LEFT_ALIGNMENT
    }
    init {
      header.horizontalAlignment = if (right) JLabel.RIGHT else JLabel.LEFT
    }
    fun append(t: String) { text.append(t) }
    fun setMeta(m: String) { header.text = m }
  }

  private fun now(): String = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))

  private fun userBubble(text: String) {
    SwingUtilities.invokeLater {
      val b = Bubble("user", "Вы · ${now()}", right = true)
      b.append(text)
      messages.add(b.row)
      revalidateScroll()
    }
    currentAgentBubble = null
  }

  private fun agentBubble(): Bubble {
    var b = currentAgentBubble
    if (b == null) {
      b = Bubble("agent", "Агент · ${now()}", right = false)
      currentAgentBubble = b
      SwingUtilities.invokeLater {
        messages.add(b.row)
        revalidateScroll()
      }
    }
    return b
  }

  private fun appendAgentText(text: String) {
    val b = agentBubble()
    SwingUtilities.invokeLater {
      b.append(text)
      revalidateScroll()
    }
  }

  private fun finishAgentBubble(seconds: Double, suffix: String?) {
    val b = currentAgentBubble ?: return
    currentAgentBubble = null
    SwingUtilities.invokeLater {
      b.setMeta("Агент · ${now()} · ${"%.1f".format(seconds)} с${suffix?.let { " · $it" } ?: ""}")
      revalidateScroll()
    }
  }

  private fun systemLine(text: String) {
    SwingUtilities.invokeLater {
      messages.add(JLabel(text).apply {
        font = font.deriveFont(Font.PLAIN, 10f)
        foreground = META_FG
        alignmentX = Component.LEFT_ALIGNMENT
        border = JBUI.Borders.empty(2, 4)
      })
      revalidateScroll()
    }
  }

  private fun revalidateScroll() {
    messages.revalidate()
    messages.repaint()
    SwingUtilities.invokeLater {
      scroll.verticalScrollBar.value = scroll.verticalScrollBar.maximum
    }
  }

  // --- AcpClient.Handler (reader thread) ---

  override fun onSessionUpdate(update: JsonObject) {
    val u = update["update"]?.jsonObject ?: return
    when (u["sessionUpdate"]?.jsonPrimitive?.contentOrNull) {
      "agent_message_chunk" -> {
        val text = u["content"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull ?: return
        stepBuffer?.append(text)
        appendAgentText(text)
      }
      "tool_call" -> systemLine("⚙ ${u["title"]?.jsonPrimitive?.contentOrNull ?: u["kind"]?.jsonPrimitive?.contentOrNull ?: "инструмент"}")
      "plan" -> systemLine("🗺 план обновлён")
      else -> {}
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

  override fun onWriteTextFile(params: JsonObject): JsonElement {
    params["path"]?.jsonPrimitive?.contentOrNull?.let { changedPaths.add(it) }
    return fileOps.writeTextFile(params)
  }

  override fun onProtocolLog(line: String) = systemLine(line)

  override fun onProcessExit(code: Int) {
    systemLine("[агент] процесс завершился (код $code)")
    client = null
  }

  private companion object {
    // Theme tokens: any theme (ours or third-party) recolours the chat via these
    // keys; the JBColor defaults keep stock light/dark themes sensible.
    val CHAT_BG = JBColor.namedColor("Vibe.Chat.background", JBColor.namedColor("Panel.background", JBColor.PanelBackground))
    val USER_BUBBLE = JBColor.namedColor("Vibe.Chat.userBubbleBackground", JBColor(0xD8ECF8, 0x2A3550))
    val AGENT_BUBBLE = JBColor.namedColor("Vibe.Chat.agentBubbleBackground", JBColor(0xEDEDED, 0x2B2D30))
    val META_FG = JBColor.namedColor("Vibe.Chat.metaForeground", JBColor.namedColor("Label.infoForeground", JBColor.GRAY))
  }
}
