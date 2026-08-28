// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.vibe.agent.acp.AcpClient
import com.vibe.agent.acp.AcpConfig
import com.vibe.agent.acp.AgentServerConfig
import com.vibe.agent.acp.ContentBlock
import com.vibe.agent.acp.IdeFileOps
import com.vibe.agent.acp.ToolCall
import com.vibe.agent.acp.ToolCallRegistry
import com.vibe.agent.audit.AuditEvent
import com.vibe.agent.audit.AuditLog
import com.vibe.agent.audit.ToolCallAudit
import com.vibe.agent.checkpoints.CheckpointService
import com.vibe.agent.design.DesignContextFile
import com.vibe.agent.gates.TurnChecks
import com.vibe.agent.gates.TurnChecksDecision
import com.vibe.agent.gates.VerifyGateDecision
import com.vibe.agent.gates.VerifyGatePolicy
import com.vibe.agent.gates.VerifyGateRunner
import com.vibe.agent.gates.VibeBreakerService
import com.vibe.agent.guard.ShellSafetyAnalyzer
import com.vibe.agent.hooks.HookDecision
import com.vibe.agent.hooks.HookEvent
import com.vibe.agent.hooks.HookRunner
import com.vibe.agent.terminal.AgentTerminalService
import com.vibe.agent.history.ChatMessageRecord
import com.vibe.agent.history.ChatThread
import com.vibe.agent.history.Role
import com.vibe.agent.history.StoredImage
import com.vibe.agent.history.ThreadState
import com.vibe.agent.history.VibeChatHistory
import com.vibe.agent.pipelines.PipelinesFile
import com.vibe.agent.providers.ChatMessage
import com.vibe.agent.providers.ImagePart
import com.vibe.agent.providers.LlmClient
import com.vibe.agent.providers.ModelEntry
import com.vibe.agent.providers.ProviderEntry
import com.vibe.agent.providers.ProviderGuard
import com.vibe.agent.providers.ProvidersService
import com.vibe.agent.settings.ModelVisibility
import com.vibe.agent.settings.VibeAgentSettings
import com.vibe.agent.settings.VibeChatSettings
import com.vibe.agent.settings.VibeProvidersConfigurable
import com.vibe.agent.ui.composer.ChatTarget
import com.vibe.agent.ui.composer.ComposedMessage
import com.vibe.agent.ui.composer.ComposerPanel
import com.vibe.agent.ui.composer.ContextSerializer
import com.vibe.agent.ui.composer.EditorContext
import com.vibe.agent.ui.composer.ImageAttachment
import com.vibe.agent.ui.composer.LandingBlock
import com.vibe.agent.ui.composer.MentionResolver
import com.vibe.agent.ui.composer.MentionSyntax
import com.vibe.agent.ui.composer.ModePicker
import com.vibe.agent.ui.composer.ModelPicker
import com.vibe.agent.ui.composer.PillButton
import com.vibe.agent.ui.history.HistoryPopup
import com.vibe.agent.ui.history.HistoryRail
import com.vibe.agent.ui.history.ThreadListPanel
import com.intellij.ide.util.PropertiesComponent
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Font
import java.awt.KeyboardFocusManager
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.Base64
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.time.format.DateTimeFormatter
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * VibeIDE-style chat: user bubbles on the RIGHT, agent text full-width on the LEFT,
 * each with a time stamp; agent rows get the response duration on finish.
 * The composer (input, @-context, attachments, pills) sits on top of an empty chat
 * and moves under the feed once the conversation starts. One turn at a time: while
 * a turn runs, new input queues and is merged into the next turn.
 *
 * Every conversation is a persistent thread ([VibeChatHistory]): the tab strip on top
 * switches between open threads, the history surfaces (landing block, «история ▾»
 * popup, right rail) reopen old ones. The feed is re-rendered from the transcript on
 * every switch; a running turn stays bound to the thread it started in.
 */
class AgentPanel(private val project: Project) : JPanel(BorderLayout()), AcpClient.Handler, Disposable {
  private val messages = JPanel().apply {
    layout = BoxLayout(this, BoxLayout.Y_AXIS)
    border = JBUI.Borders.empty(6)
    background = CHAT_BG
  }
  /** Scroll view tracks the viewport width so rows wrap instead of growing sideways. */
  private class FeedView : JPanel(BorderLayout()), javax.swing.Scrollable {
    init { isOpaque = false }
    override fun getPreferredScrollableViewportSize(): java.awt.Dimension = preferredSize
    override fun getScrollableUnitIncrement(r: java.awt.Rectangle, o: Int, d: Int): Int = JBUI.scale(16)
    override fun getScrollableBlockIncrement(r: java.awt.Rectangle, o: Int, d: Int): Int = JBUI.scale(64)
    override fun getScrollableTracksViewportWidth(): Boolean = true
    override fun getScrollableTracksViewportHeight(): Boolean = false
  }

  private val scroll = JBScrollPane(FeedView().apply {
    add(messages, BorderLayout.NORTH)
  }).apply {
    horizontalScrollBarPolicy = javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
  }

  // Loaded OFF the EDT in init (config files on disk); empty until then.
  @Volatile private var agents: List<AgentServerConfig> = emptyList()
  @Volatile private var providers: List<ProviderEntry> = emptyList()
  /** Models declared in providers.json (before any catalog fetch) — they get the «providers.json» mark. */
  @Volatile private var staticModelIds: Map<String, Set<String>> = emptyMap()
  private val llmClient = LlmClient()
  private val llmCancel = java.util.concurrent.atomic.AtomicBoolean(false)
  private val fileOps = IdeFileOps(project)
  @Volatile private var client: AcpClient? = null
  @Volatile private var clientConfig: AgentServerConfig? = null
  /** Guards check-then-act on [client]: ensureClient (pooled), onProcessExit (exit thread), dispose (EDT). */
  private val clientLock = Any()
  private val checkpoints: CheckpointService? = project.basePath?.let { CheckpointService(it) }
  // One shared audit log per project (writer here, reader in the viewer action) — see VibeAuditService.
  private val audit: AuditLog? = com.vibe.agent.audit.VibeAuditService.getInstance(project).get()
  /** Tool-calls of the running turn, assembled from the session/update stream by id. */
  private val toolCalls = ToolCallRegistry()
  private val hooks = HookRunner(project) { systemLine("[хук] $it") }
  private val terminals = AgentTerminalService(project.basePath)
  /** Live terminal consoles by terminal id (Claude _meta.terminal_output stream). */
  private val terminalConsoles = java.util.concurrent.ConcurrentHashMap<String, TerminalConsole>()
  /** The current turn's collapsible reasoning block (ACP agent_thought_chunk), created on first thought. */
  @Volatile private var thoughtsBlock: ThoughtsBlock? = null
  private val verifyRunner: VerifyGateRunner? = project.basePath?.let { VerifyGateRunner(it) }
  private val breakers = VibeBreakerService.getInstance(project)
  private val status = VibeAgentStatusService.getInstance(project)
  @Volatile private var stepBuffer: StringBuilder? = null
  @Volatile private var currentAgentMessage: AgentMessage? = null
  private val changedPaths = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
  /** Set when a turn ran an edit/command tool: its writes may be invisible to the client (agent-internal Bash). */
  @Volatile private var turnHadMutatingTool = false

  /** CAS-guarded: concurrent finishers (reader/exit/pooled threads) must not double-finish. */
  private val turnInFlight = java.util.concurrent.atomic.AtomicBoolean(false)
  @Volatile private var disposed = false
  private var target: ChatTarget? = null
  private var targets: List<ChatTarget> = emptyList()
  /** A thread's saved target that is not in [targets] yet (model catalogs load async). */
  private var desiredTargetId: String? = null
  private var conversationStarted = false

  // --- threads (wave C): the store is the source of truth, the feed is a projection ---
  private val history = VibeChatHistory.getInstance()
  private var currentThreadId: String = ""
  private val openTabIds = ArrayList<String>()
  /** Per-thread composer drafts; in-memory by design (a restart drops them). */
  private val drafts = HashMap<String, ComposedMessage>()
  /** The thread the running turn appends to (the user may switch tabs meanwhile). */
  @Volatile private var turnThreadId: String? = null
  /** Assistant text of the running turn (reader thread appends, EDT projects). */
  private val turnText = StringBuffer()
  /** How much of [turnText] the live feed row already shows (EDT-only). */
  private var uiConsumed = 0
  /** Row components aligned with the current thread's message indices (best effort during a live turn). */
  private val recordRows = ArrayList<JComponent>()

  private val composer = ComposerPanel(project, this, object : ComposerPanel.Listener {
    override fun onSend(message: ComposedMessage): Boolean = startTurn(message)
    override fun onStop() = cancelTurn()
    override fun onNotice(text: String) = systemLine("[композер] $text")
  })
  private val modelPicker = ModelPicker({ selectTarget(it) }, { openSettings() })
  private val modePicker = ModePicker { modeId -> switchMode(modeId) }
  private val historyCallbacks = object : ThreadListPanel.Callbacks {
    override fun onOpen(threadId: String) = activateThread(threadId)
    override fun onOpenAtMessage(threadId: String, messageIndex: Int) = openThreadAt(threadId, messageIndex)
  }
  private val landingList = ThreadListPanel(project, ThreadListPanel.Mode.LANDING, this, historyCallbacks)
  private val landing = LandingBlock(landingList) { text -> startTurn(ComposedMessage(text)) }
  private val historyPill = PillButton(icon = AllIcons.Vcs.History, dropdown = true) { openHistoryPopup() }.apply {
    toolTipText = "История чатов"
  }
  private val tabsStrip = ChatTabsStrip(object : ChatTabsStrip.Callbacks {
    override fun onSelect(threadId: String) = activateThread(threadId)
    override fun onClose(threadId: String) = closeTab(threadId)
    override fun onNewChat() = newChat()
    override fun onToggleRail() = toggleRail()
  })
  private var rail: HistoryRail? = null
  private var railDisposable: Disposable? = null
  private val centerWrap = JPanel(BorderLayout()).apply { isOpaque = false }
  private val topColumn = JPanel().apply {
    layout = BoxLayout(this, BoxLayout.Y_AXIS)
    isOpaque = false
  }

  init {
    border = JBUI.Borders.empty(4)
    composer.addPill(modePicker.pill)
    composer.addPill(modelPicker.pill)
    composer.addPill(PillButton(icon = AllIcons.Actions.RunAll) { choosePipeline() }.apply { toolTipText = "Пайплайн… (.vibe/pipelines.json)" })
    composer.addPill(PillButton(icon = AllIcons.General.Settings) { openSettings() }.apply { toolTipText = "Провайдеры и ключи API (Settings → Tools → VibeIDEA)" })
    composer.addRightPill(historyPill)
    add(tabsStrip, BorderLayout.NORTH)
    add(centerWrap, BorderLayout.CENTER)
    centerWrap.add(scroll, BorderLayout.CENTER)
    restoreTabs()
    relayout()
    applyRailVisibility()
    history.addListener(this) { onHistoryChanged() }
    systemLine("Ключи провайдеров: Settings → Tools → VibeIDEA → Провайдеры (или .vibe/.env). Реестры: ${AcpConfig.configPath()}, ~/.vibe/providers.json.")
    // Config files live on disk — never read (or seed) them on the EDT; publish results back here.
    ApplicationManager.getApplication().executeOnPooledThread {
      val loadedAgents = AcpConfig.load { systemLine("[конфиг] $it") }
      val loadedProviders = ProvidersService.load(project.basePath) { systemLine("[providers] $it") }
      hooks.seedExampleIfNeeded()
      val hooksDisabled = hooks.hasHooksButDisabled()
      val guardFindings = ProviderGuard.scan(loadedProviders)
      SwingUtilities.invokeLater {
        if (disposed) return@invokeLater
        agents = loadedAgents
        providers = loadedProviders
        staticModelIds = loadedProviders.associate { p -> p.id to p.models.map { it.id }.toSet() }
        systemLine("Vibe Agent готов. Агенты: ${loadedAgents.joinToString { it.name }}; провайдеры: ${loadedProviders.joinToString { it.name }.ifEmpty { "нет" }}.")
        guardFindings.forEach { f -> systemLine("[guard:${f.severity}] ${f.message}") }
        if (hooksDisabled) systemLine("[хуки] в проекте есть .vibe/hooks.json, но хуки выключены — включить: Settings → Tools → VibeIDEA → Агент")
        rebuildTargets()
        fetchProviderModels()
      }
    }
    project.messageBus.connect(this).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
      override fun selectionChanged(event: FileEditorManagerEvent) = updateLanding()
    })
  }

  /** The tool window points its preferred focus here (otherwise a read-only bubble wins after re-activation). */
  val preferredFocusComponent: JComponent get() = composer.inputComponent

  override fun dispose() {
    disposed = true
    turnThreadId?.let { history.endTurn(it) }
    llmCancel.set(true)
    llmClient.cancel()
    composer.queue.clear()
    turnInFlight.set(false)
    terminals.disposeAll()
    // audit is owned by VibeAuditService (project-scoped) — do not close it here.
    synchronized(clientLock) {
      client?.stop()
      client = null
      clientConfig = null
    }
  }

  // --- layout: composer on top of an empty chat, under the feed once the conversation starts ---

  private fun relayout() {
    // Removing the focused composer transfers focus away; bring it back after re-adding.
    val focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
    val hadFocus = focusOwner != null && SwingUtilities.isDescendingFrom(focusOwner, composer)
    centerWrap.remove(composer)
    centerWrap.remove(topColumn)
    topColumn.removeAll()
    if (conversationStarted) {
      centerWrap.add(composer, BorderLayout.SOUTH)
    }
    else {
      topColumn.add(composer.apply { alignmentX = Component.LEFT_ALIGNMENT })
      topColumn.add(landing.apply { alignmentX = Component.LEFT_ALIGNMENT })
      centerWrap.add(topColumn, BorderLayout.NORTH)
      updateLanding()
    }
    revalidate()
    repaint()
    if (hadFocus) composer.focusInput()
  }

  private fun updateLanding() {
    if (conversationStarted) return
    val label = when (val t = target) {
      is ChatTarget.Agent -> t.label
      is ChatTarget.Model -> "${t.provider.name}:${t.model.name}"
      null -> null
    }
    // The gate must agree with what the landing list actually shows (same scope predicate).
    val listedThreads = history.listed()
    val scoped = if (history.showAllProjects) listedThreads else listedThreads.filter { history.matchesWorkspace(it, project.basePath) }
    landing.update(EditorContext.activeFile(project)?.name, label, showPastChats = scoped.isNotEmpty())
    landingList.refresh()
  }

  private fun markConversationStarted() {
    if (conversationStarted) return
    conversationStarted = true
    relayout()
  }

  // --- targets ---

  private fun buildTargets(): List<ChatTarget> = buildList {
    agents.forEach { add(ChatTarget.Agent(it)) }
    providers.forEach { p ->
      p.models.filter { it.active && !ModelVisibility.isHidden(p.id, it.id) }
        .sortedWith(compareByDescending<ModelEntry> { it.default }.thenByDescending { it.pinned }.thenBy { it.name })
        .forEach { m -> add(ChatTarget.Model(p, m, static = staticModelIds[p.id]?.contains(m.id) == true)) }
    }
  }

  private fun rebuildTargets() {
    targets = buildTargets()
    val wanted = desiredTargetId
      ?: target?.id
      ?: history.get(currentThreadId)?.state?.targetId
      ?: VibeChatSettings.rememberedTarget(project)?.let { t ->
        val model = VibeChatSettings.rememberedModel(project)
        if (model != null) "$t/$model" else t
      }
    val found = targets.firstOrNull { it.id == wanted }
    // A fallback pick must not overwrite the thread's saved choice: keep wanting the real one
    // until the async model catalogs bring it (fetchProviderModels re-runs this).
    desiredTargetId = if (found == null) wanted else null
    val selected = found ?: targets.firstOrNull()
    modelPicker.setTargets(targets, selected)
    selectTarget(selected, persistToThread = found != null)
  }

  private fun selectTargetById(id: String?) {
    val t = targets.firstOrNull { it.id == id }
    if (t == null) {
      desiredTargetId = id
      return
    }
    desiredTargetId = null
    modelPicker.setTargets(targets, t)
    selectTarget(t)
  }

  private fun selectTarget(t: ChatTarget?, persistToThread: Boolean = true) {
    if (persistToThread) desiredTargetId = null
    target = t
    composer.targetAvailable = t != null
    when (t) {
      is ChatTarget.Agent -> {
        VibeChatSettings.rememberChoice(project, "acp:${t.config.name}", null)
        val c = client?.takeIf { clientConfig == t.config && it.isAlive }
        modePicker.setModes(c?.modes)
        composer.setImagesAllowed(c?.capabilities?.image != false, NO_IMAGE_AGENT)
      }
      is ChatTarget.Model -> {
        VibeChatSettings.rememberChoice(project, "llm:${t.provider.id}", t.model.id)
        modePicker.setModes(null)
        composer.setImagesAllowed(t.model.vision != false, "Модель ${t.model.name} не принимает изображения (vision: false в providers.json)")
      }
      null -> {
        modePicker.setModes(null)
        composer.setImagesAllowed(true, null)
      }
    }
    // The choice follows the thread (restored when its tab is activated).
    if (persistToThread && currentThreadId.isNotEmpty() && history.get(currentThreadId)?.state?.targetId != t?.id) {
      history.updateState(currentThreadId, ThreadState(t?.id))
    }
    updateLanding()
  }

  private fun openSettings() {
    ShowSettingsUtil.getInstance().showSettingsDialog(project, VibeProvidersConfigurable::class.java)
  }

  private fun switchMode(modeId: String) {
    val c = client ?: return
    ApplicationManager.getApplication().executeOnPooledThread {
      try {
        c.setMode(modeId).whenComplete { _, error ->
          if (error != null) systemLine("[агент] режим не переключён: ${error.message}")
          SwingUtilities.invokeLater { modePicker.setModes(c.modes) }
        }
      }
      catch (e: Exception) {
        systemLine("[агент] режим не переключён: ${e.message}")
      }
    }
  }

  // --- turns ---

  /** Validates, shows the user bubble and starts the turn; false keeps the draft in the composer. */
  private fun startTurn(message: ComposedMessage, threadId: String = currentThreadId): Boolean {
    if (disposed) return false
    val t = target ?: run {
      systemLine("[ошибка] некому отправлять: добавьте агента в ${AcpConfig.configPath()} или провайдера в ~/.vibe/providers.json")
      return false
    }
    if (turnInFlight.get()) {
      composer.queue.add(message)
      return true
    }
    if (t is ChatTarget.Model && t.model.vision == false && message.images.isNotEmpty()) {
      Messages.showErrorDialog(project,
        "Выбранная модель (${t.provider.name}/${t.model.name}) не поддерживает изображения. Переключитесь на vision-модель либо удалите вложение.",
        "Vibe Agent")
      return false
    }
    // A latched security breaker blocks starting an agent turn until the user clears it (VibeIDE contract).
    if (t is ChatTarget.Agent && breakers.isBlocking() && !confirmClearBreakers()) return false
    // The store is app-wide: an untagged thread can be open in another window too.
    if (!history.tryBeginTurn(threadId)) {
      systemLine("[тред] занят другим окном — дождитесь завершения его хода")
      return false
    }
    turnInFlight.set(true)
    status.set(VibeAgentStatusService.State.RUNNING)
    // The cancel flag belongs to the whole turn (Stop during context resolution must not be lost).
    llmCancel.set(false)
    composer.busy = true
    turnThreadId = threadId
    turnText.setLength(0)
    uiConsumed = 0
    // Persist first: the feed and the store must agree even if the turn dies during context resolution.
    val displayText = message.text.ifBlank { ContextSerializer.ATTACHMENTS_ONLY_TEXT }
    val storedImages = message.images.map { StoredImage(it.name, it.mimeType, Base64.getEncoder().encodeToString(it.bytes)) }
    history.append(threadId, ChatMessageRecord(Role.USER, displayText, storedImages, nowIso()))
    if (threadId == currentThreadId) {
      markConversationStarted()
      userBubble(displayText)
    }
    val selection = EditorContext.currentSelection(project)
    val startedAt = System.currentTimeMillis()
    ApplicationManager.getApplication().executeOnPooledThread {
      try {
        val tokens = MentionSyntax.parse(message.text)
        // Non-blocking reads yield to pending write actions instead of stalling the EDT on a cold index.
        val resolution = if (tokens.isEmpty()) null else ReadAction.nonBlocking(Callable {
          MentionResolver(project).resolve(tokens, selection)
        }).expireWith(this).executeSynchronously()
        resolution?.unresolved?.takeIf { it.isNotEmpty() }?.let { bad ->
          systemLine("[контекст] не удалось разрешить ссылки: ${bad.joinToString(", ")} — проверьте путь или имя символа")
        }
        val refs = (message.context + resolution?.refs.orEmpty()).distinctBy { it.key }
        val loaded = ReadAction.nonBlocking(Callable { ContextSerializer.load(project, refs) }).expireWith(this).executeSynchronously()
        if (refs.isNotEmpty()) systemLine("[контекст] приложено: ${refs.joinToString { it.label }}")
        // The wire text (with inlined context) becomes known only now — fill it into the stored record.
        if (t is ChatTarget.Model) {
          history.setLastUserWireText(threadId, ContextSerializer.llmText(message.text, loaded).takeIf { it != displayText })
        }
        if (llmCancel.get() || disposed) {
          systemLine("[стоп] ход отменён до отправки")
          finishTurn()
          return@executeOnPooledThread
        }
        when (t) {
          is ChatTarget.Model -> sendToLlm(t, startedAt)
          is ChatTarget.Agent -> sendToAcp(t, message.text, loaded, message.images, startedAt)
        }
      }
      catch (e: Exception) {
        systemLine("[ошибка] ${e.message}")
        finishTurn()
      }
    }
    return true
  }

  /** Called from any thread when the turn ends (normally, cancelled or failed). Drains the queue. */
  private fun finishTurn() {
    // CAS: the reader, exit and pooled threads may all report the end of the same turn.
    if (!turnInFlight.compareAndSet(true, false)) return
    val endedThreadId = turnThreadId
    turnThreadId = null
    endedThreadId?.let { history.endTurn(it) }
    status.set(if (breakers.isBlocking()) VibeAgentStatusService.State.BLOCKED else VibeAgentStatusService.State.IDLE)
    if (disposed) return
    notifyTurnEndIfAway()
    SwingUtilities.invokeLater {
      if (disposed) return@invokeLater
      composer.busy = false
      // Queued notes belong to the thread whose turn just ended, not to whichever tab is open now.
      composer.queue.drain()?.let { merged ->
        if (!startTurn(merged, endedThreadId ?: currentThreadId)) composer.restoreDraft(merged)
      }
    }
  }

  /** Modern touch: a balloon when a turn finishes while the IDE window is NOT active (you tabbed away). */
  private fun notifyTurnEndIfAway() {
    SwingUtilities.invokeLater {
      if (disposed) return@invokeLater
      val active = com.intellij.openapi.wm.WindowManager.getInstance().getFrame(project)?.isActive == true
      if (active) return@invokeLater
      com.intellij.notification.NotificationGroupManager.getInstance()
        .getNotificationGroup("Vibe Agent")
        .createNotification("Vibe Agent завершил ход", com.intellij.notification.NotificationType.INFORMATION)
        .notify(project)
    }
  }

  private fun cancelTurn() {
    llmCancel.set(true)
    llmClient.cancel()
    val c = client
    systemLine("[стоп] прерываю ход…")
    if (c == null) return
    // Off the EDT: send() is synchronized and may sit behind a multi-megabyte prompt write.
    ApplicationManager.getApplication().executeOnPooledThread {
      if (c.sessionId == null) {
        // Still in the handshake: nothing to session/cancel — kill the process, pending futures fail, the turn ends.
        synchronized(clientLock) {
          c.stop()
          if (client === c) { client = null; clientConfig = null }
        }
      }
      else c.cancel()
    }
  }

  private fun sendToAcp(t: ChatTarget.Agent, text: String, loaded: List<ContextSerializer.Loaded>, images: List<ImageAttachment>, startedAt: Long) {
    checkpoints?.create("сообщение: ${text.take(CHECKPOINT_LABEL_LEN)}")?.let {
      checkpointLine(it)
      audit?.append(AuditEvent(System.currentTimeMillis(), AuditEvent.Action.CHECKPOINT, ok = true,
        meta = mapOf("hash" to it.hash.take(12))))
    }
    val design = DesignContextFile.load(project.basePath)
    val fullPrompt = if (design != null) DesignContextFile.promptBlock(design) + "\n" + text else text
    val c = ensureClient(t.config)
    // A fresh turn: tool-call ids and the changed-files set are per-turn.
    toolCalls.reset()
    terminalConsoles.clear()
    changedPaths.clear()
    turnHadMutatingTool = false
    // thoughtsBlock is EDT-owned (created/read in appendThought's invokeLater) — reset it there, not here.
    audit?.append(AuditEvent(System.currentTimeMillis(), AuditEvent.Action.PROMPT, ok = true,
      model = "acp/${t.config.name}", meta = mapOf("chars" to text.length.toString())))
    SwingUtilities.invokeLater {
      modePicker.setModes(c.modes)
      composer.setImagesAllowed(c.capabilities?.image != false, NO_IMAGE_AGENT)
    }
    if (images.isNotEmpty() && c.capabilities?.image != true) systemLine("[агент] не принимает изображения — отправлено без вложений")
    val blocks = ContextSerializer.acpBlocks(fullPrompt, loaded, images, c.capabilities)
    promptAcpTurn(c, blocks, t, startedAt, verifyAttempt = 0, checkAttempt = 0)
  }

  /**
   * Send one ACP prompt and, on end_turn, run the post-turn gates. A gate that
   * BOUNCES re-prompts the same session with a synthetic corrective message
   * (attempt counters carried forward) instead of ending the turn — this is how
   * VERIFY-GATE/TURN-CHECKS enforce "not done until green" in the ACP model,
   * where there is no `vibe_complete` tool to hang them on.
   */
  private fun promptAcpTurn(c: AcpClient, blocks: List<ContentBlock>, t: ChatTarget.Agent, startedAt: Long, verifyAttempt: Int, checkAttempt: Int) {
    c.prompt(blocks).whenComplete { result, error ->
      // Any throw here (a non-object result, a re-prompt failing) must still END the turn — otherwise
      // turnInFlight/history.activeTurns stay stuck and the panel wedges app-wide until restart.
      try {
        val secs = (System.currentTimeMillis() - startedAt) / 1000.0
        if (error != null) {
          finishAgentBubble(secs, "ошибка")
          systemLine("[ошибка] ${error.message}")
          audit?.append(AuditEvent(System.currentTimeMillis(), AuditEvent.Action.REPLY, ok = false,
            model = "acp/${t.config.name}", latencyMs = System.currentTimeMillis() - startedAt,
            meta = mapOf("error" to (error.message ?: "error"))))
          finishTurn()
          return@whenComplete
        }
        // Lenient: a null / non-object result (JsonNull from a `{"result":null}` reply) yields stop=null, not a throw.
        val stop = (result as? JsonObject)?.get("stopReason")?.jsonPrimitive?.contentOrNull
        if (stop == STOP_CANCELLED || llmCancel.get() || disposed) {
          finishAgentBubble(secs, stop)
          finishTurn()
          return@whenComplete
        }
        // Gates may run a build command and read files — never on the reader thread that completed us.
        ApplicationManager.getApplication().executeOnPooledThread {
          try {
            status.set(VibeAgentStatusService.State.GATE)
            val bounce = evaluateGates(verifyAttempt, checkAttempt)
            if (bounce == null) status.set(VibeAgentStatusService.State.RUNNING)
            if (bounce != null && !llmCancel.get() && !disposed && c.isAlive) {
              finishAgentBubble(secs, "проверка: возврат")
              promptAcpTurn(c, listOf(ContentBlock.Text(bounce.message)), t, startedAt, bounce.verifyAttempt, bounce.checkAttempt)
            }
            else {
              finishAgentBubble(secs, stop)
              audit?.append(AuditEvent(System.currentTimeMillis(), AuditEvent.Action.REPLY, ok = true,
                model = "acp/${t.config.name}", latencyMs = System.currentTimeMillis() - startedAt,
                meta = mapOf("stopReason" to (stop ?: "end_turn"))))
              runTurnEndHooks()
              finishTurn()
            }
          }
          catch (e: Exception) {
            finishAgentBubble(secs, "ошибка")
            systemLine("[ошибка] гейт/возврат: ${e.message}")
            finishTurn()
          }
        }
      }
      catch (e: Exception) {
        systemLine("[ошибка] завершение хода: ${e.message}")
        finishTurn()
      }
    }
  }

  private data class GateBounce(val message: String, val verifyAttempt: Int, val checkAttempt: Int)

  /**
   * Post-turn gates over the files this turn changed. Returns a bounce (synthetic
   * follow-up prompt) or null to complete. Only runs when the turn mutated files.
   */
  private fun evaluateGates(verifyAttempt: Int, checkAttempt: Int): GateBounce? {
    // Stop pressed → the user is done with this turn; do not launch a minutes-long verify build.
    if (llmCancel.get() || disposed) return null
    // A Bash/edit tool may have changed files the client never saw as fs/write, so the gate runs on
    // any mutating turn — not only when changedPaths is populated.
    if (changedPaths.isEmpty() && !turnHadMutatingTool) return null
    val paths = changedPaths.toList()
    val cMode = VibeAgentSettings.checksMode

    // --- TURN-CHECKS: scan + trip FIRST, unconditionally. A leaked secret or a protected-path write
    // is a present harm that must latch the breaker even if VERIFY-GATE bounces/stops this round. ---
    val findings = if (cMode == VibeAgentSettings.CHECKS_OFF) emptyList() else {
      val maxFiles = VibeAgentSettings.checksMaxFiles
      val scanned = paths.take(maxFiles)
      val contents = scanned.mapNotNull { p -> readFileForScan(p)?.let { p to it } }
      // A silently-unscanned file weakens the secret-leak guarantee — say so rather than hide it.
      val skippedByCount = paths.size - scanned.size
      val skippedBySize = scanned.size - contents.size
      // Only the CONTENT (secret) scan is capped; protected-path checks every path below.
      if (skippedByCount > 0 || skippedBySize > 0) systemLine(
        "[проверки хода] на утечку секретов не просканировано файлов: ${skippedByCount + skippedBySize}" +
          (if (skippedByCount > 0) " (сверх лимита $maxFiles)" else "") +
          (if (skippedBySize > 0) " (крупнее ${VibeAgentSettings.checksMaxFileKb} КБ)" else ""))
      TurnChecks.scanSecretLeak(contents, maxFiles) + TurnChecks.scanProtectedPath(paths)
    }
    if (findings.isNotEmpty()) {
      findings.forEach { f ->
        val id = if (f.check == com.vibe.agent.gates.TurnCheckId.NO_SECRET_LEAK) VibeBreakerService.SECRET_LEAK else VibeBreakerService.PROTECTED_PATH
        if (breakers.trip(id, "${f.detail}: ${f.path}", System.currentTimeMillis())) {
          audit?.append(AuditEvent(System.currentTimeMillis(), AuditEvent.Action.CIRCUIT_BREAKER_OPENED, ok = false,
            meta = mapOf("breaker" to id, "reason" to f.detail)))
        }
      }
      audit?.append(AuditEvent(System.currentTimeMillis(), AuditEvent.Action.TURN_CHECK, ok = false,
        meta = mapOf("findings" to findings.size.toString(), "mode" to cMode)))
    }

    // --- VERIFY-GATE (build/tests) ---
    val vMode = VibeAgentSettings.verifyMode
    if (vMode != VibeAgentSettings.VERIFY_OFF && verifyRunner != null && VibeAgentSettings.verifyCommand.isNotBlank()) {
      val res = verifyRunner.run(VibeAgentSettings.verifyCommand, VibeAgentSettings.verifyTimeoutMs) { llmCancel.get() || disposed }
      audit?.append(AuditEvent(System.currentTimeMillis(), AuditEvent.Action.VERIFY_GATE, ok = res.passed,
        meta = mapOf("ran" to res.ran.toString(), "exit" to (res.exitCode?.toString() ?: "none"))))
      // Stop pressed while the build ran → complete the turn, do not bounce the agent again.
      if (llmCancel.get() || disposed) return null
      when (VerifyGatePolicy.decide(vMode, res.ran, res.passed, verifyAttempt, VibeAgentSettings.verifyMaxAttempts)) {
        VerifyGateDecision.BOUNCE -> return GateBounce(
          "⛔ VERIFY-GATE: команда «${VibeAgentSettings.verifyCommand}» упала (exit ${res.exitCode ?: "timeout"}). Задача НЕ выполнена — исправь причину и продолжай (попытка ${verifyAttempt + 1} из ${maxOf(1, VibeAgentSettings.verifyMaxAttempts)}).\n${res.outputTail}",
          verifyAttempt + 1, checkAttempt)
        VerifyGateDecision.STOP -> {
          // Terminal: giving up hands control to the user — do not then bounce on turn checks.
          systemLine("⛔ VERIFY-GATE: проверка всё ещё падает после ${maxOf(1, VibeAgentSettings.verifyMaxAttempts)} попыток — прогон остановлен, доработайте вручную")
          return null
        }
        VerifyGateDecision.WARN_COMPLETE ->
          systemLine("⚠️ VERIFY-GATE (предупреждение): проверка не прошла (exit ${res.exitCode ?: "timeout"}), но ход завершён")
        VerifyGateDecision.COMPLETE -> {}
      }
    }

    // --- TURN-CHECKS decision (findings already scanned + tripped above) ---
    when (TurnChecks.decide(cMode, findings, checkAttempt, VibeAgentSettings.checksMaxAttempts)) {
      TurnChecksDecision.BOUNCE -> return GateBounce(
        TurnChecks.renderCorrective(findings, checkAttempt + 1, maxOf(1, VibeAgentSettings.checksMaxAttempts)),
        verifyAttempt, checkAttempt + 1)
      TurnChecksDecision.STOP ->
        systemLine("⛔ ПРОВЕРКИ ХОДА: после ${maxOf(1, VibeAgentSettings.checksMaxAttempts)} попыток проблемы остались — прогон остановлен")
      TurnChecksDecision.NOTIFY_COMPLETE ->
        systemLine("🔎 ПРОВЕРКИ ХОДА: " + findings.joinToString("; ") { "${it.detail}: ${it.path}" })
      TurnChecksDecision.COMPLETE -> {}
    }
    return null
  }

  /** Read a changed file for the secret scan; skips huge/binary/unreadable files. */
  private fun readFileForScan(path: String): String? = try {
    val p = java.nio.file.Path.of(path)
    if (!java.nio.file.Files.isRegularFile(p) || java.nio.file.Files.size(p) > VibeAgentSettings.checksMaxFileBytes) null
    else java.nio.file.Files.readString(p)
  } catch (e: Exception) { null }

  /** Confirm clearing latched security breakers before an agent turn (manual-only, VibeIDE contract). */
  private fun confirmClearBreakers(): Boolean {
    var cleared = false
    ApplicationManager.getApplication().invokeAndWait {
      val choice = Messages.showYesNoDialog(project,
        "Сработал защитный предохранитель агента:\n\n${breakers.openReasons().joinToString("\n")}\n\nОн снимается только вашим решением. Снять и продолжить?",
        "Vibe Agent: предохранитель", "Снять и продолжить", "Отмена", Messages.getWarningIcon())
      cleared = choice == Messages.YES
    }
    if (cleared) {
      val n = breakers.clearAll()
      status.set(VibeAgentStatusService.State.IDLE)
      audit?.append(AuditEvent(System.currentTimeMillis(), AuditEvent.Action.CIRCUIT_BREAKER_RECOVERED, ok = true,
        meta = mapOf("cleared" to n.toString())))
      systemLine("[предохранитель] снят ($n) — можно продолжать")
    }
    return cleared
  }

  /** preToolUse/postToolUse gate for one tool-call: runs the chain, audits, surfaces messages. */
  private fun runToolHook(event: HookEvent, tool: String?, params: JsonObject?): HookDecision {
    val decision = hooks.run(event, tool, params, emptyList())
    // ok reflects whether a hook flagged a problem (exit 2), not merely whether it blocked —
    // a postToolUse refusal is a real "not ok" even though it cannot stop the already-run tool.
    audit?.append(AuditEvent(System.currentTimeMillis(), AuditEvent.Action.HOOK, ok = !decision.flagged,
      meta = mapOf("event" to event.wire, "tool" to (tool ?: ""), "blocked" to decision.blocked.toString(),
        "broken" to decision.brokenHooks.size.toString())))
    // Notes and post/turnEnd requirements are for the agent; the ACP model can't inject a mid-turn
    // message, so we surface them in the feed (VibeIDE dropped preToolUse notes entirely — we don't).
    decision.agentMessage?.takeIf { !decision.blocked }?.let { systemLine("🪝 $it") }
    return decision
  }

  private fun runTurnEndHooks() {
    val decision = hooks.run(HookEvent.TURN_END, null, null, changedPaths.toList())
    audit?.append(AuditEvent(System.currentTimeMillis(), AuditEvent.Action.HOOK, ok = !decision.flagged,
      meta = mapOf("event" to HookEvent.TURN_END.wire, "changedFiles" to changedPaths.size.toString(),
        "broken" to decision.brokenHooks.size.toString())))
    decision.agentMessage?.let { systemLine("🪝 ПРОВЕРКА ПРОЕКТА: $it") }
  }

  private fun sendToLlm(t: ChatTarget.Model, startedAt: Long) {
    try {
      val resolved = ProvidersService.resolve(t.provider, project.basePath) { systemLine("[providers] $it") }
      if (resolved == null) {
        systemLine("[providers] у '${t.provider.id}' нет baseURL — отправлять некуда")
        return
      }
      if (resolved.apiKey == null && !resolved.isLocal) {
        systemLine("[providers] нет ключа для '${t.provider.id}': Settings → Tools → VibeIDEA → Провайдеры, либо .vibe/.env")
        return
      }
      if (resolved.isLocal) systemLine("[локальная модель]")
      // The conversation so far (this turn's user record included) — rebuilt from the thread each time,
      // so reopening an old thread resumes with its full context.
      val threadId = turnThreadId ?: return
      val transcript = history.get(threadId)?.messages.orEmpty()
      // Old screenshots stop paying rent: only the last few user messages keep their images on the wire.
      val imageBearing = transcript.indices.filter { transcript[it].role == Role.USER }.takeLast(MAX_IMAGE_HISTORY_MESSAGES).toSet()
      val wireMessages = transcript.withIndex().filter { it.value.role != Role.OTHER }.map { (index, r) ->
        ChatMessage(
          role = if (r.role == Role.USER) "user" else "assistant",
          text = r.wireText ?: r.text,
          images = if (index in imageBearing) r.images.map { ImagePart(it.mimeType, it.base64) } else emptyList(),
        )
      }
      // A model declared non-vision must not receive images lingering in the history either.
      val wire = if (t.model.vision == false) wireMessages.map { it.withoutImages() } else wireMessages
      llmClient.chat(resolved, t.model, wire, { llmCancel.get() }) { delta ->
        appendAgentText(delta)
      }
      finishAgentBubble((System.currentTimeMillis() - startedAt) / 1000.0, t.model.id)
    }
    catch (e: java.io.InterruptedIOException) {
      // The partial answer stays in the transcript — a stop is not amnesia.
      finishAgentBubble((System.currentTimeMillis() - startedAt) / 1000.0, "прервано")
      systemLine("[стоп] ${e.message}")
    }
    catch (e: Exception) {
      finishAgentBubble((System.currentTimeMillis() - startedAt) / 1000.0, "ошибка")
      // A rejected payload must not poison every later request in this thread.
      turnThreadId?.let { if (history.dropImagesFromLastUser(it)) systemLine("[история] картинки убраны из последнего сообщения — провайдер отверг запрос") }
      systemLine("[ошибка] ${e.message}")
    }
    finally {
      finishTurn()
    }
  }

  private fun ensureClient(config: AgentServerConfig): AcpClient {
    val fresh = synchronized(clientLock) {
      // Checked INSIDE the lock: dispose() completes under it, so a racing turn thread
      // cannot spawn an orphan process after the panel is gone.
      check(!disposed) { "панель закрыта" }
      val existing = client
      if (existing != null && existing.isAlive && existing.sessionId != null && clientConfig == config) return existing
      existing?.stop()
      systemLine("[агент] запускаю: ${config.command} ${config.args.joinToString(" ")}")
      AcpClient(config, project.basePath, this, advertiseTerminalExec = VibeAgentSettings.terminalEnabled).also {
        it.start()
        client = it
        clientConfig = config
      }
    }
    val handshakeSec = VibeAgentSettings.handshakeTimeoutSec.toLong()
    try {
      fresh.initializeAndOpenSession().get(handshakeSec, TimeUnit.SECONDS)
    }
    catch (e: TimeoutException) {
      synchronized(clientLock) {
        fresh.stop()
        if (client === fresh) { client = null; clientConfig = null }
      }
      throw IllegalStateException("агент не ответил на initialize/session/new за $handshakeSec с — проверьте команду и ACP-флаг в ${AcpConfig.configPath()}")
    }
    systemLine("[агент] сессия открыта" + (fresh.modes?.let { m -> " · режим: ${m.available.firstOrNull { it.id == m.currentModeId }?.name ?: m.currentModeId}" } ?: ""))
    // A fresh session starts a fresh context — drop the stale usage chip until the agent reports anew.
    SwingUtilities.invokeLater { composer.setUsage(null, null, warn = false) }
    return fresh
  }

  // --- threads & tabs (wave C) ---

  private fun nowIso(): String = Instant.now().toString()

  private fun timeOf(atIso: String): String = try {
    Instant.parse(atIso).atZone(ZoneId.systemDefault()).toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"))
  } catch (ignored: Exception) { "" }

  private fun restoreTabs() {
    val props = PropertiesComponent.getInstance(project)
    val saved = props.getValue(KEY_OPEN_TABS)?.split('\n')?.distinct()?.filter { history.get(it) != null }.orEmpty()
    openTabIds.addAll(saved)
    if (openTabIds.isEmpty()) openTabIds.add(history.create(project.basePath, project.name).id)
    currentThreadId = props.getValue(KEY_ACTIVE_TAB)?.takeIf { it in openTabIds } ?: openTabIds.first()
    history.get(currentThreadId)?.let { renderTranscript(it) }
    updateTabsStrip()
  }

  private fun saveTabs() {
    val props = PropertiesComponent.getInstance(project)
    props.setValue(KEY_OPEN_TABS, openTabIds.joinToString("\n"))
    props.setValue(KEY_ACTIVE_TAB, currentThreadId)
  }

  private fun updateTabsStrip() {
    val tabs = openTabIds.mapNotNull { history.get(it) }.map { t ->
      ChatTabsStrip.TabInfo(t.id, ChatTabsStrip.label(t.title), t.title)
    }
    tabsStrip.update(tabs, currentThreadId, railOpen = rail != null)
  }

  /** Switches to (and opens a tab for) the given thread; idempotent for the active one. */
  private fun activateThread(id: String, saveCurrentDraft: Boolean = true) {
    if (history.get(id) == null) return
    if (id == currentThreadId) {
      if (id !in openTabIds) { openTabIds.add(id); evictTabs(); updateTabsStrip(); saveTabs() }
      composer.focusInput()
      return
    }
    if (saveCurrentDraft && history.get(currentThreadId) != null) {
      val draft = composer.composed()
      if (!draft.isEmpty) drafts[currentThreadId] = draft else drafts.remove(currentThreadId)
    }
    composer.clearDraft()
    if (id !in openTabIds) { openTabIds.add(id); evictTabs() }
    currentThreadId = id
    val thread = history.get(id) ?: return
    renderTranscript(thread)
    thread.state.targetId?.let { selectTargetById(it) }
    drafts.remove(id)?.let { composer.restoreDraft(it) }
    updateTabsStrip()
    saveTabs()
    rail?.currentThreadId = id
    composer.focusInput()
  }

  /** VibeIDE: beyond the limit the OLDEST tab is silently evicted (never the active or streaming one). */
  private fun evictTabs() {
    while (openTabIds.size > VibeChatSettings.maxOpenTabs) {
      val victim = openTabIds.firstOrNull { it != currentThreadId && it != turnThreadId } ?: return
      openTabIds.remove(victim)
      drafts.remove(victim)
      deleteIfEmpty(victim)
    }
  }

  /** An empty thread nobody can see (lists hide them) has no reason to survive its tab. */
  private fun deleteIfEmpty(threadId: String) {
    val thread = history.get(threadId) ?: return
    if (thread.messages.isEmpty() && !(turnInFlight.get() && turnThreadId == threadId)) history.delete(threadId)
  }

  /** «+»: an empty current thread is reused (restamped onto this project), otherwise a fresh one opens. */
  private fun newChat() {
    val current = history.get(currentThreadId)
    if (current != null && current.messages.isEmpty() && !(turnInFlight.get() && turnThreadId == currentThreadId)) {
      history.reassign(current.id, project.basePath, project.name)
      composer.focusInput()
      return
    }
    activateThread(history.create(project.basePath, project.name).id)
  }

  private fun closeTab(id: String) {
    val index = openTabIds.indexOf(id)
    if (index < 0) return
    openTabIds.removeAt(index)
    drafts.remove(id)
    deleteIfEmpty(id)
    if (id == currentThreadId) {
      // The left neighbour wins, else the right one; no tabs left → a fresh chat (the view is never empty).
      val neighbor = openTabIds.getOrNull(index - 1) ?: openTabIds.getOrNull(index)
      if (neighbor != null) activateThread(neighbor, saveCurrentDraft = false)
      else activateThread(history.create(project.basePath, project.name).id, saveCurrentDraft = false)
    }
    else {
      updateTabsStrip()
      saveTabs()
    }
  }

  private fun openThreadAt(threadId: String, messageIndex: Int) {
    if (threadId == currentThreadId) {
      // Re-project from the store: live rows may have drifted from record indices.
      history.get(threadId)?.let { renderTranscript(it) }
    }
    else activateThread(threadId)
    SwingUtilities.invokeLater { revealRecord(messageIndex) }
  }

  /** Scrolls the record's row into view and highlights it; the highlight fades on its own. */
  private fun revealRecord(index: Int) {
    val row = recordRows.getOrNull(index) ?: return
    row.scrollRectToVisible(java.awt.Rectangle(0, 0, row.width, row.height))
    val wasOpaque = row.isOpaque
    row.isOpaque = true
    row.background = REVEAL_BG
    row.repaint()
    Timer(REVEAL_HIGHLIGHT_MS) {
      row.isOpaque = wasOpaque
      row.background = null
      row.repaint()
    }.apply { isRepeats = false; start() }
  }

  /** Deleted threads close their tabs; the view is never left without an active thread. */
  private fun onHistoryChanged() {
    if (disposed) return
    val removed = openTabIds.filter { history.get(it) == null }
    if (removed.isNotEmpty()) {
      openTabIds.removeAll(removed.toSet())
      removed.forEach { drafts.remove(it) }
      if (currentThreadId in removed) {
        val next = openTabIds.firstOrNull() ?: history.create(project.basePath, project.name).id
        activateThread(next, saveCurrentDraft = false)
      }
    }
    updateTabsStrip()
    saveTabs()
    updateLanding()
  }

  /** Entry point for the palette action «История чата» and the «история ▾» pill. */
  fun openHistoryPopup() {
    HistoryPopup.show(project, historyPill, this, { activateThread(it) }, { id, index -> openThreadAt(id, index) })
  }

  private fun toggleRail() {
    HistoryRail.collapsed = !HistoryRail.collapsed
    applyRailVisibility()
  }

  private fun applyRailVisibility() {
    val show = !HistoryRail.collapsed
    if (show && rail == null) {
      // Per-instance disposable: toggling the rail must not pile up listeners until panel disposal.
      val disposable = com.intellij.openapi.util.Disposer.newDisposable("vibe-history-rail")
      com.intellij.openapi.util.Disposer.register(this, disposable)
      railDisposable = disposable
      rail = HistoryRail(project, disposable, historyCallbacks) { toggleRail() }.also {
        it.currentThreadId = currentThreadId
        add(it, BorderLayout.EAST)
      }
    }
    else if (!show && rail != null) {
      remove(rail)
      rail = null
      railDisposable?.let { com.intellij.openapi.util.Disposer.dispose(it) }
      railDisposable = null
    }
    updateTabsStrip()
    revalidate()
    repaint()
  }

  /** Rebuilds the feed from the transcript; a live turn of this thread gets its streaming row back. */
  private fun renderTranscript(thread: ChatThread) {
    messages.removeAll()
    recordRows.clear()
    currentAgentMessage = null
    uiConsumed = 0
    for (record in thread.messages) {
      val row = when (record.role) {
        Role.USER -> buildUserRow(record.text, timeOf(record.at))
        Role.ASSISTANT -> buildAssistantRow(record.text, timeOf(record.at))
        Role.OTHER -> buildToolRow(record.text)
      }
      messages.add(row)
      recordRows.add(row)
    }
    val liveTurnHere = turnInFlight.get() && turnThreadId == thread.id
    if (liveTurnHere) {
      // removeAll() detached the live turn's transient surfaces — re-attach them so their ongoing
      // streams (appendThought / appendTerminalOutput) keep landing in the visible feed. Exact
      // interleaving with the text is lost on a mid-turn switch; that is acceptable.
      thoughtsBlock?.let { messages.add(it) }
      terminalConsoles.values.forEach { messages.add(it) }
    }
    val live = if (liveTurnHere) turnText.toString() else ""
    if (live.isNotEmpty()) {
      // The stream continues into a fresh row; finished text is already a stored record.
      val m = AgentMessage()
      m.append(live)
      uiConsumed = live.length
      currentAgentMessage = m
      messages.add(m.row)
      recordRows.add(m.row)
    }
    conversationStarted = thread.messages.isNotEmpty() || liveTurnHere
    relayout()
    revalidateScroll()
  }

  // --- пайплайны ---

  private fun choosePipeline() {
    if (turnInFlight.get()) {
      systemLine("[pipelines] дождитесь окончания текущего хода")
      return
    }
    val pipelines = PipelinesFile.load(project.basePath) { systemLine("[pipelines] $it") }
    if (pipelines.isEmpty()) {
      systemLine("[pipelines] нет пайплайнов: создайте .vibe/pipelines.json (спека — docs/vibe/manuals/pipelinesSpec.md)")
      return
    }
    val agent = agents.firstOrNull() ?: run { systemLine("[pipelines] нужен агент ACP (${AcpConfig.configPath()})"); return }
    val names = pipelines.map { "${it.name} (${it.steps.size} шагов)" }
    val choice = Messages.showDialog(project, "Какой пайплайн запустить?", "Vibe Agent: пайплайны", names.toTypedArray(), 0, Messages.getQuestionIcon())
    if (choice >= 0) runPipeline(pipelines[choice], (target as? ChatTarget.Agent)?.config ?: agent)
  }

  private fun runPipeline(pipeline: com.vibe.agent.pipelines.Pipeline, agent: AgentServerConfig) {
    if (!history.tryBeginTurn(currentThreadId)) {
      systemLine("[тред] занят другим окном — дождитесь завершения его хода")
      return
    }
    systemLine("═══ Пайплайн «${pipeline.name}» — ${pipeline.steps.size} шагов ═══")
    turnInFlight.set(true)
    status.set(VibeAgentStatusService.State.RUNNING)
    composer.busy = true
    turnThreadId = currentThreadId
    turnText.setLength(0)
    uiConsumed = 0
    markConversationStarted()
    history.append(currentThreadId, ChatMessageRecord(Role.USER, "Пайплайн «${pipeline.name}» (${pipeline.steps.size} шагов)", at = nowIso()))
    ApplicationManager.getApplication().executeOnPooledThread {
      val artifacts = LinkedHashSet<String>()
      var lastSummary: String? = null
      var failed = false
      try {
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
            val c = ensureClient(agent)
            changedPaths.clear()
            stepBuffer = StringBuilder()
            val startedAt = System.currentTimeMillis()
            val result = c.prompt(prompt).get()
            val stop = result?.jsonObject?.get("stopReason")?.jsonPrimitive?.contentOrNull
            finishAgentBubble((System.currentTimeMillis() - startedAt) / 1000.0, "шаг ${i + 1}")
            if (stop == STOP_CANCELLED) {
              failed = true
              systemLine("$header остановлен пользователем")
              return@forEachIndexed
            }
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
      finally {
        finishTurn()
      }
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
          rebuildTargets()
          systemLine("[providers] каталоги моделей подтянуты")
        }
      }
    }
  }

  // --- рендер ленты (дизайн VibeIDE: пузырь только у пользователя; агент — полная ширина; лучше оригинала: кап ширины строки, единая шкала радиусов 4/8, один язык подписей) ---

  /** A chat row NEVER stretches vertically: max height is pinned to preferred. */
  private open class ChatRow(layout: java.awt.LayoutManager) : JPanel(layout) {
    init { isOpaque = false; alignmentX = Component.LEFT_ALIGNMENT }
    override fun getMaximumSize(): java.awt.Dimension =
      java.awt.Dimension(Int.MAX_VALUE, preferredSize.height)
  }

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
    /** Raw streamed text lives here; on finish it may be re-rendered into prose + code blocks. */
    val text = proseArea()
    private var fullText = ""
    val meta = metaLabel("Агент · ${now()}", right = false)
    private val metaRow = JPanel(BorderLayout()).apply {
      isOpaque = false
      add(meta, BorderLayout.WEST)
      add(copyLink { fullText.ifEmpty { text.text } }, BorderLayout.EAST)
    }
    val row: JPanel = object : ChatRow(BorderLayout(0, JBUI.scale(2))) {
      // better than the original: cap the text column so lines stay readable in a wide panel
      override fun getMaximumSize(): java.awt.Dimension =
        java.awt.Dimension(JBUI.scale(720), preferredSize.height)
    }.apply {
      border = JBUI.Borders.empty(4, 4, 8, 4)
      add(text, BorderLayout.CENTER)
      add(metaRow, BorderLayout.SOUTH)
    }
    fun append(s: String) { text.append(s) }

    /** Swap the plain text area for a prose+code-block stack when the message has fenced code. */
    fun renderSegments(content: String) {
      fullText = content
      if (!MessageSegments.hasCode(content)) return
      val stack = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); isOpaque = false; alignmentX = Component.LEFT_ALIGNMENT }
      for (seg in MessageSegments.parse(content)) when (seg) {
        is MessageSegment.Prose -> stack.add(proseArea().also { it.text = seg.text; it.alignmentX = Component.LEFT_ALIGNMENT })
        is MessageSegment.Code -> stack.add(CodeBlockPanel(project, seg.lang, seg.code))
      }
      (text.parent as? java.awt.Container)?.let { c ->
        c.remove(text)
        c.add(stack, BorderLayout.CENTER)
        c.revalidate(); c.repaint()
      }
    }

    fun finish(seconds: Double, suffix: String?) {
      meta.text = "Агент · ${now()} · ${"%.1f".format(seconds)} с${suffix?.let { " · $it" } ?: ""}"
      renderSegments(text.text)
    }
  }

  /** A quiet "копировать" affordance (shared factory — see ChatTheme). */
  private fun copyLink(textSupplier: () -> String): JLabel =
    ChatTheme.copyLabel("Скопировать текст сообщения", textSupplier)

  private fun now(): String = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))

  private fun buildUserRow(text: String, time: String): JPanel {
    val bubble = RoundedPanel(USER_BUBBLE, radius = 8).apply {
      layout = BorderLayout()
      border = JBUI.Borders.empty(6, 8)
      add(proseArea().also { it.text = text }, BorderLayout.CENTER)
    }
    return ChatRow(BorderLayout(0, JBUI.scale(2))).apply {
      border = JBUI.Borders.empty(4, 4, 8, 4)
      add(bubble, BorderLayout.CENTER)
      add(metaLabel("Вы · $time", right = true), BorderLayout.SOUTH)
      // width-fit вправо: слева распорка съедает всё лишнее (мин. четверть ширины)
      add(Box.createHorizontalStrut(JBUI.scale(160)), BorderLayout.WEST)
    }
  }

  private fun buildAssistantRow(text: String, time: String): JPanel = AgentMessage().let { m ->
    m.append(text)
    m.meta.text = "Агент · $time"
    m.renderSegments(text)
    m.row
  }

  private fun buildToolRow(title: String): JPanel {
    val card = RoundedPanel(TOOL_CARD, radius = 4).apply {
      layout = BorderLayout()
      border = JBUI.Borders.empty(3, 8)
      add(JLabel(title).apply {
        font = com.intellij.util.ui.JBFont.label().deriveFont(Font.ITALIC, 11f)
        foreground = META_FG
      }, BorderLayout.WEST)
    }
    return ChatRow(BorderLayout()).apply {
      border = JBUI.Borders.empty(1, 4)
      add(card, BorderLayout.WEST)
    }
  }

  private fun userBubble(text: String) {
    SwingUtilities.invokeLater {
      val row = buildUserRow(text, now())
      messages.add(row)
      recordRows.add(row)
      revalidateScroll()
    }
    currentAgentMessage = null
  }

  /** Streams into the live row of the turn's thread; the store gets the full text at finish. */
  private fun appendAgentText(text: String) {
    turnText.append(text)
    SwingUtilities.invokeLater {
      if (turnThreadId != currentThreadId) return@invokeLater
      var m = currentAgentMessage
      if (m == null) {
        // A stray delta after the turn finished must not spawn a ghost empty row.
        if (!turnInFlight.get()) return@invokeLater
        m = AgentMessage()
        currentAgentMessage = m
        messages.add(m.row)
        recordRows.add(m.row)
      }
      // Project from the shared buffer: a tab switch mid-stream re-rendered the row from it already.
      val full = turnText.toString()
      if (uiConsumed < full.length) {
        m.append(full.substring(uiConsumed))
        uiConsumed = full.length
      }
      revalidateScroll()
    }
  }

  private fun finishAgentBubble(seconds: Double, suffix: String?) {
    val threadId = turnThreadId
    // Atomic capture+clear: queued per-delta projections then see an empty buffer and no-op.
    val fullText = synchronized(turnText) {
      val t = turnText.toString()
      turnText.setLength(0)
      t
    }
    if (threadId != null && fullText.isNotBlank()) {
      history.append(threadId, ChatMessageRecord(Role.ASSISTANT, fullText, at = nowIso()))
    }
    // currentAgentMessage is EDT-owned (appendAgentText also touches it on the EDT); read+clear it there.
    SwingUtilities.invokeLater {
      val m = currentAgentMessage
      currentAgentMessage = null
      // Each response (turn or gate sub-turn) gets its own reasoning block; reset on the EDT.
      thoughtsBlock = null
      if (m != null) {
        // Flush the tail the per-delta projections did not reach before the buffer was cleared.
        if (uiConsumed < fullText.length) m.append(fullText.substring(uiConsumed))
        m.finish(seconds, suffix)
      }
      uiConsumed = 0
      revalidateScroll()
    }
  }

  /** Compact tool-call card, VibeIDE style: 4px radius, quiet border, 11px italic. */
  private fun toolCard(title: String) {
    // Out-of-turn agent notifications land in the visible thread, not in a finished turn's one.
    val targetThread = (if (turnInFlight.get()) turnThreadId else null) ?: currentThreadId
    history.append(targetThread, ChatMessageRecord(Role.OTHER, title, at = nowIso()))
    SwingUtilities.invokeLater {
      if (targetThread != currentThreadId) return@invokeLater
      val row = buildToolRow(title)
      messages.add(row)
      recordRows.add(row)
      revalidateScroll()
    }
  }

  /** Clickable checkpoint line in the feed (VibeIDE pattern): click = confirm + roll back. */
  private fun checkpointLine(cp: com.vibe.agent.checkpoints.Checkpoint) {
    SwingUtilities.invokeLater {
      val label = JLabel("— чекпоинт ${cp.hash.take(8)} · ${now()} · нажмите, чтобы откатить к этой точке —", JLabel.CENTER)
      label.font = com.intellij.util.ui.JBFont.label().deriveFont(Font.PLAIN, 10f)
      label.foreground = META_FG
      label.alignmentX = Component.LEFT_ALIGNMENT
      label.border = JBUI.Borders.empty(3)
      label.cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
      label.toolTipText = "Откатить рабочую папку к состоянию перед этим сообщением (файлы, созданные позже, останутся)"
      label.addMouseListener(object : java.awt.event.MouseAdapter() {
        override fun mouseClicked(e: java.awt.event.MouseEvent) {
          val confirm = Messages.showYesNoDialog(project,
            "Рабочее дерево будет перезаписано снимком ${cp.hash.take(8)} («${cp.label}»). Файлы, созданные позже, останутся. Продолжить?",
            "Откат к чекпоинту", Messages.getWarningIcon())
          if (confirm == Messages.YES) {
            ApplicationManager.getApplication().executeOnPooledThread {
              val service = checkpoints ?: return@executeOnPooledThread
              val ok = service.restore(cp)
              systemLine(if (ok) "⚑ откат к ${cp.hash.take(8)} выполнен" else "[чекпоинты] откат не удался (см. git)")
              ApplicationManager.getApplication().invokeLater {
                com.intellij.openapi.vfs.VfsUtil.markDirtyAndRefresh(true, true, true, com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(project.basePath!!))
              }
            }
          }
        }
      })
      messages.add(label)
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
    // Stick-to-bottom only when the user was already at (or near) the bottom — a reader scrolled
    // up through history must not be yanked down by every streaming delta.
    val bar = scroll.verticalScrollBar
    val wasAtBottom = bar.value + bar.visibleAmount >= bar.maximum - JBUI.scale(STICK_TO_BOTTOM_SLACK)
    messages.revalidate()
    messages.repaint()
    if (wasAtBottom) SwingUtilities.invokeLater { bar.value = bar.maximum }
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
      "agent_thought_chunk" -> {
        val text = u["content"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull ?: return
        appendThought(text)
      }
      "usage_update" -> onUsageUpdate(u)
      "tool_call" -> {
        val call = toolCalls.onToolCall(u)
        if (call != null) { auditToolCall(AuditEvent.Action.TOOL_CALL_START, call); harvestMutation(call) }
        toolCard(call?.title ?: u["title"]?.jsonPrimitive?.contentOrNull ?: u["kind"]?.jsonPrimitive?.contentOrNull ?: "инструмент")
        // Claude adapter announces a terminal for this tool-call — open a live console.
        terminalInfoId(u)?.let { openTerminalConsole(it, call?.title ?: "терминал") }
      }
      "tool_call_update" -> {
        val call = toolCalls.onToolCallUpdate(u) ?: return
        harvestMutation(call)
        // Stream Claude Bash output / exit into the console for this terminal.
        terminalOutputFrame(u)?.let { (id, data) -> appendTerminalOutput(id, data) }
        terminalExitFrame(u)?.let { (id, code, sig) -> markTerminalExit(id, code, sig) }
        if (call.isDone) {
          auditToolCall(AuditEvent.Action.TOOL_CALL_DONE, call)
          // postToolUse: the tool already ran and cannot be undone, so run the hook OFF the reader thread —
          // a 30 s hook must not stall the session/update stream.
          val tool = call.toolName ?: call.kind
          val params = call.rawInput
          ApplicationManager.getApplication().executeOnPooledThread { runToolHook(HookEvent.POST_TOOL_USE, tool, params) }
        }
      }
      "plan" -> toolCard("план обновлён")
      else -> {}
    }
  }

  // --- Claude terminal stream (_meta.terminal_*) rendering ---

  private fun metaObj(u: JsonObject): JsonObject? = u["_meta"]?.jsonObject
  private fun terminalInfoId(u: JsonObject): String? =
    metaObj(u)?.get("terminal_info")?.jsonObject?.get("terminal_id")?.jsonPrimitive?.contentOrNull
  private fun terminalOutputFrame(u: JsonObject): Pair<String, String>? {
    val o = metaObj(u)?.get("terminal_output")?.jsonObject ?: return null
    val id = o["terminal_id"]?.jsonPrimitive?.contentOrNull ?: return null
    return id to (o["data"]?.jsonPrimitive?.contentOrNull ?: "")
  }
  private fun terminalExitFrame(u: JsonObject): Triple<String, Int?, String?>? {
    val o = metaObj(u)?.get("terminal_exit")?.jsonObject ?: return null
    val id = o["terminal_id"]?.jsonPrimitive?.contentOrNull ?: return null
    return Triple(id, o["exit_code"]?.jsonPrimitive?.intOrNull, o["signal"]?.jsonPrimitive?.contentOrNull)
  }

  private fun openTerminalConsole(terminalId: String, title: String) {
    val targetThread = (if (turnInFlight.get()) turnThreadId else null) ?: currentThreadId
    SwingUtilities.invokeLater {
      if (targetThread != currentThreadId || terminalConsoles.containsKey(terminalId)) return@invokeLater
      val console = TerminalConsole(title)
      terminalConsoles[terminalId] = console
      messages.add(console)
      // Not added to recordRows: consoles are not history records, and would shift the reveal index.
      revalidateScroll()
    }
  }

  private fun appendTerminalOutput(terminalId: String, data: String) {
    if (data.isEmpty()) return
    SwingUtilities.invokeLater { terminalConsoles[terminalId]?.append(data) }
  }

  /** ACP usage_update {used, size, cost?} → compact context chip in the composer. */
  private fun onUsageUpdate(u: JsonObject) {
    val used = u["used"]?.jsonPrimitive?.longOrNull ?: return
    val size = u["size"]?.jsonPrimitive?.longOrNull ?: return
    if (size <= 0) return
    val pct = (used * 100 / size).toInt().coerceIn(0, 100)
    val cost = (u["cost"] as? JsonObject)?.let { c ->
      val amount = c["amount"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
      val currency = c["currency"]?.jsonPrimitive?.contentOrNull ?: ""
      amount?.let { " · %.2f %s".format(it, currency).trimEnd() }
    } ?: ""
    SwingUtilities.invokeLater {
      composer.setUsage(
        text = "⛁ $pct%",
        tooltip = "Контекст: %,d / %,d токенов$cost".format(used, size),
        warn = pct >= USAGE_WARN_PCT,
      )
    }
  }

  /** Stream the agent's reasoning into a collapsible block on the turn's thread. */
  private fun appendThought(text: String) {
    val targetThread = (if (turnInFlight.get()) turnThreadId else null) ?: currentThreadId
    SwingUtilities.invokeLater {
      if (targetThread != currentThreadId) return@invokeLater
      var block = thoughtsBlock
      if (block == null) {
        if (!turnInFlight.get()) return@invokeLater
        block = ThoughtsBlock()
        thoughtsBlock = block
        // Reasoning precedes the answer: when the answer row already streams, insert ABOVE it.
        val answerIdx = currentAgentMessage?.row?.let { messages.components.indexOf(it as Component) } ?: -1
        if (answerIdx >= 0) messages.add(block, answerIdx) else messages.add(block)
        // Not added to recordRows: reasoning is not a history record (would shift the reveal index).
      }
      block.append(text)
      revalidateScroll()
    }
  }

  private fun markTerminalExit(terminalId: String, exitCode: Int?, signal: String?) {
    audit?.append(AuditEvent(System.currentTimeMillis(), AuditEvent.Action.TERMINAL, ok = exitCode == 0,
      meta = mapOf("exit" to (exitCode?.toString() ?: "signal:${signal ?: "?"}"))))
    SwingUtilities.invokeLater { terminalConsoles[terminalId]?.markExit(exitCode, signal) }
  }

  /**
   * Note that a turn touched files. Client-side writes reach [changedPaths] via fs/write, but an
   * agent's own edit tools and Bash do not — so mark the turn as mutating (so the gates still run)
   * and harvest any declared edit path so turn-checks can scan it.
   */
  private fun harvestMutation(call: ToolCall) {
    val kind = call.kind
    val name = call.toolName
    val isNamedEdit = name != null && name in EDIT_TOOLS
    // execute-kind (a command) may change files invisibly → mark the turn mutating so the gate runs…
    if (isNamedEdit || kind in MUTATING_KINDS) turnHadMutatingTool = true
    // …but only tools that actually WRITE contribute paths. ACP `locations` is read-inclusive, so
    // harvesting it for a command (`grep KEY .env`) would wrongly trip the protected-path breaker.
    val isWrite = isNamedEdit || kind == "edit" || kind == "delete" || kind == "move"
    if (isWrite) {
      call.rawInput?.let { ri -> for (key in EDIT_PATH_KEYS) ri[key]?.jsonPrimitive?.contentOrNull?.let { changedPaths.add(it) } }
      changedPaths.addAll(call.locations)
    }
  }

  /** Emit a privacy-filtered tool-call audit record (no args/command bodies — only tool + target path). */
  private fun auditToolCall(action: String, call: ToolCall) {
    val log = audit ?: return
    val tool = call.toolName ?: call.kind ?: "tool"
    val target = ToolCallAudit.safeTargetPath(tool, call.rawParamsFlat(), call.kind)
    log.append(AuditEvent(
      ts = System.currentTimeMillis(),
      action = action,
      ok = call.status != ToolCall.STATUS_FAILED,
      files = target?.let { listOf(it) },
      meta = mapOf("tool" to tool, "status" to call.status),
    ))
  }

  override fun onRequestPermission(params: JsonObject): JsonElement {
    val toolCall = params["toolCall"]?.jsonObject
    val title = toolCall?.get("title")?.jsonPrimitive?.contentOrNull ?: "Действие агента"
    // preToolUse hook: this is one of the two points the client controls (the other is fs/write).
    val hookTool = toolCall?.get("name")?.jsonPrimitive?.contentOrNull ?: toolCall?.get("kind")?.jsonPrimitive?.contentOrNull
    val hookParams = toolCall?.get("rawInput") as? JsonObject
    val preHook = runToolHook(HookEvent.PRE_TOOL_USE, hookTool, hookParams)
    if (preHook.blocked) {
      systemLine("🪝 ${preHook.agentMessage}")
      return buildJsonObject { put("outcome", buildJsonObject { put("outcome", "cancelled") }) }
    }
    // Deterministic destructive-command warning for the agent's own command tools (Claude runs Bash itself).
    val command = hookParams?.get("command")?.jsonPrimitive?.contentOrNull
    val destructive = command?.let { ShellSafetyAnalyzer.analyzeLine(it) }
    val dialogText = if (destructive != null)
      "⚠️ Разрушительная команда (${destructive.reasons.joinToString(", ")}):\n${command.take(DESTRUCTIVE_PREVIEW_LEN)}\n\n$title"
    else title
    val options = params["options"]?.jsonArray?.map { it.jsonObject } ?: emptyList()
    var selected: String? = null
    ApplicationManager.getApplication().invokeAndWait {
      val names = options.map { it["name"]?.jsonPrimitive?.contentOrNull ?: it.getValue("optionId").jsonPrimitive.content }
      val choice = Messages.showDialog(project, dialogText, "Vibe Agent: разрешение", names.toTypedArray(), 0,
        if (destructive != null) Messages.getWarningIcon() else Messages.getQuestionIcon())
      if (choice >= 0) selected = options[choice].getValue("optionId").jsonPrimitive.content
    }
    val chosen = selected
    audit?.append(AuditEvent(System.currentTimeMillis(), AuditEvent.Action.PERMISSION, ok = chosen != null,
      meta = mapOf("title" to title.take(120), "outcome" to if (chosen != null) "selected" else "cancelled")))
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
    val path = params["path"]?.jsonPrimitive?.contentOrNull
    // preToolUse hook on the client-controlled write path (WritePreview is the interactive gate;
    // a blocking hook refuses before the diff even appears).
    val preHook = runToolHook(HookEvent.PRE_TOOL_USE, "write_text_file", params)
    if (preHook.blocked) {
      systemLine("🪝 ${preHook.agentMessage}")
      throw IllegalStateException(preHook.agentMessage ?: "запись отклонена хуком проекта")
    }
    path?.let { changedPaths.add(it) }
    val result = fileOps.writeTextFile(params)
    audit?.append(AuditEvent(System.currentTimeMillis(), AuditEvent.Action.FS_WRITE, ok = true,
      files = path?.let { listOf(it.take(ToolCallAudit.MAX_TARGET_LEN)) }))
    return result
  }

  // --- standard ACP terminal/… (non-Claude agents that delegate execution to us) ---

  override fun onCreateTerminal(params: JsonObject): JsonElement {
    if (!VibeAgentSettings.terminalEnabled) throw IllegalStateException("исполнение терминала выключено (Settings → Tools → VibeIDEA → Агент)")
    val command = params["command"]?.jsonPrimitive?.contentOrNull ?: throw IllegalStateException("terminal/create без command")
    val args = (params["args"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
    val env = (params["env"] as? JsonArray)?.mapNotNull { e ->
      val o = e as? JsonObject ?: return@mapNotNull null
      val name = o["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
      name to (o["value"]?.jsonPrimitive?.contentOrNull ?: "")
    }?.toMap() ?: emptyMap()
    val cwd = params["cwd"]?.jsonPrimitive?.contentOrNull
    // Never unbounded: fall back to a cap when the agent omits outputByteLimit.
    val outputByteLimit = params["outputByteLimit"]?.jsonPrimitive?.longOrNull ?: VibeAgentSettings.DEFAULT_TERMINAL_OUTPUT_BYTE_LIMIT
    // Destructive-command gate: same deterministic classifier as VibeIDE, asked before execution.
    val verdict = ShellSafetyAnalyzer.analyzeLine((listOf(command) + args).joinToString(" "))
    if (verdict != null) {
      var approved = false
      ApplicationManager.getApplication().invokeAndWait {
        val choice = Messages.showYesNoDialog(project,
          "Агент хочет выполнить разрушительную команду:\n\n${(listOf(command) + args).joinToString(" ").take(DESTRUCTIVE_PREVIEW_LEN)}\n\nПризнаки: ${verdict.reasons.joinToString(", ")}\n\nЭто действие может уничтожить данные, и отменить его нечем.",
          "Vibe Agent: разрушительная команда", "Выполнить", "Отмена", Messages.getWarningIcon())
        approved = choice == Messages.YES
      }
      audit?.append(AuditEvent(System.currentTimeMillis(), AuditEvent.Action.TERMINAL, ok = approved,
        meta = mapOf("gate" to "destructive", "reasons" to verdict.reasons.joinToString(","), "approved" to approved.toString())))
      if (!approved) throw IllegalStateException("пользователь отказался выполнять разрушительную команду (${verdict.reasons.joinToString(", ")})")
    }
    val terminalId = terminals.create(command, args, env, cwd, outputByteLimit)
    return buildJsonObject { put("terminalId", terminalId) }
  }

  override fun onTerminalOutput(params: JsonObject): JsonElement {
    val id = params["terminalId"]?.jsonPrimitive?.contentOrNull ?: throw IllegalStateException("terminal/output без terminalId")
    val snap = terminals.output(id) ?: throw IllegalStateException("неизвестный terminalId: $id")
    return buildJsonObject {
      put("output", snap.output)
      put("truncated", snap.truncated)
      if (snap.finished) put("exitStatus", buildJsonObject {
        if (snap.exitCode != null) put("exitCode", snap.exitCode) else put("exitCode", JsonNull)
        if (snap.signal != null) put("signal", snap.signal) else put("signal", JsonNull)
      })
    }
  }

  override fun onWaitForTerminalExit(params: JsonObject): JsonElement {
    val id = params["terminalId"]?.jsonPrimitive?.contentOrNull ?: throw IllegalStateException("terminal/wait_for_exit без terminalId")
    val exit = terminals.waitForExit(id) ?: throw IllegalStateException("неизвестный terminalId: $id")
    return buildJsonObject {
      if (exit.exitCode != null) put("exitCode", exit.exitCode) else put("exitCode", JsonNull)
      if (exit.signal != null) put("signal", exit.signal) else put("signal", JsonNull)
    }
  }

  override fun onKillTerminal(params: JsonObject): JsonElement {
    params["terminalId"]?.jsonPrimitive?.contentOrNull?.let { terminals.kill(it) }
    return buildJsonObject { }
  }

  override fun onReleaseTerminal(params: JsonObject): JsonElement {
    params["terminalId"]?.jsonPrimitive?.contentOrNull?.let { terminals.release(it) }
    return buildJsonObject { }
  }

  override fun onProtocolLog(line: String) = systemLine(line)

  override fun onModeChanged(modeId: String) {
    SwingUtilities.invokeLater { modePicker.setModes(client?.modes) }
  }

  override fun onProcessExit(client: AcpClient, code: Int) {
    synchronized(clientLock) {
      // An exit of a client we already replaced is not ours to react to.
      if (this.client !== client) return
      this.client = null
      clientConfig = null
    }
    systemLine("[агент] процесс завершился (код $code)")
    SwingUtilities.invokeLater { modePicker.setModes(null) }
    // No finishTurn() here: an idle agent's death must not end an unrelated (e.g. LLM) turn.
    // A turn that WAS talking to this process ends through its failed request futures.
  }

  private companion object {
    const val NO_IMAGE_AGENT = "Агент не принимает изображения (promptCapabilities.image)"
    const val STOP_CANCELLED = "cancelled"
    const val KEY_OPEN_TABS = "vibe.chat.openTabs"
    /** Images ride the wire only for this many most recent user messages (cost + poison control). */
    const val MAX_IMAGE_HISTORY_MESSAGES = 4
    /** Context-usage chip turns warning-coloured at this fill percentage. */
    const val USAGE_WARN_PCT = 80
    /** «У низа» для прилипания скролла: столько px недоскролла всё ещё считается низом. */
    const val STICK_TO_BOTTOM_SLACK = 48
    /** Truncation of a command preview shown in a destructive-command confirm dialog. */
    const val DESTRUCTIVE_PREVIEW_LEN = 300
    /** Checkpoint label preview length. */
    const val CHECKPOINT_LABEL_LEN = 48
    /** ACP tool kinds that can change files (gates run when a turn used one). */
    val MUTATING_KINDS = setOf("edit", "delete", "move", "execute")
    /** Tool names known to write files — their declared path is harvested into the changed set. */
    val EDIT_TOOLS = setOf("write_text_file", "Write", "Edit", "MultiEdit", "NotebookEdit",
      "edit_file", "rewrite_file", "create_file_or_folder", "delete_file_or_folder")
    /** Single source of truth for path-shaped input keys (shared with the audit filter). */
    val EDIT_PATH_KEYS = ToolCallAudit.PATH_KEYS
    const val KEY_ACTIVE_TAB = "vibe.chat.activeTab"
    /** VibeIDE: the reveal highlight fades after 2600 ms. */
    const val REVEAL_HIGHLIGHT_MS = 2600
    val REVEAL_BG = JBColor.namedColor("Vibe.History.revealBackground", JBColor(0xDCE7F8, 0x28324A))
    // Theme tokens: any theme (ours or third-party) recolours the chat via these
    // keys; the JBColor defaults keep stock light/dark themes sensible.
    val CHAT_BG = JBColor.namedColor("Vibe.Chat.background", JBColor.namedColor("Panel.background", JBColor.PanelBackground))
    val USER_BUBBLE = JBColor.namedColor("Vibe.Chat.userBubbleBackground", JBColor(0xD8ECF8, 0x2A3550))
    val TOOL_CARD = JBColor.namedColor("Vibe.Chat.toolCardBackground", JBColor(0xF2F2F2, 0x26282E))
    val META_FG = JBColor.namedColor("Vibe.Chat.metaForeground", JBColor.namedColor("Label.infoForeground", JBColor.GRAY))
  }
}
