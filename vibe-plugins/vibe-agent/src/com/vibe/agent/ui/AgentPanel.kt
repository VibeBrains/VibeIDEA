// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.ui

import com.vibe.agent.i18n.VibeI18n.t
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
import com.vibe.agent.design.DesignHookPolicy
import com.vibe.agent.design.DesignReview
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
import com.vibe.agent.providers.CatalogReport
import com.vibe.agent.providers.ChatMessage
import com.vibe.agent.providers.ImagePart
import com.vibe.agent.providers.LlmClient
import com.vibe.agent.providers.ModelCatalogCache
import com.vibe.agent.providers.ModelEntry
import com.vibe.agent.providers.ProviderEntry
import com.vibe.agent.providers.ProviderGuard
import com.vibe.agent.providers.ProvidersChangeListener
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
class AgentPanel(private val project: Project) : com.vibe.agent.http.VibeAgentGateway.Target, JPanel(BorderLayout()), AcpClient.Handler, Disposable {
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

  private val scroll = VibeScroll.pane(FeedView().apply {
    add(messages, BorderLayout.NORTH)
  }).apply {
    horizontalScrollBarPolicy = javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
  }

  // Loaded OFF the EDT in init (config files on disk); empty until then.
  @Volatile private var agents: List<AgentServerConfig> = emptyList()
  @Volatile private var providers: List<ProviderEntry> = emptyList()
  /** Models hand-declared in providers files (before any catalog fetch) — they get the «кастом» mark. */
  @Volatile private var staticModelIds: Map<String, Set<String>> = emptyMap()
  private val llmClient = LlmClient()
  private val llmCancel = java.util.concurrent.atomic.AtomicBoolean(false)
  private val runs = com.vibe.agent.runs.VibeAgentRunService.getInstance(project)
  private val fileOps = IdeFileOps(
    project,
    onNotice = { message -> systemLine(message) },
    onFinding = { path, findings -> reportContextFindings(path, findings) },
    roleNow = { currentRole },
  )
  @Volatile private var client: AcpClient? = null
  @Volatile private var clientConfig: AgentServerConfig? = null
  /** Guards check-then-act on [client]: ensureClient (pooled), onProcessExit (exit thread), dispose (EDT). */
  private val clientLock = Any()
  private val checkpoints: CheckpointService? = project.basePath?.let { CheckpointService(it) }
  // One shared audit log per project (writer here, reader in the viewer action) — see VibeAuditService.
  private val audit: AuditLog? = com.vibe.agent.audit.VibeAuditService.getInstance(project).get()
  /** Tool-calls of the running turn, assembled from the session/update stream by id. */
  private val toolCalls = ToolCallRegistry()

  /** Shapes of this turn's tool calls — the loop detector reads nothing else. */
  private val loopHistory = com.vibe.agent.safety.LoopDetector.History()

  /** Targets already tried in this turn: a chain must never send the turn back where it just failed. */
  private val failoverTried = java.util.Collections.synchronizedSet(HashSet<com.vibe.agent.resilience.FailoverPlan.Target>())

  /** What the turn actually did — the feed shows the agent's story, this shows the events. */
  private val trace = com.vibe.agent.trace.TurnTrace.Recorder()

  @Volatile private var turnStartedAtMs = 0L

  /** Start times of running tool calls, so a finished call can report how long it took. */
  private val toolStarts = java.util.concurrent.ConcurrentHashMap<String, Long>()

  /**
   * The pipeline role running right now, or null in an ordinary chat.
   *
   * Volatile because it is set on the pipeline thread and read on the ACP reader thread: the whole
   * value of the restriction is that it is in force at the moment the write arrives.
   */
  @Volatile private var currentRole: String? = null

  /** Last sign of life in the current turn: a token, a tool call, any update. */
  private val lastActivityMs = java.util.concurrent.atomic.AtomicLong(0)

  /** Said once per turn: repeating «агент молчит» every minute teaches people to ignore it. */
  private val staleAnnounced = java.util.concurrent.atomic.AtomicBoolean(false)

  /**
   * One timer for the whole panel rather than one per turn: a turn that dies without finishing
   * would leave its own timer behind, and leaked timers are how a quiet IDE starts warming a lap.
   */
  private val silenceTimer = Timer(SILENCE_CHECK_MS) { checkSilence() }.apply { isRepeats = true; start() }
  private val hooks = HookRunner(project) { systemLine(t("chat.hookNotice", "text" to it)) }
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

  /** Full texts of outputs that were shrunk for the model — `/output <handle>` prints one back. */
  private val outputStore = com.vibe.agent.context.OutputCompressor.Store()

  /** Rough running total of this chat's tokens; the session ceiling is checked against it. */
  private val sessionTokens = java.util.concurrent.atomic.AtomicLong(0)

  /** Thresholds already said out loud for this chat: a warning repeated every frame is noise. */
  private val announcedContextLevels = java.util.Collections.synchronizedSet(HashSet<String>())
  /** Set when a turn ran an edit/command tool: its writes may be invisible to the client (agent-internal Bash). */
  @Volatile private var turnHadMutatingTool = false
  /** Did the turn end in anything other than a normal finish? The autopilot refuses to resume such a turn. */
  @Volatile private var turnEndedBadly = false
  /** What travelled in this turn's context, for the per-file spend estimate. */
  @Volatile private var turnAttachments: List<com.vibe.agent.budget.FileSpend.Attachment> = emptyList()
  /** Outcomes of the recent tool calls, for the thrash and repeated-timeout breakers. */
  private val thrashHistory = ArrayList<com.vibe.agent.safety.ThrashDetector.Event>()
  /** The agent as the journal names it: the role it plays and the target that runs it. */
  private fun agentActor(): com.vibe.agent.audit.AuditActor =
    com.vibe.agent.audit.AuditActor.agent(currentRole, target?.auditName())

  /**
   * Whose turn this is, for the journal: the person by default, the autopilot when it continued
   * by itself. Held per turn because the answer changes between turns, not between records.
   */
  @Volatile private var turnActor: com.vibe.agent.audit.AuditActor = com.vibe.agent.audit.AuditActor.HUMAN

  /** Turns the autopilot has taken since the person last spoke. */
  @Volatile private var autopilotTurns = 0
  /** What the last few turns moved, for the stall detector. */
  private val turnProgress = java.util.Collections.synchronizedList(ArrayList<com.vibe.agent.safety.StallDetector.Turn>())
  /** Tokens spent since the person last spoke — the autopilot's stretch is capped in money, not only in turns. */
  private val stretchTokens = java.util.concurrent.atomic.AtomicLong(0)

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
    override fun onSend(message: ComposedMessage): Boolean = run {
      // The person spoke: the autopilot's stretch of unattended turns starts counting again, and
      // so does the stall history — a new instruction is movement by definition.
      autopilotTurns = 0
      turnProgress.clear()
      stretchTokens.set(0)
      // The person intervened: failures from before their message say nothing about what happens
      // next, and a breaker that trips on somebody else's history is a breaker people switch off.
      synchronized(thrashHistory) { thrashHistory.clear() }
      dispatch(message)
    }

    private fun dispatch(message: ComposedMessage): Boolean = when {
      // A command typed without the argument it cannot work without is answered, not sent to the
      // model: «/bg» as a question is the shape of a feature that looks broken.
      reportMissingArgument(message) -> false
      handleOutputCommand(message) -> true
      handleGitCommand(message) -> true
      handleCouncilCommand(message) -> true
      handleHandoffCommand(message) -> true
      handleTraceCommand(message) -> true
      handleHelpCommand(message) -> true
      handleFindCommand(message) -> true
      handleSimplifyCommand(message) -> true
      handleMeasureCommand(message) -> true
      handleLearnCommand(message) -> true
      handleDeployCommand(message) -> true
      handleBackgroundCommand(message) -> true
      handleUndoCommand(message) -> true
      handleBlameCommand(message) -> true
      handleMapCommand(message) -> true
      handleRulesCommand(message) -> true
      sessionCeilingReached(message.text) -> false
      handleWatchCommand(message) -> true
      else -> startTurn(message)
    }
    override fun onStop() = cancelTurn()
    override fun onNotice(text: String) = systemLine(t("chat.composerNotice", "text" to text))
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
    toolTipText = t("chat.historyPill")
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
    composer.addPill(PillButton(icon = AllIcons.Actions.RunAll) { choosePipeline() }.apply { toolTipText = t("chat.pipelinePill") })
    composer.addPill(PillButton(icon = AllIcons.General.Settings) { openSettings() }.apply { toolTipText = t("chat.settingsPill") })
    composer.addRightPill(historyPill)
    add(tabsStrip, BorderLayout.NORTH)
    add(centerWrap, BorderLayout.CENTER)
    centerWrap.add(scroll, BorderLayout.CENTER)
    restoreTabs()
    relayout()
    applyRailVisibility()
    history.addListener(this) { onHistoryChanged() }
    systemLine(t("chat.greeting.keys", "acp" to AcpConfig.configPath()))
    // Config files live on disk — never read (or seed) them on the EDT; publish results back here.
    ApplicationManager.getApplication().executeOnPooledThread {
      val loadedAgents = AcpConfig.load { systemLine(t("chat.configNotice", "text" to it)) }
      val loadedProviders = ProvidersService.load(project.basePath) { systemLine("[providers] $it") }
      val catalogCache = ModelCatalogCache.load()
      // .vibe seeding lives in VibeDefaultsSeeder (project open), not here.
      val hooksDisabled = hooks.hasHooksButDisabled()
      val guardFindings = ProviderGuard.scan(loadedProviders)
      SwingUtilities.invokeLater {
        if (disposed) return@invokeLater
        agents = loadedAgents
        // staticModelIds is snapshotted BEFORE the cache is merged in: cached models are catalog
        // models, and passing them off as hand-declared would leak them into the curated picker.
        staticModelIds = loadedProviders.associate { p -> p.id to p.models.map { it.id }.toSet() }
        providers = applyCatalogCache(loadedProviders, catalogCache)
        systemLine(t("chat.greeting.ready",
          "agents" to loadedAgents.joinToString { it.name },
          "providers" to loadedProviders.joinToString { it.name }.ifEmpty { t("chat.greeting.none") }))
        guardFindings.forEach { f -> systemLine("[guard:${f.severity}] ${f.message}") }
        // A repository seen for the first time gets one line about what its files can and cannot do.
        if (com.vibe.agent.security.ForeignProjectNotice.noticeOnce(project.basePath)) {
          systemLine("🛡 " + com.vibe.agent.security.ForeignProjectNotice.TEXT)
        }
        if (hooksDisabled) systemLine(t("chat.hooksDisabled"))
        rebuildTargets()
        fetchProviderModels()
      }
    }
    project.messageBus.connect(this).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
      override fun selectionChanged(event: FileEditorManagerEvent) = updateLanding()
    })
    // Applying a key in Settings → Провайдеры must bring the models in without an IDE restart.
    project.messageBus.connect(this).subscribe(ProvidersChangeListener.TOPIC, ProvidersChangeListener {
      reloadProviderRegistry()
    })
    // The HTTP API runs tasks in a real window — tell the gateway this one is available.
    com.vibe.agent.http.VibeAgentGateway.getInstance().register(this)
  }

  /** Re-read the provider registry and re-pull model catalogs (e.g. after a key was applied in Settings). */
  private fun reloadProviderRegistry() {
    ApplicationManager.getApplication().executeOnPooledThread {
      val loadedProviders = ProvidersService.load(project.basePath) { systemLine("[providers] $it") }
      val catalogCache = ModelCatalogCache.load()
      val guardFindings = ProviderGuard.scan(loadedProviders)
      SwingUtilities.invokeLater {
        if (disposed) return@invokeLater
        staticModelIds = loadedProviders.associate { p -> p.id to p.models.map { it.id }.toSet() }
        providers = applyCatalogCache(loadedProviders, catalogCache)
        systemLine(t("chat.providersChanged"))
        guardFindings.forEach { f -> systemLine("[guard:${f.severity}] ${f.message}") }
        rebuildTargets()
        fetchProviderModels()
      }
    }
  }

  /** The tool window points its preferred focus here (otherwise a read-only bubble wins after re-activation). */
  val preferredFocusComponent: JComponent get() = composer.inputComponent

  override fun dispose() {
    disposed = true
    silenceTimer.stop()
    com.vibe.agent.http.VibeAgentGateway.getInstance().unregister(this)
    externalWaiters.values.forEach { it.countDown() }
    externalWaiters.clear()
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

  /**
   * Providers that resolved to no API key at all (and are not local). Their models are dropped from
   * «Модель ▾»: an entry that fails on the first request is worse than an absent one; the summary
   * line says where to add the key. Filled by the catalog round, so it is empty on the first paint.
   */
  @Volatile private var keylessProviders: Set<String> = emptySet()

  private fun buildTargets(): List<ChatTarget> = buildList {
    agents.forEach { add(ChatTarget.Agent(it)) }
    providers.filter { it.id !in keylessProviders }.forEach { p ->
      // Curated list: a catalog-only model is hidden until enabled on the «Модели» page,
      // a hand-declared one is visible by default (VibeIDE §7); explicit toggles win.
      p.models.filter { m ->
        val custom = staticModelIds[p.id]?.contains(m.id) == true
        m.active && !ModelVisibility.isHidden(p.id, m.id, defaultHidden = !custom) &&
        // A model whose access has ended stays in the file — so the person can see WHY it is gone —
        // but offering it would only produce a 403 with the reason hidden in a stack trace.
        !com.vibe.agent.providers.ModelSunset.isRetired(m, java.time.LocalDate.now())
      }
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
        composer.setImagesAllowed(t.model.vision != false, com.vibe.agent.i18n.VibeI18n.t("chat.model.noVision", "model" to t.model.name))
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
          if (error != null) systemLine(t("chat.modeNotSwitched", "reason" to error.message))
          SwingUtilities.invokeLater { modePicker.setModes(c.modes) }
        }
      }
      catch (e: Exception) {
        systemLine(t("chat.modeNotSwitched", "reason" to e.message))
      }
    }
  }

  // --- turns ---


  // --- /watch ---

  /**
   * Intercepts `/watch <ссылка|путь> [вопрос]` BEFORE the message reaches a model.
   *
   * Returns true when the command was taken over. The pipeline runs in the background with a
   * progress line and a working Стоп: downloading a lecture takes minutes, and a cancel that only
   * lands at the end is not a cancel.
   */
  /**
   * «Сделал» is a promise, not a fact, when the change is something one can SEE.
   *
   * A model cannot look at the screen: it can be certain the button is centred and be wrong, and
   * the cost is paid by whoever opens the app expecting finished work. So a turn that touched
   * something visible ends with a short list of what to open — and with the three states a model
   * never renders in its head: empty, error, narrow screen.
   */
  private fun announceEyesChecklist() {
    val visible = com.vibe.agent.handoff.EyesChecklist.visibleFiles(changedPaths.toList())
    if (visible.isEmpty()) return
    val text = com.vibe.agent.handoff.EyesChecklist.render(
      visible,
      listOf(t("eyes.state.empty"), t("eyes.state.error"), t("eyes.state.narrow")),
      t("eyes.header"),
      { count -> t("eyes.more", "count" to count) },
    )
    if (text.isNotEmpty()) systemLine("👀 " + text)
  }

  /**
   * `/handoff` — the work handed over by form rather than «на словах».
   *
   * What is expensive to lose is not the code that was written — that is in the diff — but what was
   * LEARNED: which approach was already tried and why it failed. That is the section a chat never
   * has, so the form asks for it explicitly and names the sections left empty.
   */
  private fun handleHandoffCommand(message: ComposedMessage): Boolean {
    val text = message.text.trim()
    if (text != HANDOFF_COMMAND && !text.startsWith("$HANDOFF_COMMAND ")) return false
    val note = text.removePrefix(HANDOFF_COMMAND).trim()
    val threadId = currentThreadId
    val plan = threadId?.let { com.vibe.agent.plans.PlanStore.getInstance(project).load(it) }
    val handoff = com.vibe.agent.handoff.HandoffForm.Handoff(
      goal = note.ifEmpty { history.get(threadId ?: "")?.title.orEmpty() },
      done = plan?.steps?.filter { it.status == com.vibe.agent.plans.AgentPlan.Status.COMPLETED }?.map { it.content }.orEmpty(),
      remaining = plan?.steps?.filter { it.status != com.vibe.agent.plans.AgentPlan.Status.COMPLETED }?.map { it.content }.orEmpty(),
      touchedFiles = changedPaths.toList().sorted(),
      howToVerify = VibeAgentSettings.verifyCommand.takeIf { it.isNotBlank() },
    )
    val rendered = com.vibe.agent.handoff.HandoffForm.render(handoff, handoffLabels())
    val gaps = com.vibe.agent.handoff.HandoffForm.gaps(handoff)
    userBubble(text)
    SwingUtilities.invokeLater {
      val console = TerminalConsole(t("handoff.title"))
      console.append(rendered)
      messages.add(console)
      revalidateScroll()
    }
    if (gaps.isNotEmpty()) systemLine(t("handoff.gaps", "sections" to gaps.joinToString(", ") { gapLabel(it) }))
    return true
  }

  private fun gapLabel(gap: String): String = when (gap) {
    com.vibe.agent.handoff.HandoffForm.GOAL -> t("handoff.goal")
    com.vibe.agent.handoff.HandoffForm.REMAINING -> t("handoff.remaining")
    else -> t("handoff.verify")
  }

  private fun handoffLabels() = com.vibe.agent.handoff.HandoffForm.Labels(
    title = t("handoff.title"), goal = t("handoff.goal"), done = t("handoff.done"),
    remaining = t("handoff.remaining"), traps = t("handoff.traps"), files = t("handoff.files"),
    verify = t("handoff.verify"), empty = t("handoff.empty"),
  )

  /**
   * Moves the turn to another provider when the chosen one cannot answer — and only then.
   *
   * Failover is not retry: retry waits out the SAME provider because it asked to be waited for,
   * failover gives up and asks someone else. Confusing them produces the worst of both — hammering
   * a dead endpoint, or abandoning a live one that merely asked for thirty seconds. So this runs
   * after the retries inside the client are already exhausted, and never on a bad key: the key is
   * wrong at the next provider too, and switching would hide the real message behind an unrelated
   * second failure.
   */
  private fun failOver(from: ChatTarget.Model, error: Exception, startedAt: Long): Boolean {
    val kind = com.vibe.agent.resilience.RetryPolicy.classify(
      com.vibe.agent.resilience.RetryPolicy.statusFromMessage(error.message), error)
    if (!com.vibe.agent.resilience.FailoverPlan.shouldFailOver(kind, retriesExhausted = true)) return false
    val chain = com.vibe.agent.resilience.FailoverPlan.parseChain(VibeAgentSettings.failoverChain)
    if (chain.isEmpty()) return false
    val current = com.vibe.agent.resilience.FailoverPlan.Target(from.provider.id, from.model.id)
    failoverTried.add(current)
    val next = com.vibe.agent.resilience.FailoverPlan.next(chain, failoverTried) ?: run {
      systemLine(t("failover.exhausted"))
      return false
    }
    val provider = providers.firstOrNull { it.id == next.providerId } ?: run {
      systemLine(t("failover.unknownProvider", "id" to next.providerId))
      return false
    }
    failoverTried.add(next)
    systemLine(t("failover.switching", "from" to current.toString(), "to" to next.toString(),
                "reason" to (error.message?.take(120) ?: "")))
    val target = ChatTarget.Model(provider, com.vibe.agent.providers.ModelEntry(id = next.modelId), static = true)
    sendToLlm(target, startedAt)
    return true
  }

  /**
   * `/trace` — what the last turn actually did, step by step.
   *
   * The feed shows the story the agent tells about itself; the trace shows the events. The two
   * differ exactly where it matters — a tool retried three times, a gate that bounced the turn
   * back, a file read twice — and none of that is visible in prose.
   */
  private fun handleTraceCommand(message: ComposedMessage): Boolean {
    if (message.text.trim() != TRACE_COMMAND) return false
    val events = trace.snapshot()
    userBubble(message.text.trim())
    val text = com.vibe.agent.trace.TurnTrace.render(events, turnStartedAtMs, traceLabels())
    val repeated = com.vibe.agent.trace.TurnTrace.repeated(events)
    // Near-repeats are shown, never enforced: a semantic loop is real, but stopping a turn on a
    // guess about somebody else's arguments is how a safety becomes a nuisance.
    val near = com.vibe.agent.trace.TurnTrace.nearRepeats(events).filterKeys { key -> repeated[key] == null }
    SwingUtilities.invokeLater {
      val console = TerminalConsole(t("trace.title"))
      console.append(text)
      if (repeated.isNotEmpty()) {
        console.append("\n\n" + t("trace.repeated", "items" to repeated.entries.joinToString { "${it.key} ×${it.value}" }))
      }
      if (near.isNotEmpty()) {
        console.append("\n\n" + t("trace.nearRepeated", "items" to near.entries.joinToString { "${it.key} ×${it.value}" }))
      }
      messages.add(console)
      revalidateScroll()
    }
    return true
  }

  private fun traceLabels() = object : com.vibe.agent.trace.TurnTrace.Labels {
    override fun header(count: Int) = t("trace.header", "count" to count)
    // Literal keys, not «"trace.kind." + name»: a key assembled by concatenation cannot be found
    // by searching the code, and the catalogue gate reports it as both missing and dead at once.
    override fun kind(kind: com.vibe.agent.trace.TurnTrace.Kind) = when (kind) {
      com.vibe.agent.trace.TurnTrace.Kind.TOOL -> t("trace.kind.tool")
      com.vibe.agent.trace.TurnTrace.Kind.GATE -> t("trace.kind.gate")
      com.vibe.agent.trace.TurnTrace.Kind.RETRY -> t("trace.kind.retry")
      com.vibe.agent.trace.TurnTrace.Kind.LOOP -> t("trace.kind.loop")
      com.vibe.agent.trace.TurnTrace.Kind.PLAN -> t("trace.kind.plan")
      com.vibe.agent.trace.TurnTrace.Kind.HOOK -> t("trace.kind.hook")
      com.vibe.agent.trace.TurnTrace.Kind.ERROR -> t("trace.kind.error")
      com.vibe.agent.trace.TurnTrace.Kind.NOTE -> t("trace.kind.note")
    }
    override val empty: String get() = t("trace.empty")
    override val ms: String get() = t("trace.ms")
    override val failureMark: String get() = "✖"
  }

  /** Says which argument is missing, instead of letting the command become a prompt. */
  private fun reportMissingArgument(message: ComposedMessage): Boolean {
    val parsed = com.vibe.agent.ui.ChatCommands.parse(message.text) ?: return false
    if (!com.vibe.agent.ui.ChatCommands.missesArgument(parsed)) return false
    systemLine(t("slash.needsArgument", "command" to parsed.spec.name, "what" to parsed.spec.description()))
    return true
  }

  /**
   * `/map` — the shape of the project as a diagram.
   *
   * The graph already answers «кто кого импортирует», but a list of edges is read with a finger on
   * the screen. Grouped by module and drawn as mermaid, the same data answers «как этот проект
   * устроен» in one glance — and mermaid renders in the IDE, in the repository and in a chat
   * without a single dependency.
   */
  private fun handleMapCommand(message: ComposedMessage): Boolean {
    if (message.text.trim() != MAP_COMMAND) return false
    userBubble(message.text.trim())
    ApplicationManager.getApplication().executeOnPooledThread {
      val nodes = com.vibe.agent.graph.CodeGraphBuilder.build(project)
      val graph = com.vibe.agent.graph.CodeGraphIndex.build(nodes)
      val edges = com.vibe.agent.graph.GraphDiagram.modules(graph.edges.map { it.from to it.to })
      val diagram = com.vibe.agent.graph.GraphDiagram.mermaid(edges)
      if (diagram.isEmpty()) {
        systemLine(t("map.empty"))
        return@executeOnPooledThread
      }
      systemLine(t("map.built", "modules" to edges.flatMap { listOf(it.from, it.to) }.distinct().size,
                   "edges" to edges.size))
      SwingUtilities.invokeLater {
        val console = TerminalConsole(t("map.title"))
        console.append("```mermaid\n" + diagram + "\n```")
        messages.add(console)
        revalidateScroll()
      }
    }
    return true
  }

  /**
   * `/rules` — which project rules apply right now, and why each one is here.
   *
   * Rules are mixed into every turn silently, and silence is the problem: a rule that stopped
   * matching its glob and a rule that was never read look exactly the same from the chat. This says
   * which are always on, which matched the files in play and which were called by name.
   */
  private fun handleRulesCommand(message: ComposedMessage): Boolean {
    if (message.text.trim() != RULES_COMMAND) return false
    userBubble(message.text.trim())
    val all = com.vibe.agent.context.ProjectContextService.getInstance(project).rules()
    if (all.isEmpty()) {
      systemLine(t("rules.none", "dir" to com.vibe.agent.context.ProjectRules.RULES_DIR))
      return true
    }
    val text = all.joinToString("\n") { rule ->
      val why = when {
        rule.alwaysApply -> t("rules.why.always")
        rule.globs.isNotEmpty() -> t("rules.why.globs", "globs" to rule.globs.joinToString(", "))
        else -> t("rules.why.byName", "name" to rule.name)
      }
      "  " + rule.name + " — " + why + (rule.description?.let { " · " + it } ?: "")
    }
    SwingUtilities.invokeLater {
      val console = TerminalConsole(t("rules.title", "count" to all.size))
      console.append(text)
      messages.add(console)
      revalidateScroll()
    }
    return true
  }

  /**
   * `/undo` — put the working folder back to the state before the agent's last turn.
   *
   * The checkpoint line in the feed already does this, but only if one can find the right one by
   * eye, and after a long conversation that is scrolling. The common case — «отмени, что он сейчас
   * наделал» — deserves a command, and it must name WHAT it is about to undo: an undo that acts
   * silently is one people are afraid to use, which makes it useless.
   */
  private fun handleUndoCommand(message: ComposedMessage): Boolean {
    if (message.text.trim() != UNDO_COMMAND) return false
    userBubble(message.text.trim())
    val service = checkpoints ?: run { systemLine(t("undo.unavailable")); return true }
    val latest = service.list().firstOrNull() ?: run { systemLine(t("undo.none")); return true }
    val confirmed = Messages.showYesNoDialog(
      project,
      t("undo.confirm", "hash" to latest.hash.take(8), "label" to latest.label, "time" to timeOfMillis(latest.atMillis)),
      t("undo.title"), t("undo.yes"), t("common.cancel"), Messages.getWarningIcon(),
    )
    if (confirmed != Messages.YES) return true
    if (service.restore(latest)) systemLine(t("undo.done", "hash" to latest.hash.take(8)))
    else systemLine(t("undo.failed"))
    return true
  }

  /**
   * `/blame <файл>` — why the code is the way it is.
   *
   * A model reading a file sees WHAT is written and guesses at why; the commits that touched it say
   * it outright — «это откатили в прошлый раз», «так сделано ради обхода бага». That is the context
   * whose absence produces confident rewrites of decisions somebody already made deliberately.
   */
  private fun handleBlameCommand(message: ComposedMessage): Boolean {
    val text = message.text.trim()
    if (!text.startsWith("$BLAME_COMMAND ")) return false
    val path = text.removePrefix(BLAME_COMMAND).trim()
    if (path.isEmpty()) return false
    userBubble(text)
    ApplicationManager.getApplication().executeOnPooledThread {
      com.vibe.agent.git.GitStateService.getInstance(project).history(path, BLAME_COMMITS)
        .onFailure { systemLine(t("blame.failed", "path" to path, "reason" to it.message)) }
        .onSuccess { commits ->
          if (commits.isEmpty()) {
            systemLine(t("blame.none", "path" to path))
            return@onSuccess
          }
          systemLine(t("blame.found", "count" to commits.size, "path" to path))
          val block = commits.joinToString("\n") { "  " + it.hash + " " + it.subject }
          SwingUtilities.invokeLater {
            startTurn(ComposedMessage(text = t("blame.question", "path" to path) + "\n\n" +
                                        "<context ref=\"git-log:" + path + "\">\n" + block + "\n</context>"))
          }
        }
    }
    return true
  }

  /**
   * `/bg <команда>` — a long command that reports back when it is done.
   *
   * A build, a test suite, a watcher: waiting for them blocks the turn, and starting them and
   * forgetting means the result is discovered by accident half an hour later. Here the command runs
   * outside the turn and its ending arrives as a line in the feed — with the tail of the output,
   * because «упало» without the last twenty lines sends one back to the terminal anyway.
   */
  private fun handleBackgroundCommand(message: ComposedMessage): Boolean {
    val text = message.text.trim()
    if (!text.startsWith("$BG_COMMAND ")) return false
    val command = text.removePrefix(BG_COMMAND).trim()
    if (command.isEmpty()) return false
    userBubble(text)
    systemLine(t("bg.started", "command" to command))
    ApplicationManager.getApplication().executeOnPooledThread {
      val started = System.currentTimeMillis()
      val output = runCatching { runShell(command) }.getOrElse { error ->
        systemLine(t("bg.failed", "command" to command, "reason" to error.message))
        return@executeOnPooledThread
      }
      val seconds = (System.currentTimeMillis() - started) / 1000
      val filtered = com.vibe.agent.context.ContextFilter.filter(
        output, com.vibe.agent.context.ContextFilter.modeOf(VibeAgentSettings.contextFilterMode),
        repeatMark = { count -> t("filter.repeat", "count" to count) },
      )
      val tail = filtered.text.lines().takeLast(BG_TAIL_LINES).joinToString("\n")
      systemLine(t("bg.finished", "command" to command, "seconds" to seconds))
      SwingUtilities.invokeLater {
        val console = TerminalConsole(t("bg.title", "command" to command.take(60)))
        console.append(tail)
        messages.add(console)
        revalidateScroll()
      }
    }
    return true
  }

  /**
   * `/deploy` — the plan for getting this project out of the laptop, and the rule that governs it.
   *
   * The plan is generated because the answer is boring and the same every time; what is not boring
   * is which steps reach outside. Those cost money, create resources with someone's name on them
   * and cannot be undone, so they are marked and each one is confirmed separately. A deploy that
   * «просто взяло и сделало» is the story people tell about the tool they stopped using.
   */
  private fun handleDeployCommand(message: ComposedMessage): Boolean {
    if (message.text.trim() != DEPLOY_COMMAND) return false
    userBubble(message.text.trim())
    val base = project.basePath ?: run { systemLine(t("deploy.noProject")); return true }
    val files = runCatching {
      java.nio.file.Files.list(java.nio.file.Path.of(base)).use { stream ->
        stream.map { it.fileName.toString() }.toList().toSet()
      }
    }.getOrDefault(emptySet())
    val kind = com.vibe.agent.deploy.DeployPlan.detect(files)
    val plan = com.vibe.agent.deploy.DeployPlan.plan(kind, files)
    val text = buildString {
      appendLine(t("deploy.kind", "kind" to kindLabel(kind)))
      appendLine()
      plan.steps.forEachIndexed { index, step ->
        appendLine((index + 1).toString() + ". " + stepLabel(step.id) + (if (step.external) "  " + t("deploy.externalMark") else ""))
      }
      if (plan.warnings.isNotEmpty()) {
        appendLine()
        plan.warnings.forEach { appendLine("⚠️ " + warningLabel(it)) }
      }
      appendLine()
      append(t("deploy.rule"))
    }
    SwingUtilities.invokeLater {
      val console = TerminalConsole(t("deploy.title"))
      console.append(text)
      messages.add(console)
      revalidateScroll()
    }
    return true
  }

  private fun kindLabel(kind: com.vibe.agent.deploy.DeployPlan.Kind): String = when (kind) {
    com.vibe.agent.deploy.DeployPlan.Kind.NODE -> "Node.js"
    com.vibe.agent.deploy.DeployPlan.Kind.PYTHON -> "Python"
    com.vibe.agent.deploy.DeployPlan.Kind.GO -> "Go"
    com.vibe.agent.deploy.DeployPlan.Kind.JVM -> "JVM"
    com.vibe.agent.deploy.DeployPlan.Kind.STATIC -> t("deploy.kind.static")
    com.vibe.agent.deploy.DeployPlan.Kind.DOCKER -> "Docker"
    com.vibe.agent.deploy.DeployPlan.Kind.UNKNOWN -> t("deploy.kind.unknown")
  }

  private fun stepLabel(id: String): String = when (id) {
    com.vibe.agent.deploy.DeployPlan.STEP_CHECK -> t("deploy.step.check")
    com.vibe.agent.deploy.DeployPlan.STEP_DOCKERFILE -> t("deploy.step.dockerfile")
    com.vibe.agent.deploy.DeployPlan.STEP_STATIC_SERVER -> t("deploy.step.staticServer")
    com.vibe.agent.deploy.DeployPlan.STEP_BUILD_IMAGE -> t("deploy.step.buildImage")
    com.vibe.agent.deploy.DeployPlan.STEP_LOCAL_RUN -> t("deploy.step.localRun")
    com.vibe.agent.deploy.DeployPlan.STEP_REGISTRY -> t("deploy.step.registry")
    com.vibe.agent.deploy.DeployPlan.STEP_HOST -> t("deploy.step.host")
    com.vibe.agent.deploy.DeployPlan.STEP_DOMAIN -> t("deploy.step.domain")
    com.vibe.agent.deploy.DeployPlan.STEP_TLS -> t("deploy.step.tls")
    else -> t("deploy.step.ci")
  }

  private fun warningLabel(id: String): String = when (id) {
    com.vibe.agent.deploy.DeployPlan.WARN_UNKNOWN_KIND -> t("deploy.warn.unknownKind")
    com.vibe.agent.deploy.DeployPlan.WARN_NO_DOCKERIGNORE -> t("deploy.warn.noDockerignore")
    else -> t("deploy.warn.envInRepo")
  }

  /**
   * `/learn <навык>` — a lesson that remembers where the last one stopped.
   *
   * An ordinary chat teaches badly for two structural reasons: it starts from zero every time, so
   * the tenth lesson repeats the first, and it asks nothing first, so it teaches an average person
   * an average version of the topic — which the internet already does for free. Hence the mission
   * gate and the stored progress.
   */
  private fun handleLearnCommand(message: ComposedMessage): Boolean {
    val text = message.text.trim()
    if (!text.startsWith("$LEARN_COMMAND ") && text != LEARN_COMMAND) return false
    val skill = text.removePrefix(LEARN_COMMAND).trim()
    val store = com.vibe.agent.learning.LearningStore.getInstance(project)
    if (skill.isEmpty()) {
      val known = store.list()
      systemLine(if (known.isEmpty()) t("learn.usage") else t("learn.known", "skills" to known.joinToString(", ")))
      return true
    }
    userBubble(text)
    val progress = store.load(skill)
    val missing = com.vibe.agent.learning.LearningPlan.missingMissionParts(progress.mission)
    if (missing.isNotEmpty()) {
      // The gate: no lesson until the three questions are answered. Asked as questions in the chat,
      // because a form is something people close.
      systemLine(t("learn.missionGate"))
      return startTurn(ComposedMessage(text = t("learn.missionPrompt", "skill" to skill,
                                                "questions" to missing.joinToString("\n") { missionQuestion(it) })))
    }
    val resources = store.resources()
    val prompt = com.vibe.agent.learning.LearningPlan.lessonPrompt(progress, resources, learningLabels())
    systemLine(t("learn.lesson", "skill" to skill, "lessons" to progress.lessonsDone,
                 "difficulty" to progress.difficulty.name.lowercase()))
    return startTurn(ComposedMessage(text = prompt))
  }

  private fun missionQuestion(part: String): String = when (part) {
    com.vibe.agent.learning.LearningPlan.WHY -> t("learn.question.why")
    com.vibe.agent.learning.LearningPlan.ALREADY -> t("learn.question.already")
    else -> t("learn.question.result")
  }

  private fun learningLabels() = object : com.vibe.agent.learning.LearningPlan.Labels {
    override val role: String get() = t("learn.role")
    override val sources: String get() = t("learn.sources")
    override val noSources: String get() = t("learn.noSources")
    override val format: String get() = t("learn.format")
    override fun skill(skill: String) = t("learn.skill", "skill" to skill)
    override fun mission(why: String, already: String, result: String) =
      t("learn.mission", "why" to why, "already" to already, "result" to result)
    override fun progress(lessons: Int, difficulty: String, lastLesson: String?) =
      t("learn.progress", "lessons" to lessons, "difficulty" to difficulty, "last" to (lastLesson ?: "—"))
  }

  /**
   * `/measure <команда>` — the number this task is optimised against, measured rather than felt.
   *
   * «Ускорь», «урежь размер сборки», «подними покрытие» have no threshold at which they are done, so
   * a model working on them declares victory by adjective. The first measurement becomes the
   * baseline, every later one is compared against it, and the direction of «better» is fixed in the
   * settings BEFORE the work starts — deciding it afterwards is how a regression becomes a success.
   */
  private fun handleMeasureCommand(message: ComposedMessage): Boolean {
    val text = message.text.trim()
    if (!text.startsWith("$MEASURE_COMMAND ") && text != MEASURE_COMMAND) return false
    val command = text.removePrefix(MEASURE_COMMAND).trim()
    if (command.isEmpty()) {
      systemLine(t("measure.usage"))
      return true
    }
    userBubble(text)
    systemLine(t("measure.running", "command" to command))
    ApplicationManager.getApplication().executeOnPooledThread {
      val output = runCatching { runShell(command) }.getOrElse {
        systemLine(t("measure.failed", "reason" to it.message))
        return@executeOnPooledThread
      }
      val result = com.vibe.agent.specs.MetricRun.extract(output, VibeAgentSettings.metricPattern)
      if (result == null) {
        systemLine(t("measure.noNumber", "pattern" to VibeAgentSettings.metricPattern.ifBlank { com.vibe.agent.specs.MetricRun.DEFAULT_PATTERN }))
        return@executeOnPooledThread
      }
      val direction = com.vibe.agent.specs.MetricRun.directionOf(VibeAgentSettings.metricDirection)
      val baseline = metricBaseline
      if (baseline == null) {
        metricBaseline = result.value
        systemLine(t("measure.baseline", "value" to result.value, "raw" to result.raw))
        return@executeOnPooledThread
      }
      val comparison = com.vibe.agent.specs.MetricRun.compare(baseline, result.value, direction)
      // Literal keys in both branches: a key chosen inside the t(...) call is invisible to the
      // catalogue gate, which then reports it as dead while the code uses it.
      val percent = "%.1f".format(comparison.percent)
      systemLine(
        if (comparison.improved) t("measure.better", "before" to baseline, "after" to result.value, "percent" to percent)
        else t("measure.worse", "before" to baseline, "after" to result.value, "percent" to percent)
      )
    }
    return true
  }

  /** Baseline of the current optimisation; belongs to the chat it was measured in. */
  @Volatile private var metricBaseline: Double? = null

  /**
   * Everything counted per chat rather than per panel.
   *
   * Called when the visible chat changes: these numbers describe ONE conversation, and carrying
   * them over produces a fresh chat that is already «дорогой» and already warned about.
   */
  private fun resetChatCounters() {
    sessionTokens.set(0)
    announcedContextLevels.clear()
    metricBaseline = null
    lastAccountedUsed.set(0)
  }

  private fun runShell(command: String): String {
    val process = ProcessBuilder(com.vibe.agent.util.ProcessSupport.shellCommand(command))
      .directory(project.basePath?.let { java.io.File(it) })
      .redirectErrorStream(true)
      .start()
    val out = com.vibe.agent.util.ProcessSupport.drain(process.inputStream, "vibe-measure")
    if (!process.waitFor(MEASURE_TIMEOUT_SEC, java.util.concurrent.TimeUnit.SECONDS)) {
      process.destroyForcibly()
      error(t("measure.timeout", "seconds" to MEASURE_TIMEOUT_SEC))
    }
    return out.get(5, java.util.concurrent.TimeUnit.SECONDS).orEmpty()
  }

  /**
   * `/simplify` — the current diff read back as a DELETE LIST.
   *
   * Review asks «правильно ли это». This asks the question nobody asks — «что отсюда можно убрать,
   * ничего не потеряв» — because the code was just written and every line of it felt necessary an
   * hour ago. The answer must be actionable, so it comes back as file, line, what, why; prose is
   * reported as unparsed rather than shown as an essay.
   */
  private fun handleSimplifyCommand(message: ComposedMessage): Boolean {
    if (message.text.trim() != SIMPLIFY_COMMAND) return false
    userBubble(message.text.trim())
    ApplicationManager.getApplication().executeOnPooledThread {
      val diff = com.vibe.agent.git.GitStateService.getInstance(project).diff()
        .getOrElse { systemLine(t("simplify.noDiff")); return@executeOnPooledThread }
      if (diff.isBlank()) {
        systemLine(t("simplify.empty"))
        return@executeOnPooledThread
      }
      val target = target as? ChatTarget.Model ?: run {
        systemLine(t("simplify.needsModel"))
        return@executeOnPooledThread
      }
      val resolved = ProvidersService.resolve(target.provider, project.basePath) { } ?: return@executeOnPooledThread
      val prompt = com.vibe.agent.minimalism.SimplifyPrompt.build(diff, t("simplify.instruction"), t("simplify.ladder"))
      val answer = StringBuilder()
      runCatching {
        llmClient.chat(resolved, target.model, listOf(com.vibe.agent.providers.ChatMessage("user", prompt))) { delta ->
          answer.append(delta)
        }
      }.onFailure {
        systemLine(t("simplify.failed", "reason" to it.message))
        return@executeOnPooledThread
      }
      val (items, unparsed) = com.vibe.agent.minimalism.SimplifyPrompt.parseAnswer(answer.toString())
      if (items.isEmpty()) {
        systemLine(t("simplify.nothing"))
        if (unparsed.isNotEmpty()) systemLine(t("simplify.unparsed", "count" to unparsed.size))
        return@executeOnPooledThread
      }
      SwingUtilities.invokeLater {
        val console = TerminalConsole(t("simplify.title", "count" to items.size))
        console.append(items.joinToString("\n") { item ->
          item.file + (item.line?.let { ":" + it } ?: "") + " — " + item.what + (if (item.why.isBlank()) "" else " — " + item.why)
        })
        if (unparsed.isNotEmpty()) console.append("\n\n" + t("simplify.unparsed", "count" to unparsed.size))
        messages.add(console)
        revalidateScroll()
      }
    }
    return true
  }

  /**
   * `/find <запрос>` — search by MEANING, and `/index` to build the index.
   *
   * Next to grep and the code graph this answers the third question: «где мы делаем то же самое
   * другими словами». That is the question people have in an unfamiliar project, and it is exactly
   * the one a text search cannot answer.
   */
  private fun handleFindCommand(message: ComposedMessage): Boolean {
    val text = message.text.trim()
    val isIndex = text == INDEX_COMMAND
    if (!isIndex && !text.startsWith("$FIND_COMMAND ")) return false
    userBubble(text)
    val rag = com.vibe.agent.rag.RagIndex.getInstance(project)
    if (isIndex) {
      systemLine(t("rag.indexing"))
      ApplicationManager.getApplication().executeOnPooledThread {
        rag.rebuild { progress -> if (progress.indexed % INDEX_PROGRESS_STEP == 0) systemLine(
          t("rag.progress", "indexed" to progress.indexed, "total" to progress.total)) }
          .onSuccess { systemLine(t("rag.indexed", "files" to it.indexed, "skipped" to it.skipped, "chunks" to rag.size())) }
          .onFailure { systemLine(ragError(it)) }
      }
      return true
    }
    val query = text.removePrefix(FIND_COMMAND).trim()
    ApplicationManager.getApplication().executeOnPooledThread {
      rag.search(query)
        .onFailure { systemLine(ragError(it)) }
        .onSuccess { hits ->
          if (hits.isEmpty()) {
            systemLine(t("rag.nothing"))
            return@onSuccess
          }
          systemLine(t("rag.found", "count" to hits.size))
          val block = hits.joinToString("\n\n") { hit ->
            "<context ref=\"" + hit.chunk.path + ":" + hit.chunk.fromLine + "-" + hit.chunk.toLine + "\">\n" +
              hit.chunk.text + "\n</context>"
          }
          SwingUtilities.invokeLater {
            startTurn(ComposedMessage(text = query + "\n\n" + t("rag.header") + "\n" + block))
          }
        }
    }
    return true
  }

  private fun ragError(error: Throwable): String = when (error.message) {
    com.vibe.agent.rag.RagIndex.NOT_CONFIGURED -> t("rag.notConfigured")
    com.vibe.agent.rag.RagIndex.NOT_INDEXED -> t("rag.notIndexed")
    com.vibe.agent.rag.RagIndex.NO_PROVIDER -> t("rag.noProvider")
    com.vibe.agent.rag.RagIndex.OFFLINE -> t("rag.offline")
    else -> t("rag.failed", "reason" to error.message)
  }

  /**
   * `/help <вопрос>` — the product's own documentation, attached to the turn.
   *
   * Asked «как здесь устроен дизайн-гейт», a model without this answers from its memory of some
   * other product, confidently. The docs ship inside the build, so the agent reads the real text of
   * the version it is running in — and reads the FILES, not a summary: the bundle is small enough
   * to name precisely and too large to inline.
   */
  private fun handleHelpCommand(message: ComposedMessage): Boolean {
    val text = message.text.trim()
    if (text != HELP_COMMAND && !text.startsWith("$HELP_COMMAND ")) return false
    val question = text.removePrefix(HELP_COMMAND).trim()
    if (question.isEmpty()) {
      userBubble(text)
      SwingUtilities.invokeLater {
        val console = TerminalConsole(t("help.title"))
        console.append(com.vibe.agent.help.HelpBundle.list().joinToString("\n") { "- ${it.title}" })
        messages.add(console)
        revalidateScroll()
      }
      return true
    }
    val docs = com.vibe.agent.help.HelpBundle.find(question)
    if (docs.isEmpty()) {
      systemLine(t("help.nothing"))
      return true
    }
    val bodies = docs.mapNotNull { doc ->
      com.vibe.agent.help.HelpBundle.read(doc.resource)?.let { doc to it }
    }
    systemLine(t("help.using", "docs" to docs.joinToString { it.title }))
    val block = bodies.joinToString("\n\n") { (doc, body) ->
      "<context ref=\"help:${doc.resource}\">\n${body.take(HELP_DOC_CHARS)}\n</context>"
    }
    return startTurn(ComposedMessage(text = question + "\n\n" + t("help.header") + "\n" + block))
  }

  /**
   * `/council <вопрос>` — one question to several DIFFERENT models, each blind to the others.
   *
   * The value is the difference between them: the same model asked twice agrees with itself, and
   * that second answer reads as confirmation while being nothing of the kind. Two models trained
   * differently disagreeing is information — the question is open, or one of them knows something.
   *
   * Advisers answer in parallel because a council that takes four sequential round trips is a
   * council nobody uses.
   */
  private fun handleCouncilCommand(message: ComposedMessage): Boolean {
    val text = message.text.trim()
    if (!text.startsWith("$COUNCIL_COMMAND ") && text != COUNCIL_COMMAND) return false
    val question = text.removePrefix(COUNCIL_COMMAND).trim()
    if (question.isEmpty()) {
      systemLine(t("council.noQuestion"))
      return true
    }
    val plan = com.vibe.agent.council.CouncilPlan.parse(
      VibeAgentSettings.councilAdvisers,
      unknownProvider = { id -> providers.none { it.id == id } },
    )
    if (plan.problems.isNotEmpty()) systemLine(t("council.badEntries", "entries" to plan.problems.joinToString(", ")))
    if (!plan.isUsable) {
      systemLine(t("council.notConfigured", "min" to com.vibe.agent.council.CouncilPlan.MIN_ADVISERS))
      return true
    }
    userBubble(text)
    systemLine(t("council.asking", "advisers" to plan.advisers.joinToString { it.toString() }))
    ApplicationManager.getApplication().executeOnPooledThread {
      val answers = java.util.concurrent.ConcurrentHashMap<Int, String>()
      val threads = plan.advisers.mapIndexed { index, adviser ->
        Thread({
          val answer = askAdviser(adviser, question)
          if (answer != null) answers[index] = answer
        }, "vibe-council-$index").apply { isDaemon = true }
      }
      threads.forEach { it.start() }
      threads.forEach { it.join(COUNCIL_TIMEOUT_MS) }
      val collected = answers.toSortedMap().values.toList()
      if (collected.size < com.vibe.agent.council.CouncilPlan.MIN_ADVISERS) {
        systemLine(t("council.tooFewAnswers", "count" to collected.size))
        return@executeOnPooledThread
      }
      SwingUtilities.invokeLater {
        collected.forEachIndexed { index, answer ->
          val console = TerminalConsole(t("council.opinion", "index" to (index + 1)))
          console.append(answer)
          messages.add(console)
        }
        revalidateScroll()
      }
      // The synthesis goes through the ordinary turn: it is an answer like any other, and it must
      // land in the transcript so the chat can be continued from it.
      SwingUtilities.invokeLater {
        startTurn(ComposedMessage(text = com.vibe.agent.council.CouncilPlan.synthesisPrompt(
          question, collected, t("council.synthesis"))))
      }
    }
    return true
  }

  /** One adviser, one blocking call; failures are reported and do not take the council down. */
  private fun askAdviser(adviser: com.vibe.agent.council.CouncilPlan.Adviser, question: String): String? {
    val provider = providers.firstOrNull { it.id == adviser.providerId } ?: return null
    val resolved = ProvidersService.resolve(provider, project.basePath) { } ?: return null
    val model = com.vibe.agent.providers.ModelEntry(id = adviser.modelId)
    val answer = StringBuilder()
    return runCatching {
      com.vibe.agent.providers.LlmClient().chat(
        resolved, model,
        listOf(com.vibe.agent.providers.ChatMessage("user",
          com.vibe.agent.council.CouncilPlan.adviserPrompt(question, t("council.adviser")))),
      ) { delta -> answer.append(delta) }
      answer.toString().trim().ifEmpty { null }
    }.getOrElse { error ->
      systemLine(t("council.adviserFailed", "adviser" to adviser.toString(), "reason" to error.message))
      null
    }
  }

  /**
   * `/git [вопрос]` — the state of the repository as a fact, attached to the turn.
   *
   * Without it the model reaches for the terminal: it pays for `git status`, then for `git diff`,
   * then re-reads a diff it half-remembers — three round trips and a wall of output for four lines
   * of answer. A large `git diff` is also exactly the shape that fills the window and makes the
   * model forget the task it was given.
   *
   * The state goes in as CONTEXT, not as the message: the user's question stays the question.
   */
  private fun handleGitCommand(message: ComposedMessage): Boolean {
    val text = message.text.trim()
    if (text != GIT_COMMAND && !text.startsWith("$GIT_COMMAND ")) return false
    val question = text.removePrefix(GIT_COMMAND).trim()
    val state = com.vibe.agent.git.GitStateService.getInstance(project).collect().getOrElse { error ->
      systemLine(
        if (error.message == com.vibe.agent.git.GitStateService.NOT_A_REPO) t("git.notARepo")
        else t("git.failed", "reason" to error.message)
      )
      return true
    }
    val report = com.vibe.agent.git.RepoState.report(state, GIT_REPORT_LIMIT, gitLabels())
    userBubble(text)
    systemLine(t("git.collected", "files" to state.changes.size))
    // A question of its own turns the state into a turn; a bare /git just shows it.
    if (question.isEmpty()) {
      SwingUtilities.invokeLater {
        val console = TerminalConsole(t("git.title"))
        console.append(report)
        messages.add(console)
        revalidateScroll()
      }
      return true
    }
    return startTurn(ComposedMessage(
      text = question + "\n\n<context ref=\"git\">\n" + report + "\n</context>",
      images = message.images, context = message.context,
    ))
  }

  private fun gitLabels(): com.vibe.agent.git.RepoState.Labels = object : com.vibe.agent.git.RepoState.Labels {
    override fun header(branch: String?, upstream: String?, ahead: Int, behind: Int, detached: Boolean): String = when {
      detached -> t("git.header.detached")
      upstream != null -> t("git.header.tracking", "branch" to branch, "upstream" to upstream, "ahead" to ahead, "behind" to behind)
      else -> t("git.header.branch", "branch" to (branch ?: "?"))
    }
    override val clean: String get() = t("git.clean")
    override fun change(change: com.vibe.agent.git.RepoState.Change): String {
      val size = if (change.binary) t("git.binary")
                 else if (change.untracked) t("git.new")
                 else t("git.size", "added" to change.added, "removed" to change.removed)
      return "  ${change.status} ${change.path} — $size"
    }
    override fun more(count: Int): String = "  " + t("git.more", "count" to count)
    override val commitsHeader: String get() = t("git.commits")
  }

  /**
   * `/output <handle>` — the full text of an output that was shrunk for the model.
   *
   * Compression is only honest while the removed part can be got back: without this command the
   * marker would be an apology, and the next thing anyone would do is turn compression off.
   */
  /**
   * The session ceiling, asked BEFORE the turn: reporting an overspend after the money is gone is
   * a receipt, not a guard.
   */
  private fun sessionCeilingReached(text: String): Boolean {
    val limit = VibeChatSettings.sessionTokenLimit
    if (limit <= 0) return false
    val projected = sessionTokens.get() + com.vibe.agent.context.ContextBudget.estimateTokens(text)
    val status = com.vibe.agent.context.ContextBudget.check(0, 0, projected, limit)
    if (status.verdict != com.vibe.agent.context.ContextBudget.Verdict.SESSION_EXCEEDED) {
      sessionTokens.set(projected)
      return false
    }
    systemLine(t("context.sessionExceeded", "used" to "%,d".format(projected), "limit" to "%,d".format(limit)))
    return true
  }

  private fun handleOutputCommand(message: ComposedMessage): Boolean {
    val text = message.text.trim()
    if (!text.startsWith(OUTPUT_COMMAND)) return false
    val handle = text.removePrefix(OUTPUT_COMMAND).trim()
    val full = outputStore.get(handle)
    if (full == null) {
      systemLine(t("output.unknownHandle", "handle" to handle))
      return true
    }
    userBubble(text)
    SwingUtilities.invokeLater {
      val console = TerminalConsole(t("output.full", "handle" to handle))
      console.append(full)
      messages.add(console)
      revalidateScroll()
    }
    return true
  }

  private fun handleWatchCommand(message: ComposedMessage): Boolean {
    val command = com.vibe.agent.watch.WatchInput.parse(message.text) ?: return false
    val tools = com.vibe.agent.watch.WatchTools.resolve().getOrElse { error ->
      systemLine("[watch] " + (error.message ?: ""))
      return true
    }
    val target = target
    val hint = com.vibe.agent.watch.WatchInput.classify(command.source)
    // Vision gate BEFORE the pipeline: downloading a lecture to then say t("chat.switchModel") wastes
    // minutes. Audio needs no vision model, so it is not gated.
    if (hint != com.vibe.agent.watch.WatchInput.Kind.AUDIO && !targetAcceptsImages(target)) {
      systemLine("[watch] " + com.vibe.agent.i18n.VibeI18n.t("watch.noVisionTarget"))
      return true
    }

    systemLine("[watch] ${command.source}")
    val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
    watchCancel = cancelled
    ApplicationManager.getApplication().executeOnPooledThread {
      val workDir = com.vibe.agent.watch.WatchPipeline.workDir()
      try {
        val pipeline = com.vibe.agent.watch.WatchPipeline(
          tools, workDir,
          onProgress = { stage -> systemLine("[watch] $stage") },
          isCancelled = { cancelled.get() || disposed },
        )
        val result = pipeline.run(command.source).getOrElse { error ->
          systemLine("[watch] " + com.vibe.agent.i18n.VibeI18n.t("watch.failed", "reason" to error.message))
          return@executeOnPooledThread
        }
        if (cancelled.get() || disposed) {
          systemLine("[watch] " + com.vibe.agent.i18n.VibeI18n.t("watch.cancelled"))
          return@executeOnPooledThread
        }
        result.warning?.let { systemLine("[watch] $it") }
        val images = result.frames.mapNotNull { path ->
          runCatching {
            ImageAttachment(path.fileName.toString(), "image/jpeg", java.nio.file.Files.readAllBytes(path))
          }.getOrNull()
        }
        // The second vision check: a video renamed to .mp3 slips past the early gate, and finding
        // out at send time would be a hard error instead of a sentence.
        if (images.isNotEmpty() && !targetAcceptsImages(this.target)) {
          systemLine("[watch] " + com.vibe.agent.i18n.VibeI18n.t("watch.textOnly"))
        }
        val prompt = com.vibe.agent.watch.WatchPrompt.build(result, command.question)
        val send = ComposedMessage(prompt, emptyList(), if (targetAcceptsImages(this.target)) images else emptyList())
        SwingUtilities.invokeLater { if (!disposed) startTurn(send) }
      }
      finally {
        watchCancel = null
        // Frames live only long enough to be read into the message.
        runCatching { workDir.toFile().deleteRecursively() }
      }
    }
    return true
  }

  private fun targetAcceptsImages(t: ChatTarget?): Boolean = when (t) {
    is ChatTarget.Model -> t.model.vision != false
    is ChatTarget.Agent -> client?.capabilities?.image != false
    null -> false
  }

  // --- skills ---

  /**
   * Turns `/skill:<id>` mentions into the actual recipe.
   *
   * Before this the token was only text: the model received the literal «/skill:grill» and never a
   * line of the skill, so a seeded skill looked like it worked and quietly did nothing. A missing
   * or broken package is said out loud for the same reason — a slightly worse answer is the one
   * failure nobody ever investigates.
   */
  private fun resolveSkills(text: String): List<ContextSerializer.LoadedSkill> {
    val ids = com.vibe.agent.skills.SkillExpansion.mentioned(text)
    if (ids.isEmpty()) return emptyList()
    val resolved = ArrayList<ContextSerializer.LoadedSkill>()
    for (id in ids) {
      val entry = com.vibe.agent.skills.SkillsStore.find(project.basePath, id)
      if (entry == null) {
        systemLine(t("chat.skillNotFound", "id" to id, "path" to "${com.vibe.agent.skills.SkillPackage.SKILLS_DIR}/$id/${com.vibe.agent.skills.SkillPackage.SKILL_FILE}"))
        continue
      }
      if (entry.isBroken) {
        val errors = entry.findings.filter { it.level == com.vibe.agent.skills.SkillValidator.Level.ERROR }
        systemLine(t("chat.skillBroken", "id" to id, "reasons" to errors.joinToString("; ") { it.message }))
        continue
      }
      // A skill is text from disk like any other — same guard as project files.
      val clean = com.vibe.agent.security.ContextSanitizer.sanitize(entry.pkg.body)
      if (clean.findings.isNotEmpty()) reportContextFindings("$id/${com.vibe.agent.skills.SkillPackage.SKILL_FILE}", clean.findings)
      resolved.add(ContextSerializer.LoadedSkill(id, clean.text))
    }
    if (resolved.isNotEmpty()) systemLine(t("chat.skillsApplied", "ids" to resolved.joinToString { it.id }))
    return resolved
  }

  // --- context guard ---

  /**
   * One line per file, and only for what a person can act on. The guard runs on every file that
   * enters the model's context — the noisy version of this would print on every read.
   */
  private fun reportContextFindings(path: String, findings: List<com.vibe.agent.security.ContextSanitizer.Finding>) {
    if (findings.isEmpty()) return
    val name = path.substringAfterLast('/')
    val parts = findings.map { finding ->
      when (finding.kind) {
        com.vibe.agent.security.ContextSanitizer.Kind.INVISIBLE -> t("guard.invisible", "count" to finding.count)
        com.vibe.agent.security.ContextSanitizer.Kind.BIDI -> t("guard.bidi", "count" to finding.count)
        com.vibe.agent.security.ContextSanitizer.Kind.INSTRUCTION -> t("guard.instruction")
        com.vibe.agent.security.ContextSanitizer.Kind.SECRET -> t("guard.secret", "detail" to finding.detail)
      }
    }
    systemLine(t("guard.contextLine", "file" to name, "items" to parts.joinToString("; ")))
  }

  // --- external tasks (incoming HTTP API) ---

  /** Latches for callers that asked to wait for the end of a turn they started over HTTP. */
  private val externalWaiters = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.CountDownLatch>()

  /** Set while a /watch pipeline is running, so Стоп can interrupt a minutes-long download. */
  @Volatile private var watchCancel: java.util.concurrent.atomic.AtomicBoolean? = null

  /** Thread id → ledger run id, so the end of a turn can close the record that started it. */
  private val externalRuns = java.util.concurrent.ConcurrentHashMap<String, String>()

  override val projectName: String get() = project.name

  override fun putImageIntoComposer(name: String, mimeType: String, bytes: ByteArray) {
    SwingUtilities.invokeLater {
      if (disposed) return@invokeLater
      composer.attachImages(listOf(ImageAttachment(name, mimeType, bytes)))
      com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow("VibeAgent")?.activate(null)
    }
  }

  override fun putIntoComposer(text: String) {
    SwingUtilities.invokeLater {
      if (disposed) return@invokeLater
      composer.appendDraft(text)
      com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow("VibeAgent")?.activate(null)
    }
  }

  override fun ownsSession(sessionId: String): Boolean =
    history.get(sessionId)?.let { history.matchesWorkspace(it, project.basePath) } == true

  /**
   * Runs a task that came from outside the IDE (HTTP API). The turn goes through the very same
   * path as a typed message — queue, breakers, hooks, gates — because an automated caller must not
   * get a weaker set of safeguards than a person sitting at the keyboard.
   *
   * An unknown [sessionId] is NOT an error: a new thread is started and its id returned. The
   * session may simply have been deleted by the user, and refusing would leave a pipeline with no
   * way forward (VibeIDE contract).
   */
  override fun runExternalTask(task: String, sessionId: String?, wait: Boolean): String {
    check(!ApplicationManager.getApplication().isDispatchThread) { "runExternalTask blocks — must not be called from the EDT" }
    val started = java.util.concurrent.CompletableFuture<String>()
    SwingUtilities.invokeLater {
      if (disposed) {
        started.completeExceptionally(IllegalStateException(t("chat.panelClosed")))
        return@invokeLater
      }
      val threadId = sessionId?.takeIf { history.get(it) != null }
                     ?: history.create(project.basePath, project.name).id
      activateThread(threadId)
      val latch = java.util.concurrent.CountDownLatch(1)
      externalWaiters[threadId] = latch
      if (startTurn(ComposedMessage(text = task), threadId)) started.complete(threadId)
      else {
        externalWaiters.remove(threadId)
        started.completeExceptionally(IllegalStateException(t("chat.noTargetForTurn")))
      }
    }
    val threadId = started.get(SUBMIT_TIMEOUT_SEC, java.util.concurrent.TimeUnit.SECONDS)
    // The corner of the project this task is about, guessed from its own words: asking a caller to
    // declare it would be asking for the field everybody leaves empty.
    val territory = com.vibe.agent.runs.TerritoryGuess.prefixes(task)
    warnAboutTerritory(territory)
    val runId = runs.started(
      com.vibe.agent.runs.AgentRunLedger.Source.HTTP_API,
      goal = task,
      target = target?.id,
      territory = territory,
    )
    // null when the ledger is off — then there is simply nothing to close later.
    runId?.let { externalRuns[threadId] = it }
    if (!wait) return threadId
    val latch = externalWaiters[threadId] ?: return threadId
    val timeout = VibeAgentSettings.DEFAULT_HTTP_API_WAIT_TIMEOUT_SEC.toLong()
    if (!latch.await(timeout, java.util.concurrent.TimeUnit.SECONDS)) {
      externalWaiters.remove(threadId)
      runs.finished(externalRuns.remove(threadId), com.vibe.agent.runs.AgentRunLedger.Status.FAILED, t("chat.turnTimeout", "seconds" to timeout))
      throw IllegalStateException(t("chat.turnTimeout", "seconds" to timeout))
    }
    return threadId
  }

  /**
   * Says out loud when another run is already working in the same corner.
   *
   * Says rather than refuses: the guess is made from words, and a guesser that BLOCKS work on its
   * own reading of a sentence would stop legitimate runs for the crime of mentioning a path. The
   * damage of two agents in one folder is silent, so naming it out loud is the whole fix — the
   * decision stays with the person, who can see both goals.
   */
  private fun warnAboutTerritory(territory: List<String>) {
    val busy = runCatching { runs.territoryConflicts(territory) }.getOrDefault(emptyList())
    if (busy.isEmpty()) return
    systemLine(t("runs.territoryBusy",
                 "prefixes" to territory.joinToString(", "),
                 "goals" to busy.joinToString("; ") { it.goal }))
  }

  /** Validates, shows the user bubble and starts the turn; false keeps the draft in the composer. */
  private fun startTurn(
    message: ComposedMessage,
    threadId: String = currentThreadId,
    actor: com.vibe.agent.audit.AuditActor = com.vibe.agent.audit.AuditActor.HUMAN,
  ): Boolean {
    if (disposed) return false
    turnActor = actor
    turnEndedBadly = false
    // Cleared at the START, not only filled on load: a turn without attachments would otherwise
    // inherit the previous turn's files and quietly bill them again.
    turnAttachments = emptyList()
    val t = target ?: run {
      systemLine(t("chat.noTargetHint", "path" to AcpConfig.configPath()))
      return false
    }
    if (turnInFlight.get()) {
      composer.queue.add(message)
      return true
    }
    if (t is ChatTarget.Model && t.model.vision == false && message.images.isNotEmpty()) {
      Messages.showErrorDialog(project,
        com.vibe.agent.i18n.VibeI18n.t("chat.model.noVisionDialog", "provider" to t.provider.name, "model" to t.model.name),
        "Vibe Agent")
      return false
    }
    // A latched security breaker blocks starting an agent turn until the user clears it (VibeIDE contract).
    if (t is ChatTarget.Agent && breakers.isBlocking() && !confirmClearBreakers()) return false
    // The store is app-wide: an untagged thread can be open in another window too.
    if (!history.tryBeginTurn(threadId)) {
      systemLine(t("chat.threadBusy"))
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
          systemLine(t("chat.contextUnresolved", "items" to bad.joinToString(", ")))
        }
        val refs = (message.context + resolution?.refs.orEmpty()).distinctBy { it.key }
        val loaded = ReadAction.nonBlocking(Callable { ContextSerializer.load(project, refs, VibeAgentSettings.maskSecretsInContext) })
          .expireWith(this).executeSynchronously()
        loaded.forEach { reportContextFindings(it.relPath, it.findings) }
        // Remembered for the spend report: sizes as they actually travelled, after masking and
        // compression, because that is what was paid for.
        turnAttachments = loaded.mapNotNull { item ->
          item.text?.length?.takeIf { it > 0 }?.let { com.vibe.agent.budget.FileSpend.Attachment(item.relPath, it) }
        }
        val skills = resolveSkills(message.text)
        if (refs.isNotEmpty()) systemLine(t("chat.contextAttached", "items" to refs.joinToString { it.label }))
        // The wire text (with inlined context) becomes known only now — fill it into the stored record.
        if (t is ChatTarget.Model) {
          history.setLastUserWireText(threadId,
            prependProjectRules(prependKnowledge(ContextSerializer.llmText(message.text, loaded, skills), message.text),
                                message.text, loaded)
              .takeIf { it != displayText })
        }
        if (llmCancel.get() || disposed) {
          systemLine(t("chat.cancelledBeforeSend"))
          finishTurn()
          return@executeOnPooledThread
        }
        when (t) {
          is ChatTarget.Model -> sendToLlm(t, startedAt)
          is ChatTarget.Agent -> sendToAcp(t, message.text, loaded, message.images, startedAt, skills)
        }
      }
      catch (e: Exception) {
        systemLine(t("chat.error", "reason" to e.message))
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
    endedThreadId?.let {
      history.endTurn(it)
      // A caller blocked on `wait: true` must be released on ANY ending — done, cancelled, failed.
      externalWaiters.remove(it)?.countDown()
      externalRuns.remove(it)?.let { runId ->
        runs.finished(runId, com.vibe.agent.runs.AgentRunLedger.Status.COMPLETED, t("chat.turnFinished"))
      }
    }
    status.set(if (breakers.isBlocking()) VibeAgentStatusService.State.BLOCKED else VibeAgentStatusService.State.IDLE)
    if (disposed) return
    announceEyesChecklist()
    notifyTurnEndIfAway()
    // The sound is for someone who looked away; the policy inside decides whether to play at all.
    com.vibe.agent.sound.VibeSoundService.getInstance()
      .play(com.vibe.agent.sound.SoundPolicy.Event.TURN_FINISHED, project)
    SwingUtilities.invokeLater {
      if (disposed) return@invokeLater
      composer.busy = false
      // Queued notes belong to the thread whose turn just ended, not to whichever tab is open now.
      val stalled = noteProgressAndWarn(endedThreadId ?: currentThreadId)
      val queued = composer.queue.drain()
      if (queued != null) {
        // A note the person left while the turn ran outranks the autopilot: they have said
        // something newer than the plan.
        autopilotTurns = 0
        if (!startTurn(queued, endedThreadId ?: currentThreadId)) composer.restoreDraft(queued)
        return@invokeLater
      }
      maybeAutopilot(endedThreadId ?: currentThreadId, stalled)
    }
  }

  /**
   * Says when turns keep happening and nothing moves.
   *
   * The loud failures have their own safeties; this is the quiet one — prose produced, tokens
   * spent, not one file changed and not one tick of the plan. Said once when the threshold is
   * crossed rather than on every turn afterwards: a warning repeated every turn is a warning people
   * learn to scroll past.
   */
  private fun noteProgressAndWarn(threadId: String?): Boolean {
    val plan = threadId?.let { id -> runCatching { com.vibe.agent.plans.PlanStore.getInstance(project).load(id) }.getOrNull() }
    val turn = com.vibe.agent.safety.StallDetector.Turn(
      changedFiles = if (changedPaths.isNotEmpty()) changedPaths.size else if (turnHadMutatingTool) 1 else 0,
      planDone = plan?.done ?: 0,
      planTotal = plan?.total ?: 0,
    )
    val history: List<com.vibe.agent.safety.StallDetector.Turn>
    synchronized(turnProgress) {
      turnProgress.add(turn)
      val trimmed = com.vibe.agent.safety.StallDetector.trim(turnProgress.toList())
      turnProgress.clear()
      turnProgress.addAll(trimmed)
      history = trimmed
    }
    val stalled = com.vibe.agent.safety.StallDetector.stalledTurns(history)
    if (!com.vibe.agent.safety.StallDetector.isStalled(history)) return false
    // Only on the turn that crosses the line: after that the person has been told.
    if (stalled == com.vibe.agent.safety.StallDetector.DEFAULT_STALL_TURNS) systemLine(t("stall.warning", "count" to stalled))
    return true
  }

  /**
   * The autopilot: takes the next step of the plan by itself, and asks at checkpoints.
   *
   * The whole human contribution to a long task is usually the word «продолжай», and automating it
   * is the feature. The policy — not this method — decides when that word is unsafe; here we only
   * carry out the decision and always say out loud which one it was, because an agent that starts a
   * turn nobody asked for, silently, is indistinguishable from a bug.
   */
  private fun maybeAutopilot(threadId: String?, stalled: Boolean) {
    val id = threadId ?: return
    if (!VibeAgentSettings.autopilotEnabled || disposed) return
    val plan = runCatching { com.vibe.agent.plans.PlanStore.getInstance(project).load(id) }.getOrNull()
    val state = com.vibe.agent.autopilot.AutopilotPolicy.State(
      enabled = VibeAgentSettings.autopilotEnabled,
      autoTurnsDone = autopilotTurns,
      maxTurns = VibeAgentSettings.autopilotMaxTurns,
      checkpointEvery = VibeAgentSettings.autopilotCheckpointEvery,
      plan = plan,
      // A stall is exactly the situation the autopilot must not drive through: it would spend the
      // whole turn budget on turns that already proved they move nothing.
      lastTurnFailed = turnEndedBadly || stalled,
      breakerTripped = breakers.isBlocking(),
      spentTokens = stretchTokens.get(),
      maxTokens = VibeAgentSettings.autopilotMaxTokens.toLong(),
    )
    val remaining = com.vibe.agent.autopilot.AutopilotPolicy.remaining(plan)
    when (com.vibe.agent.autopilot.AutopilotPolicy.decide(state)) {
      com.vibe.agent.autopilot.AutopilotPolicy.Decision.OFF -> Unit
      com.vibe.agent.autopilot.AutopilotPolicy.Decision.CONTINUE -> {
        autopilotTurns++
        systemLine(t("autopilot.step", "index" to autopilotTurns,
                     "max" to VibeAgentSettings.autopilotMaxTurns, "remaining" to remaining))
        if (!startTurn(ComposedMessage(text = t("autopilot.continue")), id,
                       com.vibe.agent.audit.AuditActor.agent(currentRole, target?.auditName()))) autopilotTurns = 0
      }
      com.vibe.agent.autopilot.AutopilotPolicy.Decision.CHECKPOINT -> {
        autopilotTurns = 0
        systemLine(t("autopilot.checkpoint",
                     "step" to (com.vibe.agent.autopilot.AutopilotPolicy.currentStep(plan) ?: ""),
                     "remaining" to remaining))
      }
      com.vibe.agent.autopilot.AutopilotPolicy.Decision.STOP_PLAN_DONE -> {
        autopilotTurns = 0
        systemLine(t("autopilot.planDone"))
      }
      com.vibe.agent.autopilot.AutopilotPolicy.Decision.STOP_LIMIT -> {
        autopilotTurns = 0
        systemLine(t("autopilot.limit", "max" to VibeAgentSettings.autopilotMaxTurns))
      }
      com.vibe.agent.autopilot.AutopilotPolicy.Decision.STOP_BUDGET -> {
        autopilotTurns = 0
        systemLine(t("autopilot.budget", "spent" to stretchTokens.get(), "max" to VibeAgentSettings.autopilotMaxTokens))
      }
      com.vibe.agent.autopilot.AutopilotPolicy.Decision.STOP_UNSAFE -> {
        autopilotTurns = 0
        systemLine(t("autopilot.unsafe"))
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
        .createNotification(t("chat.turnDoneNotification"), com.intellij.notification.NotificationType.INFORMATION)
        .notify(project)
    }
  }

  /**
   * Watches the SHAPE of the turn's tool calls and stops an agent that is going in circles.
   *
   * A loop is not a mistake the model can notice: from the inside every step looks reasonable, and
   * the same step looks reasonable again. It ends when someone outside counts. The breaker is
   * tripped rather than the turn merely cancelled, so the next turn has to be started deliberately
   * — an agent released straight back into the same circle would simply resume it.
   */
  /**
   * The plan the agent narrates, kept as data: shown as a checklist now, and — more importantly —
   * still on disk after the IDE is restarted mid-task, which is exactly when one installs updates.
   */
  private fun onPlanUpdate(update: JsonObject) {
    val plan = com.vibe.agent.plans.AgentPlan.parse(update, System.currentTimeMillis())
    val threadId = turnThreadId ?: currentThreadId ?: return
    com.vibe.agent.plans.PlanStore.getInstance(project).save(threadId, plan)
    if (plan.isEmpty) return
    toolCard(t("plan.updated", "done" to plan.done, "total" to plan.total))
    SwingUtilities.invokeLater {
      val console = TerminalConsole(t("plan.title", "done" to plan.done, "total" to plan.total))
      console.append(com.vibe.agent.plans.AgentPlan.render(plan) { status -> planMark(status) })
      messages.add(console)
      revalidateScroll()
    }
  }

  private fun planMark(status: com.vibe.agent.plans.AgentPlan.Status): String = when (status) {
    com.vibe.agent.plans.AgentPlan.Status.COMPLETED -> "✔"
    com.vibe.agent.plans.AgentPlan.Status.IN_PROGRESS -> "▸"
    com.vibe.agent.plans.AgentPlan.Status.PENDING -> "·"
  }

  /**
   * Says an unfinished plan is waiting — once, when the thread is opened.
   *
   * Without this the restart leaves a chat that ends mid-sentence: the steps that were done, the
   * one in progress and the point of the whole thing are all gone, and «продолжай» means nothing.
   */
  private fun announceUnfinishedPlan(threadId: String) {
    val plan = com.vibe.agent.plans.PlanStore.getInstance(project).load(threadId) ?: return
    if (plan.isEmpty || plan.isFinished) return
    systemLine(t("plan.unfinished", "done" to plan.done, "total" to plan.total,
                 "current" to (plan.current?.content ?: "")))
  }

  /**
   * The daily ceiling for a role, asked BEFORE the step: a budget reported after the spend is a
   * receipt. Per role rather than per run because the runaway case is not one expensive turn — it
   * is a reviewer restarted forty times by a loop nobody was watching.
   */
  private fun roleBudgetExceeded(role: String): Boolean {
    val limit = VibeAgentSettings.roleBudgetTokens.toLong()
    if (limit <= 0) return false
    val spent = com.vibe.agent.budget.VibeSpendService.getInstance().spentByRole(role)
    val status = com.vibe.agent.budget.RoleBudget.check(spent, limit)
    when (status.verdict) {
      com.vibe.agent.budget.RoleBudget.Verdict.EXCEEDED -> {
        systemLine(t("budget.exceeded", "role" to role, "spent" to "%,d".format(spent), "limit" to "%,d".format(limit)))
        return true
      }
      com.vibe.agent.budget.RoleBudget.Verdict.WARN ->
        systemLine(t("budget.warn", "role" to role, "percent" to status.percent,
                     "spent" to "%,d".format(spent), "limit" to "%,d".format(limit)))
      else -> {}
    }
    return false
  }

  /**
   * Watches OUTCOMES, where the loop detector watches shapes.
   *
   * A hung command restarted forever and a run where every second call fails both look fine to the
   * loop detector: the calls differ. They look fine to a «подряд» counter too, because a success
   * resets it. They do not look fine here.
   */
  private fun noteOutcome(call: com.vibe.agent.acp.ToolCall) {
    val outcome = when {
      call.status != com.vibe.agent.acp.ToolCall.STATUS_FAILED -> com.vibe.agent.safety.ThrashDetector.Outcome.OK
      // A timeout is a failure of a special kind: waiting has been tried and did not help.
      com.vibe.agent.safety.ThrashDetector.looksLikeTimeout(call.title) -> com.vibe.agent.safety.ThrashDetector.Outcome.TIMEOUT
      else -> com.vibe.agent.safety.ThrashDetector.Outcome.ERROR
    }
    val fingerprint = com.vibe.agent.safety.LoopDetector.fingerprint(call.toolName ?: call.kind, call.rawInput?.toString())
    synchronized(thrashHistory) {
      thrashHistory.add(com.vibe.agent.safety.ThrashDetector.Event(fingerprint, outcome))
      val trimmed = com.vibe.agent.safety.ThrashDetector.trim(thrashHistory.toList())
      thrashHistory.clear()
      thrashHistory.addAll(trimmed)
    }
    val finding = com.vibe.agent.safety.ThrashDetector.check(thrashHistory.toList())
    if (finding.verdict == com.vibe.agent.safety.ThrashDetector.Verdict.OK) return
    val reason = when (finding.verdict) {
      com.vibe.agent.safety.ThrashDetector.Verdict.REPEATED_TIMEOUT ->
        t("thrash.timeout", "count" to finding.count, "call" to finding.detail.take(120))
      else -> t("thrash.failures", "count" to finding.count, "window" to com.vibe.agent.safety.ThrashDetector.WINDOW)
    }
    systemLine("⛔ " + reason)
    breakers.trip(com.vibe.agent.safety.ThrashDetector::class.java.simpleName.lowercase(), reason, System.currentTimeMillis())
    synchronized(thrashHistory) { thrashHistory.clear() }
    cancelTurn()
  }

  private fun noteLoop(call: com.vibe.agent.acp.ToolCall) {
    loopHistory.add(com.vibe.agent.safety.LoopDetector.fingerprint(call.toolName ?: call.kind, call.rawInput?.toString()))
    val finding = com.vibe.agent.safety.LoopDetector.check(loopHistory.snapshot())
    if (finding.verdict == com.vibe.agent.safety.LoopDetector.Verdict.OK) return
    val reason = if (finding.verdict == com.vibe.agent.safety.LoopDetector.Verdict.REPEAT)
      t("loop.repeat", "count" to finding.count, "call" to finding.fingerprint.take(120))
    else t("loop.cycle", "pattern" to finding.fingerprint.take(160))
    systemLine("🔁 " + reason)
    breakers.trip(com.vibe.agent.safety.LoopDetector::class.java.simpleName.lowercase(), reason, System.currentTimeMillis())
    loopHistory.clear()
    cancelTurn()
  }

  /** Any sign of life from the agent resets the silence clock. */
  private fun noteActivity() {
    lastActivityMs.set(System.currentTimeMillis())
  }

  /**
   * A hung turn looks exactly like a thinking one — same spinner, same silence. Without a clock the
   * honest answer to «оно ещё работает?» is a shrug, and people find out by leaving it overnight.
   */
  private fun checkSilence() {
    if (!turnInFlight.get()) return
    val silenceMs = VibeAgentSettings.agentSilenceMinutes * 60_000L
    val now = System.currentTimeMillis()
    when (com.vibe.agent.safety.DeadManSwitch.check(lastActivityMs.get(), now, silenceMs)) {
      com.vibe.agent.safety.DeadManSwitch.Verdict.ALIVE -> return
      com.vibe.agent.safety.DeadManSwitch.Verdict.STALE -> {
        if (staleAnnounced.compareAndSet(false, true)) {
          systemLine(t("deadman.stale", "minutes" to com.vibe.agent.safety.DeadManSwitch.silentMinutes(lastActivityMs.get(), now)))
        }
      }
      com.vibe.agent.safety.DeadManSwitch.Verdict.DEAD -> {
        systemLine(t("deadman.dead", "minutes" to com.vibe.agent.safety.DeadManSwitch.silentMinutes(lastActivityMs.get(), now)))
        cancelTurn()
      }
    }
  }

  private fun cancelTurn() {
    llmCancel.set(true)
    llmClient.cancel()
    // A /watch download runs before any turn exists — Стоп must reach it too, or a minutes-long
    // download would keep going after the user gave up on it.
    watchCancel?.set(true)
    val c = client
    systemLine(t("chat.stopping"))
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

  /**
   * Project rules in front of the turn: what the repository itself demands, before what the user
   * asked for. Rules chosen by [ProjectRules.applicable] — always-on ones, those whose globs match
   * the files in play, and those called by name — so an unrelated turn does not pay for them.
   *
   * Bodies pass the same guard as any other file from disk: a rule file is text someone else wrote,
   * and «правила проекта» is exactly the label an injection would like to wear.
   */
  private fun prependProjectRules(prompt: String, userText: String, loaded: List<ContextSerializer.Loaded>): String {
    val context = com.vibe.agent.context.ProjectContextService.getInstance(project)
    val touched = loaded.map { it.relPath }
    // Rules of the folders in play, not only of the root: in a monorepo the package's own
    // convention must beat the repository's for files inside that package.
    val all = context.rules(touched)
    if (all.isEmpty()) return prompt
    val applicable = com.vibe.agent.context.ProjectRules.applicable(all, touched, userText)
      // A nested rule travels only with files under its own folder — otherwise a package rule
      // would arrive in turns about other packages, which is the opposite of why it was written.
      .filter { rule -> rule.dir.isEmpty() || touched.any { com.vibe.agent.context.ProjectRules.coversPath(rule, it) } }
    if (applicable.isEmpty()) return prompt
    val guarded = applicable.map { rule ->
      val clean = com.vibe.agent.security.ContextSanitizer.sanitize(rule.body)
      if (clean.findings.isNotEmpty()) reportContextFindings("${com.vibe.agent.context.ProjectRules.RULES_DIR}/${rule.name}", clean.findings)
      rule.copy(body = clean.text)
    }
    systemLine(t("rules.applied", "names" to guarded.joinToString { it.name }))
    return com.vibe.agent.context.ProjectRules.promptBlock(guarded, t("rules.header")) + "\n\n" + prompt
  }

  /**
   * What the project already wrote down about this — named before the agent starts guessing.
   *
   * Projects accumulate hard-won notes («этот гейт нельзя чинить так»), and an agent that does not
   * read them re-derives them badly at full price. Nobody remembers to paste the right note at the
   * right moment, so the index is matched against the request and the two or three relevant
   * entries are named. Paths, not contents: a note is a page long, and the agent reads what it
   * decides it needs — which is how a person uses an index too.
   */
  /**
   * The minimalism ladder, when the project asked for it: left alone a model writes a wrapper
   * around one call, a flag nobody sets and a comment restating the line below — each defensible
   * alone, and together the reason agent-written code becomes unreadable faster than hand-written.
   */
  private fun prependMinimalism(prompt: String): String {
    val mode = com.vibe.agent.minimalism.MinimalismPolicy.modeOf(VibeAgentSettings.minimalismMode)
    if (mode == com.vibe.agent.minimalism.MinimalismPolicy.Mode.OFF) return prompt
    val rules = com.vibe.agent.minimalism.MinimalismPolicy.Rules(
      light = t("minimalism.light"), full = t("minimalism.full"), ultra = t("minimalism.ultra"))
    return com.vibe.agent.minimalism.MinimalismPolicy.preamble(mode, rules) + "\n\n" + prompt
  }

  private fun prependKnowledge(prompt: String, userText: String): String {
    val index = com.vibe.agent.knowledge.KnowledgeIndex.getInstance(project)
    val entries = index.entries()
    if (entries.isEmpty()) return prompt
    val hits = com.vibe.agent.knowledge.Librarian.find(entries, userText)
    if (hits.isEmpty()) return prompt
    val withPaths = hits.map { hit ->
      hit.copy(entry = hit.entry.copy(path = index.relativeTo(hit.entry)))
    }
    systemLine(t("knowledge.found", "paths" to withPaths.joinToString { it.entry.path }))
    return com.vibe.agent.knowledge.Librarian.promptBlock(withPaths, t("knowledge.header")) + "\n\n" + prompt
  }

  private fun sendToAcp(
    t: ChatTarget.Agent, text: String, loaded: List<ContextSerializer.Loaded>,
    images: List<ImageAttachment>, startedAt: Long,
    skills: List<ContextSerializer.LoadedSkill> = emptyList(),
  ) {
    checkpoints?.create(t("chat.checkpointLabel", "text" to text.take(CHECKPOINT_LABEL_LEN)))?.let {
      checkpointLine(it)
      audit?.append(AuditEvent(System.currentTimeMillis(), AuditEvent.Action.CHECKPOINT, ok = true, actor = turnActor,
        meta = mapOf("hash" to it.hash.take(12))))
    }
    val design = DesignContextFile.load(project.basePath)
    val designed = if (design != null) DesignContextFile.promptBlock(design) + "\n" + text else text
    val fullPrompt = prependMinimalism(prependProjectRules(prependKnowledge(designed, text), text, loaded))
    val c = ensureClient(t.config)
    // A fresh turn: tool-call ids and the changed-files set are per-turn.
    toolCalls.reset()
    loopHistory.clear()
    trace.clear()
    toolStarts.clear()
    failoverTried.clear()
    turnStartedAtMs = System.currentTimeMillis()
    lastActivityMs.set(System.currentTimeMillis())
    staleAnnounced.set(false)
    terminalConsoles.clear()
    changedPaths.clear()
    turnHadMutatingTool = false
    // thoughtsBlock is EDT-owned (created/read in appendThought's invokeLater) — reset it there, not here.
    audit?.append(AuditEvent(System.currentTimeMillis(), AuditEvent.Action.PROMPT, ok = true, actor = turnActor,
      model = "acp/${t.config.name}", meta = mapOf("chars" to text.length.toString())))
    SwingUtilities.invokeLater {
      modePicker.setModes(c.modes)
      composer.setImagesAllowed(c.capabilities?.image != false, NO_IMAGE_AGENT)
    }
    if (images.isNotEmpty() && c.capabilities?.image != true) systemLine(t("chat.noImagesAgent"))
    val blocks = ContextSerializer.acpBlocks(fullPrompt, loaded, images, c.capabilities, skills)
    promptAcpTurn(c, blocks, t, startedAt, verifyAttempt = 0, checkAttempt = 0)
  }

  /**
   * Send one ACP prompt and, on end_turn, run the post-turn gates. A gate that
   * BOUNCES re-prompts the same session with a synthetic corrective message
   * (attempt counters carried forward) instead of ending the turn — this is how
   * VERIFY-GATE/TURN-CHECKS enforce "not done until green" in the ACP model,
   * where there is no `vibe_complete` tool to hang them on.
   */
  private fun promptAcpTurn(c: AcpClient, blocks: List<ContentBlock>, t: ChatTarget.Agent, startedAt: Long, verifyAttempt: Int, checkAttempt: Int, designAttempt: Int = 0) {
    c.prompt(blocks).whenComplete { result, error ->
      // Any throw here (a non-object result, a re-prompt failing) must still END the turn — otherwise
      // turnInFlight/history.activeTurns stay stuck and the panel wedges app-wide until restart.
      try {
        val secs = (System.currentTimeMillis() - startedAt) / 1000.0
        if (error != null) {
          finishAgentBubble(secs, t("chat.failed"))
          systemLine(t("chat.error", "reason" to error.message))
          audit?.append(AuditEvent(System.currentTimeMillis(), AuditEvent.Action.REPLY, ok = false, actor = agentActor(),
            model = "acp/${t.config.name}", latencyMs = System.currentTimeMillis() - startedAt,
            meta = mapOf("error" to (error.message ?: "error"))))
          turnEndedBadly = true
          finishTurn()
          return@whenComplete
        }
        // Lenient: a null / non-object result (JsonNull from a `{"result":null}` reply) yields stop=null, not a throw.
        val stop = (result as? JsonObject)?.get("stopReason")?.jsonPrimitive?.contentOrNull
        if (stop == STOP_CANCELLED || llmCancel.get() || disposed) {
          finishAgentBubble(secs, stop)
          turnEndedBadly = true
          finishTurn()
          return@whenComplete
        }
        // Gates may run a build command and read files — never on the reader thread that completed us.
        ApplicationManager.getApplication().executeOnPooledThread {
          try {
            status.set(VibeAgentStatusService.State.GATE)
            val bounce = evaluateGates(verifyAttempt, checkAttempt, designAttempt)
            if (bounce == null) status.set(VibeAgentStatusService.State.RUNNING)
            if (bounce != null && !llmCancel.get() && !disposed && c.isAlive) {
              finishAgentBubble(secs, t("chat.checkBounceLabel"))
              promptAcpTurn(c, listOf(ContentBlock.Text(bounce.message)), t, startedAt, bounce.verifyAttempt, bounce.checkAttempt, bounce.designAttempt)
            }
            else {
              finishAgentBubble(secs, stop)
              audit?.append(AuditEvent(System.currentTimeMillis(), AuditEvent.Action.REPLY, ok = true, actor = agentActor(),
                model = "acp/${t.config.name}", latencyMs = System.currentTimeMillis() - startedAt,
                meta = mapOf("stopReason" to (stop ?: "end_turn"))))
              runTurnEndHooks()
              finishTurn()
            }
          }
          catch (e: Exception) {
            finishAgentBubble(secs, t("chat.failed"))
            systemLine(t("chat.gateError", "reason" to e.message))
            finishTurn()
          }
        }
      }
      catch (e: Exception) {
        systemLine(t("chat.turnEndError", "reason" to e.message))
        finishTurn()
      }
    }
  }

  private data class GateBounce(val message: String, val verifyAttempt: Int, val checkAttempt: Int, val designAttempt: Int = 0)

  /**
   * Post-turn gates over the files this turn changed. Returns a bounce (synthetic
   * follow-up prompt) or null to complete. Only runs when the turn mutated files.
   */
  private fun evaluateGates(verifyAttempt: Int, checkAttempt: Int, designAttempt: Int = 0): GateBounce? {
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
        t("chat.checks.notScanned",
          "count" to (skippedByCount + skippedBySize),
          "details" to (if (skippedByCount > 0) t("chat.checks.overCount", "max" to maxFiles) else "") +
                       (if (skippedBySize > 0) t("chat.checks.overSize", "kb" to VibeAgentSettings.checksMaxFileKb) else "")))
      TurnChecks.scanSecretLeak(contents, maxFiles) + TurnChecks.scanProtectedPath(paths)
    }
    if (findings.isNotEmpty()) {
      findings.forEach { f ->
        val id = if (f.check == com.vibe.agent.gates.TurnCheckId.NO_SECRET_LEAK) VibeBreakerService.SECRET_LEAK else VibeBreakerService.PROTECTED_PATH
        if (breakers.trip(id, "${f.detail}: ${f.path}", System.currentTimeMillis())) {
          audit?.append(AuditEvent(System.currentTimeMillis(), AuditEvent.Action.CIRCUIT_BREAKER_OPENED, ok = false, actor = com.vibe.agent.audit.AuditActor.IDE,
            meta = mapOf("breaker" to id, "reason" to f.detail)))
        }
      }
      audit?.append(AuditEvent(System.currentTimeMillis(), AuditEvent.Action.TURN_CHECK, ok = false, actor = com.vibe.agent.audit.AuditActor.IDE,
        meta = mapOf("findings" to findings.size.toString(), "mode" to cMode)))
    }

    // --- VERIFY-GATE (build/tests) ---
    val vMode = VibeAgentSettings.verifyMode
    if (vMode != VibeAgentSettings.VERIFY_OFF && verifyRunner != null && VibeAgentSettings.verifyCommand.isNotBlank()) {
      val res = verifyRunner.run(VibeAgentSettings.verifyCommand, VibeAgentSettings.verifyTimeoutMs) { llmCancel.get() || disposed }
      audit?.append(AuditEvent(System.currentTimeMillis(), AuditEvent.Action.VERIFY_GATE, ok = res.passed, actor = com.vibe.agent.audit.AuditActor.IDE,
        meta = mapOf("ran" to res.ran.toString(), "exit" to (res.exitCode?.toString() ?: "none"))))
      // Stop pressed while the build ran → complete the turn, do not bounce the agent again.
      if (llmCancel.get() || disposed) return null
      when (VerifyGatePolicy.decide(vMode, res.ran, res.passed, verifyAttempt, VibeAgentSettings.verifyMaxAttempts)) {
        VerifyGateDecision.BOUNCE -> return GateBounce(
          t("chat.verify.bounce",
            "command" to VibeAgentSettings.verifyCommand, "code" to (res.exitCode ?: "timeout"),
            "attempt" to (verifyAttempt + 1), "max" to maxOf(1, VibeAgentSettings.verifyMaxAttempts),
            "output" to res.outputTail),
          verifyAttempt + 1, checkAttempt, designAttempt)
        VerifyGateDecision.STOP -> {
          // Terminal: giving up hands control to the user — do not then bounce on turn checks.
          systemLine(t("chat.verify.stop", "max" to maxOf(1, VibeAgentSettings.verifyMaxAttempts)))
          com.vibe.agent.sound.VibeSoundService.getInstance()
            .play(com.vibe.agent.sound.SoundPolicy.Event.TURN_STOPPED, project)
          return null
        }
        VerifyGateDecision.WARN_COMPLETE ->
          systemLine(t("chat.verify.warn", "code" to (res.exitCode ?: "timeout")))
        VerifyGateDecision.COMPLETE -> {}
      }
    }

    // --- TURN-CHECKS decision (findings already scanned + tripped above) ---
    when (TurnChecks.decide(cMode, findings, checkAttempt, VibeAgentSettings.checksMaxAttempts)) {
      TurnChecksDecision.BOUNCE -> return GateBounce(
        TurnChecks.renderCorrective(findings, checkAttempt + 1, maxOf(1, VibeAgentSettings.checksMaxAttempts)),
        verifyAttempt, checkAttempt + 1, designAttempt)
      TurnChecksDecision.STOP ->
        systemLine(t("chat.checks.stop", "max" to maxOf(1, VibeAgentSettings.checksMaxAttempts)))
      TurnChecksDecision.NOTIFY_COMPLETE ->
        systemLine(t("chat.checks.notify", "items" to findings.joinToString("; ") { "${it.detail}: ${it.path}" }))
      TurnChecksDecision.COMPLETE -> {}
    }

    // --- DESIGN GATE: measure the page after a turn that touched the interface ---
    val designMode = when (VibeAgentSettings.designMode) {
      VibeAgentSettings.DESIGN_NOTIFY -> DesignHookPolicy.Mode.NOTIFY
      VibeAgentSettings.DESIGN_ENFORCE_FLOOR -> DesignHookPolicy.Mode.ENFORCE_FLOOR
      else -> DesignHookPolicy.Mode.OFF
    }
    if (designMode != DesignHookPolicy.Mode.OFF && DesignHookPolicy.touchesUi(paths)) {
      val measured = com.vibe.agent.design.DesignMeasurementService.getInstance(project)
        .measure(VibeAgentSettings.DESIGN_MEASURE_TIMEOUT_MS)
      val designFindings = measured.findings
      if (designFindings == null) {
        // Why the detector is silent must be said: silence otherwise reads as t("chat.pageFine").
        systemLine(t("chat.design.notMeasured", "reason" to measured.reason))
      }
      else {
        when (DesignHookPolicy.decide(designMode, designFindings, designAttempt, VibeAgentSettings.designMaxAttempts)) {
          DesignHookPolicy.Decision.BOUNCE -> return GateBounce(
            DesignHookPolicy.corrective(designFindings, designAttempt + 1, VibeAgentSettings.designMaxAttempts),
            verifyAttempt, checkAttempt, designAttempt + 1)
          DesignHookPolicy.Decision.STOP ->
            systemLine(t("chat.design.stop", "max" to VibeAgentSettings.designMaxAttempts))
          DesignHookPolicy.Decision.REPORT -> systemLine("🎨 " + DesignReview.summary(designFindings))
          DesignHookPolicy.Decision.SKIP -> {}
        }
      }
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
        t("chat.breakerDialog", "reasons" to breakers.openReasons().joinToString("\n")),
        t("chat.breakerTitle"), t("chat.breakerClear"), t("common.cancel"), Messages.getWarningIcon())
      cleared = choice == Messages.YES
    }
    if (cleared) {
      val n = breakers.clearAll()
      status.set(VibeAgentStatusService.State.IDLE)
      audit?.append(AuditEvent(System.currentTimeMillis(), AuditEvent.Action.CIRCUIT_BREAKER_RECOVERED, ok = true, actor = com.vibe.agent.audit.AuditActor.IDE,
        meta = mapOf("cleared" to n.toString())))
      systemLine(t("chat.breakerCleared", "count" to n))
    }
    return cleared
  }

  /** preToolUse/postToolUse gate for one tool-call: runs the chain, audits, surfaces messages. */
  private fun runToolHook(event: HookEvent, tool: String?, params: JsonObject?): HookDecision {
    val decision = hooks.run(event, tool, params, emptyList())
    // ok reflects whether a hook flagged a problem (exit 2), not merely whether it blocked —
    // a postToolUse refusal is a real "not ok" even though it cannot stop the already-run tool.
    audit?.append(AuditEvent(System.currentTimeMillis(), AuditEvent.Action.HOOK, ok = !decision.flagged, actor = com.vibe.agent.audit.AuditActor.IDE,
      meta = mapOf("event" to event.wire, "tool" to (tool ?: ""), "blocked" to decision.blocked.toString(),
        "broken" to decision.brokenHooks.size.toString())))
    // Notes and post/turnEnd requirements are for the agent; the ACP model can't inject a mid-turn
    // message, so we surface them in the feed (VibeIDE dropped preToolUse notes entirely — we don't).
    decision.agentMessage?.takeIf { !decision.blocked }?.let { systemLine("🪝 $it") }
    return decision
  }

  private fun runTurnEndHooks() {
    val decision = hooks.run(HookEvent.TURN_END, null, null, changedPaths.toList())
    audit?.append(AuditEvent(System.currentTimeMillis(), AuditEvent.Action.HOOK, ok = !decision.flagged, actor = com.vibe.agent.audit.AuditActor.IDE,
      meta = mapOf("event" to HookEvent.TURN_END.wire, "changedFiles" to changedPaths.size.toString(),
        "broken" to decision.brokenHooks.size.toString())))
    decision.agentMessage?.let { systemLine(t("chat.projectCheck", "text" to it)) }
  }

  private fun sendToLlm(t: ChatTarget.Model, startedAt: Long) {
    try {
      val resolved = ProvidersService.resolve(t.provider, project.basePath) { systemLine("[providers] $it") }
      if (resolved == null) {
        systemLine(com.vibe.agent.i18n.VibeI18n.t("chat.provider.noBaseUrl", "id" to t.provider.id))
        return
      }
      if (resolved.apiKey == null && !resolved.isLocal) {
        systemLine(com.vibe.agent.i18n.VibeI18n.t("chat.provider.noKey", "id" to t.provider.id))
        return
      }
      if (resolved.isLocal) systemLine(t("chat.localModel"))
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
      llmClient.chat(
        resolved, t.model, wire, { llmCancel.get() },
        onWaiting = { attempt, delayMs, reason ->
          // Said out loud: a silent wait is indistinguishable from a hang, and the user reaches
          // for Stop exactly when the provider was about to let us back in.
          systemLine(t("retry.waiting", "attempt" to attempt, "seconds" to (delayMs / 1000),
                       "reason" to (reason ?: "")))
        },
      ) { delta -> appendAgentText(delta) }
      finishAgentBubble((System.currentTimeMillis() - startedAt) / 1000.0, t.model.id)
    }
    catch (e: java.io.InterruptedIOException) {
      // The partial answer stays in the transcript — a stop is not amnesia.
      finishAgentBubble((System.currentTimeMillis() - startedAt) / 1000.0, t("chat.interrupted"))
      systemLine(t("chat.stopError", "reason" to e.message))
    }
    catch (e: Exception) {
      if (failOver(t, e, startedAt)) return
      finishAgentBubble((System.currentTimeMillis() - startedAt) / 1000.0, t("chat.failed"))
      // A rejected payload must not poison every later request in this thread.
      turnThreadId?.let { if (history.dropImagesFromLastUser(it)) systemLine(t("chat.imagesDropped")) }
      systemLine(t("chat.error", "reason" to e.message))
    }
    finally {
      finishTurn()
    }
  }

  private fun ensureClient(config: AgentServerConfig): AcpClient {
    val fresh = synchronized(clientLock) {
      // Checked INSIDE the lock: dispose() completes under it, so a racing turn thread
      // cannot spawn an orphan process after the panel is gone.
      check(!disposed) { t("chat.panelClosed") }
      val existing = client
      if (existing != null && existing.isAlive && existing.sessionId != null && clientConfig == config) return existing
      existing?.stop()
      systemLine(t("chat.agentStarting", "command" to (config.command + " " + config.args.joinToString(" "))))
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
      throw IllegalStateException(t("chat.handshakeTimeout", "seconds" to handshakeSec, "path" to AcpConfig.configPath()))
    }
    systemLine(t("chat.sessionOpen") + (fresh.modes?.let { m -> t("chat.modeSuffix", "mode" to (m.available.firstOrNull { it.id == m.currentModeId }?.name ?: m.currentModeId)) } ?: ""))
    // A fresh session starts a fresh context — drop the stale usage chip until the agent reports anew.
    SwingUtilities.invokeLater { composer.setUsage(null, null, warn = false) }
    return fresh
  }

  // --- threads & tabs (wave C) ---

  private fun nowIso(): String = Instant.now().toString()

  /** Same clock format as the feed, from epoch millis — checkpoints carry those, not an ISO text. */
  private fun timeOfMillis(millis: Long): String =
    java.time.Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalTime()
      .format(DateTimeFormatter.ofPattern("HH:mm"))

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
    // Per-CHAT state must not travel between chats: a new conversation inheriting the previous
    // one's token count would hit the session ceiling on its first message, and the «окно почти
    // полное» line would already be said and therefore never repeated.
    resetChatCounters()
    announceUnfinishedPlan(id)
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
    for ((index, record) in thread.messages.withIndex()) {
      val row = when (record.role) {
        Role.USER -> buildUserRow(record.text, timeOf(record.at), index, record.pinned)
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
      systemLine(t("chat.pipelineBusy"))
      return
    }
    val pipelines = PipelinesFile.load(project.basePath) { systemLine("[pipelines] $it") }
    if (pipelines.isEmpty()) {
      systemLine(t("pipeline.none"))
      return
    }
    val agent = agents.firstOrNull() ?: run { systemLine(t("pipeline.needsAgent", "path" to AcpConfig.configPath())); return }
    val names = pipelines.map { t("pipeline.choice", "name" to it.name, "count" to it.steps.size) }
    val choice = Messages.showDialog(project, t("pipeline.choosePrompt"), t("pipeline.chooseTitle"), names.toTypedArray(), 0, Messages.getQuestionIcon())
    if (choice >= 0) runPipeline(pipelines[choice], (target as? ChatTarget.Agent)?.config ?: agent)
  }

  private fun runPipeline(pipeline: com.vibe.agent.pipelines.Pipeline, agent: AgentServerConfig) {
    if (!history.tryBeginTurn(currentThreadId)) {
      systemLine(t("chat.threadBusy"))
      return
    }
    systemLine(t("pipeline.header", "name" to pipeline.name, "count" to pipeline.steps.size))
    turnInFlight.set(true)
    status.set(VibeAgentStatusService.State.RUNNING)
    composer.busy = true
    turnThreadId = currentThreadId
    turnText.setLength(0)
    uiConsumed = 0
    markConversationStarted()
    history.append(currentThreadId, ChatMessageRecord(Role.USER, t("pipeline.userLine", "name" to pipeline.name, "count" to pipeline.steps.size), at = nowIso()))
    ApplicationManager.getApplication().executeOnPooledThread {
      val artifacts = LinkedHashSet<String>()
      var lastSummary: String? = null
      var failed = false
      // Unattended work goes into the ledger: a pipeline runs for minutes with nobody watching,
      // and if the window dies mid-way the only trace left is this record.
      val territory = com.vibe.agent.runs.TerritoryGuess.prefixes(pipeline.steps.joinToString("\n") { it.task })
      warnAboutTerritory(territory)
      val runId = runs.started(
        com.vibe.agent.runs.AgentRunLedger.Source.PIPELINE,
        goal = t("pipeline.goal", "name" to pipeline.name),
        target = "acp/${agent.name}",
        maxSteps = pipeline.steps.size,
        territory = territory,
      )
      try {
        pipeline.steps.forEachIndexed { i, step ->
          val header = t("pipeline.step", "index" to (i + 1), "total" to pipeline.steps.size, "role" to step.role)
          if (failed && !step.continueOnFailure) {
            systemLine(t("pipeline.stepSkipped", "header" to header))
            return@forEachIndexed
          }
          if (roleBudgetExceeded(step.role)) {
            failed = true
            return@forEachIndexed
          }
          systemLine("$header ${step.task.take(80)}")
          val prompt = buildString {
            appendLine(PipelinesFile.rolePreamble(step.role))
            appendLine(t("pipeline.step.task", "task" to step.task))
            step.acceptance?.let { appendLine(t("pipeline.step.acceptance", "acceptance" to it)) }
            if (!step.ignorePreviousArtifacts) {
              if (artifacts.isNotEmpty()) appendLine(t("pipeline.step.artifacts", "files" to artifacts.joinToString()))
              lastSummary?.let { appendLine(t("pipeline.step.summary", "summary" to it)) }
            }
          }
          try {
            val c = ensureClient(agent)
            currentRole = step.role
            changedPaths.clear()
            stepBuffer = StringBuilder()
            val startedAt = System.currentTimeMillis()
            val result = c.prompt(prompt).get()
            val stop = result?.jsonObject?.get("stopReason")?.jsonPrimitive?.contentOrNull
            finishAgentBubble((System.currentTimeMillis() - startedAt) / 1000.0, t("pipeline.stepLabel", "index" to (i + 1)))
            if (stop == STOP_CANCELLED) {
              failed = true
              systemLine(t("pipeline.stepStopped", "header" to header))
              return@forEachIndexed
            }
            val summaryText = stepBuffer?.toString().orEmpty()
            lastSummary = summaryText.takeLast(2000).ifBlank { t("pipeline.noText") }
            artifacts.addAll(changedPaths)
            runs.progress(runId, steps = i + 1, changedFiles = artifacts.size)
            systemLine(t("pipeline.stepDone", "header" to header, "files" to changedPaths.size))
          }
          catch (e: Exception) {
            failed = true
            systemLine(t("pipeline.stepFailed", "header" to header, "reason" to e.message))
          }
          finally {
            stepBuffer = null
            // The role dies with its step: an ordinary chat inheriting «ревьюер» rights would be a
            // restriction appearing from nowhere, which is worse than no restriction at all.
            currentRole = null
          }
        }
        systemLine(t("pipeline.finished", "name" to pipeline.name, "outcome" to (if (failed) t("pipeline.outcome.failed") else t("pipeline.outcome.done"))))
        runs.finished(
          runId,
          if (failed) com.vibe.agent.runs.AgentRunLedger.Status.FAILED else com.vibe.agent.runs.AgentRunLedger.Status.COMPLETED,
          if (failed) t("pipeline.run.failed") else t("pipeline.run.completed", "count" to pipeline.steps.size),
        )
      }
      finally {
        currentRole = null
        finishTurn()
      }
    }
  }

  // --- models.fetch ---

  /** EDT: folds ONE provider's fresh catalog into the registry (unknown ids only) and refreshes the picker. */
  private fun addCatalogModels(providerId: String, ids: List<String>) {
    val current = providers.firstOrNull { it.id == providerId } ?: return
    val known = current.models.map { it.id }.toSet()
    val extra = ids.filter { it !in known }.map { ModelEntry(id = it) }
    if (extra.isEmpty()) return
    providers = providers.map { if (it.id == providerId) it.copy(models = it.models + extra) else it }
    rebuildTargets()
  }

  /** Serves the last known catalogs so the picker is complete on the first frame; the network refreshes it. */
  private fun applyCatalogCache(
    loaded: List<ProviderEntry>,
    cache: Map<String, ModelCatalogCache.Entry>,
  ): List<ProviderEntry> {
    val merged = ModelCatalogCache.merge(loaded, cache)
    val used = loaded.filter { p -> cache[p.id]?.fingerprint == ModelCatalogCache.fingerprint(p) }
    if (used.isNotEmpty()) {
      val now = System.currentTimeMillis()
      val freshest = used.mapNotNull { cache[it.id]?.fetchedAtMs }.maxOrNull() ?: now
      systemLine(t("providers.fromCache", "count" to used.size, "word" to providersWord(used.size)) + ", " +
                 ModelCatalogCache.ageText(freshest, now) + ") — " + t("providers.refreshingInBackground"))
    }
    return merged
  }

  private fun providersWord(n: Int): String = when {
    n % 10 == 1 && n % 100 != 11 -> t("providers.word.one")
    n % 10 in 2..4 && n % 100 !in 12..14 -> t("providers.word.few")
    else -> t("providers.word.many")
  }

  /**
   * Asks every provider for its catalog CONCURRENTLY — a sequential walk paid the connect
   * timeout of each dead provider in turn (tens of seconds before the picker filled up).
   * Every answer is published on its own, so the list grows as replies arrive; only
   * successful answers reach the cache, so a 401 never erases yesterday's catalog.
   */
  private fun fetchProviderModels() {
    val snapshot = providers
    if (snapshot.isEmpty()) return
    ApplicationManager.getApplication().executeOnPooledThread {
      val llm = LlmClient.forCatalog()
      val fresh = java.util.Collections.synchronizedMap(LinkedHashMap<String, ModelCatalogCache.Entry>())
      val updated = java.util.Collections.synchronizedList(ArrayList<String>())
      val keyless = java.util.Collections.synchronizedList(ArrayList<String>())
      val rejected = java.util.Collections.synchronizedList(ArrayList<String>())
      val localDown = java.util.Collections.synchronizedList(ArrayList<String>())
      val failed = java.util.Collections.synchronizedList(ArrayList<Pair<String, String>>())
      val pending = snapshot.mapNotNull { p ->
        if (p.modelsFetch?.enabled == false) return@mapNotNull null // absent = fetch on (default)
        val resolved = ProvidersService.resolve(p, project.basePath) { } ?: return@mapNotNull null
        // No key and not a local endpoint: asking would earn a predictable 401. Не спрашиваем и
        // не называем это ошибкой провайдера — у человека просто не введён ключ.
        if (resolved.apiKey == null && !resolved.isLocal) {
          keyless += p.id
          return@mapNotNull null
        }
        ApplicationManager.getApplication().executeOnPooledThread {
          try {
            val ids = llm.listModels(resolved, p.modelsFetch?.url)
            fresh[p.id] = ModelCatalogCache.Entry(
              fingerprint = ModelCatalogCache.fingerprint(p),
              modelIds = ids,
              fetchedAtMs = System.currentTimeMillis(),
            )
            updated += p.id
            SwingUtilities.invokeLater { if (!disposed) addCatalogModels(p.id, ids) }
          }
          catch (e: Exception) {
            val reason = CatalogReport.reason(e)
            when {
              CatalogReport.isRejectedKey(reason) -> rejected += p.id
              resolved.isLocal -> localDown += p.id
              else -> failed += (p.id to reason)
            }
          }
        }
      }
      pending.forEach { runCatching { it.get() } }
      ModelCatalogCache.put(fresh)
      val report = CatalogReport(
        updated = updated.toList(), keyless = keyless.toList(), rejected = rejected.toList(),
        localDown = localDown.toList(), failed = failed.toList(),
      )
      val keylessChanged = keylessProviders != keyless.toSet()
      keylessProviders = keyless.toSet()
      SwingUtilities.invokeLater {
        if (disposed) return@invokeLater
        // Provider without a key has nothing to offer the picker — drop its models from it.
        // Rebuild on ANY change of the set: a key added in Settings must bring the models back.
        if (keylessChanged) rebuildTargets()
        report.summary().takeIf { it.isNotEmpty() }?.let { systemLine(it) }
        // Catalog models are hidden by default (curated picker) — say where to turn them on.
        val dormant = providers.filter { p ->
          p.id !in keylessProviders && p.models.isNotEmpty() && p.models.none { m ->
            val custom = staticModelIds[p.id]?.contains(m.id) == true
            m.active && !ModelVisibility.isHidden(p.id, m.id, defaultHidden = !custom) &&
        // A model whose access has ended stays in the file — so the person can see WHY it is gone —
        // but offering it would only produce a 403 with the reason hidden in a stack trace.
        !com.vibe.agent.providers.ModelSunset.isRetired(m, java.time.LocalDate.now())
          }
        }
        if (dormant.isNotEmpty()) {
          systemLine(t("providers.dormant", "providers" to dormant.joinToString { it.name }))
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

  /**
   * Prose as Markdown when it looks like Markdown, plain text otherwise.
   *
   * Falling back to plain text matters: the HTML view re-wraps and re-lays out on every change, so
   * a streaming answer would flicker if everything went through it.
   */
  private fun proseComponent(text: String): JComponent {
    val html = MarkdownInline.toHtml(text) ?: return proseArea().also { it.text = text }
    return javax.swing.JEditorPane("text/html", "<html><body style='font-family:sans-serif'>$html</body></html>").apply {
      isEditable = false
      isOpaque = false
      border = JBUI.Borders.empty()
      font = com.intellij.util.ui.JBFont.label().deriveFont(13f)
      // A link in a model's answer opens in the browser, not inside the IDE's HTML view.
      addHyperlinkListener { event ->
        if (event.eventType == javax.swing.event.HyperlinkEvent.EventType.ACTIVATED) {
          event.url?.let { com.intellij.ide.BrowserUtil.browse(it) }
        }
      }
    }
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
    val meta = metaLabel(t("chat.role.agentAt", "time" to now()), right = false)
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
        // Markdown, when the model wrote any: an unrendered list of steps reads as one paragraph,
        // and that is exactly where a person skips a step.
        is MessageSegment.Prose -> stack.add(proseComponent(seg.text).also { it.alignmentX = Component.LEFT_ALIGNMENT })
        is MessageSegment.Code -> stack.add(CodeBlockPanel(project, seg.lang, seg.code))
      }
      (text.parent as? java.awt.Container)?.let { c ->
        c.remove(text)
        c.add(stack, BorderLayout.CENTER)
        c.revalidate(); c.repaint()
      }
    }

    fun finish(seconds: Double, suffix: String?) {
      meta.text = t("chat.agentMetaTimed", "time" to now(), "seconds" to "%.1f".format(seconds)) + (suffix?.let { " · $it" } ?: "")
      renderSegments(text.text)
    }
  }

  /** A quiet "copy" affordance (shared factory — see ChatTheme). */
  private fun copyLink(textSupplier: () -> String): JLabel =
    ChatTheme.copyLabel(t("chat.copyMessage"), textSupplier)

  private fun now(): String = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))

  private fun buildUserRow(text: String, time: String, recordIndex: Int = -1, pinned: Boolean = false): JPanel {
    val bubble = RoundedPanel(USER_BUBBLE, radius = 8).apply {
      layout = BorderLayout()
      border = JBUI.Borders.empty(6, 8)
      add(proseArea().also { it.text = text }, BorderLayout.CENTER)
    }
    return ChatRow(BorderLayout(0, JBUI.scale(2))).apply {
      border = JBUI.Borders.empty(4, 4, 8, 4)
      add(bubble, BorderLayout.CENTER)
      add(messageFooter(time, recordIndex, pinned), BorderLayout.SOUTH)
      // width-fit вправо: слева распорка съедает всё лишнее (мин. четверть ширины)
      add(Box.createHorizontalStrut(JBUI.scale(160)), BorderLayout.WEST)
    }
  }

  /**
   * Time plus the two things one wants to do with a message that already happened: keep it from
   * being forgotten, and try another path from it.
   *
   * Both need the record index, so a live row created before the record is stored (index -1) shows
   * the time alone rather than buttons that would act on the wrong message.
   */
  private fun messageFooter(time: String, recordIndex: Int, pinned: Boolean): JComponent {
    val meta = metaLabel(t("chat.role.you", "time" to time), right = true)
    if (recordIndex < 0) return meta
    val strip = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, JBUI.scale(4), 0)).apply { isOpaque = false }
    val pin = JLabel(if (pinned) PIN_ON else PIN_OFF).apply {
      toolTipText = if (pinned) t("chat.pin.off") else t("chat.pin.on")
      cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
      foreground = META_FG
      font = com.intellij.util.ui.JBFont.label().deriveFont(11f)
    }
    pin.addMouseListener(object : java.awt.event.MouseAdapter() {
      override fun mouseClicked(e: java.awt.event.MouseEvent) {
        val threadId = currentThreadId ?: return
        val now = history.setPinned(threadId, recordIndex, !pinned) ?: return
        systemLine(if (now) t("chat.pin.done") else t("chat.pin.undone"))
        history.get(threadId)?.let { renderTranscript(it) }
      }
    })
    val branch = JLabel(BRANCH_ICON).apply {
      toolTipText = t("chat.branch.tooltip")
      cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
      foreground = META_FG
      font = com.intellij.util.ui.JBFont.label().deriveFont(11f)
    }
    branch.addMouseListener(object : java.awt.event.MouseAdapter() {
      override fun mouseClicked(e: java.awt.event.MouseEvent) {
        val threadId = currentThreadId ?: return
        val copy = history.branch(threadId, recordIndex) ?: return
        activateThread(copy.id)
        systemLine(t("chat.branch.done"))
      }
    })
    strip.add(pin)
    strip.add(branch)
    strip.add(meta)
    return strip
  }

  private fun buildAssistantRow(text: String, time: String): JPanel = AgentMessage().let { m ->
    m.append(text)
    m.meta.text = t("chat.role.agentAt", "time" to time)
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
    // The record is stored before the bubble is drawn, so its index is the last one — that is what
    // makes the pin and the branch act on THIS message and not on a neighbour.
    val index = currentThreadId?.let { history.get(it)?.messages?.lastIndex } ?: -1
    SwingUtilities.invokeLater {
      val row = buildUserRow(text, now(), index)
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
      // Rough, and pessimistic on purpose: the session ceiling is about money, and a counter that
      // under-counts is a ceiling that never trips.
      val estimated = com.vibe.agent.context.ContextBudget.estimateTokens(fullText)
      sessionTokens.addAndGet(estimated)
      com.vibe.agent.budget.VibeSpendService.getInstance().record(
        currentRole, targetLabel(), estimated, null, null,
        com.vibe.agent.budget.FileSpend.attribute(estimated, turnAttachments))
      stretchTokens.addAndGet(estimated)
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
      val label = JLabel(t("chat.checkpoint.line", "hash" to cp.hash.take(8), "time" to now()), JLabel.CENTER)
      label.font = com.intellij.util.ui.JBFont.label().deriveFont(Font.PLAIN, 10f)
      label.foreground = META_FG
      label.alignmentX = Component.LEFT_ALIGNMENT
      label.border = JBUI.Borders.empty(3)
      label.cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
      label.toolTipText = t("chat.checkpoint.tooltip")
      label.addMouseListener(object : java.awt.event.MouseAdapter() {
        override fun mouseClicked(e: java.awt.event.MouseEvent) {
          val confirm = Messages.showYesNoDialog(project,
            t("chat.checkpoint.confirm", "hash" to cp.hash.take(8), "label" to cp.label),
            t("chat.checkpoint.title"), Messages.getWarningIcon())
          if (confirm == Messages.YES) {
            ApplicationManager.getApplication().executeOnPooledThread {
              val service = checkpoints ?: return@executeOnPooledThread
              val ok = service.restore(cp)
              systemLine(if (ok) t("chat.checkpoint.done", "hash" to cp.hash.take(8)) else t("chat.checkpoint.failed"))
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
    // Any frame at all is a sign of life — including one we do not handle below.
    noteActivity()
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
        if (call != null) {
          auditToolCall(AuditEvent.Action.TOOL_CALL_START, call); harvestMutation(call); noteLoop(call)
          toolStarts[call.id] = System.currentTimeMillis()
        }
        toolCard(call?.title ?: u["title"]?.jsonPrimitive?.contentOrNull ?: u["kind"]?.jsonPrimitive?.contentOrNull ?: t("chat.tool"))
        // Claude adapter announces a terminal for this tool-call — open a live console.
        terminalInfoId(u)?.let { openTerminalConsole(it, call?.title ?: t("chat.terminal")) }
      }
      "tool_call_update" -> {
        val call = toolCalls.onToolCallUpdate(u) ?: return
        harvestMutation(call)
        // Stream Claude Bash output / exit into the console for this terminal.
        terminalOutputFrame(u)?.let { (id, data) -> appendTerminalOutput(id, data) }
        terminalExitFrame(u)?.let { (id, code, sig) -> markTerminalExit(id, code, sig) }
        if (call.isDone) {
          auditToolCall(AuditEvent.Action.TOOL_CALL_DONE, call)
          noteOutcome(call)
          val startedAt = toolStarts.remove(call.id)
          trace.add(com.vibe.agent.trace.TurnTrace.Event(
            atMs = System.currentTimeMillis(),
            kind = com.vibe.agent.trace.TurnTrace.Kind.TOOL,
            name = call.title.take(80),
            durationMs = startedAt?.let { System.currentTimeMillis() - it },
            ok = call.status != com.vibe.agent.acp.ToolCall.STATUS_FAILED,
          ))
          // postToolUse: the tool already ran and cannot be undone, so run the hook OFF the reader thread —
          // a 30 s hook must not stall the session/update stream.
          val tool = call.toolName ?: call.kind
          val params = call.rawInput
          ApplicationManager.getApplication().executeOnPooledThread { runToolHook(HookEvent.POST_TOOL_USE, tool, params) }
        }
      }
      "plan" -> onPlanUpdate(u)
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
        tooltip = t("chat.contextTooltip", "used" to "%,d".format(used), "size" to "%,d".format(size)) + cost,
        warn = pct >= USAGE_WARN_PCT,
      )
    }
    noteWindowFill(used, size)
    // Recorded per role: a single total says the month cost money, a split by role says WHICH
    // role burned it, and only the second is something one can act on.
    val amount = (u["cost"] as? JsonObject)?.get("amount")?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
    val currency = (u["cost"] as? JsonObject)?.get("currency")?.jsonPrimitive?.contentOrNull
    // ACP reports the window total, and a new turn starts it lower again: without the floor the
    // difference goes negative and the day's spend silently shrinks.
    val delta = (used - lastAccountedUsed.getAndSet(used)).coerceAtLeast(0)
    com.vibe.agent.budget.VibeSpendService.getInstance()
      // The split between the turn's files is an estimate by size — a request is billed whole.
      .record(currentRole, targetLabel(), delta, amount, currency,
              com.vibe.agent.budget.FileSpend.attribute(delta, turnAttachments))
    stretchTokens.addAndGet(delta)
  }

  /** ACP reports the window total, not a delta: the difference is what this turn actually added. */
  private val lastAccountedUsed = java.util.concurrent.atomic.AtomicLong(0)

  private fun targetLabel(): String = when (val t = target) {
    is ChatTarget.Agent -> "acp/${t.config.name}"
    is ChatTarget.Model -> "${t.provider.id}/${t.model.id}"
    null -> "?"
  }

  /**
   * Says the window is filling up — once per threshold, not once per frame.
   *
   * An overflowing window does not announce itself: it makes the model forget the beginning of the
   * conversation, and the user concludes the model got worse. Saying it at 75% costs one line;
   * saying nothing costs the answer.
   */
  private fun noteWindowFill(used: Long, size: Long) {
    val status = com.vibe.agent.context.ContextBudget.check(
      usedTokens = used, windowSize = size,
      sessionUsed = sessionTokens.get(), sessionLimit = VibeChatSettings.sessionTokenLimit.takeIf { it > 0 },
    )
    if (status.verdict == com.vibe.agent.context.ContextBudget.Verdict.OK) return
    val level = when (status.verdict) {
      com.vibe.agent.context.ContextBudget.Verdict.SESSION_EXCEEDED -> "session"
      com.vibe.agent.context.ContextBudget.Verdict.BLOCK -> "block"
      else -> "warn"
    }
    if (!announcedContextLevels.add(level)) return
    when (status.verdict) {
      com.vibe.agent.context.ContextBudget.Verdict.SESSION_EXCEEDED ->
        systemLine(t("context.sessionExceeded", "used" to "%,d".format(status.sessionUsed),
                     "limit" to "%,d".format(status.sessionLimit ?: 0)))
      com.vibe.agent.context.ContextBudget.Verdict.BLOCK ->
        systemLine(t("context.block", "percent" to status.windowPercent))
      else -> systemLine(t("context.warn", "percent" to status.windowPercent))
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
    audit?.append(AuditEvent(System.currentTimeMillis(), AuditEvent.Action.TERMINAL, ok = exitCode == 0, actor = agentActor(),
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
      actor = agentActor(),
      ok = call.status != ToolCall.STATUS_FAILED,
      files = target?.let { listOf(it) },
      meta = mapOf("tool" to tool, "status" to call.status),
    ))
  }

  override fun onRequestPermission(params: JsonObject): JsonElement {
    // The most important of the three events: here a person is needed RIGHT NOW, and they left.
    com.vibe.agent.sound.VibeSoundService.getInstance()
      .play(com.vibe.agent.sound.SoundPolicy.Event.AWAITING_PERMISSION, project)
    val toolCall = params["toolCall"]?.jsonObject
    val title = toolCall?.get("title")?.jsonPrimitive?.contentOrNull ?: t("chat.permission.default")
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
      t("chat.permission.destructive", "reasons" to destructive.reasons.joinToString(", "), "command" to command.take(DESTRUCTIVE_PREVIEW_LEN), "title" to title)
    else title
    val options = params["options"]?.jsonArray?.map { it.jsonObject } ?: emptyList()
    var selected: String? = null
    ApplicationManager.getApplication().invokeAndWait {
      val names = options.map { it["name"]?.jsonPrimitive?.contentOrNull ?: it.getValue("optionId").jsonPrimitive.content }
      val choice = Messages.showDialog(project, dialogText, t("chat.permission.title"), names.toTypedArray(), 0,
        if (destructive != null) Messages.getWarningIcon() else Messages.getQuestionIcon())
      if (choice >= 0) selected = options[choice].getValue("optionId").jsonPrimitive.content
    }
    val chosen = selected
    audit?.append(AuditEvent(System.currentTimeMillis(), AuditEvent.Action.PERMISSION, ok = chosen != null, actor = com.vibe.agent.audit.AuditActor.HUMAN,
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
      throw IllegalStateException(preHook.agentMessage ?: t("chat.write.rejectedByHook"))
    }
    path?.let { changedPaths.add(it) }
    val result = fileOps.writeTextFile(params)
    audit?.append(AuditEvent(System.currentTimeMillis(), AuditEvent.Action.FS_WRITE, ok = true, actor = agentActor(),
      files = path?.let { listOf(it.take(ToolCallAudit.MAX_TARGET_LEN)) }))
    return result
  }

  // --- standard ACP terminal/… (non-Claude agents that delegate execution to us) ---

  override fun onCreateTerminal(params: JsonObject): JsonElement {
    if (!VibeAgentSettings.terminalEnabled) throw IllegalStateException(t("chat.terminal.disabled"))
    // A role that only judges cannot run commands either: running a command is how a read-only
    // role writes anyway.
    val role = currentRole
    if (!com.vibe.agent.pipelines.RoleRights.mayRunCommands(role)) {
      systemLine(t("role.commandDenied", "role" to role))
      throw IllegalStateException(t("role.commandDenied", "role" to role))
    }
    val command = params["command"]?.jsonPrimitive?.contentOrNull ?: throw IllegalStateException(t("chat.terminal.noCommand"))
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
      // The same question in two places: the dialog here and, when the bridge is running, buttons
      // on the phone. A long unattended run otherwise waits silently for someone who left the room.
      val body = t("chat.destructive.body",
                   "command" to (listOf(command) + args).joinToString(" ").take(DESTRUCTIVE_PREVIEW_LEN),
                   "reasons" to verdict.reasons.joinToString(", "))
      val request = com.vibe.agent.telegram.PendingApprovals.open(body)
      val onPhone = runCatching {
        com.vibe.agent.telegram.TelegramBridge.getInstance()
          .askApproval(request, t("telegram.approvalQuestion", "body" to body))
      }.getOrDefault(false)
      ApplicationManager.getApplication().invokeAndWait {
        approved = com.vibe.agent.telegram.ApprovalDialog.ask(
          project, t("chat.destructive.title"), body, request, onPhone, t("chat.destructive.run"))
      }
      audit?.append(AuditEvent(System.currentTimeMillis(), AuditEvent.Action.TERMINAL, ok = approved, actor = com.vibe.agent.audit.AuditActor.HUMAN,
        meta = mapOf("gate" to "destructive", "reasons" to verdict.reasons.joinToString(","), "approved" to approved.toString())))
      if (!approved) throw IllegalStateException(t("chat.destructive.refused", "reasons" to verdict.reasons.joinToString(", ")))
    }
    val terminalId = terminals.create(command, args, env, cwd, outputByteLimit)
    return buildJsonObject { put("terminalId", terminalId) }
  }

  override fun onTerminalOutput(params: JsonObject): JsonElement {
    val id = params["terminalId"]?.jsonPrimitive?.contentOrNull ?: throw IllegalStateException(t("chat.terminal.noId", "method" to "terminal/output"))
    val snap = terminals.output(id) ?: throw IllegalStateException(t("chat.terminal.unknownId", "id" to id))
    // Shrunk BEFORE it reaches the model, and never silently: a test run says what matters in its
    // first and last lines, the middle is scrollback that costs the same tokens as the code the
    // model still has to read. The full text stays available through /output <handle>.
    // Noise goes BEFORE the cut: collapsing four hundred progress lines first means the head and
    // tail that survive the cut carry information instead of a redrawn progress bar.
    val filtered = com.vibe.agent.context.ContextFilter.filter(
      snap.output, com.vibe.agent.context.ContextFilter.modeOf(VibeAgentSettings.contextFilterMode),
      repeatMark = { count -> t("filter.repeat", "count" to count) },
    )
    if (filtered.removedLines > 0) systemLine(t("filter.removed", "count" to filtered.removedLines))
    val handle = if (filtered.text.lines().size >= com.vibe.agent.context.OutputCompressor.MIN_LINES_TO_COMPRESS)
      outputStore.put(snap.output) else ""
    val shrunk = com.vibe.agent.context.OutputCompressor.compress(filtered.text, handle, { dropped, h ->
      t("output.compressed", "dropped" to dropped, "handle" to h)
    })
    if (shrunk.compressed) systemLine(t("output.compressedNote", "dropped" to shrunk.droppedLines, "handle" to handle))
    return buildJsonObject {
      put("output", shrunk.text)
      put("truncated", snap.truncated)
      if (snap.finished) put("exitStatus", buildJsonObject {
        if (snap.exitCode != null) put("exitCode", snap.exitCode) else put("exitCode", JsonNull)
        if (snap.signal != null) put("signal", snap.signal) else put("signal", JsonNull)
      })
    }
  }

  override fun onWaitForTerminalExit(params: JsonObject): JsonElement {
    val id = params["terminalId"]?.jsonPrimitive?.contentOrNull ?: throw IllegalStateException(t("chat.terminal.noId", "method" to "terminal/wait_for_exit"))
    val exit = terminals.waitForExit(id) ?: throw IllegalStateException(t("chat.terminal.unknownId", "id" to id))
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
    systemLine(t("chat.processExited", "code" to code))
    SwingUtilities.invokeLater { modePicker.setModes(null) }
    // No finishTurn() here: an idle agent's death must not end an unrelated (e.g. LLM) turn.
    // A turn that WAS talking to this process ends through its failed request futures.
  }

  private companion object {
    const val SILENCE_CHECK_MS = 30_000
    const val OUTPUT_COMMAND = "/output"
    const val GIT_COMMAND = "/git"
    const val COUNCIL_COMMAND = "/council"
    const val HANDOFF_COMMAND = "/handoff"
    const val TRACE_COMMAND = "/trace"
    const val HELP_COMMAND = "/help"
    const val FIND_COMMAND = "/find"
    const val SIMPLIFY_COMMAND = "/simplify"
    const val MEASURE_COMMAND = "/measure"
    const val LEARN_COMMAND = "/learn"
    const val DEPLOY_COMMAND = "/deploy"
    const val BG_COMMAND = "/bg"
    const val UNDO_COMMAND = "/undo"
    const val MAP_COMMAND = "/map"
    const val RULES_COMMAND = "/rules"
    const val BLAME_COMMAND = "/blame"

    /** Enough history to see a decision and its reversal; more is archaeology. */
    const val BLAME_COMMITS = 10

    /** Enough to see what failed; the rest is in the terminal for whoever wants it. */
    const val BG_TAIL_LINES = 40
    const val MEASURE_TIMEOUT_SEC = 900L
    const val INDEX_COMMAND = "/index"
    const val INDEX_PROGRESS_STEP = 25

    /** One manual is pages long; two of them plus the question still fit a modest window. */
    const val HELP_DOC_CHARS = 20_000
    const val COUNCIL_TIMEOUT_MS = 180_000L
    const val GIT_REPORT_LIMIT = 25
    const val PIN_ON = "📌"
    const val PIN_OFF = "📍"
    const val BRANCH_ICON = "⑂"
    /** How long an external caller waits just for the turn to START (EDT hop + validation). */
    const val SUBMIT_TIMEOUT_SEC = 30L
    val NO_IMAGE_AGENT: String get() = t("chat.noImagesCapability")
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
