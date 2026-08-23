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
import com.vibe.agent.checkpoints.CheckpointService
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
  private val checkpoints: CheckpointService? = project.basePath?.let { CheckpointService(it) }
  @Volatile private var stepBuffer: StringBuilder? = null
  @Volatile private var currentAgentMessage: AgentMessage? = null
  private val changedPaths = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

  init {
    border = JBUI.Borders.empty(4)
    val pipelineButton = JButton("Пайплайн…").apply { addActionListener { choosePipeline() } }
    val checkpointsButton = JButton("Чекпоинты…").apply { addActionListener { showCheckpoints() } }
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
    val leftButtons = JPanel().apply {
      layout = BoxLayout(this, BoxLayout.X_AXIS)
      add(pipelineButton)
      add(checkpointsButton)
    }
    val top = JPanel(BorderLayout()).apply {
      add(leftButtons, BorderLayout.WEST)
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
        checkpoints?.create("сообщение: ${text.take(48)}")?.let { checkpointLine("— чекпоинт ${it.hash.take(8)} · ${now()} —") }
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

  private fun showCheckpoints() {
    val service = checkpoints ?: run { systemLine("[чекпоинты] проект не под git — недоступны"); return }
    ApplicationManager.getApplication().executeOnPooledThread {
      val list = service.list()
      SwingUtilities.invokeLater {
        if (list.isEmpty()) { systemLine("[чекпоинты] пока нет — создаются на каждое сообщение агенту"); return@invokeLater }
        val fmt = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")
        val names = list.take(30).map {
          "${java.time.Instant.ofEpochMilli(it.atMillis).atZone(java.time.ZoneId.systemDefault()).toLocalTime().format(fmt)} · ${it.hash.take(8)} · ${it.label}"
        }
        val choice = Messages.showDialog(project, "Откатить рабочую папку к снимку? Файлы, созданные позже, останутся.", "Vibe Agent: чекпоинты", names.toTypedArray(), 0, Messages.getQuestionIcon())
        if (choice >= 0) {
          val cp = list[choice]
          val confirm = Messages.showYesNoDialog(project, "Рабочее дерево будет перезаписано состоянием снимка ${cp.hash.take(8)} («${cp.label}»). Продолжить?", "Подтверждение отката", Messages.getWarningIcon())
          if (confirm == Messages.YES) {
            ApplicationManager.getApplication().executeOnPooledThread {
              val ok = service.restore(cp)
              systemLine(if (ok) "⚑ откат к ${cp.hash.take(8)} выполнен" else "[чекпоинты] откат не удался (см. git)")
              ApplicationManager.getApplication().invokeLater {
                com.intellij.openapi.vfs.VfsUtil.markDirtyAndRefresh(true, true, true, com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(project.basePath!!))
              }
            }
          }
        }
      }
    }
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

  // --- рендер ленты (дизайн VibeIDE: пузырь только у пользователя; агент — полная ширина; лучше оригинала: кап ширины строки, единая шкала радиусов 4/8, один язык подписей) ---

  private class RoundedPanel(private val bg: java.awt.Color, private val radius: Int) : JPanel() {
    init { isOpaque = false }
    override fun paintComponent(g: java.awt.Graphics) {
      val g2 = g.create() as java.awt.Graphics2D
      g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
      g2.color = bg
      g2.fillRoundRect(0, 0, width, height, JBUI.scale(radius), JBUI.scale(radius))
      g2.dispose()
      super.paintComponent(g)
    }
  }

  private fun proseArea(fg: java.awt.Color? = null): JTextArea = JTextArea().apply {
    isEditable = false
    isOpaque = false
    lineWrap = true
    wrapStyleWord = true
    font = com.intellij.util.ui.JBFont.label().deriveFont(13f)
    fg?.let { foreground = it }
    border = JBUI.Borders.empty()
  }

  private fun metaLabel(text: String, right: Boolean): JLabel = JLabel(text).apply {
    font = com.intellij.util.ui.JBFont.label().deriveFont(Font.PLAIN, 11f)
    foreground = META_FG
    horizontalAlignment = if (right) JLabel.RIGHT else JLabel.LEFT
  }

  private inner class AgentMessage {
    val text = proseArea()
    val meta = metaLabel("Агент · ${now()}", right = false)
    val row = JPanel(BorderLayout(0, JBUI.scale(2))).apply {
      isOpaque = false
      border = JBUI.Borders.empty(4, 4, 8, 4)
      add(text, BorderLayout.CENTER)
      add(meta, BorderLayout.SOUTH)
      // better than the original: cap the text column so lines stay readable in a wide panel
      add(Box.createHorizontalStrut(0), BorderLayout.WEST)
      alignmentX = Component.LEFT_ALIGNMENT
      maximumSize = java.awt.Dimension(JBUI.scale(720), Int.MAX_VALUE)
    }
    fun append(s: String) { text.append(s) }
    fun finish(seconds: Double, suffix: String?) {
      meta.text = "Агент · ${now()} · ${"%.1f".format(seconds)} с${suffix?.let { " · $it" } ?: ""}"
    }
  }

  private fun now(): String = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))

  private fun userBubble(text: String) {
    SwingUtilities.invokeLater {
      val bubble = RoundedPanel(USER_BUBBLE, radius = 8).apply {
        layout = BorderLayout()
        border = JBUI.Borders.empty(6, 8)
        add(proseArea().also { it.text = text }, BorderLayout.CENTER)
      }
      val row = JPanel(BorderLayout(0, JBUI.scale(2))).apply {
        isOpaque = false
        border = JBUI.Borders.empty(4, 4, 8, 4)
        add(bubble, BorderLayout.CENTER)
        add(metaLabel("Вы · ${now()}", right = true), BorderLayout.SOUTH)
        add(Box.createHorizontalStrut(JBUI.scale(120)), BorderLayout.WEST)
        alignmentX = Component.LEFT_ALIGNMENT
      }
      messages.add(row)
      revalidateScroll()
    }
    currentAgentMessage = null
  }

  private fun agentMessage(): AgentMessage {
    var m = currentAgentMessage
    if (m == null) {
      m = AgentMessage()
      currentAgentMessage = m
      SwingUtilities.invokeLater {
        messages.add(m.row)
        revalidateScroll()
      }
    }
    return m
  }

  private fun appendAgentText(text: String) {
    val m = agentMessage()
    SwingUtilities.invokeLater {
      m.append(text)
      revalidateScroll()
    }
  }

  private fun finishAgentBubble(seconds: Double, suffix: String?) {
    val m = currentAgentMessage ?: return
    currentAgentMessage = null
    SwingUtilities.invokeLater {
      m.finish(seconds, suffix)
      revalidateScroll()
    }
  }

  /** Compact tool-call card, VibeIDE style: 4px radius, quiet border, 11px italic. */
  private fun toolCard(title: String) {
    SwingUtilities.invokeLater {
      val card = RoundedPanel(TOOL_CARD, radius = 4).apply {
        layout = BorderLayout()
        border = JBUI.Borders.empty(3, 8)
        add(JLabel(title).apply {
          font = com.intellij.util.ui.JBFont.label().deriveFont(Font.ITALIC, 11f)
          foreground = META_FG
        }, BorderLayout.WEST)
      }
      val row = JPanel(BorderLayout()).apply {
        isOpaque = false
        border = JBUI.Borders.empty(1, 4)
        add(card, BorderLayout.WEST)
        alignmentX = Component.LEFT_ALIGNMENT
      }
      messages.add(row)
      revalidateScroll()
    }
  }

  /** Centered checkpoint line, ghost-quiet. */
  private fun checkpointLine(text: String) {
    SwingUtilities.invokeLater {
      messages.add(JLabel(text, JLabel.CENTER).apply {
        font = com.intellij.util.ui.JBFont.label().deriveFont(Font.PLAIN, 10f)
        foreground = META_FG
        alignmentX = Component.LEFT_ALIGNMENT
        border = JBUI.Borders.empty(2)
      })
      revalidateScroll()
    }
  }

  private fun systemLine(text: String) {
    SwingUtilities.invokeLater {
      messages.add(JLabel(text).apply {
        font = com.intellij.util.ui.JBFont.label().deriveFont(Font.PLAIN, 10f)
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
      "tool_call" -> toolCard(u["title"]?.jsonPrimitive?.contentOrNull ?: u["kind"]?.jsonPrimitive?.contentOrNull ?: "инструмент")
      "plan" -> toolCard("план обновлён")
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
    val TOOL_CARD = JBColor.namedColor("Vibe.Chat.toolCardBackground", JBColor(0xF2F2F2, 0x26282E))
    val META_FG = JBColor.namedColor("Vibe.Chat.metaForeground", JBColor.namedColor("Label.infoForeground", JBColor.GRAY))
  }
}
