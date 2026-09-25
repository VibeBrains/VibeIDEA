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
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.vibe.agent.acp.AcpClient
import com.vibe.agent.acp.AcpConfig
import com.vibe.agent.acp.AcpSessionMemory
import com.vibe.agent.acp.AgentServerConfig
import com.vibe.agent.acp.ContentBlock
import com.vibe.agent.acp.IdeFileOps
import com.vibe.agent.acp.ToolCall
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
import com.vibe.agent.providers.AuthSpec
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
import kotlinx.serialization.json.JsonPrimitive
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

  /** Лента со стрелкой «в конец»: сама вниз не ездит, поэтому вернуться вниз можно кнопкой. */
  private val feedEnd = FeedEndButton(scroll)

  /**
   * Выделение текста ЧЕРЕЗ границы сообщений: Ctrl+A на весь разговор, протяжка мышью через
   * пузыри и блоки кода, тройной клик на сообщение целиком (правило владельца 18.09.2026).
   */
  private val feedSelection = FeedSelection(messages, scroll).also { it.install() }

  // Loaded OFF the EDT in init (config files on disk); empty until then.
  @Volatile private var agents: List<AgentServerConfig> = emptyList()
  @Volatile private var providers: List<ProviderEntry> = emptyList()
  /** Models hand-declared in providers files (before any catalog fetch) — they get the «кастом» mark. */
  @Volatile private var staticModelIds: Map<String, Set<String>> = emptyMap()
  private val llmClient = LlmClient(projectBase = project.basePath)
  private val llmCancel = java.util.concurrent.atomic.AtomicBoolean(false)
  private val runs = com.vibe.agent.runs.VibeAgentRunService.getInstance(project)
  private val fileOps = IdeFileOps(
    project,
    onNotice = { message -> systemLine(message) },
    onFinding = { path, findings -> reportContextFindings(path, findings) },
  )
  @Volatile private var client: AcpClient? = null
  @Volatile private var clientConfig: AgentServerConfig? = null
  /** Guards check-then-act on [client]: ensureClient (pooled), onProcessExit (exit thread), dispose (EDT). */
  private val clientLock = Any()
  /** Serializes which session is current: a turn's, a tab switch's. Held across a handshake, so never taken on the EDT. */
  private val sessionLock = Any()
  /** Thread → its session on the running [client]; emptied whenever the client is replaced or gone. */
  private val threadSessions = java.util.concurrent.ConcurrentHashMap<String, String>()
  private val checkpoints: CheckpointService? = project.basePath?.let { CheckpointService(it) }
  // One shared audit log per project (writer here, reader in the viewer action) — see VibeAuditService.
  private val audit: AuditLog? = com.vibe.agent.audit.VibeAuditService.getInstance(project).get()

  /** Tools of the direct chat: the IDE's own tools, then the shared memory server started on the first turn that offers tools. */
  private val directTools = com.vibe.agent.mcp.DirectChatTools(listOf(
    com.vibe.agent.mcp.IdeToolsSource { name, arguments -> com.vibe.agent.mcp.VibeMcpTools { project }.dispatch(project, name, arguments) },
    com.vibe.agent.mcp.MemoryServerSource(
      connect = {
        val offer = com.vibe.agent.mcp.MemoryServerOffer.resolve()
        if (offer.reason != com.vibe.agent.mcp.MemoryServerOffer.Reason.OFFERED) null
        else com.vibe.agent.mcp.McpClient.start(offer.path.toString(), com.vibe.agent.mcp.MemoryServerOffer.ARGS,
                                                project.basePath?.let { java.nio.file.Path.of(it) })
      },
      clientVersion = com.intellij.openapi.application.ApplicationInfo.getInstance().fullVersion,
    ),
    // The team memories on the VibeMemory host; their tools carry the team in the name, so the order does not hide them
    com.vibe.agent.mcp.TeamMemorySource(
      teams = { com.vibe.agent.mcp.TeamMemory.teams(teamMemoryRoot()) },
      connect = { team -> teamMemoryClient(team) },
      clientVersion = com.intellij.openapi.application.ApplicationInfo.getInstance().fullVersion,
      onFailure = { team, e -> systemLine(teamMemoryProblem(team, e)) },
    ),
    // Свои серверы человека идут ПОСЛЕДНИМИ: имя инструмента, совпавшее с нашим, достаётся нам —
    // иначе чужой сервер молча подменил бы `vibe_read_file`, и понять это было бы не по чему.
    com.vibe.agent.mcp.ConfiguredServersSource(
      servers = { com.vibe.agent.mcp.McpServersFile.load(project.basePath).servers },
      workingDir = project.basePath?.let { java.nio.file.Path.of(it) },
      clientVersion = com.intellij.openapi.application.ApplicationInfo.getInstance().fullVersion,
      onFailure = { failures -> failures.forEach { systemLine(t("mcp.servers.problem", "text" to it)) } },
      // Description drift is a security event, so it is named in the feed rather than the log: the person agreed to
      // other descriptions, and the decision is theirs (see ToolFingerprint).
      onDrift = { server, drift -> systemLine(com.vibe.agent.mcp.DriftMessage.of(server, drift)) },
    ),
  ))

  /** Why the direct chat goes without tools is said once per panel: a line on every turn stops being read. */
  @Volatile private var directToolsNoted = false
  /** Why a turn with tools went without reasoning is said once per panel, for the same reason. */
  @Volatile private var reasoningYieldNoted = false
  /** Targets already tried in this turn: a chain must never send the turn back where it just failed. */
  private val failoverTried = java.util.Collections.synchronizedSet(HashSet<com.vibe.agent.resilience.FailoverPlan.Target>())

  /** What the turn actually did — the feed shows the agent's story, this shows the events. */
  private val trace = com.vibe.agent.trace.TurnTrace.Recorder()

  @Volatile private var turnStartedAtMs = 0L

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

  /**
   * Часы, перечитывающие список живых процессов агента.
   *
   * Полем, а не анонимным таймером в init: живой `javax.swing.Timer` держит панель в памяти после
   * закрытия вкладки, и это ровно тот класс утечки, который сегодня чинился в двух других местах.
   */
  private val commandsTimer = javax.swing.Timer(COMMANDS_POLL_MS) { refreshRunningCommands() }
    .apply { isRepeats = true }

  /** Анимированная строка «сейчас работаю» в конце ленты; живёт ровно ход. */
  private var workingLine: WorkingLine? = null


  private val verifyRunner: VerifyGateRunner? = project.basePath?.let { VerifyGateRunner(it) }
  private val breakers = VibeBreakerService.getInstance(project)
  private val status = VibeAgentStatusService.getInstance(project)

  /** Full texts of outputs that were shrunk for the model — `/output <handle>` prints one back. */
  private val outputStore = com.vibe.agent.context.OutputCompressor.Store()

  /** Rough running total of this chat's tokens; the session ceiling is checked against it. */
  private val sessionTokens = java.util.concurrent.atomic.AtomicLong(0)

  /** Чем был занят контекст последнего запроса; пусто до первого хода. */
  @Volatile private var lastContext = com.vibe.agent.context.ContextBreakdown.NONE

  /** Окно модели, которой ушёл последний запрос; null — модель его не объявила. */
  @Volatile private var lastContextWindow: Int? = null

  /** Thresholds already said out loud for this chat: a warning repeated every frame is noise. */
  private val announcedContextLevels = java.util.Collections.synchronizedSet(HashSet<String>())

  /**
   * Threads whose agent session was opened new rather than resumed: the first turn in each carries
   * the unfinished plan, which a new session otherwise knows nothing about.
   */
  private val planCarryThreads: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

  /** Признаки «трифекты», накопленные за текущий ход; разбор — [com.vibe.agent.guard.Trifecta]. */
  /**
   * Trifecta signals per conversation thread, not per turn.
   *
   * A leak spread over turns — a secret read in turn 1, foreign text in turn 3, `curl` in turn 5 —
   * never had all three signals in one turn, so it was never seen. The threshold is unchanged (all
   * three are still required), so the warning stays as rare as it was.
   */
  private val sessionSignals = java.util.concurrent.ConcurrentHashMap<String, MutableSet<com.vibe.agent.guard.Trifecta.Signal>>()

  /** The signals of a thread; a thread is a session, a new chat starts clean. */
  private fun threadSignals(threadId: String): MutableSet<com.vibe.agent.guard.Trifecta.Signal> =
    sessionSignals.computeIfAbsent(threadId) { java.util.concurrent.ConcurrentHashMap.newKeySet() }
  /** Did the turn end in anything other than a normal finish? The autopilot refuses to resume such a turn. */
  @Volatile private var turnEndedBadly = false
  /** What travelled in this turn's context, for the per-file spend estimate. */
  @Volatile private var turnAttachments: List<com.vibe.agent.budget.FileSpend.Attachment> = emptyList()
  /** Outcomes of the recent tool calls, for the thrash and repeated-timeout breakers. */
  private val thrashHistory = ArrayList<com.vibe.agent.safety.ThrashDetector.Event>()
  /** The agent as the journal names it: the role it plays in [turn] and the target that runs it. */
  private fun agentActor(turn: TurnState = turns.chat): com.vibe.agent.audit.AuditActor =
    com.vibe.agent.audit.AuditActor.agent(turn.role, target?.auditName())

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

  /** The pipeline run in progress, so every spend line of its steps is summed into one bill. */
  @Volatile private var pipelineRunId: String? = null

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

  /** Последний вопрос человека — им предзаполняется заголовок решения: решение отвечает на вопрос. */
  private var lastUserText: String = ""
  /** Row components aligned with the current thread's message indices (best effort during a live turn). */
  private val recordRows = ArrayList<JComponent>()

  /** The feed itself, where the chat's turn and a step outside a wave put their rows. */
  private val mainFeed = object : TurnFeed {
    override fun addRecord(row: JComponent) {
      messages.add(row)
      recordRows.add(row)
    }

    override fun addExtra(row: JComponent, before: Component?) {
      val index = before?.let { messages.components.indexOf(it) } ?: -1
      if (index >= 0) messages.add(row, index) else messages.add(row)
    }
  }

  /** The running turns: the chat's, and the steps of a pipeline by their sessions. */
  private val turns = TurnRouter(chatTurn(""))

  /** A fresh turn of the chat in [threadId]; its trifecta signals are the thread's, which add up over its turns. */
  private fun chatTurn(threadId: String): TurnState = TurnState(role = null, signals = threadSignals(threadId), feed = mainFeed)

  /** The chat's turn while a pipeline runs; see [TurnState.whileRunning]. */
  private fun pipelineChatTurn(threadId: String): TurnState = TurnState.whileRunning(threadSignals(threadId), mainFeed)

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
      handleSpendCommand(message) -> true
      handleCascadeCommand(message) -> true
      sessionCeilingReached(message.text) -> false
      spendCeilingReached() -> false
      handleWatchCommand(message) -> true
      else -> startTurn(message)
    }
    override fun onStop() = cancelTurn()
    override fun onNotice(text: String) = systemLine(t("chat.composerNotice", "text" to text))
  })
  private val modelPicker = ModelPicker({ selectTarget(it) }, { openSettings() })
  private val modePicker = ModePicker { modeId -> switchMode(modeId) }

  /** Насколько агенту разрешено действовать без вопроса; умолчание — автопилот. */
  private val permissionPicker: com.vibe.agent.ui.composer.PermissionModePicker =
    com.vibe.agent.ui.composer.PermissionModePicker { mode ->
      VibeAgentSettings.permissionMode = mode.id
      permissionPicker.setMode(mode)
      systemLine(t("permission.switched", "mode" to mode.title))
    }.also { it.setMode(com.vibe.agent.mcp.PermissionMode.of(VibeAgentSettings.permissionMode)) }
  private val configPicker = com.vibe.agent.ui.composer.ConfigOptionsPicker({ id, value -> switchConfigOption(id, value) }, { id, value -> switchConfigChoice(id, value) })
  private val historyCallbacks = object : ThreadListPanel.Callbacks {
    override fun onOpen(threadId: String) = activateThread(threadId)
    override fun onOpenAtMessage(threadId: String, messageIndex: Int) = openThreadAt(threadId, messageIndex)
  }
  private val landingList = ThreadListPanel(project, ThreadListPanel.Mode.LANDING, this, historyCallbacks)
  private val landing = LandingBlock(landingList) { text -> startTurn(ComposedMessage(text)) }
  // Без шеврона: он обещает второй смысл у кнопки, которого нет — клик открывает список, и это
  // всё, что она делает. Прижатая к иконке стрелка читалась как теснота, а не как подсказка.
  private val historyPill = PillButton(icon = AllIcons.Vcs.History) { openHistoryPopup() }.apply {
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

  /** Composer microphone: click to record, click again to transcribe into the draft. */
  /**
   * Микрофон композера: клик — запись, повторный — расшифровка в черновик.
   *
   * Иконка своя, а не платформенная: `AllIcons.Ide.Macro.Recording_1` рисует кассету, и на месте
   * микрофона это читается как «что-то сломалось» (владелец, 0.6.4). Во время записи микрофон
   * красный — состояние видно, не наводя мышь.
   */
  private val voicePill: PillButton =
    PillButton(icon = VibeIcons.MIC) { toggleVoice() }.apply { toolTipText = t("chat.voice.start") }

  init {
    border = JBUI.Borders.empty(4)
    composer.addPill(modePicker.pill)
    composer.addPill(configPicker.pill)
    composer.addPill(modelPicker.pill)
    composer.addPill(PillButton(icon = AllIcons.Actions.RunAll) { choosePipeline() }.apply { toolTipText = t("chat.pipelinePill") })
    // Шестерёнка уехала в шапку панели (VibeAgentToolWindowFactory): настройки открывают раз в
    // месяц, а место в ряду композера занимают всегда — там живёт то, что меняют по ходу работы.
    composer.addPill(permissionPicker.pill)
    // The microphone appears only where recording is possible: a button that cannot work is worse
    // than no button.
    if (com.vibe.agent.voice.VoiceCapture.isSupported()) composer.addPill(voicePill)
    feedSelection.markdown = { history.get(currentThreadId)?.let { com.vibe.agent.history.ChatExport.toMarkdown(it) } }
    composer.onCopyChat = { copyConversation() }
    composer.onExportChat = { exportConversation() }
    composer.onAcceptAll = { acceptAllEdits() }
    composer.onRejectAll = { rejectAllEdits() }
    composer.onShowChanges = { showChangedFiles() }
    composer.onShowCommands = { showRunningCommands() }
    // Раз в несколько секунд: процессы кончаются сами, и подпись обязана это замечать без хода.
    commandsTimer.start()
    composer.onUsageClick = { showContextPopup() }
    // Кольцо контекста — последним в ряду пилюль, как у VibeIDE: после модели, режима и микрофона.
    composer.addContextRing()
    composer.addRightPill(historyPill)
    add(tabsStrip, BorderLayout.NORTH)
    add(centerWrap, BorderLayout.CENTER)
    centerWrap.add(feedEnd, BorderLayout.CENTER)
    restoreTabs()
    relayout()
    applyRailVisibility()
    history.addListener(this) { onHistoryChanged() }
    systemLine(t("chat.greeting.keys", "acp" to AcpConfig.configPath()))
    // Config files live on disk — never read (or seed) them on the EDT; publish results back here.
    ApplicationManager.getApplication().executeOnPooledThread {
      val loadedAgents = AcpConfig.load(project.basePath) { systemLine(t("chat.configNotice", "text" to it)) }
      val loadedProviders = ProvidersService.load(project.basePath) { systemLine("[providers] $it") }
      val catalogCache = ModelCatalogCache.load()
      // Read here so the spending ceiling, which is asked on the EDT, never has to touch the disk.
      com.vibe.agent.budget.VibeSpendService.getInstance().prime()
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
    commandsTimer.stop()
    com.vibe.agent.http.VibeAgentGateway.getInstance().unregister(this)
    externalWaiters.values.forEach { it.countDown() }
    externalWaiters.clear()
    turnThreadId?.let { history.endTurn(it) }
    llmCancel.set(true)
    llmClient.cancel()
    directTools.close()
    composer.queue.clear()
    turnInFlight.set(false)
    workingLine?.stop()
    composer.disposeStatus()
    terminals.disposeAll()
    // audit is owned by VibeAuditService (project-scoped) — do not close it here.
    synchronized(clientLock) {
      client?.stop()
      client = null
      clientConfig = null
      threadSessions.clear()
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
      // The tab asked for a target the catalogs have not brought yet. Keep wanting it — but SAY
      // which model the turn would actually go to: a tab that shows one name and spends on another
      // is the silent half of «switched the model, the old one keeps working».
      desiredTargetId = id
      if (id != null && target != null && id != target?.id) {
        systemLine(com.vibe.agent.i18n.VibeI18n.t(
          "chat.targetNotHere", "wanted" to id, "instead" to (target?.id ?: "")))
      }
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
        modePicker.setModes(c?.modes); configPicker.setOptions(c?.configOptions)
        composer.setImagesAllowed(c?.capabilities?.image != false, NO_IMAGE_AGENT)
      }
      is ChatTarget.Model -> {
        VibeChatSettings.rememberChoice(project, "llm:${t.provider.id}", t.model.id)
        modePicker.setModes(null)
        configPicker.setOptions(null)
        composer.setImagesAllowed(t.model.vision != false, com.vibe.agent.i18n.VibeI18n.t("chat.model.noVision", "model" to t.model.name))
      }
      null -> {
        modePicker.setModes(null)
        configPicker.setOptions(null)
        composer.setImagesAllowed(true, null)
      }
    }
    // The choice follows the thread (restored when its tab is activated).
    if (persistToThread && currentThreadId.isNotEmpty() && history.get(currentThreadId)?.state?.targetId != t?.id) {
      history.updateState(currentThreadId, ThreadState(t?.id))
    }
    updateLanding()
  }

  /** Разговор в буфер обмена — тем же markdown, что уходит в файл. */
  private fun copyConversation() {
    val thread = history.get(currentThreadId) ?: run { systemLine(t("chat.noThread")); return }
    com.intellij.openapi.ide.CopyPasteManager.getInstance()
      .setContents(java.awt.datatransfer.StringSelection(com.vibe.agent.history.ChatExport.toMarkdown(thread)))
    systemLine(t("chat.strip.copied"))
  }

  private fun journal() = com.vibe.agent.mcp.AgentEditJournal.getInstance(project)

  private fun commands() = com.vibe.agent.mcp.AgentCommands.getInstance(project)

  private fun refreshRunningCommands() {
    if (disposed) return
    composer.setRunningCommands(commands().alive().size)
  }

  /**
   * Что агент запустил и не остановил — списком, с кнопкой остановки на каждую.
   *
   * Это машина человека: решать судьбу процессов должен он, а не только агент, который их поднял.
   */
  private fun showRunningCommands() {
    val alive = commands().alive()
    if (alive.isEmpty()) {
      systemLine(t("chat.commands.none"))
      return
    }
    val panel = JPanel()
    panel.layout = javax.swing.BoxLayout(panel, javax.swing.BoxLayout.Y_AXIS)
    panel.border = JBUI.Borders.empty(8, 10)
    panel.isOpaque = false
    val title = JLabel(t("chat.commands.title"))
    title.font = com.intellij.util.ui.JBFont.label().deriveFont(Font.BOLD, 12f)
    title.alignmentX = Component.LEFT_ALIGNMENT
    panel.add(title)
    alive.forEach { (id, command) ->
      val row = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(2)))
      row.isOpaque = false
      row.alignmentX = Component.LEFT_ALIGNMENT
      row.add(JLabel(command.take(COMMAND_LABEL_CHARS)).apply {
        font = com.intellij.util.ui.JBFont.label().deriveFont(Font.PLAIN, 12f)
        toolTipText = command
      })
      row.add(com.vibe.agent.ui.composer.PillButton(icon = AllIcons.Actions.Suspend) {
        if (commands().stop(id)) systemLine(t("chat.commands.stopped", "id" to id))
        refreshRunningCommands()
      }.apply { toolTipText = t("chat.commands.stop") })
      panel.add(row)
    }
    JBPopupFactory.getInstance()
      .createComponentPopupBuilder(panel, null)
      .setRequestFocus(true)
      .createPopup()
      .showUnderneathOf(composer)
  }

  private fun refreshChangedFiles() {
    SwingUtilities.invokeLater { composer.setChangedFiles(journal().size()) }
  }

  /**
   * Принять всё: правки остаются, список пустеет.
   *
   * Принятие ничего не пишет на диск — файлы уже такие. Оно снимает вопрос, и в этом вся разница
   * с откатом: одно решение стоит ноль действий, другое возвращает байты.
   */
  private fun acceptAllEdits() {
    val count = journal().size()
    if (count == 0) return
    journal().acceptAll()
    refreshChangedFiles()
    systemLine(t("chat.changes.accepted", "count" to count))
  }

  /** Отклонить всё: каждый файл возвращается к состоянию до работы агента, и каждый отказ назван. */
  private fun rejectAllEdits() {
    journal().all().forEach { entry -> rejectEdit(entry.path) }
    refreshChangedFiles()
  }

  private fun rejectEdit(path: String) {
    when (val result = journal().reject(path)) {
      is com.vibe.agent.mcp.AgentEditJournal.Revert.Done -> systemLine(t("chat.changes.rejected", "path" to path))
      is com.vibe.agent.mcp.AgentEditJournal.Revert.Drifted -> systemLine(t("chat.changes.drifted", "path" to path))
      is com.vibe.agent.mcp.AgentEditJournal.Revert.Failed ->
        systemLine(t("chat.changes.failed", "path" to path, "reason" to result.reason))
    }
    refreshChangedFiles()
  }

  /**
   * Список правок агента: по файлу на строку, с кнопками решения и показом самих изменений.
   *
   * Показать изменения — не украшение: решать «принять или отклонить», не видя диффа, значит
   * решать наугад, а именно это и было до сих пор (просьба владельца 18.09.2026).
   */
  private fun showChangedFiles() {
    val entries = journal().all()
    if (entries.isEmpty()) return
    val panel = JPanel()
    panel.layout = javax.swing.BoxLayout(panel, javax.swing.BoxLayout.Y_AXIS)
    panel.border = JBUI.Borders.empty(8, 10)
    panel.isOpaque = false
    val title = JLabel(t("chat.changes.title"))
    title.font = com.intellij.util.ui.JBFont.label().deriveFont(Font.BOLD, 12f)
    title.alignmentX = Component.LEFT_ALIGNMENT
    panel.add(title)
    val base = project.basePath
    entries.forEach { entry ->
      val row = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(2)))
      row.isOpaque = false
      row.alignmentX = Component.LEFT_ALIGNMENT
      val shown = base?.let { entry.path.removePrefix(it).removePrefix("/") } ?: entry.path
      val name = JLabel(shown + (if (entry.before == null) "  (" + t("chat.changes.created") + ")" else ""))
      name.font = com.intellij.util.ui.JBFont.label().deriveFont(Font.PLAIN, 12f)
      row.add(name)
      row.add(com.vibe.agent.ui.composer.PillButton(icon = AllIcons.Actions.Diff) { showEditDiff(entry) }
                .apply { toolTipText = t("chat.changes.diff") })
      row.add(com.vibe.agent.ui.composer.PillButton(icon = AllIcons.Actions.Commit) {
        journal().accept(entry.path); refreshChangedFiles()
      }.apply { toolTipText = t("chat.changes.accept") })
      row.add(com.vibe.agent.ui.composer.PillButton(icon = AllIcons.Actions.Cancel) {
        rejectEdit(entry.path)
      }.apply { toolTipText = t("chat.changes.reject") })
      panel.add(row)
    }
    JBPopupFactory.getInstance()
      .createComponentPopupBuilder(panel, null)
      .setRequestFocus(true)
      .createPopup()
      .showUnderneathOf(composer)
  }

  /** Сами изменения — платформенным окном сравнения: своё было бы хуже и жило бы отдельно. */
  private fun showEditDiff(entry: com.vibe.agent.mcp.AgentEditJournal.Entry) {
    val factory = com.intellij.diff.DiffContentFactory.getInstance()
    val before = factory.create(project, entry.before.orEmpty())
    val after = factory.create(project, runCatching { java.io.File(entry.path).readText() }.getOrDefault(entry.after))
    com.intellij.diff.DiffManager.getInstance().showDiff(
      project,
      com.intellij.diff.requests.SimpleDiffRequest(entry.path, before, after,
                                                   t("chat.changes.title"), t("chat.changes.diff")))
  }

  /**
   * Сохранить текущий разговор файлом: markdown для чтения, json для возврата.
   *
   * Формат спрашивается расширением в диалоге сохранения, а не отдельным вопросом: человек и так
   * пишет имя файла, и второй диалог ради выбора из двух — лишний шаг.
   */
  fun exportConversation() {
    val thread = history.get(currentThreadId) ?: run { systemLine(t("export.failed", "reason" to t("chat.noThread"))); return }
    val descriptor = com.intellij.openapi.fileChooser.FileSaverDescriptor(
      t("export.title"), t("export.format.markdown") + " / " + t("export.format.json"), "md", "json")
    val chosen = com.intellij.openapi.fileChooser.FileChooserFactory.getInstance()
      .createSaveFileDialog(descriptor, project)
      .save(null as com.intellij.openapi.vfs.VirtualFile?, com.vibe.agent.history.ChatExport.fileName(thread, "md"))
      ?: return
    val file = chosen.file
    val text = if (file.name.endsWith(".json")) com.vibe.agent.history.ChatExport.toJson(thread)
               else com.vibe.agent.history.ChatExport.toMarkdown(thread)
    try {
      file.writeText(text)
      systemLine(t("export.done", "path" to file.path))
    }
    catch (e: Exception) {
      systemLine(t("export.failed", "reason" to (e.message ?: e.javaClass.simpleName)))
    }
  }

  /**
   * Загрузить разговор из json — НОВЫМ тредом, а не поверх существующего.
   *
   * Иначе файл, выгруженный из этой же IDE, вернулся бы с тем же идентификатором и затёр то, что
   * человек продолжал писать после выгрузки.
   */
  fun importConversation() {
    val descriptor = com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
      .createSingleFileDescriptor("json").withTitle(t("import.title"))
    val file = com.intellij.openapi.fileChooser.FileChooser.chooseFile(descriptor, project, null) ?: return
    val source = com.vibe.agent.history.ChatExport.fromJson(String(file.contentsToByteArray(), Charsets.UTF_8))
    if (source == null) {
      systemLine(t("import.failed"))
      return
    }
    val fresh = history.create(project.basePath, project.name)
    source.messages.forEach { history.append(fresh.id, it) }
    activateThread(fresh.id)
    systemLine(t("import.done", "title" to source.title.ifBlank { t("export.untitled") }))
  }

  /**
   * Контекст и токены: сколько потрачено за сессию и чем занят запрос.
   *
   * Всплывашкой по клику на счётчик, а не строкой в ленте: это справка, которую спрашивают в
   * момент вопроса, и в переписке она была бы шумом на каждый ход.
   */
  private fun showContextPopup() {
    val panel = JPanel()
    panel.layout = javax.swing.BoxLayout(panel, javax.swing.BoxLayout.Y_AXIS)
    panel.border = JBUI.Borders.empty(10, 12)
    panel.isOpaque = false
    fun line(text: String, bold: Boolean = false, dim: Boolean = false) {
      val label = JLabel(text)
      label.font = com.intellij.util.ui.JBFont.label().deriveFont(if (bold) Font.BOLD else Font.PLAIN, 12f)
      if (dim) label.foreground = META_FG
      label.alignmentX = Component.LEFT_ALIGNMENT
      panel.add(label)
    }
    line(t("context.popup.title"), bold = true)
    val limit = VibeChatSettings.sessionTokenLimit.takeIf { it > 0 }
    val used = sessionTokens.get()
    line(if (limit == null) t("context.popup.session", "used" to "%,d".format(used))
         else t("context.popup.sessionOf", "used" to "%,d".format(used), "limit" to "%,d".format(limit),
                "percent" to (used * 100 / limit.coerceAtLeast(1))))
    panel.add(javax.swing.Box.createVerticalStrut(JBUI.scale(8)))
    val snapshot = lastContext
    if (snapshot.isEmpty) {
      line(t("context.popup.noData"), dim = true)
    }
    else {
      line(t("context.popup.parts"), bold = true)
      snapshot.parts.forEach { part ->
        line(part.title + "   " + (if (part.tokens > 0) "%,d".format(part.tokens) else ""), dim = true)
      }
      line(t("context.popup.total", "tokens" to "%,d".format(snapshot.total)), dim = true)
      line(t("context.popup.estimate"), dim = true)
    }
    JBPopupFactory.getInstance()
      .createComponentPopupBuilder(panel, null)
      .setRequestFocus(false)
      .createPopup()
      .showUnderneathOf(composer.usageAnchor())
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
          SwingUtilities.invokeLater { modePicker.setModes(c.modes); configPicker.setOptions(c.configOptions) }
        }
      }
      catch (e: Exception) {
        systemLine(t("chat.modeNotSwitched", "reason" to e.message))
      }
    }
  }

  /**
   * Flips one boolean session option of the agent.
   *
   * The picker is redrawn from what the AGENT reported back, not from what was asked for: the
   * answer carries the whole set, and an option that silently refused to change would otherwise
   * keep showing the value we wanted rather than the one in force.
   */
  private fun switchConfigOption(configId: String, value: Boolean) =
    sendConfigChange({ reason -> t("chat.configNotSwitched", "reason" to reason) }) { it.setConfigOption(configId, value) }

  /** Picks a value of a select option; redrawn from the agent's answer, as a switch is. */
  private fun switchConfigChoice(configId: String, value: String) =
    sendConfigChange({ reason -> t("chat.configNotChosen", "reason" to reason) }) { it.setConfigChoice(configId, value) }

  private fun sendConfigChange(failure: (String?) -> String, send: (AcpClient) -> java.util.concurrent.CompletableFuture<Unit>) {
    val c = client ?: return
    ApplicationManager.getApplication().executeOnPooledThread {
      try {
        send(c).whenComplete { _, error ->
          if (error != null) systemLine(failure(error.message))
          SwingUtilities.invokeLater { configPicker.setOptions(c.configOptions) }
        }
      }
      catch (e: Exception) {
        systemLine(failure(e.message))
      }
    }
  }

  /**
   * Инструменты IDE — агенту, которого IDE запустила.
   *
   * Предлагаются, только когда MCP-сервер УЖЕ поднят: он живёт на том же входе, что HTTP API, а тот
   * выключен по умолчанию осознанно. Включать его за пользователя ради удобства агента значит
   * менять его решение о безопасности.
   *
   * Причина отказа называется в ленте один раз за сессию: молчание здесь неотличимо от «работает»,
   * а разница — целый набор инструментов, которого агент не видит.
   */
  override fun ideToolsFor(agentSupportsHttp: Boolean): Map<String, Any>? {
    val service = com.vibe.agent.http.VibeHttpApiService.getInstance()
    val token = runCatching { com.vibe.agent.http.VibeApiToken.peek() }.getOrNull()
    val offer = com.vibe.agent.mcp.IdeToolsOffer.httpServer(
      running = service.isRunning, port = service.port, token = token, agentSupportsHttp = agentSupportsHttp)
    systemLine(when (com.vibe.agent.mcp.IdeToolsOffer.reason(service.isRunning, token, agentSupportsHttp)) {
      com.vibe.agent.mcp.IdeToolsOffer.Reason.OFFERED -> t("mcp.offer.done")
      com.vibe.agent.mcp.IdeToolsOffer.Reason.API_OFF -> t("mcp.offer.apiOff")
      com.vibe.agent.mcp.IdeToolsOffer.Reason.AGENT_CANNOT_HTTP -> t("mcp.offer.agentCannot")
      com.vibe.agent.mcp.IdeToolsOffer.Reason.NO_TOKEN -> t("mcp.offer.noToken")
    })
    return offer
  }

  /**
   * Свои серверы человека — ACP-агенту, тем же списком, что и прямому чату.
   *
   * Жалобы разбора называются один раз за сессию: конфиг с опечаткой молча дающий на два сервера
   * меньше — это часы поисков «почему инструмент пропал».
   */
  override fun configuredServers(): List<Map<String, Any>> {
    val parsed = com.vibe.agent.mcp.McpServersFile.load(project.basePath)
    parsed.problems.forEach { systemLine(t("mcp.servers.problem", "text" to describe(it))) }
    val live = parsed.servers.filterNot { it.disabled }
    if (live.isNotEmpty()) systemLine(t("mcp.servers.offered", "names" to live.joinToString { it.name }))
    return live.map { com.vibe.agent.mcp.McpServersFile.acpEntry(it) }
  }

  /** Жалоба разбора `mcp.json` — человеческой фразой из каталога, по коду и имени записи. */
  private fun describe(complaint: com.vibe.agent.mcp.McpServersFile.Complaint): String {
    val name = complaint.name.orEmpty()
    return when (complaint.problem) {
      com.vibe.agent.mcp.McpServersFile.Problem.NOT_JSON -> t("mcp.servers.notJson")
      com.vibe.agent.mcp.McpServersFile.Problem.NO_SECTION -> t("mcp.servers.noSection")
      com.vibe.agent.mcp.McpServersFile.Problem.NOT_AN_OBJECT -> t("mcp.servers.notAnObject", "name" to name)
      com.vibe.agent.mcp.McpServersFile.Problem.NO_COMMAND -> t("mcp.servers.noCommand", "name" to name)
      com.vibe.agent.mcp.McpServersFile.Problem.UNREADABLE -> t("mcp.servers.unreadable")
    }
  }

  override fun teamMemoryServers(agentSupportsHttp: Boolean): List<Map<String, Any>> {
    val teams = com.vibe.agent.mcp.TeamMemory.teams(teamMemoryRoot())
    if (teams.isEmpty()) return emptyList()
    // The person's own word in ~/.jetbrains/acp.json covers every server we would add, the team ones included
    if (com.vibe.agent.acp.AcpConfig.useCustomMcp() == false) return emptyList()
    if (!agentSupportsHttp) {
      systemLine(t("mcp.team.agentNoHttp", "teams" to teams.joinToString { it.team }))
      return emptyList()
    }
    val helper = com.vibe.agent.mcp.TeamMemory.helperPath(teamMemoryRoot(), com.vibe.agent.util.ExecutableNames.isWindows())
    val offered = teams.mapNotNull { team ->
      runCatching { com.vibe.agent.mcp.TeamMemory.acpEntry(team, com.vibe.agent.mcp.TeamMemory.headers(helper, team.team)) }
        .onFailure { systemLine(teamMemoryProblem(team, it)) }
        .getOrNull()
    }
    if (offered.isNotEmpty()) systemLine(t("mcp.team.offered", "teams" to teams.joinToString { it.team }))
    return offered
  }

  private fun teamMemoryRoot(): java.nio.file.Path =
    com.vibe.agent.mcp.TeamMemory.root(System.getenv("VIBEMEMORY_DIR"), System.getProperty("user.home"))

  /** A connection to one team's server, with the header asked of VibeMemory's helper right now and kept nowhere */
  private fun teamMemoryClient(team: com.vibe.agent.mcp.TeamMemory.Team): com.vibe.agent.mcp.McpClient {
    val helper = com.vibe.agent.mcp.TeamMemory.helperPath(teamMemoryRoot(), com.vibe.agent.util.ExecutableNames.isWindows())
    val headers = com.vibe.agent.mcp.TeamMemory.headers(helper, team.team)
    return com.vibe.agent.mcp.McpClient(com.vibe.agent.mcp.McpHttpTransport(
      java.net.URI.create(team.url), headers, com.vibe.agent.mcp.McpHttpTransport.ideClient(TEAM_MEMORY_CONNECT_TIMEOUT)))
  }

  /** What went wrong with a team's memory, in words the person can act on: a refused token says where to renew it */
  private fun teamMemoryProblem(team: com.vibe.agent.mcp.TeamMemory.Team, e: Throwable): String =
    if (e is com.vibe.agent.mcp.McpClient.Unauthorized)
      t("mcp.team.unauthorized", "team" to team.team, "cabinet" to (team.cabinet ?: "VibeMemory"))
    else t("mcp.team.failed", "team" to team.team, "reason" to (e.message ?: e.javaClass.simpleName))

  /**
   * The shared VibeMemory server — offered to every ACP agent this IDE starts, when it is installed and
   * answers. The reason it was not offered is named in the feed once per session: an agent without the
   * common memory looks exactly like an agent with it until the day it forgets what was agreed.
   */
  override fun memoryServer(): Map<String, Any>? {
    // The person's own word in ~/.jetbrains/acp.json wins over our offer.
    if (com.vibe.agent.acp.AcpConfig.useCustomMcp() == false) {
      systemLine(t("mcp.memory.disabledByAcpJson", "path" to com.vibe.agent.acp.AcpConfig.configPath().toString()))
      return null
    }
    val offer = com.vibe.agent.mcp.MemoryServerOffer.resolve()
    systemLine(when (offer.reason) {
      com.vibe.agent.mcp.MemoryServerOffer.Reason.OFFERED -> t("mcp.memory.offered")
      com.vibe.agent.mcp.MemoryServerOffer.Reason.NOT_INSTALLED -> t("mcp.memory.notInstalled", "path" to offer.path.toString())
      com.vibe.agent.mcp.MemoryServerOffer.Reason.NOT_RUNNING -> t("mcp.memory.notRunning", "path" to offer.path.toString())
    })
    return offer.entry
  }

  override fun onConfigOptionsChanged(options: List<com.vibe.agent.acp.SessionConfigOption>) {
    SwingUtilities.invokeLater { configPicker.setOptions(options) }
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
    val visible = com.vibe.agent.handoff.EyesChecklist.visibleFiles(turns.chat.changedPaths.toList())
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
      touchedFiles = turns.chat.changedPaths.toList().sorted(),
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
  /**
   * `/spend` — where the money went and how much of each window is left.
   *
   * The report already existed as a menu action, but money is asked about mid-work, in the chat,
   * right after a turn feels expensive — and an answer that requires leaving the conversation is
   * an answer people look up once and then stop looking up.
   */
  private fun handleSpendCommand(message: ComposedMessage): Boolean {
    if (message.text.trim() != SPEND_COMMAND) return false
    userBubble(message.text.trim())
    // Disk IO for the ledger: off the EDT, then the text comes back.
    ApplicationManager.getApplication().executeOnPooledThread {
      val service = com.vibe.agent.budget.VibeSpendService.getInstance()
      val month = service.entries(com.vibe.agent.budget.SpendCeiling.MONTH_MS)
      val limits = VibeChatSettings.spendLimits()
      val text = buildString {
        // This conversation first: «во что мне обошёлся этот чат» is the question people ask, and
        // a day's total covers work they were not asking about.
        com.vibe.agent.budget.SpendLedger.ofThread(month, currentThreadId)?.let { line ->
          appendLine(t("spend.thisChat") + " " + com.vibe.agent.budget.SpendLines.of(line))
          appendLine()
        }
        val day = com.vibe.agent.budget.SpendLedger.within(month, System.currentTimeMillis(), com.vibe.agent.budget.SpendLedger.DAY_MS)
        appendLine(t("spend.window.day", "tokens" to "%,d".format(day.sumOf { it.tokens })))
        for (line in com.vibe.agent.budget.SpendLedger.byRole(day)) {
          appendLine("  " + com.vibe.agent.budget.SpendLines.of(line))
        }
        if (limits.any) {
          appendLine()
          val atMs = System.currentTimeMillis()
          for (verdict in com.vibe.agent.budget.SpendCeiling.check(month, atMs, limits)) {
            val forecast = com.vibe.agent.budget.SpendCeiling.timeToLimitMs(month, atMs, verdict)
            appendLine(t("spend.ceiling.line", "window" to windowName(verdict.window.id),
                         "spent" to money(verdict.spent), "limit" to money(verdict.limit),
                         "left" to money(verdict.left)) +
                       (forecast?.let { " " + t("spend.ceiling.forecast", "minutes" to it / 60_000) } ?: ""))
          }
        }
        else appendLine("\n" + t("spend.ceiling.off"))
        // What the subscriptions have left, as the vendors say — asked now, because the person asked.
        val quota = com.vibe.agent.budget.QuotaLines.render(
          com.vibe.agent.providers.SubscriptionQuotaFetch.fetchAll(providers, project.basePath), System.currentTimeMillis())
        if (quota.isNotEmpty()) {
          appendLine()
          quota.forEach { appendLine(it) }
        }
      }
      SwingUtilities.invokeLater {
        val console = TerminalConsole(t("spend.title"))
        console.append(text)
        messages.add(console)
        revalidateScroll()
      }
    }
    return true
  }

  /**
   * `/cascade` — окупается ли каскад.
   *
   * Каскад имеет смысл ровно настолько, насколько РЕДКО срабатывает эскалация, и пока это число
   * никто не показывает, «дешёвая модель с гейтом» держится на вере — том же основании, что и
   * чужие обещания «−67% стоимости». Считается по журналу, который и так пишется.
   */
  private fun handleCascadeCommand(message: ComposedMessage): Boolean {
    if (message.text.trim() != CASCADE_COMMAND) return false
    userBubble(message.text.trim())
    ApplicationManager.getApplication().executeOnPooledThread {
      val lines = audit?.readRecent(CASCADE_JOURNAL_LINES).orEmpty()
      val report = com.vibe.agent.pipelines.CascadeStats.of(com.vibe.agent.pipelines.CascadeStats.parse(lines))
      val text = buildString {
        if (report.gated == 0) {
          // Пустой отчёт объясняет, чего не хватает: гейта в hooks.json или самих прогонов.
          appendLine(t("cascade.none"))
        }
        else {
          appendLine(t("cascade.gated", "count" to report.gated))
          report.escalationShare?.let {
            appendLine(t("cascade.share",
                         "escalation" to "%.0f".format(it * 100),
                         "accepted" to "%.0f".format((1 - it) * 100)))
          }
          appendLine(t("cascade.skipped", "count" to report.skipped))
          appendLine()
          // Деньги — только по названным ценам (правило №40: своей таблицы цен у нас нет).
          appendLine(t("cascade.savingsHint"))
        }
      }
      SwingUtilities.invokeLater {
        val console = TerminalConsole(t("cascade.title"))
        console.append(text)
        messages.add(console)
        revalidateScroll()
      }
    }
    return true
  }

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
      // Batching is invisible to the loop detector — those calls are different, nothing repeats.
      // What gives a regression away is the shape: single-call turns where batches used to be.
      com.vibe.agent.trace.TurnTrace.averageBatch(events)?.let { average ->
        console.append("\n\n" + t("trace.batches",
                                  "batches" to com.vibe.agent.trace.TurnTrace.toolBatches(events).size,
                                  "average" to String.format(java.util.Locale.ROOT, "%.1f", average)))
      }
      // Контекстный налог: во что разговору обошлось повторное напоминание контекста. Считается
      // по числам провайдера, поэтому появляется только там, где он их присылает.
      val tax = com.vibe.agent.budget.ContextTax.of(threadUsages.toList(), turns.chat.pricing)
      if (tax.turns > 0) {
        console.append("\n\n" + t("trace.contextTax",
                                   "turns" to tax.turns,
                                   "input" to "%,d".format(tax.inputTokens + tax.cacheReadTokens),
                                   "output" to "%,d".format(tax.outputTokens),
                                   "perTurn" to "%,d".format(tax.perTurn)))
        tax.ratio?.let {
          console.append(" " + t("trace.contextTaxRatio", "ratio" to String.format(java.util.Locale.ROOT, "%.1f", it)))
        }
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
    if (text == BG_COMMAND || text.startsWith("$BG_COMMAND ")) {
      val rest = text.removePrefix(BG_COMMAND).trim()
      // `/bg` alone and `/bg stop <id>` are questions about the jobs, not new jobs.
      if (rest.isEmpty() || rest == BG_LIST) return listBackgroundTasks(text)
      if (rest.startsWith(BG_STOP)) return stopBackgroundTask(text, rest.removePrefix(BG_STOP).trim())
    }
    if (!text.startsWith("$BG_COMMAND ")) return false
    val command = text.removePrefix(BG_COMMAND).trim()
    if (command.isEmpty()) return false
    userBubble(text)
    // The lifetime is declared up front, the way MCP Tasks makes a server declare it: a job with
    // no stated deadline is indistinguishable from a hung one. Until now /bg silently borrowed a
    // timeout meant for measurements, and would have killed a long build with a message about
    // measurement.
    val limits = VibeAgentSettings.backgroundLimits()
    systemLine(t("bg.started", "command" to command) + " " +
               t("bg.limits", "minutes" to limits.ttlMs / 60_000, "seconds" to limits.pollIntervalMs / 1000))
    ApplicationManager.getApplication().executeOnPooledThread {
      val started = System.currentTimeMillis()
      // The handle is created before the work starts: a job that exists only after it finishes is
      // a job nobody could have stopped.
      val handle = java.util.concurrent.atomic.AtomicReference<Process?>(null)
      val task = tasks.start(command, started) { handle.get()?.destroyForcibly() }
      tasksChanged()
      systemLine(t("bg.handle", "id" to task.id))
      val output = runCatching { runShellWithLimits(command, limits, handle) }.getOrElse { error ->
        tasks.finish(task.id, com.vibe.agent.background.TaskRegistry.State.FAILED, System.currentTimeMillis())
        tasksChanged()
        systemLine(t("bg.failed", "command" to command, "reason" to error.message))
        return@executeOnPooledThread
      }
      tasks.finish(task.id, com.vibe.agent.background.TaskRegistry.State.DONE, System.currentTimeMillis())
      tasks.forgetFinished(System.currentTimeMillis())
      tasksChanged()
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

  /** `/bg` without arguments: what is running, since when, and what has just finished. */
  private fun listBackgroundTasks(said: String): Boolean {
    userBubble(said)
    val now = System.currentTimeMillis()
    val all = tasks.all()
    if (all.isEmpty()) {
      systemLine(t("bg.none"))
      return true
    }
    val text = all.joinToString("\n") { task ->
      t("bg.listLine", "id" to task.id, "state" to taskState(task.state),
        "seconds" to task.ageMs(now) / 1000, "command" to task.command.take(BG_LIST_COMMAND_LEN))
    }
    systemLine(text + "\n" + t("bg.stopHint"))
    return true
  }

  /** `/bg stop <id>` — or `/bg stop` for everything still running. */
  private fun stopBackgroundTask(said: String, id: String): Boolean {
    userBubble(said)
    val now = System.currentTimeMillis()
    if (id.isEmpty()) {
      // Stop first, mark second: finish() drops the stopper, so the other order would leave every
      // process running while the list happily reported them stopped.
      val running = tasks.running()
      val count = running.count { tasks.stop(it.id) }
      running.forEach { tasks.finish(it.id, com.vibe.agent.background.TaskRegistry.State.STOPPED, now) }
      tasksChanged()
      systemLine(if (count == 0) t("bg.none") else t("bg.stoppedAll", "count" to count))
      return true
    }
    val task = tasks.get(id)
    if (task == null || !task.running) {
      // «Этой задачи уже нет» is a real answer, and saying it plainly beats a silent no-op.
      systemLine(t("bg.unknown", "id" to id))
      return true
    }
    tasks.stop(id)
    tasks.finish(id, com.vibe.agent.background.TaskRegistry.State.STOPPED, now)
    tasksChanged()
    systemLine(t("bg.stopped", "id" to id, "command" to task.command.take(BG_LIST_COMMAND_LEN)))
    return true
  }

  private fun taskState(state: com.vibe.agent.background.TaskRegistry.State): String = when (state) {
    com.vibe.agent.background.TaskRegistry.State.RUNNING -> t("bg.state.running")
    com.vibe.agent.background.TaskRegistry.State.DONE -> t("bg.state.done")
    com.vibe.agent.background.TaskRegistry.State.FAILED -> t("bg.state.failed")
    com.vibe.agent.background.TaskRegistry.State.STOPPED -> t("bg.state.stopped")
    com.vibe.agent.background.TaskRegistry.State.EXPIRED -> t("bg.state.expired")
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
    sessionMeters.clear()
    // Контекстный налог — про ЭТОТ разговор: числа прошлого в новом чате отвечают на вопрос,
    // которого никто не задавал, и выглядят при этом достоверно.
    threadUsages.clear()
  }

  /**
   * A background command with a declared lifetime and a heartbeat.
   *
   * Separate from [runShell], which serves measurements: a measurement that outlives its timeout is
   * a broken measurement, while a build that runs for twenty minutes is just a build. Sharing one
   * timeout meant one of the two was always wrong.
   */
  private fun runShellWithLimits(
    command: String,
    limits: com.vibe.agent.background.TaskLimits.Limits,
    handle: java.util.concurrent.atomic.AtomicReference<Process?>,
  ): String {
    val process = ProcessBuilder(com.vibe.agent.util.ProcessSupport.shellCommand(command))
      .directory(project.basePath?.let { java.io.File(it) })
      .redirectErrorStream(true)
      .start()
    handle.set(process)
    val out = com.vibe.agent.util.ProcessSupport.drain(process.inputStream, "vibe-bg")
    val started = System.currentTimeMillis()
    var lastReport = started
    while (process.isAlive) {
      if (process.waitFor(BG_TICK_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) break
      val now = System.currentTimeMillis()
      if (com.vibe.agent.background.TaskLimits.expired(started, now, limits)) {
        process.destroyForcibly()
        error(t("bg.expired", "command" to command.take(60), "minutes" to limits.ttlMs / 60_000))
      }
      if (com.vibe.agent.background.TaskLimits.progressDue(lastReport, now, limits)) {
        lastReport = now
        val left = com.vibe.agent.background.TaskLimits.remainingSeconds(started, now, limits)
        systemLine(t("bg.running", "command" to command.take(60), "seconds" to (now - started) / 1000,
                     "left" to (left ?: 0)))
      }
    }
    return out.get(5, java.util.concurrent.TimeUnit.SECONDS).orEmpty()
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
    // Поиск ПО ТЕКСТУ, тот же, которым пользуется агент инструментом vibe_docs_search: два
    // ранжирования одного набора отвечали бы по-разному на один вопрос, смотря кто спросил.
    val hits = com.vibe.agent.help.HelpSearch.search(com.vibe.agent.help.HelpBundle.sections, question, HELP_SECTIONS)
    if (hits.isEmpty()) {
      systemLine(t("help.nothing"))
      return true
    }
    systemLine(t("help.using", "docs" to hits.map { it.section.file }.distinct().joinToString()))
    val block = hits.joinToString("\n\n") { hit ->
      "<context ref=\"help:${hit.section.file}#${hit.section.line}\">\n${hit.section.heading}\n${hit.section.body.take(HELP_DOC_CHARS)}\n</context>"
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
        answers.toSortedMap().entries.forEachIndexed { shown, (asked, answer) ->
          val adviser = plan.advisers[asked]
          // An adviser on a floating id may be a different model next time: the opinion says so where it is read,
          // because the answer itself carries the same name either way (VibeIDE's semantics, 15.09.2026).
          val label = adviser.toString() + if (isFloating(adviser)) " " + t("council.floating") else ""
          val console = TerminalConsole(t("council.opinion", "index" to (shown + 1), "adviser" to label))
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

  /** Whether the adviser's model is declared a floating alias — by the registry, catalog included. */
  private fun isFloating(adviser: com.vibe.agent.council.CouncilPlan.Adviser): Boolean =
    providers.firstOrNull { it.id == adviser.providerId }?.models?.firstOrNull { it.id == adviser.modelId }?.floating == true

  /** One adviser, one blocking call; failures are reported and do not take the council down. */
  private fun askAdviser(adviser: com.vibe.agent.council.CouncilPlan.Adviser, question: String): String? {
    val provider = providers.firstOrNull { it.id == adviser.providerId } ?: return null
    val resolved = ProvidersService.resolve(provider, project.basePath) { } ?: return null
    val model = com.vibe.agent.providers.ModelEntry(id = adviser.modelId)
    val answer = StringBuilder()
    return runCatching {
      com.vibe.agent.providers.LlmClient(projectBase = project.basePath).chat(
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

  /**
   * The money ceiling of the plan's own windows, asked BEFORE the turn.
   *
   * Subscriptions are rationed in dollars over five hours, a week and a month, and a token ceiling
   * cannot express that: the same hundred thousand tokens costs cents on one model and dollars on
   * another. Checked here for the same reason as the session ceiling — a limit reported after the
   * money is gone is a receipt, not a guard.
   */
  private fun spendCeilingReached(): Boolean {
    val limits = VibeChatSettings.spendLimits()
    if (!limits.any) return false
    // Memory only: this runs on the EDT, and a ledger flush here would be a freeze on a slow disk.
    // Not loaded yet means «не знаю», and «не знаю» must not refuse work.
    val entries = com.vibe.agent.budget.VibeSpendService.getInstance()
      .cachedEntries(com.vibe.agent.budget.SpendCeiling.MONTH_MS) ?: return false
    val now = System.currentTimeMillis()
    com.vibe.agent.budget.SpendCeiling.blocking(entries, now, limits)?.let { verdict ->
      val body = t("spend.ceiling.reached", "window" to windowName(verdict.window.id),
                   "spent" to money(verdict.spent), "limit" to money(verdict.limit))
      systemLine(body)
      // An unattended stretch is stopped, not asked: nobody is at the keyboard, and a question
      // with no one to answer it is a turn that hangs until morning. This is also the honest
      // reading of the ceiling — it exists to end exactly this kind of run.
      if (turnActor.kind == com.vibe.agent.audit.AuditActor.Kind.AGENT) return true
      // A person, though, set this ceiling themselves and is sitting right here. Refusing without
      // asking would send them into settings mid-thought to raise a number they will lower again —
      // and the answer belongs to them, not to us. The same question goes to the phone, like every
      // other one that can hold up a run.
      val request = com.vibe.agent.telegram.PendingApprovals.open(body)
      // Posted from a background thread: sending the question to the phone reads the token out of
      // the OS keychain and makes an HTTP call, and this method runs on the EDT. The dialog does
      // not wait for it — the phone's answer is noticed by polling either way.
      val onPhone = VibeAgentSettings.telegramEnabled
      ApplicationManager.getApplication().executeOnPooledThread {
        runCatching {
          com.vibe.agent.telegram.TelegramBridge.getInstance()
            .askApproval(request, t("telegram.approvalQuestion", "body" to body))
        }
      }
      val approved = askOnEdt {
        com.vibe.agent.telegram.ApprovalDialog.ask(
          project, t("spend.ceiling.title"), body, request, onPhone, t("spend.ceiling.continue"))
      }
      audit?.append(AuditEvent(System.currentTimeMillis(), AuditEvent.Action.PROMPT, ok = approved,
        actor = com.vibe.agent.audit.AuditActor.HUMAN,
        meta = mapOf("gate" to "spendCeiling", "window" to verdict.window.id,
                     "spent" to money(verdict.spent), "approved" to approved.toString())))
      if (!approved) return true
      systemLine(t("spend.ceiling.overridden", "window" to windowName(verdict.window.id)))
      return false
    }
    // The warning is worth exactly one line, and only while there is still room to act on it.
    com.vibe.agent.budget.SpendCeiling.warning(entries, now, limits)?.let { verdict ->
      val forecast = com.vibe.agent.budget.SpendCeiling.timeToLimitMs(entries, now, verdict)
      systemLine(t("spend.ceiling.near", "window" to windowName(verdict.window.id),
                   "spent" to money(verdict.spent), "limit" to money(verdict.limit),
                   "left" to money(verdict.left)) +
                 (forecast?.let { " " + t("spend.ceiling.forecast", "minutes" to it / 60_000) } ?: ""))
    }
    return false
  }

  private fun windowName(id: String): String = when (id) {
    com.vibe.agent.budget.SpendCeiling.FIVE_HOURS -> t("spend.window.fiveHours")
    com.vibe.agent.budget.SpendCeiling.WEEK -> t("spend.window.weekName")
    else -> t("spend.window.monthName")
  }

  private fun money(value: Double): String = "%.2f".format(value)

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
    // Vision gate BEFORE the pipeline: downloading a lecture only to answer «switch the model»
    // wastes minutes. Audio needs no vision model, so it is not gated.
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
   * Asks about a skill whose content the person has not approved yet, and remembers the answer.
   *
   * Bound to a digest of every file in the skill directory: a script rewritten by a pull request
   * changes what runs even when SKILL.md did not change. The file map is stored beside the digest,
   * so the next question can say WHICH files changed. Approval is per project, because a skill of
   * the same name in another repository is another skill.
   */
  private fun approveSkill(id: String, entry: com.vibe.agent.skills.SkillsStore.Entry, digest: String): Boolean {
    val key = SKILL_APPROVAL_KEY + id
    val filesKey = SKILL_APPROVED_FILES_KEY + id
    val props = com.intellij.ide.util.PropertiesComponent.getInstance(project)
    val verdict = com.vibe.agent.skills.SkillApproval.verdictFor(digest, props.getValue(key))
    if (verdict == com.vibe.agent.skills.SkillApproval.Verdict.UNCHANGED) return true
    val approvedFiles = props.getValue(filesKey)?.let { com.vibe.agent.skills.SkillApproval.decodeFiles(it) }
    val body = com.vibe.agent.skills.SkillApprovalText.render(id, entry, verdict, approvedFiles)
    val approved = askOnEdt {
      Messages.showYesNoDialog(project, body, t("skills.approve.title"),
                               t("skills.approve.use"), t("common.cancel"), Messages.getQuestionIcon()) == Messages.YES
    }
    if (!approved) {
      systemLine(t("skills.approve.refused", "id" to id))
      return false
    }
    props.setValue(key, digest)
    props.setValue(filesKey, com.vibe.agent.skills.SkillApproval.encodeFiles(entry.files.hashes))
    return true
  }

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
      // What was approved is the CONTENT, not the name: a skill lives in the repository and
      // arrives with a pull request, so «я разрешил его вчера» says nothing about what it does now.
      val digest = entry.digest()
      if (!approveSkill(id, entry, digest)) continue
      // A skill is text from disk like any other — same guard as project files.
      val clean = com.vibe.agent.security.ContextSanitizer.sanitize(entry.pkg.body)
      // Заголовок проверяется ОТДЕЛЬНО и до тела: в контекст он не уходит, но именно его человек
      // читает, решая одобрить, — спрятанная там строка обманывает не модель, а его. Находки
      // сливаются в одну строку ленты: два сообщения об одном файле читаются как два файла.
      val header = com.vibe.agent.security.ContextSanitizer.sanitize(entry.pkg.frontmatter)
      val findings = clean.findings + header.findings
      if (findings.isNotEmpty()) reportContextFindings("$id/${com.vibe.agent.skills.SkillPackage.SKILL_FILE}", findings)
      resolved.add(ContextSerializer.LoadedSkill(id, clean.text, digest))
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
        // Длинный непрерывный прогон — это спрятанная СТРОКА, а не мусор кодировки, и говорить
        // о нём той же фразой значит прятать разницу, ради которой градация и заведена.
        com.vibe.agent.security.ContextSanitizer.Kind.INVISIBLE ->
          if (finding.severity >= com.vibe.agent.security.ContextSanitizer.Severity.HIGH) {
            t("guard.invisibleSevere", "count" to finding.count, "run" to finding.longestRun)
          } else {
            t("guard.invisible", "count" to finding.count)
          }
        com.vibe.agent.security.ContextSanitizer.Kind.BIDI -> t("guard.bidi", "count" to finding.count)
        com.vibe.agent.security.ContextSanitizer.Kind.INSTRUCTION -> t("guard.instruction")
        com.vibe.agent.security.ContextSanitizer.Kind.SECRET -> t("guard.secret", "detail" to finding.detail)
      }
    }
    systemLine(t("guard.contextLine", "file" to name, "items" to parts.joinToString("; ")))
  }

  /**
   * Какой вариант разрешения предложить кнопкой по умолчанию, когда доверие понижено.
   *
   * Ищем отказ по виду варианта (`kind` протокола: `reject_once` / `reject_always`), а не по
   * подписи: подпись приходит от агента и на любом языке. Отказа среди вариантов нет — остаётся
   * первый, потому что выбор без вариантов не выбор.
   */
  private fun indexOfRefusal(options: List<JsonObject>): Int {
    val index = options.indexOfFirst {
      it["kind"]?.jsonPrimitive?.contentOrNull?.startsWith("reject") == true
    }
    return if (index >= 0) index else 0
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
      com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow(VibeToolWindows.AGENT)?.activate(null)
    }
  }

  private var recording: com.vibe.agent.voice.VoiceCapture.Recording? = null

  /** The live-transcript loop of the recording in progress (decision №84); null when there is none. */
  private var voicePreviewLoop: java.util.concurrent.ScheduledFuture<*>? = null

  /** A preview still waiting for the server: the next beat is skipped, not queued behind it. */
  private val voicePreviewBusy = java.util.concurrent.atomic.AtomicBoolean(false)

  private fun toggleVoice() {
    val active = recording
    if (active == null) {
      val target = java.io.File.createTempFile("vibe-voice-", ".wav")
      val started = runCatching { com.vibe.agent.voice.VoiceCapture.start(target) }.getOrElse {
        systemLine(t("chat.voice.noMicrophone", "reason" to (it.message ?: it.javaClass.simpleName)))
        target.delete()
        return
      }
      recording = started
      voicePill.toolTipText = t("chat.voice.stop")
      voicePill.icon = VibeIcons.MIC_ON
      voicePill.repaint()
      startVoicePreview(started)
      return
    }
    recording = null
    stopVoicePreview()
    voicePill.toolTipText = t("chat.voice.start")
    voicePill.icon = VibeIcons.MIC
    voicePill.repaint()
    val file = active.stop()
    if (file == null) {
      // Too short is a misclick, not a note. Transcribing silence costs seconds and returns
      // whisper's filler invented out of nothing.
      systemLine(t("chat.voice.tooShort"))
      return
    }
    ApplicationManager.getApplication().executeOnPooledThread { transcribeVoice(file) }
  }

  /** The resident server a live transcript can run on, or null: no `whisper-server` or no model set. */
  private fun voiceServerConfig(): com.vibe.agent.voice.VoiceServer.Config? =
    com.vibe.agent.voice.VoiceServer.configOf(VibeAgentSettings.voiceModelPath, VibeAgentSettings.telegramVoiceLanguage)

  /**
   * Every few seconds the recording so far goes to the resident server and its text shows under the
   * input — the person sees that the microphone hears before «стоп», not after. Past
   * [com.vibe.agent.voice.VoiceServer.PREVIEW_MAX_MS] the loop stops: each preview transcribes the
   * whole recording again, and the final transcript comes at «стоп» anyway. A server that cannot
   * start is named once and the loop ends; the final transcript then takes the one-shot path.
   */
  private fun startVoicePreview(active: com.vibe.agent.voice.VoiceCapture.Recording) {
    val config = voiceServerConfig() ?: return
    val server = com.vibe.agent.voice.VoiceServer
    voicePreviewLoop = com.intellij.util.concurrency.AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay({
      if (disposed || recording !== active || !voicePreviewBusy.compareAndSet(false, true)) return@scheduleWithFixedDelay
      try {
        if (active.elapsedMs > server.PREVIEW_MAX_MS) {
          SwingUtilities.invokeLater { if (recording === active) composer.setVoicePreview(t("chat.voice.previewLimit")) }
          voicePreviewLoop?.cancel(false)
          return@scheduleWithFixedDelay
        }
        val wav = active.snapshot() ?: return@scheduleWithFixedDelay
        val text = server.getInstance().transcribe(wav, config, server.PREVIEW_TIMEOUT_MS)
        if (!text.isNullOrBlank()) {
          SwingUtilities.invokeLater { if (recording === active) composer.setVoicePreview(t("chat.voice.preview", "text" to text)) }
        }
      }
      catch (e: Exception) {
        voicePreviewLoop?.cancel(false)
        systemLine(t("chat.voice.serverFailed", "reason" to (e.message ?: e.javaClass.simpleName)))
      }
      finally {
        voicePreviewBusy.set(false)
      }
    }, server.PREVIEW_INTERVAL_MS, server.PREVIEW_INTERVAL_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
  }

  private fun stopVoicePreview() {
    voicePreviewLoop?.cancel(false)
    voicePreviewLoop = null
    SwingUtilities.invokeLater { composer.setVoicePreview(null) }
  }

  private fun transcribeVoice(file: java.io.File) {
    try {
      systemLine(t("chat.voice.transcribing"))
      // The resident server first: it already holds the model the live transcript used. Failing
      // that — the one-shot transcriber, as before the server existed.
      val viaServer = voiceServerConfig()?.let { config ->
        runCatching {
          com.vibe.agent.voice.VoiceServer.getInstance().transcribe(file.readBytes(), config, com.vibe.agent.voice.VoiceServer.FINAL_TIMEOUT_MS)
        }.getOrNull()
      }
      val text = viaServer ?: transcribeOnce(file) ?: return
      val task = com.vibe.agent.voice.VoiceTranscription.taskFrom(text)
      if (task == null) {
        systemLine(t("chat.voice.empty"))
        return
      }
      // Into the draft, not sent: what to do with the words stays the person's decision — the same
      // rule the design detector's findings follow.
      putIntoComposer(task)
    }
    finally {
      runCatching { file.delete() }
    }
  }

  /** The one-shot transcriber on the machine; null after saying why there is none or it heard nothing. */
  private fun transcribeOnce(file: java.io.File): String? {
    val model = VibeAgentSettings.voiceModelPath
    val transcriber = com.vibe.agent.voice.VoiceTranscription.find(model, wav = true)
    if (transcriber == null) {
      systemLine(if (com.vibe.agent.voice.VoiceTranscription.needsModel(model)) t("chat.voice.noModel") else t("chat.voice.noTranscriber"))
      return null
    }
    val dir = file.parentFile
    val text = runCatching {
      val process = ProcessBuilder(
        com.vibe.agent.voice.VoiceTranscription.command(transcriber, file, dir, VibeAgentSettings.telegramVoiceLanguage))
        .redirectErrorStream(true).start()
      process.inputStream.readBytes()
      process.waitFor()
      val out = com.vibe.agent.voice.VoiceTranscription.outputFile(file, dir)
      out.takeIf { it.isFile }?.readText().also { runCatching { out.delete() } }
    }.getOrNull()
    if (text == null) systemLine(t("chat.voice.empty"))
    return text
  }

  override fun putIntoComposer(text: String) {
    SwingUtilities.invokeLater {
      if (disposed) return@invokeLater
      composer.appendDraft(text)
      com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow(VibeToolWindows.AGENT)?.activate(null)
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
  /**
   * A key pasted into the input box, replaced before it goes anywhere — the model, the wire, the transcript.
   *
   * Masking used to cover only attached files, and only when the person had turned it on ([ContextSerializer.load]):
   * a token typed or pasted into the message travelled as it was. It is the likeliest place for one to appear — a
   * person shows the agent the line that fails — and the least recoverable: the answer, the provider's logs and the
   * thread file all keep it. So this one is not a setting: the shape is replaced, the kind is named, and the feed says
   * the key is already compromised and must be revoked (Autopilot's rule, 17.09.2026).
   */
  private fun redactSecretsInInput(message: ComposedMessage): ComposedMessage {
    val labels = com.vibe.agent.security.SecretPatterns.labels(message.text)
    if (labels.isEmpty()) return message
    systemLine(t("chat.secretRedacted", "kinds" to labels.joinToString()))
    audit?.append(AuditEvent(System.currentTimeMillis(), AuditEvent.Action.SECRET_REDACTED, ok = false,
                             actor = com.vibe.agent.audit.AuditActor.HUMAN,
                             meta = mapOf("kinds" to labels.joinToString())))
    return message.copy(text = com.vibe.agent.security.SecretPatterns.redact(message.text))
  }

  private fun startTurn(
    message: ComposedMessage,
    threadId: String = currentThreadId,
    actor: com.vibe.agent.audit.AuditActor = com.vibe.agent.audit.AuditActor.HUMAN,
  ): Boolean {
    if (disposed) return false
    val message = redactSecretsInInput(message)
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
        com.vibe.agent.ui.VibeNotifications.AGENT)
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
    // The silence clock belongs to the TURN, not to the ACP path that used to be its only winder:
    // a direct-chat turn left it at zero, and the watchdog read that as silence since the epoch.
    noteActivity()
    staleAnnounced.set(false)
    // The cancel flag belongs to the whole turn (Stop during context resolution must not be lost).
    llmCancel.set(false)
    composer.busy = true
    showWorking(WorkingLine.Kind.THINKING)
    turnThreadId = threadId
    turns.chat = chatTurn(threadId)
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
        if (t is ChatTarget.Agent && agentLimitReached(t.config, runId = null)) {
          finishTurn()
          return@executeOnPooledThread
        }
        when (t) {
          is ChatTarget.Model -> sendToLlm(t, startedAt)
          is ChatTarget.Agent -> sendToAcp(t, message.text, loaded, message.images, startedAt, skills)
        }
      }
      catch (e: Exception) {
        turnNote(t("chat.error", "reason" to e.message))
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
      hideWorking()
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
      // The person moved to another thread during the turn: its session becomes current only now.
      if (endedThreadId != null && endedThreadId != currentThreadId) followThreadSession(currentThreadId)
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
      changedFiles = turns.chat.let { chat -> if (chat.changedPaths.isNotEmpty()) chat.changedPaths.size else if (chat.hadMutatingTool) 1 else 0 },
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
                       agentActor())) autopilotTurns = 0
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
        .getNotificationGroup(com.vibe.agent.ui.VibeNotifications.AGENT)
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
    val plan = com.vibe.agent.plans.AgentPlan.parse(update, System.currentTimeMillis()).copy(agent = target?.auditName())
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

  /** One line when the plan is continued by another agent than the one that wrote it. */
  private fun announceExecutorChange(plan: com.vibe.agent.plans.AgentPlan.Plan) {
    val (was, now) = com.vibe.agent.plans.AgentPlan.executorChange(plan, target?.auditName()) ?: return
    systemLine(t("plan.executorChanged", "was" to was, "now" to now))
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
    announceExecutorChange(plan)
  }

  /**
   * The daily ceiling for a role, asked BEFORE the step: a budget reported after the spend is a
   * receipt. Per role rather than per run because the runaway case is not one expensive turn — it
   * is a reviewer restarted forty times by a loop nobody was watching.
   */
  /**
   * The agent's own ceilings from `.vibe/agents.json` (`limits`), asked BEFORE its turn.
   *
   * A ceiling reported after the spend is a receipt. The run ceiling counts only inside a pipeline run,
   * where there is a run to bill; the daily ceilings count everywhere the agent works.
   */
  private fun agentLimitReached(config: AgentServerConfig, runId: String?): Boolean {
    val limits = config.limits ?: return false
    val spend = com.vibe.agent.budget.VibeSpendService.getInstance()
    val (tokens, cost, currency) = spend.spentByTarget("acp/${config.name}")
    val bill = runId?.let { spend.ofRun(it) }
    val hit = limits.exceeded(tokens, cost, currency, bill?.cost, bill?.currency) ?: return false
    val shown = if (hit.reason == com.vibe.agent.budget.AgentLimits.Reason.TOKENS_PER_DAY) "%,d".format(hit.spent.toLong()) else "%.4f".format(hit.spent)
    val limit = if (hit.reason == com.vibe.agent.budget.AgentLimits.Reason.TOKENS_PER_DAY) "%,d".format(hit.limit.toLong()) else "%.4f".format(hit.limit)
    val unit = limits.currency ?: currency ?: ""
    systemLine(when (hit.reason) {
      com.vibe.agent.budget.AgentLimits.Reason.COST_PER_DAY -> t("limits.costPerDay", "agent" to config.name, "spent" to shown, "limit" to limit, "currency" to unit)
      com.vibe.agent.budget.AgentLimits.Reason.COST_PER_RUN -> t("limits.costPerRun", "agent" to config.name, "spent" to shown, "limit" to limit, "currency" to unit)
      com.vibe.agent.budget.AgentLimits.Reason.TOKENS_PER_DAY -> t("limits.tokensPerDay", "agent" to config.name, "spent" to shown, "limit" to limit)
    })
    audit?.append(AuditEvent(System.currentTimeMillis(), AuditEvent.Action.HOOK, ok = false, actor = com.vibe.agent.audit.AuditActor.IDE,
      meta = mapOf("event" to "agent_limit", "agent" to config.name, "reason" to hit.reason.name.lowercase())))
    return true
  }

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
    turnNote("⛔ " + reason)
    breakers.trip(com.vibe.agent.safety.ThrashDetector::class.java.simpleName.lowercase(), reason, System.currentTimeMillis())
    synchronized(thrashHistory) { thrashHistory.clear() }
    cancelTurn()
  }

  private fun noteLoop(call: com.vibe.agent.acp.ToolCall, turn: TurnState) {
    val loopHistory = turn.loopHistory
    loopHistory.add(com.vibe.agent.safety.LoopDetector.fingerprint(call.toolName ?: call.kind, call.rawInput?.toString()))
    val finding = com.vibe.agent.safety.LoopDetector.check(loopHistory.snapshot())
    if (finding.verdict == com.vibe.agent.safety.LoopDetector.Verdict.OK) return
    val reason = if (finding.verdict == com.vibe.agent.safety.LoopDetector.Verdict.REPEAT)
      t("loop.repeat", "count" to finding.count, "call" to finding.fingerprint.take(120))
    else t("loop.cycle", "pattern" to finding.fingerprint.take(160))
    turnNote("🔁 " + reason)
    breakers.trip(com.vibe.agent.safety.LoopDetector::class.java.simpleName.lowercase(), reason, System.currentTimeMillis())
    loopHistory.clear()
    // A step that loops is stopped alone: its neighbours in the wave are not looping.
    val stepSession = turn.sessionId
    if (turn === turns.chat || stepSession == null) cancelTurn()
    else client?.let { c -> ApplicationManager.getApplication().executeOnPooledThread { runCatching { c.cancel(stepSession) } } }
  }

  /** Any sign of life from the agent resets the silence clock. */
  private fun noteActivity() {
    lastActivityMs.set(System.currentTimeMillis())
  }

  /** How long the turn has been silent, in words. */
  private fun silentFor(nowMs: Long): String =
    com.vibe.agent.util.HumanDuration.text(
      com.vibe.agent.safety.DeadManSwitch.silentMs(lastActivityMs.get(), nowMs))

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
          systemLine(t("deadman.stale", "duration" to silentFor(now)))
        }
      }
      com.vibe.agent.safety.DeadManSwitch.Verdict.DEAD -> {
        turnNote(t("deadman.dead", "duration" to silentFor(now)))
        cancelTurn()
      }
    }
  }

  private fun cancelTurn() {
    llmCancel.set(true)
    llmClient.cancel()
    // Steps on their own model talk through their own clients, and Stop reaches every one of them.
    turns.all().forEach { turn -> turn.llm?.cancel() }
    // Steps of a wave run in sessions of their own; the chat's cancel reaches only the chat's session.
    val stepSessions = turns.all().filter { it !== turns.chat }.mapNotNull { it.sessionId }.distinct()
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
          if (client === c) { client = null; clientConfig = null; threadSessions.clear() }
        }
      }
      else {
        c.cancel()
        stepSessions.filter { it != c.sessionId }.forEach { c.cancel(it) }
      }
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
    var result = prompt
    val hits = com.vibe.agent.knowledge.Librarian.find(entries, userText)
    if (entries.isNotEmpty() && hits.isNotEmpty()) {
      val withPaths = hits.map { hit ->
        hit.copy(entry = hit.entry.copy(path = index.relativeTo(hit.entry)))
      }
      systemLine(t("knowledge.found", "paths" to withPaths.joinToString { it.entry.path }))
      result = com.vibe.agent.knowledge.Librarian.promptBlock(withPaths, t("knowledge.header")) + "\n\n" + result
    }
    return prependDecisions(prependInbox(result, userText), userText)
  }

  /**
   * Принятые решения — тем же путём, что и записи знаний, и отдельным блоком.
   *
   * Отдельным намеренно: запись знаний рассказывает, как устроено, а решение говорит, что уже
   * отвергнуто и почему. Агент, не видевший второго, предложит отвергнутое — вежливо, подробно и
   * за ваши токены.
   */
  /**
   * Документы, положенные в корпус снаружи.
   *
   * Без этого «положить в корпус» кладёт документ, который никто не находит: агент про него не
   * знает, а человек знал бы и без папки. Подаются путями — договор на двенадцать страниц в
   * промпте вытеснил бы саму задачу.
   */
  private fun prependInbox(prompt: String, userText: String): String {
    val entries = com.vibe.agent.ingest.IngestStore.getInstance(project).entries()
    if (entries.isEmpty()) return prompt
    val hits = com.vibe.agent.knowledge.Librarian.find(entries, userText)
    if (hits.isEmpty()) return prompt
    systemLine(t("ingest.found", "paths" to hits.joinToString { it.entry.path }))
    return com.vibe.agent.knowledge.Librarian.promptBlock(hits, t("ingest.header")) + "\n\n" + prompt
  }

  private fun prependDecisions(prompt: String, userText: String): String {
    val entries = com.vibe.agent.decisions.DecisionStore.getInstance(project).entries()
    if (entries.isEmpty()) return prompt
    val hits = com.vibe.agent.knowledge.Librarian.find(entries, userText)
    if (hits.isEmpty()) return prompt
    systemLine(t("decisions.found", "paths" to hits.joinToString { it.entry.path }))
    return com.vibe.agent.knowledge.Librarian.promptBlock(hits, t("decisions.header")) + "\n\n" + prompt
  }

  /**
   * The unfinished plan, in front of the first prompt of a session the agent could not resume.
   *
   * The plan lives in our store and survives a restart; the agent's session may not — it expired, or
   * the agent cannot resume at all. A new session knows nothing of the plan, and the autopilot's
   * «продолжай по плану» then continues nothing. Once per new session, and only an unfinished plan.
   */
  private fun prependCarriedPlan(prompt: String): String {
    val threadId = turnThreadId ?: currentThreadId ?: return prompt
    if (!planCarryThreads.remove(threadId)) return prompt
    val plan = runCatching { com.vibe.agent.plans.PlanStore.getInstance(project).load(threadId) }.getOrNull() ?: return prompt
    if (plan.isEmpty || plan.isFinished) return prompt
    systemLine(t("plan.carried", "done" to plan.done, "total" to plan.total))
    announceExecutorChange(plan)
    val steps = com.vibe.agent.plans.AgentPlan.render(plan) { status ->
      when (status) {
        com.vibe.agent.plans.AgentPlan.Status.COMPLETED -> "[x]"
        com.vibe.agent.plans.AgentPlan.Status.IN_PROGRESS -> "[~]"
        com.vibe.agent.plans.AgentPlan.Status.PENDING -> "[ ]"
      }
    }
    return t("plan.carryPrompt", "plan" to steps) + "\n\n" + prompt
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
    // The client first: whether the agent could resume its session is known only once it is open,
    // and a session it could not resume gets the unfinished plan in front of the prompt.
    val c = ensureClient(t.config, turnThreadId ?: currentThreadId)
    val fullPrompt = prependMinimalism(prependProjectRules(prependKnowledge(prependCarriedPlan(designed), text), text, loaded))
    // A fresh turn: its tool calls, changed files and ceilings started empty with its TurnState; what the panel keeps
    // besides them is reset here.
    trace.clear()
    failoverTried.clear()
    turnStartedAtMs = System.currentTimeMillis()
    lastActivityMs.set(System.currentTimeMillis())
    staleAnnounced.set(false)
    terminalConsoles.clear()
    // Signals are NOT cleared here: they live for the whole thread (see [sessionSignals]).
    // Ход, начатый не человеком за этой клавиатурой (входящий HTTP API, мост, дежурная проверка,
    // пайплайн), несёт текст, которого никто не читал глазами.
    if (turnActor != com.vibe.agent.audit.AuditActor.HUMAN) {
      turns.chat.signals.add(com.vibe.agent.guard.Trifecta.Signal.UNTRUSTED_CONTENT)
    }
    // The turn's reasoning block is EDT-owned (created in appendThought's invokeLater); a fresh turn has none yet.
    // Which recipes, in which version, took part in the turn: without it the chain an investigation
    // walks (prompt → skill → tool call) breaks right here. The digest is the approved one.
    val promptMeta = buildMap {
      put("chars", text.length.toString())
      if (skills.isNotEmpty()) put("skills", skills.joinToString(",") { "${it.id}@${it.digest}" })
    }
    turnId = com.vibe.agent.audit.TurnId.next()
    audit?.append(AuditEvent(System.currentTimeMillis(), AuditEvent.Action.PROMPT, ok = true, actor = turnActor,
      turnId = turnId, sessionId = turnThreadId ?: currentThreadId, model = "acp/${t.config.name}", meta = promptMeta))
    SwingUtilities.invokeLater {
      modePicker.setModes(c.modes)
      configPicker.setOptions(c.configOptions)
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
  private fun promptAcpTurn(c: AcpClient, blocks: List<ContentBlock>, t: ChatTarget.Agent, startedAt: Long, verifyAttempt: Int, checkAttempt: Int,
                            designAttempt: Int = 0, slopAttempt: Int = 0) {
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
            val bounce = evaluateGates(verifyAttempt, checkAttempt, designAttempt, slopAttempt)
            if (bounce == null) status.set(VibeAgentStatusService.State.RUNNING)
            if (bounce != null && !llmCancel.get() && !disposed && c.isAlive) {
              finishAgentBubble(secs, t("chat.checkBounceLabel"))
              promptAcpTurn(c, listOf(ContentBlock.Text(bounce.message)), t, startedAt, bounce.verifyAttempt, bounce.checkAttempt,
                            bounce.designAttempt, bounce.slopAttempt)
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

  private data class GateBounce(val message: String, val verifyAttempt: Int, val checkAttempt: Int, val designAttempt: Int = 0,
                                val slopAttempt: Int = 0)

  /**
   * Post-turn gates over the files this turn changed. Returns a bounce (synthetic
   * follow-up prompt) or null to complete. Only runs when the turn mutated files.
   */
  private fun evaluateGates(verifyAttempt: Int, checkAttempt: Int, designAttempt: Int = 0, slopAttempt: Int = 0): GateBounce? {
    // Stop pressed → the user is done with this turn; do not launch a minutes-long verify build.
    if (llmCancel.get() || disposed) return null
    // A Bash/edit tool may have changed files the client never saw as fs/write, so the gate runs on
    // any mutating turn — not only when the turn's changed paths are populated.
    val chat = turns.chat
    if (chat.changedPaths.isEmpty() && !chat.hadMutatingTool) return null
    val paths = chat.changedPaths.toList()
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
          verifyAttempt + 1, checkAttempt, designAttempt, slopAttempt)
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
        verifyAttempt, checkAttempt + 1, designAttempt, slopAttempt)
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
        // Why the detector is silent must be said: silence otherwise reads as «the page is fine».
        systemLine(t("chat.design.notMeasured", "reason" to measured.reason))
      }
      else {
        when (DesignHookPolicy.decide(designMode, designFindings, designAttempt, VibeAgentSettings.designMaxAttempts)) {
          DesignHookPolicy.Decision.BOUNCE -> return GateBounce(
            DesignHookPolicy.corrective(designFindings, designAttempt + 1, VibeAgentSettings.designMaxAttempts),
            verifyAttempt, checkAttempt, designAttempt + 1, slopAttempt)
          DesignHookPolicy.Decision.STOP ->
            systemLine(t("chat.design.stop", "max" to VibeAgentSettings.designMaxAttempts))
          DesignHookPolicy.Decision.REPORT -> systemLine("🎨 " + DesignReview.summary(designFindings))
          DesignHookPolicy.Decision.SKIP -> {}
        }
      }
    }

    // --- TEXT-SLOP GATE: read the prose this turn wrote for people ---
    val slopMode = when (VibeAgentSettings.slopMode) {
      VibeAgentSettings.SLOP_NOTIFY -> com.vibe.agent.slop.SlopGatePolicy.Mode.NOTIFY
      VibeAgentSettings.SLOP_ENFORCE -> com.vibe.agent.slop.SlopGatePolicy.Mode.ENFORCE
      else -> com.vibe.agent.slop.SlopGatePolicy.Mode.OFF
    }
    val prose = com.vibe.agent.slop.SlopGatePolicy.prosePaths(paths)
    if (slopMode != com.vibe.agent.slop.SlopGatePolicy.Mode.OFF && prose.isNotEmpty()) {
      val catalog = com.vibe.agent.slop.SlopCheck.catalog(project.basePath) { systemLine(t("slop.cli.warning", "text" to it)) }
      if (catalog == null) {
        // Silence would read as «the text is clean»; a build without its catalogue says so.
        systemLine(t("slop.gate.noCatalog", "reason" to com.vibe.agent.slop.SlopCheck.builtInWarnings.joinToString("; ")))
      }
      else {
        // One budget for the whole turn: a runaway rule from .vibe/slop.json is paid for once, not once per file
        val budget = VibeAgentSettings.slopBudget()
        val reports = prose.mapNotNull { path ->
          readFileForScan(path)?.let {
            com.vibe.agent.slop.SlopGatePolicy.FileReport(path, com.vibe.agent.slop.TextSlop.analyze(it, catalog, budget))
          }
        }
        budget.skipped.takeIf { it.isNotEmpty() }?.let { systemLine(com.vibe.agent.slop.SlopLabels.skipped(it)) }
        val maxAttempts = VibeAgentSettings.slopMaxAttempts
        when (com.vibe.agent.slop.SlopGatePolicy.decide(slopMode, reports, slopAttempt, maxAttempts)) {
          com.vibe.agent.slop.SlopGatePolicy.Decision.BOUNCE -> return GateBounce(
            com.vibe.agent.slop.SlopGatePolicy.corrective(reports, slopAttempt + 1, maxAttempts),
            verifyAttempt, checkAttempt, designAttempt, slopAttempt + 1)
          com.vibe.agent.slop.SlopGatePolicy.Decision.STOP ->
            systemLine(t("slop.gate.stop", "max" to maxAttempts, "files" to com.vibe.agent.slop.SlopGatePolicy.summary(reports)))
          com.vibe.agent.slop.SlopGatePolicy.Decision.REPORT ->
            systemLine(t("slop.gate.notify", "files" to com.vibe.agent.slop.SlopGatePolicy.summary(reports)))
          com.vibe.agent.slop.SlopGatePolicy.Decision.SKIP -> {}
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

  /**
   * Asks a modal question on the EDT, from wherever the caller happens to be.
   *
   * `invokeAndWait` looks like the tool for this and is only half of it: called from a background
   * thread it is exactly right, but called FROM the EDT it runs the block inside an *intended write
   * action* — and showing a modal dialog from a write action is what the platform forbids. Both of
   * our question points can be reached either way (a turn starts on the EDT, an ACP permission
   * arrives on a protocol thread), so the choice belongs here rather than in each of them.
   */
  private fun <T> askOnEdt(block: () -> T): T {
    if (SwingUtilities.isEventDispatchThread()) return block()
    var result: T? = null
    ApplicationManager.getApplication().invokeAndWait { result = block() }
    @Suppress("UNCHECKED_CAST")
    return result as T
  }

  /** Confirm clearing latched security breakers before an agent turn (manual-only, VibeIDE contract). */
  private fun confirmClearBreakers(): Boolean {
    val cleared = askOnEdt {
      val choice = Messages.showYesNoDialog(project,
        t("chat.breakerDialog", "reasons" to breakers.openReasons().joinToString("\n")),
        t("chat.breakerTitle"), t("chat.breakerClear"), t("common.cancel"), Messages.getWarningIcon())
      choice == Messages.YES
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
    val changed = turns.chat.changedPaths.toList()
    val decision = hooks.run(HookEvent.TURN_END, null, null, changed)
    audit?.append(AuditEvent(System.currentTimeMillis(), AuditEvent.Action.HOOK, ok = !decision.flagged, actor = com.vibe.agent.audit.AuditActor.IDE,
      meta = mapOf("event" to HookEvent.TURN_END.wire, "changedFiles" to changed.size.toString(),
        "broken" to decision.brokenHooks.size.toString())))
    decision.agentMessage?.let { systemLine(t("chat.projectCheck", "text" to it)) }
  }

  /**
   * Per thread: how many oldest wire messages a summary covers, and the summary itself.
   *
   * In memory on purpose: after a restart the fold is recomputed once. A summary written to disk
   * would outlive the provider and model that produced it, and be sent to a different one unchecked.
   */
  private val compactions = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, String>>()

  /** Per thread: tools the model loaded through the tool search; the set only grows within a thread. */
  private val loadedTools = java.util.concurrent.ConcurrentHashMap<String, MutableSet<String>>()

  /** Per thread: how many oldest wire messages carry dropped tool results. Only moves forward, like a fold. */
  private val shrunkResults = java.util.concurrent.ConcurrentHashMap<String, Int>()

  /**
   * Folds the oldest part of a long conversation into a summary before the window overflows.
   *
   * An ACP agent keeps its own window; the direct model path is ours, and it used to resend the whole
   * transcript until the model silently forgot the beginning. The summary is a SYSTEM message, so no
   * provider sees two user turns in a row; pinned messages from the folded part are kept verbatim —
   * pinning is the person saying «не забывай это». A failed summary sends the history unfolded and
   * says so: a turn must not be lost to its own housekeeping.
   */
  private fun compactForWindow(
    t: ChatTarget.Model,
    resolved: com.vibe.agent.providers.ResolvedProvider,
    threadId: String,
    transcript: List<ChatMessageRecord>,
    wire: List<ChatMessage>,
    tools: List<com.vibe.agent.providers.ToolSpec>,
  ): List<ChatMessage> {
    val policy = com.vibe.agent.context.HistoryCompaction.Policy(
      VibeAgentSettings.compactTriggerPercent, VibeAgentSettings.compactTargetPercent, VibeAgentSettings.compactKeepRecent)
    // Tool schemas travel in every request; the estimate used to see only message text.
    val fixed = com.vibe.agent.providers.ToolCalls.schemaTokens(tools)
    val stored = compactions[threadId]?.takeIf { it.first <= wire.size }
    val summaryTokens = stored?.second?.let { com.vibe.agent.context.ContextBudget.estimateTokens(it) } ?: 0
    // Old tool results go before any summary: the answers built on them stay, the bulk goes.
    var shrunkUpTo = (shrunkResults[threadId] ?: 0).coerceAtMost(wire.size)
    var current = com.vibe.agent.providers.ToolRounds.shrinkResults(wire, shrunkUpTo)
    var estimates = current.map(com.vibe.agent.providers.ToolRounds::estimatedTokens)
    if (com.vibe.agent.context.HistoryCompaction.overTrigger(estimates, t.model.contextWindow, stored?.first ?: 0, summaryTokens, fixed, policy)) {
      val candidate = (wire.size - policy.keepRecent).coerceAtLeast(shrunkUpTo)
      val newlyShrunk = wire.subList(shrunkUpTo, candidate).count { it.toolRounds.isNotEmpty() }
      if (newlyShrunk > 0) {
        shrunkUpTo = candidate
        shrunkResults[threadId] = candidate
        current = com.vibe.agent.providers.ToolRounds.shrinkResults(wire, shrunkUpTo)
        estimates = current.map(com.vibe.agent.providers.ToolRounds::estimatedTokens)
        systemLine(t("context.compacted.resultsShrunk", "count" to newlyShrunk))
      }
    }
    val cut = com.vibe.agent.context.HistoryCompaction.foldCount(estimates, t.model.contextWindow, stored?.first ?: 0, summaryTokens, fixed, policy)
    if (cut == 0) return current
    val summary = if (stored != null && stored.first == cut) stored.second else {
      val folding = buildList {
        stored?.let { add(ChatMessage("system", t("context.compacted.previous", "summary" to it.second))) }
        addAll(current.subList(stored?.first ?: 0, cut))
        add(ChatMessage("user", t("context.compacted.request")))
      }
      val text = runCatching {
        val out = StringBuilder()
        LlmClient(projectBase = project.basePath).chat(resolved, t.model, folding, { llmCancel.get() }) { delta -> out.append(delta) }
        out.toString().trim()
      }.getOrElse { failure ->
        systemLine(t("context.compacted.failed", "reason" to (failure.message ?: "")))
        return current
      }
      if (text.isEmpty()) {
        systemLine(t("context.compacted.failed", "reason" to ""))
        return current
      }
      compactions[threadId] = cut to text
      systemLine(t("context.compacted.done", "count" to cut, "window" to (t.model.contextWindow ?: 0)))
      text
    }
    // Pinned records among the folded ones stay as they were written.
    val pinned = transcript.filter { it.role != Role.OTHER }.take(cut).filter { it.pinned }.map {
      ChatMessage(role = if (it.role == Role.USER) "user" else "assistant", text = it.wireText ?: it.text)
    }
    return listOf(ChatMessage("system", t("context.compacted.prefix", "summary" to summary))) + pinned + current.drop(cut)
  }

  private fun sendToLlm(t: ChatTarget.Model, startedAt: Long) {
    try {
      val resolved = ProvidersService.resolve(t.provider, project.basePath) { systemLine("[providers] $it") }
      if (resolved == null) {
        systemLine(com.vibe.agent.i18n.VibeI18n.t("chat.provider.noBaseUrl", "id" to t.provider.id))
        return
      }
      if (resolved.missingKey) {
        systemLine(com.vibe.agent.i18n.VibeI18n.t(
          "chat.provider.noKey", "id" to t.provider.id,
          "sources" to com.vibe.agent.providers.ApiKeyResolver.sourceNames(t.provider)))
        return
      }
      // A privacy label, so the model decides and not the address: a proxy on localhost may lead to the cloud
      if (resolved.runsLocally) systemLine(t("chat.localModel"))
      // The conversation so far (this turn's user record included) — rebuilt from the thread each time,
      // so reopening an old thread resumes with its full context.
      val threadId = turnThreadId ?: return
      val transcript = history.get(threadId)?.messages.orEmpty()
      // Old screenshots stop paying rent: only the newest image-bearing messages keep them.
      //
      // The boundary is STICKY. Recomputing it every turn quietly rewrote an earlier message the
      // moment a fifth picture appeared — and a rewritten earlier message is where the prompt cache
      // stops matching, so every token after it was billed as fresh input on every later turn.
      val imageIndices = transcript.indices.filter { transcript[it].images.isNotEmpty() }
      val newImageArrived = transcript.lastOrNull()?.images?.isNotEmpty() == true
      val cut = com.vibe.agent.history.WirePrefix.imageCut(
        imageIndices, MAX_IMAGE_HISTORY_MESSAGES, imageCutIndex, newImageArrived)
      imageCutIndex = cut
      val imageBearing = imageIndices.filter { it >= cut }.toSet()
      val wireMessages = transcript.withIndex().filter { it.value.role != Role.OTHER }.map { (index, r) ->
        ChatMessage(
          role = if (r.role == Role.USER) "user" else "assistant",
          text = r.wireText ?: r.text,
          images = if (index in imageBearing) r.images.map { ImagePart(it.mimeType, it.base64) } else emptyList(),
          reasoning = r.reasoning,
          toolRounds = r.toolRounds,
          responses = r.responses,
        )
      }
      // A model declared non-vision must not receive images lingering in the history either.
      val uncompacted = if (t.model.vision == false) wireMessages.map { it.withoutImages() } else wireMessages
      // Expanded after compaction: compaction cuts by transcript positions, one message per record.
      // Built before compaction: the tool schemas count towards the window.
      val allTools = directToolSpecs(t)
      val loaded = loadedTools.getOrPut(threadId) { java.util.Collections.synchronizedSet(LinkedHashSet()) }
      var tools = com.vibe.agent.mcp.ToolSearch.offered(allTools, loaded, VibeAgentSettings.toolSearchThreshold)
      // Where the turn is happening goes FIRST and always: a model that is not told invents the
      // answer, and which tool it invents with differs from endpoint to endpoint (WorkspaceBriefing).
      val wire = listOf(workspaceMessage()) +
                 com.vibe.agent.providers.ToolRounds.expand(compactForWindow(t, resolved, threadId, transcript, uncompacted, tools))
      // Said out loud when it happens: a broken prefix is invisible, and its whole cost lands on
      // the bill. The line names the turn where the conversation stopped being append-only.
      val lines = wire.map {
        com.vibe.agent.history.WirePrefix.Line(it.role, it.text, it.images.map { img -> img.base64.take(64) })
      }
      if (!com.vibe.agent.history.WirePrefix.appendOnly(lastWireLines, lines)) {
        systemLine(t("cache.prefixBroken", "shared" to com.vibe.agent.history.WirePrefix.sharedPrefix(lastWireLines, lines),
                     "total" to lastWireLines.size))
      }
      lastWireLines = lines
      // Снимок разбивки берётся ЗДЕСЬ, на собранном запросе: позже его уже не из чего сложить —
      // история сжимается, набор инструментов меняется, и «что занимало контекст» станет догадкой.
      lastContext = com.vibe.agent.context.ContextBreakdown.of(wire, tools)
      lastContextWindow = t.model.contextWindow
      val toolNames = tools.map { it.name }
      if (com.vibe.agent.history.WirePrefix.toolSetChanged(lastToolNames, toolNames)) {
        systemLine(t("cache.toolsChanged", "before" to (lastToolNames?.size ?: 0), "after" to toolNames.size))
      }
      lastToolNames = toolNames
      // Кэш протухает по часам, а не по действиям: пауза дороже, чем кажется, и сказать об этом
      // надо ДО траты, а не показать её в отчёте после.
      warnAboutCache(t.model)
      lastLlmTurnStartedAtMs = System.currentTimeMillis()
      var request = wire
      var usage = com.vibe.agent.providers.TokenUsage.NONE
      var rounds = 0
      turns.chat.responses = null
      // The tool loop: an answer that calls tools gets their results and is asked again, until the model
      // answers in words, the person stops, or the ceiling is reached. Without tools it is one pass, as before.
      while (true) {
        val roundText = StringBuilder()
        val roundReasoning = StringBuilder()
        llmClient.chat(
          resolved, t.model, request, { llmCancel.get() },
          onWaiting = { attempt, delayMs, reason ->
            // Said out loud: a silent wait is indistinguishable from a hang, and the user reaches
            // for Stop exactly when the provider was about to let us back in.
            systemLine(t("retry.waiting", "attempt" to attempt, "seconds" to (delayMs / 1000),
                         "reason" to (reason ?: "")))
          },
          // Мысль модели идёт в тот же сворачиваемый блок, что и мысль ACP-агента: у прямого LLM
          // рассуждение выглядело молчанием, потому что показывать его было некуда.
          // It is also kept with the answer: a model that requires it back (ECHO_REASONING) gets it
          // in the next request.
          onThought = { noteActivity(); turns.chat.reasoning.append(it); roundReasoning.append(it); appendThought(it) },
          tools = tools,
          // The same key on every turn of the thread: the provider routes the conversation to the server with its cache.
          promptCacheKey = com.vibe.agent.providers.PromptCacheKey.of(turnThreadId ?: currentThreadId, "agent"),
        ) { delta -> noteActivity(); roundText.append(delta); appendAgentText(delta) }
        usage = usage.merge(llmClient.lastUsage())
        llmClient.lastStopReason()?.takeIf { it.abnormal }?.let { turnNote(stopNote(it)) }
        val calls = llmClient.lastToolCalls()
        // On the Responses wire the answer's own output items go back with it; an answer that ends the turn on calls
        // without results is not kept that way — the vendor refuses a call that has no output after it.
        val replay = llmClient.lastResponses()
        if (calls.isEmpty()) turns.chat.responses = replay
        if (calls.isEmpty() || llmCancel.get()) break
        if (rounds++ >= VibeAgentSettings.directToolMaxRounds) {
          turnNote(t("directTools.roundsLimit", "limit" to VibeAgentSettings.directToolMaxRounds))
          break
        }
        val results = calls.map { call ->
          // A tool round is a sign of life too: a model that only calls tools streams no text, and
          // the watchdog must not read a working turn as a hung one.
          noteActivity()
          if (call.name == com.vibe.agent.mcp.ToolSearch.NAME) searchTools(call, allTools, loaded)
          else runDirectTool(call, t.model.id)
        }
        // A search may have loaded tools: the next round offers them.
        tools = com.vibe.agent.mcp.ToolSearch.offered(allTools, loaded, VibeAgentSettings.toolSearchThreshold)
        // The round's thinking blocks travel with it: a model that requires its reasoning back gets them in the next
        // request, and Claude keeps its reasoning through the loop (ThinkingReplay decides which).
        val thinking = llmClient.lastThinking()
        turns.chat.toolRounds.add(com.vibe.agent.providers.ToolRound(roundText.toString(), calls, results,
                                                                     roundReasoning.toString().ifEmpty { null }, thinking, replay))
        request = request +
          ChatMessage("assistant", roundText.toString(), reasoning = roundReasoning.toString().ifEmpty { null }, toolCalls = calls,
                      thinking = thinking, thinkingKey = llmClient.lastThinkingKey(), responses = replay) +
          ChatMessage(com.vibe.agent.providers.ToolCalls.ROLE, "", toolResults = results)
      }
      // What the provider itself reported, and the price the owner of the key wrote down. Both may
      // be absent — then the accounting falls back to the old estimate, and says so by omission.
      turns.chat.usage = usage
      noteModelSubstitution(t.model.id, llmClient.lastAnsweredModel())
      // Цена берётся с оглядкой на срок: у модели с истёкшей акцией считать надо по costAfter.
      turns.chat.pricing = com.vibe.agent.providers.PriceValidity.effective(t.model, java.time.LocalDate.now())
      finishAgentBubble((System.currentTimeMillis() - startedAt) / 1000.0, t.model.id)
    }
    catch (e: java.io.InterruptedIOException) {
      // The partial answer stays in the transcript — a stop is not amnesia.
      finishAgentBubble((System.currentTimeMillis() - startedAt) / 1000.0, t("chat.interrupted"))
      turnNote(t("chat.stopError", "reason" to e.message))
    }
    catch (e: Exception) {
      if (failOver(t, e, startedAt)) return
      finishAgentBubble((System.currentTimeMillis() - startedAt) / 1000.0, t("chat.failed"))
      // A rejected payload must not poison every later request in this thread.
      turnThreadId?.let { if (history.dropImagesFromLastUser(it)) systemLine(t("chat.imagesDropped")) }
      turnNote(t("chat.error", "reason" to e.message))
      // Отказ провайдера и петля выглядят одинаково — оборванный ход, — а решения противоположные:
      // первое стоит продолжить, когда провайдер вернётся, второе повторять нельзя.
      val cause = com.vibe.agent.safety.StopCause.of(
        stoppedByUser = false, breakerTripped = false,
        failureMessage = e.message, finishedCleanly = false)
      if (com.vibe.agent.safety.StopCause.resumable(cause)) systemLine(t("stop.resumable"))
    }
    finally {
      finishTurn()
    }
  }

  /** The tools this turn offers, or none — with the reason said once per panel. */
  /**
   * The first message of every direct-chat turn: which project this is and where it lives.
   *
   * Read from the open project and from `.git/HEAD` — no index, no VCS plugin, nothing that can
   * hang inside a turn. The branch is best-effort: an unreadable head simply leaves that line out.
   */
  private fun workspaceMessage(): ChatMessage {
    val root = project.basePath.orEmpty()
    val branch = root.takeIf { it.isNotEmpty() }?.let {
      runCatching { java.nio.file.Files.readString(java.nio.file.Path.of(it, ".git", "HEAD")) }.getOrNull()
    }
    return ChatMessage("system", com.vibe.agent.context.WorkspaceBriefing.text(
      com.vibe.agent.context.WorkspaceBriefing.Workspace(
        name = project.name,
        root = root,
        branch = com.vibe.agent.context.WorkspaceBriefing.branchOfHead(branch),
        openFile = runCatching { com.vibe.agent.mcp.IdeEditorFacts.selected(project)?.path }.getOrNull(),
      )))
  }

  private fun directToolSpecs(target: ChatTarget.Model): List<com.vibe.agent.providers.ToolSpec> {
    if (!VibeAgentSettings.directToolsEnabled) return emptyList()
    val wire = ProvidersService.protocolFor(target.provider.protocol, target.model.protocol)
    val support = llmClient.toolSupport(target.model, wire)
    if (support != com.vibe.agent.providers.ModelQuirks.ToolSupport.YES) {
      if (!directToolsNoted) {
        directToolsNoted = true
        systemLine(
          if (support == com.vibe.agent.providers.ModelQuirks.ToolSupport.ONLY_ON_RESPONSES) t("directTools.responsesOnly", "model" to target.model.id)
          else t("directTools.noToolsModel", "model" to target.model.id))
      }
      return emptyList()
    }
    val specs = directTools.specs { e ->
      if (!directToolsNoted) {
        directToolsNoted = true
        systemLine(t("directTools.unavailable", "reason" to (e.message ?: "")))
      }
    }
    // The client takes the reasoning off for such a turn; the dial still shows a level, so the chat says why it did not
    // apply and how to have both.
    val asked = com.vibe.agent.providers.ReasoningMode.levelOf(VibeAgentSettings.reasoningLevel)
    if (specs.isNotEmpty() && asked != com.vibe.agent.providers.ReasoningMode.Level.OFF && !reasoningYieldNoted &&
        llmClient.reasoningYieldsToTools(target.model, wire)) {
      reasoningYieldNoted = true
      systemLine(t("directTools.reasoningYields", "model" to target.model.id))
    }
    return specs
  }

  /**
   * The model's tool search, answered inside the IDE: nothing leaves the machine, nothing to approve.
   * Found tools join the thread's loaded set and are offered from the next round on.
   */
  private fun searchTools(
    call: com.vibe.agent.providers.ToolCall,
    all: List<com.vibe.agent.providers.ToolSpec>,
    loaded: MutableSet<String>,
  ): com.vibe.agent.providers.ToolResult {
    val query = (call.argumentsObject()["query"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull.orEmpty()
    val found = com.vibe.agent.mcp.ToolSearch.search(query, all)
    loaded.addAll(found.map { it.name })
    systemLine(t("directTools.searched", "query" to query,
                 "found" to (found.joinToString { it.name }.ifEmpty { t("directTools.searchedNothing") })))
    return com.vibe.agent.providers.ToolResult(call.id, call.name, com.vibe.agent.mcp.ToolSearch.answer(found))
  }

  /**
   * One call of the model: named in the feed and in the audit journal (the tool, never its arguments).
   * Reading runs; writing needs a trusted project and the person's yes, asked for this very call.
   */
  private fun runDirectTool(call: com.vibe.agent.providers.ToolCall, model: String): com.vibe.agent.providers.ToolResult {
    val label = com.vibe.agent.ui.ToolCallLabel.of(call.name, call.arguments)
    toolCard(t("directTools.call", "tool" to label))
    showWorking(WorkingLine.Kind.TOOL, label)
    composer.setStatus(com.vibe.agent.ui.composer.StatusDot.State.TOOL, label)
    val actor = com.vibe.agent.audit.AuditActor(com.vibe.agent.audit.AuditActor.Kind.AGENT, agent = model)
    audit?.append(AuditEvent(System.currentTimeMillis(), AuditEvent.Action.TOOL_CALL_START, ok = true, actor = actor,
                             callId = call.id, model = model, meta = mapOf("tool" to call.name)))
    val started = System.currentTimeMillis()
    // Путь для полоски НЕ берётся из подписи вызова: она обрезает длинные пути многоточием, и
    // список изменённого хранил бы «…/main.ts» вместо файла. Единственный источник правды —
    // журнал правок: его пишет тот, кто реально изменил байты.
    val result = directTools.execute(call) { asked, risk ->
      val verdict = com.vibe.agent.mcp.McpAccess.verdict(
        risk, com.intellij.ide.trustedProjects.TrustedProjects.isProjectTrusted(project), allowWrite = true, allowExecute = true)
      val refusal = com.vibe.agent.mcp.McpAccess.refusal(verdict)
      val mode = com.vibe.agent.mcp.PermissionMode.of(VibeAgentSettings.permissionMode)
      // Необратимое спрашивается ВСЕГДА, даже на автопилоте: `npm test` без вопроса — это удобство,
      // `rm -rf ~` без вопроса — потерянный день, и различать их обязаны мы, а не человек, который
      // научился жать «да» не читая (ревизия 18.09.2026).
      val danger = if (risk == com.vibe.agent.mcp.McpProtocol.Risk.EXECUTE)
        com.vibe.agent.mcp.ShellRisk.dangerOf(asked.arguments) else null
      val decision = if (danger != null) com.vibe.agent.mcp.PermissionMode.Decision.ASK else mode.decide(risk)
      when {
        refusal != null -> false.also { systemLine(refusal) }
        decision == com.vibe.agent.mcp.PermissionMode.Decision.ALLOW -> true
        decision == com.vibe.agent.mcp.PermissionMode.Decision.DENY ->
          false.also { systemLine(t("permission.denied.plan")) }
        else -> askOnEdt {
          val question = t("directTools.approve", "tool" to asked.name, "args" to asked.arguments.take(DIRECT_TOOL_ARGS_PREVIEW))
          val full = if (danger == null) question else t("directTools.dangerous", "reason" to danger) + "\n\n" + question
          Messages.showYesNoDialog(project, full, t("directTools.approveTitle"), Messages.getQuestionIcon()) == Messages.YES
        }.also { approved ->
          audit?.append(AuditEvent(System.currentTimeMillis(), AuditEvent.Action.PERMISSION, ok = approved,
                                   actor = com.vibe.agent.audit.AuditActor.HUMAN, callId = asked.id, meta = mapOf("tool" to asked.name)))
        }
      }
    }
    // Полоска перечитывает журнал после каждого вызова: писать в неё отдельно значит завести
    // второй счётчик, который однажды разойдётся с первым.
    refreshChangedFiles()
    audit?.append(AuditEvent(System.currentTimeMillis(), AuditEvent.Action.TOOL_CALL_DONE, ok = !result.isError, actor = actor,
                             callId = call.id, model = model, latencyMs = System.currentTimeMillis() - started,
                             meta = mapOf("tool" to call.name)))
    return result
  }

  /**
   * Говорит, во что обойдётся этот ход из-за паузы, и когда окупится запись кэша.
   *
   * Три состояния — три разных сообщения, и «ещё не писали» намеренно отделено от «протух»: во
   * втором случае человек только что заплатил за перечитывание всего контекста, и знать об этом
   * он должен по имени, а не по счёту в конце недели.
   */
  private fun warnAboutCache(model: ModelEntry) {
    val now = System.currentTimeMillis()
    val ttl = model.cacheTtl
    when (com.vibe.agent.providers.CacheWindow.state(lastLlmTurnStartedAtMs, ttl, now)) {
      com.vibe.agent.providers.CacheWindow.State.COLD ->
        systemLine(t("cache.cold", "minutes" to (now - lastLlmTurnStartedAtMs) / 60_000,
                     "pays" to com.vibe.agent.providers.CacheWindow.paysOffFromRequest(
                       com.vibe.agent.providers.PriceValidity.effective(model, java.time.LocalDate.now()), ttl)))
      com.vibe.agent.providers.CacheWindow.State.EXPIRING ->
        systemLine(t("cache.expiring",
                     "seconds" to com.vibe.agent.providers.CacheWindow.leftMs(lastLlmTurnStartedAtMs, ttl, now) / 1000))
      // Тёплый кэш молчит: строка на каждом ходе о том, что всё хорошо, перестаёт читаться, и
      // вместе с ней перестанет читаться та, что про потерю.
      else -> {}
    }
  }

  /**
   * The running agent with [threadId]'s own session made current — one session per thread (decision №96).
   *
   * One process and one handshake per agent; each thread opens its session on that connection the first time it needs
   * it, resuming the one remembered for it, and later turns only switch to it. Serialized by [sessionLock]: a tab
   * switched while a turn is starting must not make another thread's session current under the turn's prompt.
   */
  private fun ensureClient(config: AgentServerConfig, threadId: String): AcpClient = synchronized(sessionLock) {
    val running = synchronized(clientLock) {
      check(!disposed) { t("chat.panelClosed") }
      client?.takeIf { it.isAlive && it.capabilities != null && clientConfig == config }
    }
    if (running != null) {
      val known = threadSessions[threadId]
      if (known != null && running.switchTo(known)) return running
      return openThreadSession(running, config, threadId, handshake = false)
    }
    val fresh = synchronized(clientLock) {
      // Checked INSIDE the lock: dispose() completes under it, so a racing turn thread
      // cannot spawn an orphan process after the panel is gone.
      check(!disposed) { t("chat.panelClosed") }
      client?.stop()
      threadSessions.clear()
      systemLine(t("chat.agentStarting", "command" to (config.command + " " + config.args.joinToString(" "))))
      // "dir" записи — рабочая папка агента в монорепо: он видит её своим корнем и не ходит
      // по соседним пакетам. Поле было описано в сиде с самого начала и не читалось.
      val workingDir = config.dir?.let { d -> project.basePath?.let { java.nio.file.Path.of(it, d).toString() } }
        ?: project.basePath
      AcpClient(config, workingDir, this, advertiseTerminalExec = VibeAgentSettings.terminalEnabled).also {
        it.start()
        client = it
        clientConfig = config
        auditAgentStart(config, workingDir)
      }
    }
    return openThreadSession(fresh, config, threadId, handshake = true)
  }

  /**
   * Opens [threadId]'s session on [c] — with the handshake when the process is new — resuming the session remembered
   * for the thread. A sign-in the agent asks for is handled here, whichever thread first meets it. Returns the client
   * the session is on — a new one when the person chose to reconnect after signing in elsewhere.
   */
  private fun openThreadSession(c: AcpClient, config: AgentServerConfig, threadId: String, handshake: Boolean): AcpClient {
    val handshakeSec = VibeAgentSettings.handshakeTimeoutSec.toLong()
    try {
      // Возобновляем прошлую сессию этого треда, если она известна и агент это умеет: лента
      // переживала перезапуск IDE и раньше, а агент — нет, и человек пересказывал контекст
      // заново. Отказ агента возобновлять обрабатывается внутри — открывается новая.
      val remembered = AcpSessionMemory.recall(config.name, threadId, project.basePath)
      try {
        (if (handshake) c.initializeAndOpenSession(remembered) else c.openSession(remembered)).get(handshakeSec, TimeUnit.SECONDS)
      }
      catch (e: java.util.concurrent.ExecutionException) {
        if (!com.vibe.agent.acp.AgentAuth.isAuthRequired(e)) throw e
        // The agent wants a sign-in first: its ways, exactly as it declared them (decision №82).
        val choice = askOnEdt {
          com.vibe.agent.acp.AgentLoginDialog(project, config, c.authMethods).let { if (it.showAndGet()) it.choice() else null }
        }
        when (choice) {
          is com.vibe.agent.acp.AgentLoginDialog.Choice.Authenticate -> {
            signIn(c, config, choice.method)
            c.openSession(remembered).get(handshakeSec, TimeUnit.SECONDS)
          }
          // Signed in outside the IDE: what the spec prescribes next is a new connection and handshake.
          com.vibe.agent.acp.AgentLoginDialog.Choice.Reconnect -> {
            drop(c)
            return ensureClient(config, threadId)
          }
          null -> {
            drop(c)
            throw IllegalStateException(t("auth.notLoggedIn", "agent" to config.name))
          }
        }
      }
      val opened = checkNotNull(c.sessionId) { "agent returned no sessionId" }
      threadSessions[threadId] = opened
      AcpSessionMemory.remember(config.name, threadId, opened)
      // Not the session asked for — none remembered, expired, or the agent cannot resume: it knows
      // nothing of this thread's plan, so the first turn in it carries the plan.
      if (remembered == null || opened != remembered) planCarryThreads.add(threadId)
    }
    catch (e: TimeoutException) {
      drop(c)
      throw IllegalStateException(t("chat.handshakeTimeout", "seconds" to handshakeSec, "path" to AcpConfig.configPath()))
    }
    systemLine(t("chat.sessionOpen") + (c.modes?.let { m -> t("chat.modeSuffix", "mode" to (m.available.firstOrNull { it.id == m.currentModeId }?.name ?: m.currentModeId)) } ?: ""))
    // Пилюли режима и тумблеров — по СВЕЖЕЙ сессии, здесь, а не на пути хода: после
    // переподключения они иначе остаются пустыми до следующего оплаченного сообщения.
    showSessionPickers(c)
    // A fresh session starts a fresh context — drop the stale usage chip until the agent reports anew.
    SwingUtilities.invokeLater { composer.setUsage(percent = null, tooltip = null, warn = false) }
    return c
  }

  /** The pickers show the client's current session — read now, painted on the EDT. */
  private fun showSessionPickers(c: AcpClient?) {
    val modes = c?.modes
    val options = c?.configOptions
    SwingUtilities.invokeLater {
      modePicker.setModes(modes)
      configPicker.setOptions(options)
    }
  }

  /**
   * After the open thread changed: the agent's current session follows it, and the pickers show that thread's own
   * modes and switches. A thread the running agent has no session for yet gets one now — opening a session costs no
   * model request, and pickers left empty until a paid message are what [openThreadSession] exists to avoid. While a
   * turn runs nothing is switched — its session must stay current under its prompt — and the pickers of any other
   * thread stay empty until it ends.
   */
  private fun followThreadSession(threadId: String) {
    val config = synchronized(clientLock) { clientConfig.takeIf { client?.isAlive == true } }
    val agentTarget = (target as? ChatTarget.Agent)?.config
    if (config == null || agentTarget != config) return
    if (turnInFlight.get()) {
      if (threadId != turnThreadId) showSessionPickers(null)
      return
    }
    ApplicationManager.getApplication().executeOnPooledThread {
      synchronized(sessionLock) {
        if (disposed || turnInFlight.get() || currentThreadId != threadId) return@executeOnPooledThread
        runCatching { ensureClient(config, threadId) }
          .onSuccess { showSessionPickers(it) }
          .onFailure { systemLine(t("chat.reconnectFailed", "reason" to (it.message ?: it.javaClass.simpleName))) }
      }
    }
  }

  /**
   * The thread is gone from view — deleted, or its tab closed — so its session is closed on the agent (decision №96).
   * The remembered id is kept: a thread opened again resumes it, and one restored from the trash too. Not the session
   * of a running turn.
   */
  private fun closeThreadSession(threadId: String) {
    if (turnInFlight.get() && turnThreadId == threadId) return
    val sid = threadSessions.remove(threadId) ?: return
    val c = client ?: return
    ApplicationManager.getApplication().executeOnPooledThread {
      runCatching { c.closeSession(sid).get(VibeAgentSettings.handshakeTimeoutSec.toLong(), TimeUnit.SECONDS) }
        .onFailure { systemLine("[acp] session/close: ${(it.cause ?: it).message}") }
    }
  }

  /** Stops a client that did not reach a session, and forgets it if it is still the current one. */
  private fun drop(stale: AcpClient) {
    synchronized(clientLock) {
      stale.stop()
      if (client === stale) { client = null; clientConfig = null; threadSessions.clear() }
    }
  }

  /**
   * `authenticate` with a method the protocol drives, recorded in the audit. It waits for a person
   * rather than a program — the agent may be waiting on a browser — so its bound is its own, not the
   * handshake's. What is recorded is which method and whether the agent accepted it, never a credential.
   */
  private fun signIn(client: AcpClient, config: AgentServerConfig, method: com.vibe.agent.acp.AuthMethod) {
    systemLine(t("auth.loggingIn", "agent" to config.name, "method" to method.name))
    val failure = runCatching { client.authenticate(method.id).get(SIGN_IN_TIMEOUT_MIN, TimeUnit.MINUTES) }.exceptionOrNull()
    val reason = failure?.let { (it.cause ?: it).message ?: it.javaClass.simpleName }
    audit?.append(AuditEvent(System.currentTimeMillis(), AuditEvent.Action.AGENT_AUTH, ok = failure == null,
      actor = com.vibe.agent.audit.AuditActor.HUMAN,
      meta = buildMap {
        put("agent", config.name)
        put("method", method.id)
        reason?.let { put("error", it.take(DESTRUCTIVE_PREVIEW_LEN)) }
      }))
    if (failure != null) {
      drop(client)
      throw IllegalStateException(t("auth.failed", "agent" to config.name, "reason" to reason), failure)
    }
    systemLine(t("auth.loggedIn", "agent" to config.name))
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
    // Журнал правок живёт на ПРОЕКТ, а не на тред: файл, изменённый в одном разговоре, остаётся
    // изменённым и в другом — это состояние диска, а не переписки. Полоска просто перечитывает его.
    if (id != currentThreadId) refreshChangedFiles()
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
    // The model belongs to the TAB. A tab that has one is restored to it; a fresh tab adopts the
    // one in the picker and writes it down at once, so a later switch in a neighbouring tab cannot
    // move it (the choice used to exist only as the panel's, and the store's record followed
    // whatever tab happened to be open).
    val wanted = thread.state.targetId
    if (wanted != null) selectTargetById(wanted)
    else target?.let { history.updateState(id, ThreadState(it.id)) }
    drafts.remove(id)?.let { composer.restoreDraft(it) }
    updateTabsStrip()
    saveTabs()
    rail?.currentThreadId = id
    // Per-CHAT state must not travel between chats: a new conversation inheriting the previous
    // one's token count would hit the session ceiling on its first message, and the «окно почти
    // полное» line would already be said and therefore never repeated.
    resetChatCounters()
    announceUnfinishedPlan(id)
    followThreadSession(id)
    composer.focusInput()
  }

  /** VibeIDE: beyond the limit the OLDEST tab is silently evicted (never the active or streaming one). */
  private fun evictTabs() {
    while (openTabIds.size > VibeChatSettings.maxOpenTabs) {
      val victim = openTabIds.firstOrNull { it != currentThreadId && it != turnThreadId } ?: return
      openTabIds.remove(victim)
      drafts.remove(victim)
      closeThreadSession(victim)
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
    sessionSignals.remove(id)
    closeThreadSession(id)
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
      removed.forEach { drafts.remove(it); closeThreadSession(it) }
      if (currentThreadId in removed) {
        val next = openTabIds.firstOrNull() ?: history.create(project.basePath, project.name).id
        activateThread(next, saveCurrentDraft = false)
      }
    }
    updateTabsStrip()
    saveTabs()
    updateLanding()
  }

  /**
   * Переподключение внешнего агента без перезапуска IDE.
   *
   * Соединение с чужим процессом рвётся способами, которых у нас нет: процесс жив, а сессия
   * потеряна; адаптер перезапустил дочерний CLI; ушла сеть у агента, работающего по сети. До
   * сих пор единственным лечением был перезапуск всей IDE, потому что сессия открывается по пути
   * хода — то есть проверить, вылечилось ли, можно было только оплаченным сообщением модели.
   *
   * Здесь соединение закрывается и открывается заново явно: старый процесс убивается, новый
   * поднимается и делает рукопожатие, а результат виден строкой в ленте — без единого запроса к
   * модели. Режимы сессии сбрасываются: у новой сессии свои.
   *
   * Возвращает false, если переподключать нечего (агент ни разу не запускался).
   */
  fun reconnectAgent(): Boolean {
    val config = synchronized(clientLock) {
      if (disposed) return false
      val config = clientConfig ?: return false
      client?.stop()
      client = null
      clientConfig = null
      threadSessions.clear()
      config
    }
    val threadId = currentThreadId
    SwingUtilities.invokeLater { modePicker.setModes(null); configPicker.setOptions(null) }
    systemLine(t("chat.reconnecting"))
    // Рукопожатие блокирует до ответа чужого процесса — не на EDT.
    com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
      try {
        ensureClient(config, threadId)
      }
      catch (e: Exception) {
        systemLine(t("chat.reconnectFailed", "reason" to (e.message ?: e.javaClass.simpleName)))
      }
    }
    return true
  }

  /** What «Выйти из учётной записи агента» found. */
  enum class Logout { NO_AGENT, UNSUPPORTED, CANCELLED, STARTED }

  /**
   * The protocol's `logout` for the running agent, once the person confirms it.
   *
   * The agent is stopped afterwards: its session was opened under the account it has just left, and
   * the next message starts over — with a sign-in, if the agent asks for one. The remembered session
   * is kept on purpose (see [AcpSessionMemory]): an agent that will not resume it opens a new one.
   */
  fun logoutAgent(): Logout {
    val (running, config) = synchronized(clientLock) { client to clientConfig }
    if (running == null || config == null || !running.isAlive) return Logout.NO_AGENT
    if (running.capabilities?.logout != true) return Logout.UNSUPPORTED
    val sure = askOnEdt {
      Messages.showYesNoDialog(project, t("logout.confirm", "agent" to config.name), t("logout.title"),
                               t("logout.yes"), t("common.cancel"), Messages.getQuestionIcon()) == Messages.YES
    }
    if (!sure) return Logout.CANCELLED
    ApplicationManager.getApplication().executeOnPooledThread {
      val failure = runCatching { running.logout().get(VibeAgentSettings.handshakeTimeoutSec.toLong(), TimeUnit.SECONDS) }
        .exceptionOrNull()
      val reason = failure?.let { (it.cause ?: it).message ?: it.javaClass.simpleName }
      audit?.append(AuditEvent(System.currentTimeMillis(), AuditEvent.Action.AGENT_LOGOUT, ok = failure == null,
        actor = com.vibe.agent.audit.AuditActor.HUMAN,
        meta = buildMap {
          put("agent", config.name)
          reason?.let { put("error", it.take(DESTRUCTIVE_PREVIEW_LEN)) }
        }))
      if (failure != null) {
        systemLine(t("logout.failed", "agent" to config.name, "reason" to reason))
        return@executeOnPooledThread
      }
      drop(running)
      SwingUtilities.invokeLater { modePicker.setModes(null); configPicker.setOptions(null) }
      systemLine(t("logout.done", "agent" to config.name))
    }
    return Logout.STARTED
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
    val chat = turns.chat
    chat.message = null
    chat.uiConsumed = 0
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
      chat.thoughts?.let { messages.add(it) }
      // A console inside the block of a wave's step went away with that block; it comes back here, in the feed itself.
      terminalConsoles.values.filter { it.parent?.parent == null }.forEach { messages.add(it) }
    }
    val live = if (liveTurnHere) chat.text.toString() else ""
    if (live.isNotEmpty()) {
      // The stream continues into a fresh row; finished text is already a stored record.
      val m = AgentMessage()
      m.append(live)
      chat.uiConsumed = live.length
      chat.message = m
      messages.add(m.row)
      recordRows.add(m.row)
    }
    // Steps of a wave still running continue in fresh blocks: what they already said as tool cards is in the records
    // above, and their answers are text not yet stored.
    if (liveTurnHere) runningWave.filter { !it.done }.forEach { step ->
      step.feed = WaveBlockFeed(step.label.orEmpty()).also { it.show() }
      step.thoughts = null
      val text = step.text.toString()
      val m = if (text.isNotEmpty()) AgentMessage().also { it.append(text) } else null
      step.message = m
      step.uiConsumed = text.length
      m?.let { step.feed.addRecord(it.row) }
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
    if (choice < 0) return
    val chosen = pipelines[choice]
    // Смета ДО запуска: потолки расхода срабатывают уже на середине прогона, а разница между одним
    // ходом и шестью ролями видна только тому, кто за неё однажды заплатил.
    if (!confirmEstimate(chosen)) return
    val runAgent = (target as? ChatTarget.Agent)?.config ?: agent
    if (chosen.dynamic) {
      runDynamicPipeline(chosen, runAgent)
      return
    }
    runPipeline(chosen, runAgent, resumeSkip(chosen) ?: return)
  }

  /**
   * What to skip: nothing for a fresh run, the steps an interrupted run of the same pipeline finished when the person
   * chooses to continue, null when they cancel.
   *
   * Asked, not decided: the files may have moved on since the interruption, and only the person knows
   * whether the finished steps still describe the working tree.
   */
  private fun resumeSkip(pipeline: com.vibe.agent.pipelines.Pipeline): Set<Int>? {
    val point = com.vibe.agent.runs.PipelineResume.find(runs.runs(), pipeline.id, pipeline.steps.size) ?: return emptySet()
    val answer = Messages.showDialog(
      project,
      t("pipeline.resume.prompt", "name" to pipeline.name, "done" to point.done.size, "total" to pipeline.steps.size,
        "step" to (point.fromStep + 1)),
      t("pipeline.resume.title"),
      arrayOf(t("pipeline.resume.continue", "step" to (point.fromStep + 1)), t("pipeline.resume.restart"), t("common.cancel")),
      0, Messages.getQuestionIcon(),
    )
    return when (answer) {
      0 -> point.done
      1 -> emptySet()
      else -> null
    }
  }

  /**
   * Показывает смету прогона и спрашивает.
   *
   * Смета считается по СВОЕМУ журналу расхода: сколько эта роль стоила у вас в прошлый раз. Роль,
   * которую ещё не гоняли, называется отдельно и в сумму не входит — подменить её средним по чужим
   * ролям значит соврать с точностью до порядка («explore» и «qa» отличаются в разы».
   */
  private fun confirmEstimate(pipeline: com.vibe.agent.pipelines.Pipeline): Boolean {
    val model = (target as? ChatTarget.Model)?.model
    val estimate = com.vibe.agent.budget.RunEstimate.of(
      steps = pipeline.steps.map { it.role },
      // Месяц истории: смета по вчерашнему дню зависела бы от того, гоняли ли вчера эту роль.
      entries = com.vibe.agent.budget.VibeSpendService.getInstance().entries(com.vibe.agent.budget.SpendLedger.MONTH_MS),
      pricePerMillionInput = model?.let { com.vibe.agent.providers.PriceValidity.effective(it, java.time.LocalDate.now()) }?.input,
      currency = model?.pricing?.currency,
    )
    // Нечего показывать — нечего и спрашивать: пустая смета это лишний диалог, а не осторожность.
    if (estimate.tokens == 0L && estimate.unknownRoles.size == estimate.totalSteps) return true
    val money = estimate.cost?.let { " ≈ %.2f %s".format(it, estimate.currency.orEmpty()) }.orEmpty()
    val unknown = if (estimate.partial) "\n" + t("estimate.unknown", "roles" to estimate.unknownRoles.joinToString()) else ""
    val answer = Messages.showYesNoDialog(
      project,
      t("estimate.body", "name" to pipeline.name, "steps" to estimate.totalSteps,
        "tokens" to "%,d".format(estimate.tokens), "money" to money) + unknown,
      t("estimate.title"),
      t("estimate.run"),
      t("common.cancel"),
      Messages.getQuestionIcon(),
    )
    return answer == Messages.YES
  }

  /**
   * Потолки шага: прекращает шаг, выбравший свои токены или свои вызовы инструментов.
   *
   * Зовётся из двух точек потока агента — `usage_update` (токены) и `tool_call` (вызовы), — поэтому
   * оба числа необязательны: каждая точка знает своё. Считается только у шага, который потолок
   * назвал: у остальных это была бы работа ради нуля на каждом кадре.
   *
   * Прекращение — это `session/cancel` агенту, тот же механизм, что у кнопки «Стоп». Отличает их
   * [TurnState.limitHit]: иначе шаг, остановленный своим же потолком, отчитался бы как остановленный
   * человеком, и в ленте это выглядело бы как чужое вмешательство.
   */
  private fun enforceStepLimits(turn: TurnState, usedTokens: Long? = null, toolCalls: Int? = null) {
    val step = turn.limits ?: return
    if (turn.limitHit != null) return // потолок уже сработал, второй раз отменять нечего
    // Первое сообщение окна внутри шага задаёт точку отсчёта: дальше считается ПРИРОСТ.
    if (usedTokens != null && turn.tokensBase < 0) turn.tokensBase = usedTokens
    val spentInStep = if (usedTokens == null || turn.tokensBase < 0) 0L
                      else (usedTokens - turn.tokensBase).coerceAtLeast(0L)
    val verdict = com.vibe.agent.pipelines.StepLimits.check(
      usedTokens = spentInStep,
      toolCalls = toolCalls ?: turn.toolCallCount.get(),
      maxTokens = step.maxTokens,
      maxSteps = step.maxSteps,
    )
    if (verdict == com.vibe.agent.pipelines.StepLimits.Verdict.OK) return
    turn.limitHit = verdict
    systemLine(when (verdict) {
      com.vibe.agent.pipelines.StepLimits.Verdict.TOKENS ->
        t("pipeline.limit.tokens", "role" to step.role, "limit" to "%,d".format(step.maxTokens ?: 0))
      else ->
        t("pipeline.limit.steps", "role" to step.role, "limit" to (step.maxSteps ?: 0))
    })
    audit?.append(AuditEvent(System.currentTimeMillis(), AuditEvent.Action.CIRCUIT_BREAKER_OPENED, ok = false,
                             actor = agentActor(turn),
                             meta = mapOf("reason" to verdict.name, "role" to step.role)))
    // Off the EDT по той же причине, что и у «Стоп»: send() синхронизирован и может ждать записи.
    // The step's own session: a neighbour in its wave keeps working.
    val c = client ?: return
    val session = turn.sessionId ?: return
    ApplicationManager.getApplication().executeOnPooledThread { runCatching { c.cancel(session) } }
  }

  /**
   * The step hit its ceiling: ask it to hand over instead of cutting it off mid-edit.
   *
   * A ceiling used to end the step with `session/cancel`, and the next step inherited a half-made change with no idea
   * what had been decided or tried — `recover-or-skip` guessed. One extra request costs far less than that guess: the
   * step brings the work to a green state and writes five fields, of which three (decisions, dead ends, next) exist
   * nowhere on disk. Written to the run folder and handed to the next step in place of the answer's tail
   * (Autopilot's handoff, 17.09.2026).
   *
   * Asked in the step's OWN session: the step's work is only there, and a step with a fresh context never showed it to
   * the chat's session.
   *
   * One handoff per step. The request itself is not capped by the step's ceiling — the ceiling has already fired — but
   * it is bounded by the handshake timeout, and a failure returns null, which means the old behaviour: the step failed.
   */
  private fun askHandoff(turn: TurnState, header: String, runId: String?, index: Int): String? {
    val c = synchronized(clientLock) { client } ?: return null
    if (!c.isAlive) return null
    val session = turn.sessionId ?: return null
    val buffer = turn.answer ?: return null
    systemLine(t("pipeline.handoff.asking", "header" to header))
    // The handoff replaces the answer: what the step said before its ceiling is superseded by what it hands over.
    buffer.setLength(0)
    val startedAt = System.currentTimeMillis()
    val asked = runCatching {
      c.prompt(session, com.vibe.agent.pipelines.PipelinesFile.HANDOFF_PROMPT)
        .get(VibeAgentSettings.handshakeTimeoutSec.toLong() * HANDOFF_TIMEOUT_FACTOR, TimeUnit.SECONDS)
    }
    finishAgentBubble((System.currentTimeMillis() - startedAt) / 1000.0, header, turn)
    val text = buffer.toString().trim()
    if (asked.isFailure || text.isEmpty()) {
      systemLine(t("pipeline.handoff.failed", "header" to header,
                   "reason" to (asked.exceptionOrNull()?.let { (it.cause ?: it).message } ?: t("pipeline.noText"))))
      return null
    }
    val file = runId?.let { com.vibe.agent.pipelines.RunFolder.write(project.basePath, it, "handoff-${index + 1}.md", text) }
    systemLine(t("pipeline.handoff.written", "header" to header, "file" to (file?.toString() ?: "—")))
    audit?.append(AuditEvent(System.currentTimeMillis(), AuditEvent.Action.STEP_HANDOFF, ok = true, actor = agentActor(turn),
                             meta = mapOf("role" to (turn.role ?: ""), "step" to (index + 1).toString())))
    return text
  }

  /**
   * Гейт приёмки шага: хук `pipelineStepEnd` решает, годится ли черновик.
   *
   * Возвращает null, когда гейта нет, — и это не то же самое, что «принят»: без проверки принимать
   * нечем, поэтому шаги эскалации в таком пайплайне выполняются все. Отказ гейта здесь не
   * останавливает прогон: он ровно наоборот — включает следующий, более дорогой шаг.
   */
  private fun runStepGate(
    pipeline: com.vibe.agent.pipelines.Pipeline,
    step: com.vibe.agent.pipelines.PipelineStep,
    index: Int,
    answer: String,
    turn: TurnState,
  ): Boolean? {
    val context = buildJsonObject {
      put("pipeline", pipeline.id)
      put("step", index + 1)
      put("role", step.role)
      step.wave?.let { put("wave", it) }
      step.model?.let { put("model", it) }
      // What the step said about itself: a gate that judges only the diff cannot tell «could not» from «did».
      turn.report?.let { report ->
        put("status", report.status.name)
        if (report.blockers.isNotEmpty()) put("blockers", JsonArray(report.blockers.map { JsonPrimitive(it) }))
        if (report.concerns.isNotEmpty()) put("concerns", JsonArray(report.concerns.map { JsonPrimitive(it) }))
      }
      // Что ответило на самом деле: гейт судит о полученном ответе, а не о заказанном.
      if (step.model != null) turn.llm?.lastAnsweredModel()?.let { put("answeredModel", it) }
      // Ответ шага, обрезанный: гейту нужен вердикт по содержанию, а не весь транскрипт в stdin.
      put("answer", answer.take(GATE_ANSWER_CHARS))
    }
    // Спрашивается ДО запуска: гейт, разрешивший шаг молча (код 0, пустой вывод), по одному лишь
    // решению неотличим от отсутствующего гейта — а это противоположные ответы.
    if (!hooks.has(HookEvent.PIPELINE_STEP_END)) return null
    val decision = hooks.run(HookEvent.PIPELINE_STEP_END, null, null, turn.changedPaths.toList(), context)
    // Сломанный гейт (любой код кроме 0 и 2, таймаут) принять не может: считаем, что гейта не было.
    if (decision.brokenHooks.isNotEmpty() && !decision.flagged) return null
    audit?.append(AuditEvent(System.currentTimeMillis(), AuditEvent.Action.HOOK, ok = !decision.flagged,
                             actor = agentActor(turn),
                             meta = mapOf("event" to HookEvent.PIPELINE_STEP_END.wire,
                                          "pipeline" to pipeline.id, "step" to (index + 1).toString())))
    return if (decision.flagged) {
      systemLine(t("pipeline.gate.rejected", "reason" to (decision.agentMessage ?: "")))
      false
    }
    else {
      systemLine(t("pipeline.gate.accepted"))
      true
    }
  }

  /**
   * Один шаг пайплайна на СВОЕЙ модели — прямым запросом к провайдеру, без ACP-агента.
   *
   * Разговор не ведётся: модель получает ровно текст шага (роль, задача, приёмка, итог прошлого
   * шага) и отвечает текстом. Истории треда здесь нет намеренно — шаг обязан судить о том, что ему
   * дали, а не о том, что человек обсуждал в этом чате час назад.
   *
   * Расход шага учитывается там же, где расход обычного хода: иначе каскад «дешёвая модель, потом
   * дорогая» нельзя было бы сравнить с одной дорогой — а он ради этого сравнения и существует.
   */
  /**
   * Holds an `offPeak` step until its model leaves the price peak; false — the run was cancelled.
   *
   * Visible and skippable, as in VibeIDE: a run that silently stops for hours looks hung. The wait ends by itself at
   * the off-peak minute, by «Запустить сейчас», or by cancelling — the notification's button or Stop. A model with
   * no price by the hour starts at once and says why: the field cannot defer to a schedule nobody declared.
   */
  private fun waitForOffPeak(pipeline: com.vibe.agent.pipelines.Pipeline, index: Int, step: com.vibe.agent.pipelines.PipelineStep): Boolean {
    val providerId = step.provider ?: return true
    val modelId = step.model ?: return true
    val schedule = providers.firstOrNull { it.id == providerId }?.models?.firstOrNull { it.id == modelId }
      ?.pricing?.timeOfDay?.takeIf { it.stated }
    if (schedule == null) {
      systemLine(t("pipeline.offPeak.noSchedule", "role" to step.role, "model" to modelId))
      return true
    }
    val now = java.time.Instant.now()
    val until = schedule.nextOffPeak(now) ?: return true
    if (!until.isAfter(now)) return true
    val text = t("pipeline.offPeak.waiting", "pipeline" to pipeline.name, "role" to step.role, "model" to modelId,
                 "time" to java.time.format.DateTimeFormatter.ofPattern("HH:mm").withZone(java.time.ZoneOffset.UTC).format(until))
    systemLine(text)
    audit?.append(AuditEvent(System.currentTimeMillis(), AuditEvent.Action.HOOK, ok = true, actor = com.vibe.agent.audit.AuditActor.IDE,
                             meta = mapOf("event" to OFF_PEAK_WAIT, "pipeline" to pipeline.id, "step" to (index + 1).toString(),
                                          "model" to modelId, "until" to until.toString())))
    val decision = java.util.concurrent.CompletableFuture<Boolean>()
    val notification = com.intellij.notification.NotificationGroupManager.getInstance()
      .getNotificationGroup(com.vibe.agent.ui.VibeNotifications.AGENT)
      .createNotification(text, com.intellij.notification.NotificationType.INFORMATION)
      .addAction(com.intellij.notification.NotificationAction.createSimpleExpiring(t("pipeline.offPeak.runNow")) { decision.complete(true) })
      .addAction(com.intellij.notification.NotificationAction.createSimpleExpiring(t("pipeline.offPeak.cancel")) { decision.complete(false) })
    SwingUtilities.invokeLater { if (!disposed) notification.notify(project) }
    try {
      while (!decision.isDone) {
        if (llmCancel.get() || disposed) return false
        if (!java.time.Instant.now().isBefore(until)) return true
        Thread.sleep(OFF_PEAK_POLL_MS)
      }
      return decision.get()
    }
    finally {
      notification.expire()
    }
  }

  private fun runModelStep(turn: TurnState, providerId: String, modelId: String, stepPrompt: String, pack: com.vibe.agent.pipelines.PackSpec? = null) {
    val provider = providers.firstOrNull { it.id == providerId }
      ?: throw IllegalStateException(t("pipeline.step.noProvider", "id" to providerId))
    val model = provider.models.firstOrNull { it.id == modelId }
      ?: ModelEntry(id = modelId) // модели нет в каталоге — это ещё не повод не спросить о ней
    val resolved = ProvidersService.resolve(provider, project.basePath) { systemLine("[providers] $it") }
      ?: throw IllegalStateException(com.vibe.agent.i18n.VibeI18n.t("chat.provider.noBaseUrl", "id" to providerId))
    if (resolved.missingKey) {
      throw IllegalStateException(com.vibe.agent.i18n.VibeI18n.t(
        "chat.provider.noKey", "id" to providerId,
        "sources" to com.vibe.agent.providers.ApiKeyResolver.sourceNames(provider)))
    }
    val prompt = pack?.let { stepPrompt + "\n" + packRepository(it, model, stepPrompt) } ?: stepPrompt
    // Потолок токенов у шага на своей модели.
    //
    // Прямой запрос не сообщает расход по ходу — числа приходят только в конце, — поэтому здесь
    // считается ОЦЕНКА по объёму: сам запрос плюс всё, что уже пришло в ответе. Это меньше, чем
    // умеет ACP-ветка, и это сказано в спеке прямым текстом: потолок бережёт от убежавшего ответа,
    // а не отмеряет токены точно. Молчать о разнице нельзя — потолок, который «вроде есть»,
    // тратит деньги ровно там, где его поставили, чтобы не тратить.
    val ceiling = turn.limits?.maxTokens?.takeIf { it > 0 }?.toLong()
    val spent = java.util.concurrent.atomic.AtomicLong(
      if (ceiling == null) 0L else com.vibe.agent.context.ContextBudget.estimateTokens(prompt))
    // The step's own client: its last usage, its answered model and its cancel are this step's, not a neighbour's.
    val llm = LlmClient(projectBase = project.basePath)
    turn.llm = llm
    llm.chat(
      resolved, model, listOf(ChatMessage(role = "user", text = prompt)),
      {
        llmCancel.get() || (ceiling != null && spent.get() > ceiling).also {
          if (it && turn.limitHit == null) {
            turn.limitHit = com.vibe.agent.pipelines.StepLimits.Verdict.TOKENS
            systemLine(t("pipeline.limit.tokensEstimated", "role" to (turn.limits?.role ?: ""),
                         "limit" to "%,d".format(ceiling)))
          }
        }
      },
      onWaiting = { attempt, delayMs, reason ->
        systemLine(t("retry.waiting", "attempt" to attempt, "seconds" to (delayMs / 1000), "reason" to (reason ?: "")))
      },
      onThought = { appendThought(it, turn) },
    ) { delta ->
      if (ceiling != null) spent.addAndGet(com.vibe.agent.context.ContextBudget.estimateTokens(delta))
      turn.answer?.append(delta)
      appendAgentText(delta, turn)
    }
    turn.usage = llm.lastUsage()
    llm.lastStopReason()?.takeIf { it.abnormal }?.let { systemLine(stopNote(it)) }
    noteModelSubstitution(model.id, llm.lastAnsweredModel())
    turn.pricing = com.vibe.agent.providers.PriceValidity.effective(model, java.time.LocalDate.now())
  }

  /**
   * The repository files a step's `pack` asks for, as a prompt block — or an exception that stops the step before
   * anything is sent: no git, nothing to pack, over the ceiling, over a spending limit. Every refusal says by how much,
   * so the person knows what to narrow. Decision №97.
   */
  private fun packRepository(spec: com.vibe.agent.pipelines.PackSpec, model: ModelEntry, stepPrompt: String): String {
    val base = project.basePath ?: throw IllegalStateException(t("pipeline.pack.noGit"))
    val root = com.vibe.agent.context.AgentPaths.physical(java.nio.file.Path.of(base).toAbsolutePath().normalize())
      ?: throw IllegalStateException(t("pipeline.pack.noGit"))
    val tracked = gitTrackedFiles(root) ?: throw IllegalStateException(t("pipeline.pack.noGit"))
    val selected = com.vibe.agent.pipelines.RepoPack.select(tracked, spec)
    val promptTokens = com.vibe.agent.context.ContextBudget.estimateTokens(stepPrompt)
    // What the window leaves after the task and the answer: a pack that fits its own ceiling but not the model is
    // refused the same way, before the provider refuses it for money.
    val windowLeft = model.contextWindow?.let { it.toLong() - promptTokens - (model.maxOutputTokens ?: 0) }
    val limit = minOf(spec.maxTokens.toLong(), windowLeft ?: Long.MAX_VALUE).coerceAtLeast(0L)
    val maxBytes = limit * com.vibe.agent.context.ContextBudget.CHARS_PER_TOKEN * MAX_UTF8_BYTES_PER_CHAR
    val result = com.vibe.agent.pipelines.RepoPack.build(selected, limit) { path ->
      val file = com.vibe.agent.context.AgentPaths.physical(root.resolve(path).normalize())
      // A tracked symlink out of the project is not the project's source: it is not read.
      if (file == null || !file.startsWith(root) || !java.nio.file.Files.isRegularFile(file)) return@build null
      val size = runCatching { java.nio.file.Files.size(file) }.getOrNull() ?: return@build null
      // A single file that cannot fit even at four bytes a character stops the pack without being read into memory.
      if (size > maxBytes && !isBinaryFile(file)) {
        throw IllegalStateException(t("pipeline.pack.fileTooLarge", "path" to path, "limit" to "%,d".format(limit)))
      }
      runCatching { java.nio.file.Files.readAllBytes(file) }.getOrNull()
    }
    val packed = when (result) {
      is com.vibe.agent.pipelines.RepoPack.Result.Empty ->
        throw IllegalStateException(t("pipeline.pack.empty", "secrets" to result.secrets.size))
      is com.vibe.agent.pipelines.RepoPack.Result.TooLarge ->
        throw IllegalStateException(t("pipeline.pack.tooLarge", "files" to result.files, "tokens" to "%,d".format(result.tokens),
                                      "limit" to "%,d".format(result.limit)))
      is com.vibe.agent.pipelines.RepoPack.Result.Packed -> result
    }
    packed.findings.forEach { (path, findings) -> reportContextFindings(path, findings) }
    if (packed.secrets.isNotEmpty()) systemLine(t("pipeline.pack.secretsSkipped", "paths" to packed.secrets.joinToString()))
    val pricing = com.vibe.agent.providers.PriceValidity.effective(model, java.time.LocalDate.now())
    val cost = pricing?.costOf(com.vibe.agent.providers.TokenUsage(inputTokens = packed.tokens + promptTokens), java.time.Instant.now())
    systemLine(t("pipeline.pack.ready", "files" to packed.files, "tokens" to "%,d".format(packed.tokens)) + " " +
               (cost?.let { t("pipeline.pack.cost", "cost" to money(it), "currency" to pricing.currency) } ?: t("pipeline.pack.costUnknown")))
    // The spending ceilings are checked against the estimate BEFORE the request: after it, a ceiling is a receipt.
    val limits = VibeChatSettings.spendLimits()
    if (cost != null && limits.any) {
      val month = com.vibe.agent.budget.VibeSpendService.getInstance().entries(com.vibe.agent.budget.SpendCeiling.MONTH_MS)
      com.vibe.agent.budget.SpendCeiling.check(month, System.currentTimeMillis(), limits).firstOrNull { it.left < cost }?.let {
        throw IllegalStateException(t("pipeline.pack.overCeiling", "window" to windowName(it.window.id),
                                      "left" to money(it.left), "cost" to money(cost)))
      }
    }
    return t("pipeline.pack.header") + "\n" + packed.text
  }

  /** Tracked paths from `git ls-files -z`; null — not a git work tree, or git is missing. */
  private fun gitTrackedFiles(root: java.nio.file.Path): List<String>? = runCatching {
    val process = ProcessBuilder("git", "-c", "core.quotepath=off", "ls-files", "-z")
      .directory(root.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    val out = process.inputStream.readBytes()
    if (!process.waitFor(GIT_LIST_TIMEOUT_SEC, TimeUnit.SECONDS) || process.exitValue() != 0) return@runCatching null
    String(out, Charsets.UTF_8).split('\u0000').filter { it.isNotEmpty() }
  }.getOrNull()

  private fun isBinaryFile(file: java.nio.file.Path): Boolean = runCatching {
    java.nio.file.Files.newInputStream(file).use { com.vibe.agent.pipelines.RepoPack.isBinary(it.readNBytes(com.vibe.agent.pipelines.RepoPack.BINARY_PROBE_BYTES)) }
  }.getOrDefault(false)

  /**
   * Which pairs «asked → answered» were already reported, so a proxy that renames every answer says
   * it once per session instead of on every turn.
   */
  private val reportedSubstitutions = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

  /**
   * The provider answered with a model nobody asked for — a proxy, an aggregator or a failover
   * target decided so. Not an error and not a block: the answer is already here and paid for, and
   * the price is counted by the asked model, so the honest thing is to say it and write it down.
   */
  private fun noteModelSubstitution(asked: String, answered: String?) {
    if (!com.vibe.agent.providers.ModelEcho.substituted(asked, answered)) return
    if (!reportedSubstitutions.add(asked + "\u2192" + answered)) return
    systemLine(t("chat.modelSubstituted", "asked" to asked, "answered" to (answered ?: "")))
    audit?.append(AuditEvent(System.currentTimeMillis(), AuditEvent.Action.MODEL_SUBSTITUTED, ok = false,
                             actor = agentActor(),
                             meta = mapOf("asked" to asked, "answered" to (answered ?: ""))))
  }

  /**
   * The run's changes for a judge on its own model: the touched paths diffed against the snapshot
   * taken before the first step, fitted to the step's budget. That judge has no tools; without the
   * diff it judged the author's account of the work, not the work (decision №80).
   */
  private fun judgeDiff(from: com.vibe.agent.checkpoints.Checkpoint?, paths: Collection<String>, maxTokens: Int?): String? {
    val service = checkpoints ?: return null
    if (from == null || paths.isEmpty()) return null
    val relative = paths.mapNotNull { projectRelative(it) }.distinct()
    if (relative.isEmpty()) return null
    val diff = service.diff(from, relative)?.takeIf { it.isNotBlank() } ?: return null
    val fitted = com.vibe.agent.pipelines.JudgeDiff.fit(diff, maxTokens)
    return t("pipeline.step.diff", "diff" to fitted.text) + if (fitted.truncated) "\n" + t("pipeline.step.diffTruncated") else ""
  }

  /**
   * [path] relative to the project root, both resolved on disk: git reads a pathspec spelled through
   * a linked root as a path outside the repository. Null for anything outside the project.
   */
  private fun projectRelative(path: String): String? {
    val base = project.basePath ?: return null
    val root = runCatching { java.nio.file.Path.of(base).toAbsolutePath().normalize() }.getOrNull()
      ?.let { com.vibe.agent.context.AgentPaths.physical(it) } ?: return null
    val raw = runCatching { java.nio.file.Path.of(path) }.getOrNull() ?: return null
    val file = com.vibe.agent.context.AgentPaths.physical((if (raw.isAbsolute) raw else root.resolve(raw)).normalize())
               ?: return null
    if (!file.startsWith(root)) return null
    return root.relativize(file).joinToString("/") { it.toString() }
  }

  /**
   * A dynamic pipeline: the orchestrator drafts the steps, the person sees them, and only then they run.
   *
   * The drafting turn writes nothing — its scope denies every path, enforced at fs/write, not asked for
   * in the prompt. The draft is read by the same step parser as the file ([PipelinesFile.planFromAnswer]),
   * so a plan cannot pass a looser check than a hand-written pipeline. Consent is a dialog with the steps
   * listed; closing it runs nothing. The approved steps run as an ordinary pipeline — checkpoints,
   * gates, budgets and the run ledger all apply.
   */
  private fun runDynamicPipeline(pipeline: com.vibe.agent.pipelines.Pipeline, agent: AgentServerConfig) {
    if (!history.tryBeginTurn(currentThreadId)) {
      systemLine(t("chat.threadBusy"))
      return
    }
    val drafting = pipeline.steps.single()
    systemLine(t("pipeline.plan.planningStep"))
    turnInFlight.set(true)
    status.set(VibeAgentStatusService.State.RUNNING)
    // The silence clock belongs to the TURN, not to the ACP path that used to be its only winder:
    // a direct-chat turn left it at zero, and the watchdog read that as silence since the epoch.
    noteActivity()
    staleAnnounced.set(false)
    composer.busy = true
    showWorking(WorkingLine.Kind.THINKING)
    turnThreadId = currentThreadId
    turns.chat = pipelineChatTurn(currentThreadId)
    ApplicationManager.getApplication().executeOnPooledThread {
      var planned: List<com.vibe.agent.pipelines.PipelineStep>? = null
      val turn = TurnState(
        role = drafting.role,
        scope = com.vibe.agent.pipelines.RolePaths.NOTHING,
        label = t("pipeline.plan.title"),
        signals = untrustedSignals(),
        feed = mainFeed,
      )
      try {
        llmCancel.set(false)
        val prompt = buildString {
          appendLine(PipelinesFile.rolePreamble(drafting.role))
          appendLine(t("pipeline.step.task", "task" to drafting.task))
          appendLine(t("pipeline.plan.instruction", "roles" to (PipelinesFile.ROLES - PipelinesFile.ORCHESTRATOR).sorted().joinToString()))
        }
        val startedAt = System.currentTimeMillis()
        val c = ensureClient(agent, turnThreadId ?: currentThreadId)
        val session = c.openIsolatedSession().get(VibeAgentSettings.handshakeTimeoutSec.toLong(), TimeUnit.SECONDS)
        turn.sessionId = session
        turns.register(turn)
        c.prompt(session, prompt).get()
        finishAgentBubble((System.currentTimeMillis() - startedAt) / 1000.0, t("pipeline.plan.title"), turn)
        // A drafted wave passes the same check as one in the file, qa boundary included.
        val qa = com.vibe.agent.pipelines.RolesFile.load(project.basePath) { systemLine("[roles] $it") }
        when (val plan = PipelinesFile.planFromAnswer(turn.answer?.toString().orEmpty(), qa) { systemLine("[pipelines] $it") }) {
          is PipelinesFile.Plan.Refused -> systemLine(plan.reason)
          is PipelinesFile.Plan.Ok -> planned = plan.steps
        }
      }
      catch (e: Exception) {
        systemLine(t("pipeline.stepFailed", "header" to t("pipeline.plan.title"), "reason" to e.message))
      }
      finally {
        turns.unregisterAll()
        finishTurn()
      }
      val steps = planned ?: return@executeOnPooledThread
      val listed = steps.withIndex().joinToString("\n") { (i, step) -> "${i + 1}. ${step.role}: ${step.task.take(120)}" }
      val approved = askOnEdt {
        Messages.showYesNoDialog(project, t("pipeline.plan.confirm", "count" to steps.size, "steps" to listed),
                                 t("pipeline.plan.title"), t("pipeline.plan.run"), t("common.cancel"), Messages.getQuestionIcon()) == Messages.YES
      }
      if (!approved) {
        systemLine(t("pipeline.plan.declined"))
        return@executeOnPooledThread
      }
      SwingUtilities.invokeLater { runPipeline(pipeline.copy(steps = steps, dynamic = false), agent) }
    }
  }

  /** What a pipeline run carries from one step to the next; the pipeline thread only. */
  private class PipelineRun(
    val pipeline: com.vibe.agent.pipelines.Pipeline,
    val agent: AgentServerConfig,
    val runId: String?,
    val brief: String,
    /** Read once per run: a file edited mid-run must not move the boundary between two steps. */
    val qaScope: com.vibe.agent.pipelines.RolePaths.Scope,
    /** Steps an interrupted run already finished: a resumed run does not pay for them twice. */
    val skip: Set<Int>,
  ) {
    val artifacts = LinkedHashSet<String>()
    /** Швы между модулями, названные прошлыми шагами: накопительный контракт прогона. */
    val seams = LinkedHashSet<String>()
    var lastSummary: String? = null
    /** The snapshot before the first step: a judge on its own model gets the run's diff against it. */
    var runCheckpoint: com.vibe.agent.checkpoints.Checkpoint? = null
    var failed = false
    /** Отменён человеком, пока шаг ждал конца пика: дальше не идёт ни один шаг, даже continueOnFailure. */
    var runCancelled = false
    /**
     * Принял ли гейт последний проверенный шаг. Null — гейта нет вовсе: тогда «эскалация»
     * ничего не значит и шаг выполняется как обычный.
     */
    var lastGateAccepted: Boolean? = null
    /** Finished steps by index: a wave finishes them out of order, and a count would not say which. */
    val done = java.util.TreeSet<Int>()
  }

  /** How a step ended: what it hands to the next step, whether it failed, and its gate's verdict. */
  private class StepResult(val header: String, val summary: String?, val failed: Boolean, val gate: Boolean?)

  private fun runPipeline(pipeline: com.vibe.agent.pipelines.Pipeline, agent: AgentServerConfig, skip: Set<Int> = emptySet()) {
    if (!history.tryBeginTurn(currentThreadId)) {
      systemLine(t("chat.threadBusy"))
      return
    }
    systemLine(t("pipeline.header", "name" to pipeline.name, "count" to pipeline.steps.size))
    val qaScope = com.vibe.agent.pipelines.RolesFile.load(project.basePath) { systemLine("[roles] $it") }
    turnInFlight.set(true)
    status.set(VibeAgentStatusService.State.RUNNING)
    // The silence clock belongs to the TURN, not to the ACP path that used to be its only winder:
    // a direct-chat turn left it at zero, and the watchdog read that as silence since the epoch.
    noteActivity()
    staleAnnounced.set(false)
    composer.busy = true
    showWorking(WorkingLine.Kind.THINKING)
    turnThreadId = currentThreadId
    turns.chat = pipelineChatTurn(currentThreadId)
    markConversationStarted()
    history.append(currentThreadId, ChatMessageRecord(Role.USER, t("pipeline.userLine", "name" to pipeline.name, "count" to pipeline.steps.size), at = nowIso()))
    ApplicationManager.getApplication().executeOnPooledThread {
      // Прошлое нажатие «Стоп» живёт в этом флаге до следующего хода, а шаг на своей модели
      // спрашивает его на каждом чанке: без сброса такой шаг умирал бы мгновенно, причём ТОЛЬКО
      // он — шаги через ACP работали бы, и отказ выглядел бы как «модель не отвечает».
      llmCancel.set(false)
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
        pipelineId = pipeline.id,
      )
      pipelineRunId = runId
      // Задача прогона, записанная ДО того, как её коснулся агент: приёмка сверяется с ней, а не со
      // спекой и не с пересказом предыдущего шага (решение по разбору Autopilot, 17.09.2026).
      val brief = com.vibe.agent.pipelines.PipelineBrief.of(pipeline)
      runId?.let { com.vibe.agent.pipelines.RunFolder.write(project.basePath, it, com.vibe.agent.pipelines.RunFolder.BRIEF, brief) }
      val run = PipelineRun(pipeline, agent, runId, brief, qaScope, skip)
      try {
        for (group in com.vibe.agent.pipelines.PipelineWaves.groups(pipeline.steps)) {
          if (group.first == group.last) runStep(run, group.first) else runWave(run, group)
        }
        systemLine(t("pipeline.finished", "name" to pipeline.name, "outcome" to (if (run.failed) t("pipeline.outcome.failed") else t("pipeline.outcome.done"))))
        // The bill next to the outcome: the task is the unit a cascade or a council is compared by.
        runId?.let { id -> com.vibe.agent.budget.VibeSpendService.getInstance().ofRun(id) }?.let { bill ->
          systemLine(if (bill.currency != null) t("pipeline.bill.cost", "tokens" to bill.tokens, "cost" to "%.4f".format(bill.cost), "currency" to bill.currency)
                     else t("pipeline.bill.tokens", "tokens" to bill.tokens))
        }
        runs.finished(
          runId,
          if (run.failed) com.vibe.agent.runs.AgentRunLedger.Status.FAILED else com.vibe.agent.runs.AgentRunLedger.Status.COMPLETED,
          if (run.failed) t("pipeline.run.failed") else t("pipeline.run.completed", "count" to pipeline.steps.size),
        )
      }
      finally {
        // What the run changed is what the turn changed: the checklist and the handoff after it read the chat's turn.
        turns.chat.changedPaths.addAll(run.artifacts)
        turns.unregisterAll()
        pipelineRunId = null
        finishTurn()
      }
    }
  }

  /** One step on its own, in the order of the file. */
  private fun runStep(run: PipelineRun, i: Int) {
    val pipeline = run.pipeline
    val step = pipeline.steps[i]
    val header = t("pipeline.step", "index" to (i + 1), "total" to pipeline.steps.size, "role" to step.role)
    // Resumed run: steps before the interruption already ran and are not paid for twice. Their
    // artifacts are not known to this run, so the first resumed step starts without that list.
    if (i in run.skip) {
      systemLine(t("pipeline.stepAlreadyDone", "header" to header))
      markDone(run, i)
      return
    }
    if (run.runCancelled || (run.failed && !step.continueOnFailure)) {
      systemLine(t("pipeline.stepSkipped", "header" to header))
      return
    }
    // Шаг эскалации нужен ровно тогда, когда предыдущий черновик НЕ приняли.
    val accepted = run.lastGateAccepted
    // Ответ гейта относится к ОДНОМУ шагу — тому, который он проверил. Оставить его жить
    // дальше значит однажды пропустить эскалацию из-за приёмки позапрошлого шага, между
    // которыми был упавший: гасим сразу после использования, заново ставит только гейт.
    run.lastGateAccepted = null
    if (step.escalation && accepted == true) {
      systemLine(t("pipeline.stepSkippedByGate", "header" to header))
      // Пропуск пишется в журнал наравне с вердиктом гейта: без него окупаемость каскада
      // нечем считать — видно «сколько раз приняли», но не «сколько дорогого не запустили».
      audit?.append(AuditEvent(System.currentTimeMillis(), AuditEvent.Action.HOOK, ok = true,
                               actor = agentActor(),
                               meta = mapOf("event" to CASCADE_SKIP, "pipeline" to pipeline.id,
                                            "step" to (i + 1).toString(), "role" to step.role)))
      return
    }
    if (!mayStart(run, step)) {
      run.failed = true
      return
    }
    if (step.offPeak && !waitForOffPeak(pipeline, i, step)) {
      run.failed = true
      run.runCancelled = true
      systemLine(t("pipeline.offPeak.cancelled", "header" to header))
      return
    }
    systemLine("$header ${step.task.take(80)}")
    checkpoint(run, t("pipeline.checkpointLabel", "name" to pipeline.name, "index" to (i + 1), "role" to step.role), (i + 1).toString())
    val turn = stepTurn(run, step, stepName(pipeline, i), mainFeed)
    val prompt = stepPrompt(run, step)
    val startedAt = System.currentTimeMillis()
    val (stop, error) = runCatching { launchStep(run, step, turn, header, prompt).get() }
      .fold({ it to null }, { (it as? java.util.concurrent.ExecutionException)?.cause?.let { cause -> null to cause } ?: (null to it) })
    finishAgentBubble((System.currentTimeMillis() - startedAt) / 1000.0, step.model ?: t("pipeline.stepLabel", "index" to (i + 1)), turn)
    val result = finishStep(run, i, step, turn, header, stop, error)
    result.summary?.let { run.lastSummary = it }
    if (result.failed) run.failed = true
    run.lastGateAccepted = result.gate
  }

  /**
   * The steps of one wave, all at once.
   *
   * Each runs as a step on its own would — its own session, boundary, ceilings and block in the feed — and the run
   * moves on when the last of them is done. What they found goes to the next step together, in the order of the file;
   * a member that failed fails the wave as a step on its own would fail.
   */
  private fun runWave(run: PipelineRun, range: IntRange) {
    val pipeline = run.pipeline
    val wave = pipeline.steps[range.first].wave.orEmpty()
    val headers = range.associateWith { i -> t("pipeline.step", "index" to (i + 1), "total" to pipeline.steps.size, "role" to pipeline.steps[i].role) }
    // Resumed run: members that finished before the interruption are not paid for twice.
    val pending = range.filter { i ->
      if (i !in run.skip) return@filter true
      systemLine(t("pipeline.stepAlreadyDone", "header" to headers.getValue(i)))
      markDone(run, i)
      false
    }
    val members = pending.filter { i ->
      val goes = !run.runCancelled && (!run.failed || pipeline.steps[i].continueOnFailure)
      if (!goes) systemLine(t("pipeline.stepSkipped", "header" to headers.getValue(i)))
      goes
    }
    // An escalation needs the verdict on the step before it, and the loader keeps escalation out of waves: whatever
    // verdict came before the wave lapses here.
    run.lastGateAccepted = null
    if (members.isEmpty()) return
    // A wave starts whole or not at all: half a wave leaves half of what the next step expects.
    if (members.any { !mayStart(run, pipeline.steps[it]) }) {
      run.failed = true
      return
    }
    systemLine(t("pipeline.wave.start", "wave" to wave, "steps" to members.joinToString { (it + 1).toString() }))
    checkpoint(run, t("pipeline.wave.checkpointLabel", "name" to pipeline.name, "wave" to wave), members.joinToString(",") { (it + 1).toString() })
    // Every member gets what was known before the wave: a neighbour's work is not there yet.
    val prompts = members.associateWith { stepPrompt(run, pipeline.steps[it]) }
    val stepTurns = members.associateWith { i ->
      val name = stepName(pipeline, i)
      stepTurn(run, pipeline.steps[i], name, WaveBlockFeed(name))
    }
    SwingUtilities.invokeLater {
      runningWave = stepTurns.values.toList()
      stepTurns.values.forEach { (it.feed as? WaveBlockFeed)?.show() }
    }
    val futures = members.associateWith { i ->
      val step = pipeline.steps[i]
      val turn = stepTurns.getValue(i)
      val startedAt = System.currentTimeMillis()
      // Launched one by one — a session opens in a moment — and then they run at once.
      val launched = try {
        launchStep(run, step, turn, headers.getValue(i), prompts.getValue(i))
      }
      catch (e: Exception) {
        java.util.concurrent.CompletableFuture.failedFuture(e)
      }
      // A block's answer is finished when its own step ends, not when the slowest neighbour does.
      launched.whenComplete { _, _ ->
        finishAgentBubble((System.currentTimeMillis() - startedAt) / 1000.0, step.model ?: t("pipeline.stepLabel", "index" to (i + 1)), turn)
        turn.done = true
      }
    }
    val results = members.map { i ->
      val (stop, error) = runCatching { futures.getValue(i).get() }
        .fold({ it to null }, { (it as? java.util.concurrent.ExecutionException)?.cause?.let { cause -> null to cause } ?: (null to it) })
      finishStep(run, i, pipeline.steps[i], stepTurns.getValue(i), headers.getValue(i), stop, error)
    }
    SwingUtilities.invokeLater { runningWave = emptyList() }
    // The next step hears every member under its own name: one summary in place of three would drop two.
    val summaries = results.filter { it.summary != null }
    if (summaries.isNotEmpty()) run.lastSummary = summaries.joinToString("\n\n") { it.header + ":\n" + it.summary }
    if (results.any { it.failed }) run.failed = true
    // Accepted when every gated member was; no member gated — no verdict at all.
    val verdicts = results.mapNotNull { it.gate }
    run.lastGateAccepted = if (verdicts.isEmpty()) null else verdicts.all { it }
  }

  /** How a step is named in its block and in a permission dialog: «Шаг 2/5 [qa]». */
  private fun stepName(pipeline: com.vibe.agent.pipelines.Pipeline, i: Int): String =
    t("pipeline.stepName", "index" to (i + 1), "total" to pipeline.steps.size, "role" to pipeline.steps[i].role)

  /** The step's own turn: its role, its boundary, its ceilings, and trifecta signals of its own. */
  private fun stepTurn(run: PipelineRun, step: com.vibe.agent.pipelines.PipelineStep, name: String, feed: TurnFeed): TurnState =
    TurnState(
      role = step.role,
      scope = com.vibe.agent.pipelines.RolePaths.effective(step.role,
        com.vibe.agent.pipelines.RolePaths.Scope(step.paths, step.denyPaths), run.qaScope),
      // Ceilings are counted only for a step that names them: for the others it would be work for nothing on every frame.
      limits = step.takeIf { com.vibe.agent.pipelines.StepLimits.any(it) },
      label = name,
      signals = untrustedSignals(),
      feed = feed,
    )

  /**
   * Trifecta signals of a pipeline step: its own, not the thread's — and untrusted from the start, because nobody at the
   * keyboard wrote the step's text; the pipeline file did.
   */
  private fun untrustedSignals(): MutableSet<com.vibe.agent.guard.Trifecta.Signal> =
    java.util.concurrent.ConcurrentHashMap.newKeySet<com.vibe.agent.guard.Trifecta.Signal>().apply {
      add(com.vibe.agent.guard.Trifecta.Signal.UNTRUSTED_CONTENT)
    }

  /** What a step must pass before it starts: its role's daily budget, and its agent's ceiling for an agent's step. */
  private fun mayStart(run: PipelineRun, step: com.vibe.agent.pipelines.PipelineStep): Boolean {
    if (roleBudgetExceeded(step.role)) return false
    // A step on the pipeline's agent is its spend; a step on its own model is not the agent's.
    return step.model != null || !agentLimitReached(run.agent, run.runId)
  }

  /**
   * A snapshot before a step or a wave. A pipeline runs unattended: without one before each step, one bad step could be
   * rolled back only together with everything before it — or not at all.
   */
  private fun checkpoint(run: PipelineRun, label: String, steps: String) {
    checkpoints?.create(label)?.let {
      checkpointLine(it)
      audit?.append(AuditEvent(System.currentTimeMillis(), AuditEvent.Action.CHECKPOINT, ok = true,
                               actor = com.vibe.agent.audit.AuditActor.IDE,
                               meta = mapOf("hash" to it.hash.take(12), "pipeline" to run.pipeline.id, "step" to steps)))
      if (run.runCheckpoint == null) run.runCheckpoint = it
    }
  }

  /** The step's prompt from what the run knows so far. */
  private fun stepPrompt(run: PipelineRun, step: com.vibe.agent.pipelines.PipelineStep): String = buildString {
    appendLine(PipelinesFile.rolePreamble(step.role))
    appendLine(PipelinesFile.RETURN_CONTRACT)
    appendLine(t("pipeline.step.task", "task" to step.task))
    step.acceptance?.let { appendLine(t("pipeline.step.acceptance", "acceptance" to it)) }
    if (step.againstBrief) {
      // Судья видит только слова задачи: артефакты и резюме — это уже рассказ исполнителя о себе.
      appendLine(t("pipeline.step.brief", "brief" to run.brief))
    }
    else if (!step.ignorePreviousArtifacts) {
      if (run.seams.isNotEmpty()) appendLine(t("pipeline.step.seams", "seams" to run.seams.joinToString("\n") { "- $it" }))
      if (run.artifacts.isNotEmpty()) appendLine(t("pipeline.step.artifacts", "files" to run.artifacts.joinToString()))
      run.lastSummary?.let { appendLine(t("pipeline.step.summary", "summary" to it)) }
      // A judge on its own model has no tools to read the files: it gets the run's diff.
      if (step.model != null) judgeDiff(run.runCheckpoint, run.artifacts, step.maxTokens)?.let { appendLine(it) }
    }
  }

  /**
   * Starts the step and ends with the agent's stop reason: a direct request for a step on its own model, a prompt to the
   * agent otherwise — in a new session for a fresh context, in the chat's own for a shared one.
   *
   * Шаг со своей моделью идёт прямым запросом к провайдеру, мимо агента: у него нет инструментов, и он ни на что не
   * влияет, кроме собственного ответа. Загрузчик пайплайнов уже не пустил сюда пишущую роль.
   */
  private fun launchStep(
    run: PipelineRun,
    step: com.vibe.agent.pipelines.PipelineStep,
    turn: TurnState,
    header: String,
    prompt: String,
  ): java.util.concurrent.CompletableFuture<String?> {
    val provider = step.provider
    val model = step.model
    if (provider != null && model != null) {
      return java.util.concurrent.CompletableFuture.supplyAsync({
        runModelStep(turn, provider, model, prompt, step.pack)
        null
      }, com.intellij.util.concurrency.AppExecutorUtil.getAppExecutorService())
    }
    val c = ensureClient(run.agent, turnThreadId ?: currentThreadId)
    // A judging step starts clean: a new session of the same agent, not remembered, while
    // the chat's session stays current for the next turn (decision №80). An agent that
    // cannot open one fails the step with the reason — judging in the chat's session
    // instead would quietly break the very promise of the field.
    val session = if (step.context == com.vibe.agent.pipelines.StepContext.FRESH) {
      c.openIsolatedSession().get(VibeAgentSettings.handshakeTimeoutSec.toLong(), TimeUnit.SECONDS)
        .also { systemLine(t("pipeline.stepFresh", "header" to header)) }
    }
    else checkNotNull(c.sessionId) { "no session" }
    turn.sessionId = session
    turns.register(turn)
    return c.prompt(session, prompt).thenApply { result ->
      // Как и в обычном ходе: `{"result": null}` — не объект, и `.jsonObject` на JsonNull бросает.
      (result as? JsonObject)?.get("stopReason")?.jsonPrimitive?.contentOrNull
    }
  }

  /**
   * Reads what the step left: its report, its seams, its files, its gate's verdict — or the reason it failed.
   *
   * The step's answer is already finished in the feed when this runs; in a wave this runs member after member in the
   * order of the file, whatever order they finished in.
   */
  private fun finishStep(
    run: PipelineRun,
    i: Int,
    step: com.vibe.agent.pipelines.PipelineStep,
    turn: TurnState,
    header: String,
    stop: String?,
    error: Throwable?,
  ): StepResult {
    if (error != null) {
      systemLine(t("pipeline.stepFailed", "header" to header, "reason" to error.message))
      return StepResult(header, null, failed = true, gate = null)
    }
    if (stop == STOP_CANCELLED) {
      // Свой потолок и рука человека дают одинаковый stopReason, а значат разное: первое —
      // сработавшее правило пайплайна, второе — чужое вмешательство в него.
      val handoff = if (turn.limitHit != null && step.model == null) askHandoff(turn, header, run.runId, i) else null
      if (handoff != null) {
        turn.report = null
        run.artifacts.addAll(turn.changedPaths)
        markDone(run, i)
        return StepResult(header, handoff, failed = false, gate = null)
      }
      systemLine(if (turn.limitHit != null) t("pipeline.stepCapped", "header" to header)
                 else t("pipeline.stepStopped", "header" to header))
      return StepResult(header, null, failed = true, gate = null)
    }
    return try {
      val summaryText = turn.answer?.toString().orEmpty()
      // The step's own report if it wrote one; the tail of the answer otherwise — a step that ignored the
      // contract must not lose its say (see [StepReport]).
      val report = com.vibe.agent.pipelines.StepReport.parse(summaryText)
      turn.report = report
      val summary = report?.summary() ?: summaryText.takeLast(2000).ifBlank { t("pipeline.noText") }
      report?.let {
        systemLine(t("pipeline.step.report", "header" to header, "status" to it.status.name,
                     "blockers" to it.blockers.size, "concerns" to it.concerns.size))
        // Швы, которые шаг назвал, копятся в одном файле прогона, и следующие шаги читают его
        // первым делом. Ведёт его IDE, а не сами шаги: файл, который каждый дописывает сам,
        // держится на дисциплине, которой у модели нет.
        if (it.interfaces.isNotEmpty() && run.runId != null) {
          val block = "## " + header + "\n" + it.interfaces.joinToString("\n") { seam -> "- $seam" } + "\n\n"
          com.vibe.agent.pipelines.RunFolder.append(project.basePath, run.runId, com.vibe.agent.pipelines.RunFolder.INTERFACES, block)
          run.seams += it.interfaces
        }
      }
      run.artifacts.addAll(turn.changedPaths)
      markDone(run, i)
      systemLine(t("pipeline.stepDone", "header" to header, "files" to turn.changedPaths.size))
      StepResult(header, summary, failed = false, gate = runStepGate(run.pipeline, step, i, summaryText, turn))
    }
    catch (e: Exception) {
      systemLine(t("pipeline.stepFailed", "header" to header, "reason" to e.message))
      StepResult(header, null, failed = true, gate = null)
    }
  }

  /** Marks the step finished in the run and in the ledger — by index, so a resumed run knows which ones. */
  private fun markDone(run: PipelineRun, index: Int) {
    run.done.add(index)
    runs.progress(run.runId, steps = run.done.size, changedFiles = run.artifacts.size, done = run.done.toList())
  }

  /** The steps of the wave running now, for a feed redrawn mid-wave; empty outside a wave. The UI thread only. */
  private var runningWave: List<TurnState> = emptyList()

  /**
   * The feed of a step running in a wave: a block of its own under its name, so answers streaming at once do not
   * interleave line by line. The UI thread only; [show] puts the block into the feed before the step's first row.
   */
  private inner class WaveBlockFeed(private val header: String) : TurnFeed {
    private var block: JPanel? = null

    fun show() {
      panel()
    }

    private fun panel(): JPanel = block ?: JPanel().apply {
      layout = BoxLayout(this, BoxLayout.Y_AXIS)
      isOpaque = false
      alignmentX = Component.LEFT_ALIGNMENT
      border = JBUI.Borders.compound(JBUI.Borders.customLineLeft(ChatTheme.TERMINAL_BORDER), JBUI.Borders.emptyLeft(6))
      add(metaArea(header, Font.BOLD, 10f).apply { alignmentX = Component.LEFT_ALIGNMENT })
      block = this
      messages.add(this)
      revalidateScroll()
    }

    override fun addRecord(row: JComponent) {
      row.alignmentX = Component.LEFT_ALIGNMENT
      panel().add(row)
      recordRows.add(row)
    }

    override fun addExtra(row: JComponent, before: Component?) {
      val block = panel()
      val index = before?.let { block.components.indexOf(it) } ?: -1
      if (index >= 0) block.add(row, index) else block.add(row)
    }
  }

  // --- models.fetch ---

  /** EDT: folds ONE provider's fresh catalog into the registry (unknown ids only) and refreshes the picker. */
  private fun addCatalogModels(providerId: String, models: List<com.vibe.agent.providers.CatalogModel>) {
    val current = providers.firstOrNull { it.id == providerId } ?: return
    // The cache's own merge, so a catalog fetched now and one served from the cache land the same way.
    val merged = ModelCatalogCache.merge(listOf(current), mapOf(providerId to ModelCatalogCache.entryOf(current, models, 0L))).single()
    if (merged == current) return
    providers = providers.map { if (it.id == providerId) merged else it }
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
      val keyRequired = java.util.Collections.synchronizedList(ArrayList<String>())
      val localDown = java.util.Collections.synchronizedList(ArrayList<String>())
      val failed = java.util.Collections.synchronizedList(ArrayList<Pair<String, String>>())
      val pending = snapshot.mapNotNull { p ->
        if (p.modelsFetch?.enabled == false) return@mapNotNull null // absent = fetch on (default)
        // quiet: обновление каталогов — фон, и в связку ключей оно не ходит. Иначе шесть обращений
        // при каждом старте, и каждая запись с чужим списком доступа отвечает диалогом с паролем —
        // за провайдера, которым человек в этот день и не пользуется (opencode, 20.09.2026).
        // Ключ, ещё никем не прочитанный, здесь выглядит как «нет ключа» и уводит в keyless ниже:
        // каталог остаётся из кэша, а первым связку прочитает настоящий запрос человека.
        val resolved = ProvidersService.resolve(p, project.basePath, quiet = true) { } ?: return@mapNotNull null
        // No key and not a local endpoint: asking would earn a predictable 401. Не спрашиваем и
        // не называем это ошибкой провайдера — у человека просто не введён ключ.
        if (resolved.missingKey) {
          keyless += p.id
          return@mapNotNull null
        }
        ApplicationManager.getApplication().executeOnPooledThread {
          try {
            val models = llm.listModels(resolved, p.modelsFetch?.url)
            fresh[p.id] = ModelCatalogCache.entryOf(p, models, System.currentTimeMillis())
            updated += p.id
            SwingUtilities.invokeLater { if (!disposed) addCatalogModels(p.id, models) }
          }
          catch (e: Exception) {
            val reason = CatalogReport.reason(e)
            when {
              CatalogReport.isRejectedKey(reason) && p.auth.type == AuthSpec.NONE -> keyRequired += p.id
              CatalogReport.isRejectedKey(reason) -> rejected += p.id
              resolved.localAddress -> localDown += p.id
              else -> failed += (p.id to reason)
            }
          }
        }
      }
      pending.forEach { runCatching { it.get() } }
      ModelCatalogCache.put(fresh)
      val report = CatalogReport(
        updated = updated.toList(), keyless = keyless.toList(), rejected = rejected.toList(),
        keyRequired = keyRequired.toList(), localDown = localDown.toList(), failed = failed.toList(),
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

  internal inner class AgentMessage {
    /** Raw streamed text lives here; on finish it may be re-rendered into prose + code blocks. */
    val text = proseArea()
    private var fullText = ""
    val meta = metaLabel(t("chat.role.agentAt", "time" to now()), right = false)
    private val metaRow = JPanel(BorderLayout()).apply {
      isOpaque = false
      add(meta, BorderLayout.WEST)
      add(JPanel(java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, JBUI.scale(2), 0)).apply {
        isOpaque = false
        // «Решение» рядом с «копировать»: решение созревает в ответе агента и там же умирает,
        // если переносить его в файл руками. Ссылкой, а не кнопкой — сообщений в ленте сотни.
        add(ChatTheme.quietLabel(t("decisions.fromMessage"), t("decisions.fromMessage.hint")) {
          com.vibe.agent.decisions.RecordDecisionAction.record(
            project,
            question = lastUserText,
            chosen = fullText.ifEmpty { text.text }.trim().take(DECISION_PREFILL_CHARS),
            why = "",
          )
        })
        add(copyLink { fullText.ifEmpty { text.text } })
      }, BorderLayout.EAST)
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
    val rewind = JLabel(REWIND_ICON).apply {
      toolTipText = t("chat.rewind.tooltip")
      cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
      foreground = META_FG
      font = com.intellij.util.ui.JBFont.label().deriveFont(11f)
    }
    rewind.addMouseListener(object : java.awt.event.MouseAdapter() {
      override fun mouseClicked(e: java.awt.event.MouseEvent) {
        rewindTo(recordIndex)
      }
    })
    strip.add(pin)
    strip.add(rewind)
    strip.add(branch)
    strip.add(meta)
    return strip
  }

  /**
   * Back to the moment before this message: the files to the checkpoint its turn took, and the conversation to a branch
   * that ends before it, with the message back in the composer to edit and send again.
   *
   * Both halves already existed apart — the checkpoint line restores files, the branch copies the conversation — and
   * going back meant finding the right checkpoint by its time and label by eye. The original thread stays: a branch,
   * not a cut. The dialog names what will be restored, and says so when the turn took no snapshot.
   */
  private fun rewindTo(recordIndex: Int) {
    val threadId = currentThreadId ?: return
    if (turnInFlight.get()) {
      systemLine(t("chat.rewind.busy"))
      return
    }
    val thread = history.get(threadId) ?: return
    val record = thread.messages.getOrNull(recordIndex)?.takeIf { it.role == Role.USER } ?: return
    fun millis(iso: String?): Long? = iso?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
    val nextAt = thread.messages.drop(recordIndex + 1).firstOrNull { it.role == Role.USER }?.let { millis(it.at) }
    val service = checkpoints
    val checkpoint = millis(record.at)?.let { at -> service?.let { com.vibe.agent.checkpoints.RewindPoint.checkpointFor(it.list(), at, nextAt) } }
    val question = if (checkpoint != null) t("chat.rewind.confirmFiles", "hash" to checkpoint.hash.take(8))
                   else t("chat.rewind.confirmConversation")
    if (Messages.showYesNoDialog(project, question, t("chat.rewind.title"), Messages.getWarningIcon()) != Messages.YES) return
    val copy = if (recordIndex == 0) history.create(project.basePath, project.name)
               else history.branch(threadId, recordIndex - 1) ?: return
    activateThread(copy.id)
    composer.restoreDraft(com.vibe.agent.ui.composer.ComposedMessage(record.text, emptyList(), emptyList()))
    if (checkpoint == null || service == null) {
      systemLine(t("chat.rewind.conversationOnly"))
      return
    }
    ApplicationManager.getApplication().executeOnPooledThread {
      val restored = service.restore(checkpoint)
      systemLine(if (restored) t("chat.rewind.filesDone", "hash" to checkpoint.hash.take(8)) else t("chat.checkpoint.failed"))
      ApplicationManager.getApplication().invokeLater {
        com.intellij.openapi.vfs.VfsUtil.markDirtyAndRefresh(true, true, true, com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(project.basePath!!))
      }
    }
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
      add(metaArea(title, Font.ITALIC, 11f).apply { lineWrap = false }, BorderLayout.WEST)
    }
    return ChatRow(BorderLayout()).apply {
      border = JBUI.Borders.empty(1, 4)
      add(card, BorderLayout.WEST)
    }
  }

  private fun userBubble(text: String) {
    lastUserText = text.trim().take(DECISION_QUESTION_CHARS)
    // The record is stored before the bubble is drawn, so its index is the last one — that is what
    // makes the pin and the branch act on THIS message and not on a neighbour.
    val index = currentThreadId?.let { history.get(it)?.messages?.lastIndex } ?: -1
    SwingUtilities.invokeLater {
      val row = buildUserRow(text, now(), index)
      messages.add(row)
      recordRows.add(row)
      revalidateScroll()
    }
    turns.chat.message = null
  }

  /** Streams into the live row of [turn]; the store gets the full text at finish. */
  private fun appendAgentText(text: String, turn: TurnState = turns.chat) {
    turn.text.append(text)
    SwingUtilities.invokeLater {
      if (turnThreadId != currentThreadId) return@invokeLater
      var m = turn.message
      if (m == null) {
        // A stray delta after the turn finished must not spawn a ghost empty row.
        if (!turnInFlight.get()) return@invokeLater
        m = AgentMessage()
        turn.message = m
        turn.feed.addRecord(m.row)
      }
      // Project from the turn's buffer: a tab switch mid-stream re-rendered the row from it already.
      val full = turn.text.toString()
      if (turn.uiConsumed < full.length) {
        m.append(full.substring(turn.uiConsumed))
        turn.uiConsumed = full.length
      }
      revalidateScroll()
    }
  }

  private fun finishAgentBubble(seconds: Double, suffix: String?, turn: TurnState = turns.chat) {
    val threadId = turnThreadId
    // Atomic capture+clear: queued per-delta projections then see an empty buffer and no-op.
    val fullText = synchronized(turn.text) {
      val t = turn.text.toString()
      turn.text.setLength(0)
      t
    }
    // The direct model's reasoning, kept with its answer: a model that requires it back (ECHO_REASONING) gets it in
    // the next request. An ACP agent's thoughts have no next request of ours to go into.
    val reasoning = synchronized(turn.reasoning) {
      val r = turn.reasoning.toString()
      turn.reasoning.setLength(0)
      r.ifBlank { null }
    }
    val toolRounds = synchronized(turn.toolRounds) {
      com.vibe.agent.providers.ToolRounds.forStorage(turn.toolRounds.toList()).also { turn.toolRounds.clear() }
    }
    val responses = turn.responses.also { turn.responses = null }
    // A turn that produced no words used to leave NOTHING in the thread — the question alone, and
    // the reason shown once in the feed and never written down. Reopening the IDE then showed a
    // conversation where the agent had simply not answered (eight such threads in the owner's store,
    // 18.09.2026). The outcome is a record now, and the rounds it did run stay with it.
    if (threadId != null && fullText.isBlank()) {
      val note = t("chat.turnNoAnswer", "outcome" to (suffix ?: "").ifBlank { t("chat.turnNoAnswerUnknown") })
      history.append(threadId, ChatMessageRecord(Role.OTHER, note, at = nowIso(), toolRounds = toolRounds))
    }
    if (threadId != null && fullText.isNotBlank()) {
      history.append(threadId, ChatMessageRecord(Role.ASSISTANT, fullText, at = nowIso(), reasoning = reasoning, toolRounds = toolRounds,
                                                 responses = responses))
      // The provider's own numbers when it reported them; the old length-based guess only when it
      // did not. The guess counted the ANSWER and nothing else, so a request carrying two hundred
      // thousand tokens of context cost, in the report, as much as the sentence it produced —
      // which is why the spending ceiling never fired on this path.
      val usage = turn.usage
      val counted = if (usage.known) usage.total
                    else com.vibe.agent.context.ContextBudget.estimateTokens(fullText)
      // The moment of the turn, for the price by the hour: off-peak DeepSeek bills the same work at half.
      val finishedAt = java.time.Instant.now()
      val pricing = turn.pricing
      val cost = pricing?.costOf(usage, finishedAt)
      // Taken before the reset below: read after it, every spend line went down in the default currency.
      val currency = pricing?.currency ?: com.vibe.agent.providers.ModelPricing.DEFAULT_CURRENCY
      pricing?.cacheSavingOf(usage, finishedAt)?.takeIf { it > 0 }?.let { saved ->
        systemLine(t("spend.cacheSaved", "saved" to "%.2f".format(saved),
                     "tokens" to "%,d".format(usage.cacheReadTokens)))
      }
      if (usage.known) {
        threadUsages.add(usage)
        while (threadUsages.size > MAX_TRACKED_TURNS) threadUsages.removeAt(0)
      }
      turn.usage = com.vibe.agent.providers.TokenUsage.NONE
      turn.pricing = null
      sessionTokens.addAndGet(counted)
      // The chat's attached files belong to the chat's turn; a step's prompt carries none of them.
      val attachments = if (turn.role == null) turnAttachments else emptyList()
      com.vibe.agent.budget.VibeSpendService.getInstance().record(
        turn.role, targetLabel(), counted, cost, cost?.let { currency },
        com.vibe.agent.budget.FileSpend.attribute(counted, attachments), threadId)
      stretchTokens.addAndGet(counted)
    }
    // Счётчик прямого провода: у ACP-агента окно считает он сам и присылает `usage_update`, а у
    // прямой модели такого кадра нет вовсе — и до 18.09.2026 счётчик у неё просто не появлялся,
    // то есть нажать на него и посмотреть разбивку было не на чем.
    SwingUtilities.invokeLater {
      // Доля окна МОДЕЛИ, а не потолка сессии: кольцо отвечает на «сколько осталось в этом
      // разговоре», и окно — это то, что кончается первым и молча.
      val window = lastContextWindow
      val percent = if (window != null && window > 0) (lastContext.total * 100 / window).toInt() else null
      composer.setUsage(
        percent = percent,
        tooltip = t("context.popup.title"),
        warn = percent != null && percent >= USAGE_WARN_PCT,
      )
    }
    // The turn's message is EDT-owned (appendAgentText also touches it on the EDT); read+clear it there.
    SwingUtilities.invokeLater {
      val m = turn.message
      turn.message = null
      // Each response (turn or gate sub-turn) gets its own reasoning block; reset on the EDT.
      // Заголовок блока досказывается ДО сброса ссылки: иначе он навсегда остаётся на «думает…».
      turn.thoughts?.finish()
      turn.thoughts = null
      if (m != null) {
        // Flush the tail the per-delta projections did not reach before the buffer was cleared.
        if (turn.uiConsumed < fullText.length) m.append(fullText.substring(turn.uiConsumed))
        m.finish(seconds, suffix)
      }
      turn.uiConsumed = 0
      revalidateScroll()
    }
  }

  /** Compact tool-call card, VibeIDE style: 4px radius, quiet border, 11px italic. */
  private fun toolCard(title: String, turn: TurnState = turns.chat) {
    // Out-of-turn agent notifications land in the visible thread, not in a finished turn's one.
    val targetThread = (if (turnInFlight.get()) turnThreadId else null) ?: currentThreadId
    history.append(targetThread, ChatMessageRecord(Role.OTHER, title, at = nowIso()))
    SwingUtilities.invokeLater {
      if (targetThread != currentThreadId) return@invokeLater
      turn.feed.addRecord(buildToolRow(title))
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

  /**
   * A line of the feed that also stays in the thread.
   *
   * [systemLine] draws and forgets, which is right for chatter and wrong for the reason a turn
   * ended: after a restart the thread held the question and no trace of what happened to it.
   */
  private fun turnNote(text: String) {
    systemLine(text)
    val threadId = turnThreadId ?: currentThreadId
    if (threadId.isNotEmpty()) history.append(threadId, ChatMessageRecord(Role.OTHER, text, at = nowIso()))
  }

  /**
   * Why an answer ended when it did not simply finish. The vendor's own word stays visible for a reason we do not know:
   * a line that names it is a lead, a line that guesses is a false one.
   */
  private fun stopNote(reason: com.vibe.agent.providers.StopReason): String = when (reason.kind) {
    com.vibe.agent.providers.StopReason.Kind.LENGTH -> t("chat.stop.length")
    com.vibe.agent.providers.StopReason.Kind.REFUSAL ->
      (reason.category?.let { t("chat.stop.refusal", "category" to it) } ?: t("chat.stop.refusalUncategorized")) +
        (reason.explanation?.let { " " + t("chat.stop.vendorSays", "text" to it) } ?: "")
    com.vibe.agent.providers.StopReason.Kind.CONTENT_FILTER -> t("chat.stop.contentFilter")
    com.vibe.agent.providers.StopReason.Kind.REPETITION -> t("chat.stop.repetition")
    else -> t("chat.stop.other", "reason" to reason.raw)
  }

  private fun systemLine(text: String) {
    SwingUtilities.invokeLater {
      messages.add(metaArea(text, Font.PLAIN, 10f).apply {
        alignmentX = Component.LEFT_ALIGNMENT
        border = JBUI.Borders.empty(2, 4)
      })
      revalidateScroll()
    }
  }

  /**
   * Служебная строка ленты — текстом, а не подписью.
   *
   * Из `JLabel` нельзя выделить ни слова, и человек, выделивший разговор целиком, получал его с
   * дырами на месте строк вроде «[providers] …» и имён вызванных инструментов.
   */
  private fun metaArea(text: String, style: Int, size: Float): JTextArea = JTextArea(text).apply {
    isEditable = false
    isOpaque = false
    lineWrap = true
    wrapStyleWord = true
    font = com.intellij.util.ui.JBFont.label().deriveFont(style, size)
    foreground = META_FG
    border = JBUI.Borders.empty()
  }

  private fun revalidateScroll() {
    // Следование за потоком — при ДВУХ условиях, и второе появилось 18.09.2026. Первое прежнее:
    // человек уже внизу, иначе читающего историю сдёргивало бы каждой дельтой. Второе: ничего не
    // выделено — лента, прыгнувшая вниз посреди протяжки, уводит текст из-под мыши, и выделить
    // хвост длинного ответа становится нельзя. Вернуться вниз — кнопка «в конец».
    val follow = feedEnd.isAtEnd() && !feedSelection.isActive()
    messages.revalidate()
    messages.repaint()
    if (follow) SwingUtilities.invokeLater { feedEnd.scrollToEnd() }
    feedEnd.update()
  }

  // --- AcpClient.Handler (reader thread) ---

  override fun onSessionUpdate(update: JsonObject) {
    val u = update["update"] as? JsonObject ?: return
    // Every update names its session, and a step of a wave runs in its own: the update belongs to that step's turn.
    val sessionId = update["sessionId"]?.jsonPrimitive?.contentOrNull
    val turn = turns.of(sessionId)
    // Any frame at all is a sign of life — including one we do not handle below.
    noteActivity()
    when (u["sessionUpdate"]?.jsonPrimitive?.contentOrNull) {
      "agent_message_chunk" -> {
        val text = (u["content"] as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull ?: return
        turn.answer?.append(text)
        appendAgentText(text, turn)
      }
      "agent_thought_chunk" -> {
        val text = (u["content"] as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull ?: return
        appendThought(text, turn)
      }
      "usage_update" -> onUsageUpdate(u, turn, sessionId)
      "tool_call" -> {
        enforceStepLimits(turn, toolCalls = turn.toolCallCount.incrementAndGet())
        val call = turn.toolCalls.onToolCall(u)
        if (call != null) {
          auditToolCall(AuditEvent.Action.TOOL_CALL_START, call, turn); harvestMutation(call, turn); noteLoop(call, turn)
          turn.toolStarts[call.id] = System.currentTimeMillis()
        }
        toolCard(call?.title ?: u["title"]?.jsonPrimitive?.contentOrNull ?: u["kind"]?.jsonPrimitive?.contentOrNull ?: t("chat.tool"), turn)
        // Claude adapter announces a terminal for this tool-call — open a live console.
        terminalInfoId(u)?.let { openTerminalConsole(it, call?.title ?: t("chat.terminal"), turn) }
      }
      "tool_call_update" -> {
        val call = turn.toolCalls.onToolCallUpdate(u) ?: return
        harvestMutation(call, turn)
        // Stream Claude Bash output / exit into the console for this terminal.
        terminalOutputFrame(u)?.let { (id, data) -> appendTerminalOutput(id, data) }
        terminalExitFrame(u)?.let { (id, code, sig) -> markTerminalExit(id, code, sig) }
        if (call.isDone) {
          auditToolCall(AuditEvent.Action.TOOL_CALL_DONE, call, turn)
          noteOutcome(call)
          val startedAt = turn.toolStarts.remove(call.id)
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
      // The plan panel follows one plan: the chat's, or a step's when the step runs alone. Steps of a wave would
      // repaint it over each other.
      "plan" -> if (turn.feed === mainFeed) onPlanUpdate(u)
      else -> {}
    }
  }

  // --- Claude terminal stream (_meta.terminal_*) rendering ---

  private fun metaObj(u: JsonObject): JsonObject? = u["_meta"] as? JsonObject
  private fun terminalInfoId(u: JsonObject): String? =
    (metaObj(u)?.get("terminal_info") as? JsonObject)?.get("terminal_id")?.jsonPrimitive?.contentOrNull
  private fun terminalOutputFrame(u: JsonObject): Pair<String, String>? {
    val o = metaObj(u)?.get("terminal_output") as? JsonObject ?: return null
    val id = o["terminal_id"]?.jsonPrimitive?.contentOrNull ?: return null
    return id to (o["data"]?.jsonPrimitive?.contentOrNull ?: "")
  }
  private fun terminalExitFrame(u: JsonObject): Triple<String, Int?, String?>? {
    val o = metaObj(u)?.get("terminal_exit") as? JsonObject ?: return null
    val id = o["terminal_id"]?.jsonPrimitive?.contentOrNull ?: return null
    return Triple(id, o["exit_code"]?.jsonPrimitive?.intOrNull, o["signal"]?.jsonPrimitive?.contentOrNull)
  }

  private fun openTerminalConsole(terminalId: String, title: String, turn: TurnState) {
    val targetThread = (if (turnInFlight.get()) turnThreadId else null) ?: currentThreadId
    SwingUtilities.invokeLater {
      if (targetThread != currentThreadId || terminalConsoles.containsKey(terminalId)) return@invokeLater
      val console = TerminalConsole(title)
      terminalConsoles[terminalId] = console
      // Not a record: consoles are not history records, and would shift the reveal index.
      turn.feed.addExtra(console)
      revalidateScroll()
    }
  }

  private fun appendTerminalOutput(terminalId: String, data: String) {
    if (data.isEmpty()) return
    SwingUtilities.invokeLater { terminalConsoles[terminalId]?.append(data) }
  }

  /** ACP usage_update {used, size, cost?} → compact context chip in the composer. */
  private fun onUsageUpdate(u: JsonObject, turn: TurnState, sessionId: String?) {
    val used = u["used"]?.jsonPrimitive?.longOrNull ?: return
    val size = u["size"]?.jsonPrimitive?.longOrNull ?: return
    if (size <= 0) return
    // The window of a step's own session is that step's business: its ceiling reads it, the chat's ring does not.
    if (turn !== turns.chat) {
      enforceStepLimits(turn, usedTokens = used)
      recordSessionSpend(u, turn, sessionId, used)
      return
    }
    val pct = (used * 100 / size).toInt().coerceIn(0, 100)
    val cost = (u["cost"] as? JsonObject)?.let { c ->
      val amount = c["amount"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
      val currency = c["currency"]?.jsonPrimitive?.contentOrNull ?: ""
      amount?.let { " · %.2f %s".format(it, currency).trimEnd() }
    } ?: ""
    SwingUtilities.invokeLater {
      composer.setUsage(
        percent = pct,
        tooltip = t("chat.contextTooltip", "used" to "%,d".format(used), "size" to "%,d".format(size)) + cost,
        warn = pct >= USAGE_WARN_PCT,
      )
    }
    enforceStepLimits(turn, usedTokens = used)
    noteWindowFill(used, size)
    recordSessionSpend(u, turn, sessionId, used)
  }

  /**
   * One usage report of a session, into the spend ledger.
   *
   * Recorded per role: a single total says the month cost money, a split by role says WHICH role burned it, and only
   * the second is something one can act on.
   */
  private fun recordSessionSpend(u: JsonObject, turn: TurnState, sessionId: String?, used: Long) {
    val amount = (u["cost"] as? JsonObject)?.get("amount")?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
    val currency = (u["cost"] as? JsonObject)?.get("currency")?.jsonPrimitive?.contentOrNull
    // Reports are cumulative per SESSION: a step's isolated session starts from zero while the chat's is at 80%. One
    // meter for both read the step's first report as a drop and recorded nothing, and the chat's next report counted
    // its whole total again.
    val meters = sessionMeters.computeIfAbsent(sessionId ?: "") { SessionMeters() }
    // ACP reports the window total, and a new turn starts it lower again: without the floor the
    // difference goes negative and the day's spend silently shrinks.
    val delta = meters.used.advanceWhole(used)
    // The COST is cumulative in the same object, and it used to be recorded whole on every update.
    // An update arrives many times per turn, so the ledger was adding the running total again and
    // again — the report exaggerated by as many times as the agent reported progress. It showed as
    // nothing while money was only a column in a report; it became a wall the moment a ceiling
    // started reading it.
    val costDelta = meters.cost.advance(amount)
    val attachments = if (turn.role == null) turnAttachments else emptyList()
    com.vibe.agent.budget.VibeSpendService.getInstance()
      // The split between the turn's files is an estimate by size — a request is billed whole.
      .record(turn.role, targetLabel(), delta, costDelta, currency?.takeIf { costDelta != null },
              com.vibe.agent.budget.FileSpend.attribute(delta, attachments), currentThreadId, pipelineRunId)
    stretchTokens.addAndGet(delta)
  }

  /** ACP reports the window total, not a delta: the difference is what this turn actually added. */
  /**
   * Background jobs of the PROJECT, not of this panel.
   *
   * Two registries would mean a job visible in the chat and invisible in the tool window — worse
   * than no list at all, because a person who read «нет задач» would believe it.
   */
  private val tasks get() = com.vibe.agent.background.VibeTasksService.getInstance(project).registry

  /** Repaints an open task panel; the chat and the panel must never disagree about what runs. */
  private fun tasksChanged() {
    if (!project.isDisposed) {
      project.messageBus.syncPublisher(com.vibe.agent.background.TasksChangeListener.TOPIC).tasksChanged()
    }
  }

  /** Where images stop being carried; only ever moves forward, and only when a new one arrives. */
  @Volatile private var imageCutIndex: Int? = null

  /** The previous turn's wire, to notice when an earlier message changed under us. */
  @Volatile private var lastWireLines: List<com.vibe.agent.history.WirePrefix.Line> = emptyList()
  /** Tool names of the previous direct request: a changed list rewrites the start of the request. */
  private var lastToolNames: List<String>? = null

  /** What the provider reported for the turn that just finished, and the price to apply to it. */
  /**
   * Начало прошлого хода прямого LLM-чата — по нему считается, жив ли ещё промпт-кэш.
   *
   * Именно НАЧАЛО: запись в кэш происходит, когда запрос уходит, и таймер тикает, пока модель
   * думает. Ход, отвечавший четыре минуты, оставляет от пятиминутного кэша одну.
   */
  @Volatile private var lastLlmTurnStartedAtMs: Long = 0

  /**
   * Расход ходов текущего разговора — для «контекстного налога» в `/trace`.
   *
   * Живёт в памяти окна и не переживает перезапуск: вопрос «во что мне обходится ЭТОТ разговор»
   * задают по ходу дела, а на длинную дистанцию отвечает журнал расхода.
   */
  private val threadUsages = java.util.Collections.synchronizedList(ArrayList<com.vibe.agent.providers.TokenUsage>())

  /** Cumulative reports of one session, converted to increments — see [com.vibe.agent.budget.CumulativeMeter]. */
  private class SessionMeters {
    val used = com.vibe.agent.budget.CumulativeMeter()
    val cost = com.vibe.agent.budget.CumulativeMeter()
  }

  /** Meters by session id: the chat's session and every isolated session of a step report their own totals. */
  private val sessionMeters = java.util.concurrent.ConcurrentHashMap<String, SessionMeters>()

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

  /**
   * Показать (или обновить) строку «сейчас работаю» в конце ленты.
   *
   * В ленте, а не в углу композера: взгляд во время хода смотрит в конец разговора, и индикатор,
   * стоящий в другом месте экрана, отвечает на вопрос «жив ли он» тому, кто туда посмотрит.
   */
  private fun showWorking(kind: WorkingLine.Kind, detail: String? = null) {
    SwingUtilities.invokeLater {
      if (disposed || !turnInFlight.get()) return@invokeLater
      val line = workingLine ?: WorkingLine().also {
        workingLine = it
        messages.add(it)
      }
      line.setKind(kind, detail)
      revalidateScroll()
    }
  }

  /** Убрать строку и остановить её таймер: живой Timer держал бы панель после конца хода. */
  private fun hideWorking() {
    SwingUtilities.invokeLater {
      val line = workingLine ?: return@invokeLater
      workingLine = null
      line.stop()
      messages.remove(line)
      messages.revalidate()
      messages.repaint()
    }
  }

  /** Stream the agent's reasoning into a collapsible block on the turn's thread. */
  private fun appendThought(text: String, turn: TurnState = turns.chat) {
    val targetThread = (if (turnInFlight.get()) turnThreadId else null) ?: currentThreadId
    SwingUtilities.invokeLater {
      if (targetThread != currentThreadId) return@invokeLater
      var block = turn.thoughts
      if (block == null) {
        if (!turnInFlight.get()) return@invokeLater
        block = ThoughtsBlock()
        turn.thoughts = block
        // Reasoning precedes the answer: when the answer row already streams, insert ABOVE it.
        // Not a record: reasoning is not a history record (it would shift the reveal index).
        turn.feed.addExtra(block, turn.message?.row)
      }
      block.append(text)
      revalidateScroll()
    }
  }

  private fun markTerminalExit(terminalId: String, exitCode: Int?, signal: String?) {
    val owner = turns.all().firstOrNull { turn -> turn.toolCalls.snapshot().any { it.terminalId == terminalId } } ?: turns.chat
    audit?.append(AuditEvent(System.currentTimeMillis(), AuditEvent.Action.TERMINAL, ok = exitCode == 0, actor = agentActor(owner),
      callId = callIdOfTerminal(terminalId), turnId = turnId, sessionId = turnThreadId ?: currentThreadId,
      meta = mapOf("exit" to (exitCode?.toString() ?: "signal:${signal ?: "?"}"))))
    SwingUtilities.invokeLater { terminalConsoles[terminalId]?.markExit(exitCode, signal) }
  }

  /**
   * Note that a turn touched files. Client-side writes reach [TurnState.changedPaths] via fs/write, but an
   * agent's own edit tools and Bash do not — so mark the turn as mutating (so the gates still run)
   * and harvest any declared edit path so turn-checks can scan it.
   */
  private fun harvestMutation(call: ToolCall, turn: TurnState) {
    val kind = call.kind
    val name = call.toolName
    val isNamedEdit = name != null && name in EDIT_TOOLS
    // execute-kind (a command) may change files invisibly → mark the turn mutating so the gate runs…
    if (isNamedEdit || kind in MUTATING_KINDS) turn.hadMutatingTool = true
    // …but only tools that actually WRITE contribute paths. ACP `locations` is read-inclusive, so
    // harvesting it for a command (`grep KEY .env`) would wrongly trip the protected-path breaker.
    val isWrite = isNamedEdit || kind == "edit" || kind == "delete" || kind == "move"
    if (isWrite) {
      call.rawInput?.let { ri -> for (key in EDIT_PATH_KEYS) ri[key]?.jsonPrimitive?.contentOrNull?.let { turn.changedPaths.add(it) } }
      turn.changedPaths.addAll(call.locations)
    }
  }

  /** Emit a privacy-filtered tool-call audit record (no args/command bodies — only tool + target path). */
  /**
   * Ход, к которому относятся записи журнала. Живёт от промпта до промпта.
   *
   * Не идентификатор сессии: сессия у агента одна на весь разговор, а разбор идёт по одному ходу —
   * «что было сделано после вот этой просьбы».
   */
  @Volatile private var turnId: String? = null

  /** Вызов, создавший терминал: выход процесса — следствие именно его. */
  private fun callIdOfTerminal(terminalId: String): String? =
    turns.all().firstNotNullOfOrNull { turn -> turn.toolCalls.snapshot().firstOrNull { it.terminalId == terminalId }?.id }

  private fun auditToolCall(action: String, call: ToolCall, turn: TurnState) {
    val log = audit ?: return
    val tool = call.toolName ?: call.kind ?: "tool"
    val target = ToolCallAudit.safeTargetPath(tool, call.rawParamsFlat(), call.kind)
    log.append(AuditEvent(
      ts = System.currentTimeMillis(),
      action = action,
      actor = agentActor(turn),
      ok = call.status != ToolCall.STATUS_FAILED,
      callId = call.id,
      turnId = turnId, sessionId = turnThreadId ?: currentThreadId,
      files = target?.let { listOf(it) },
      meta = mapOf("tool" to tool, "status" to call.status),
    ))
  }

  /**
   * `elicitation/create`: агент просит данные — форму или переход по адресу.
   *
   * Вопрос человеку, значит тот же звук, что и у разрешения: он ушёл, а ход стоит и ждёт его.
   * URL-режим спрашивает отдельно и словами — увод во внешний браузер по просьбе чужого агента
   * это действие наружу, и молча его делать нельзя.
   */
  /**
   * `elicitation/complete`: внешний вход закончился, ход поехал дальше.
   *
   * Человек в этот момент смотрит в браузер, а не в IDE, поэтому строка в ленте — единственный
   * способ узнать, что возвращаться уже можно. Ответа нотификация не ждёт.
   */
  override fun onElicitComplete(params: JsonObject) {
    systemLine(t("elicit.url.completed"))
    audit?.append(AuditEvent(System.currentTimeMillis(), AuditEvent.Action.ELICITATION, ok = true,
                             actor = agentActor(), meta = mapOf("mode" to "url", "event" to "complete")))
  }

  override fun onElicit(params: JsonObject): JsonElement {
    com.vibe.agent.sound.VibeSoundService.getInstance()
      .play(com.vibe.agent.sound.SoundPolicy.Event.AWAITING_PERMISSION, project)
    val request = com.vibe.agent.acp.Elicitation.parse(params)
    return when (request.mode) {
      com.vibe.agent.acp.Elicitation.Mode.FORM -> {
        var values: Map<String, String> = emptyMap()
        var accepted = false
        ApplicationManager.getApplication().invokeAndWait {
          val dialog = com.vibe.agent.acp.ElicitationDialog(project, request)
          accepted = dialog.showAndGet()
          if (accepted) values = dialog.values()
        }
        // В журнал — ИМЕНА полей и исход, никогда значения: в форму вводят и токены тоже.
        audit?.append(AuditEvent(System.currentTimeMillis(), AuditEvent.Action.ELICITATION, ok = accepted,
                                 actor = agentActor(),
                                 meta = mapOf("mode" to "form", "fields" to request.fields.joinToString { it.name })))
        if (!accepted) {
          systemLine(t("elicit.declined"))
          com.vibe.agent.acp.Elicitation.response(com.vibe.agent.acp.Elicitation.Outcome.DECLINE)
        }
        else {
          systemLine(t("elicit.sent", "fields" to values.keys.joinToString()))
          com.vibe.agent.acp.Elicitation.response(
            com.vibe.agent.acp.Elicitation.Outcome.ACCEPT, values, request.fields)
        }
      }
      com.vibe.agent.acp.Elicitation.Mode.URL -> {
        val url = request.url
        if (url.isNullOrBlank()) {
          systemLine(t("elicit.unsupported"))
          return com.vibe.agent.acp.Elicitation.response(com.vibe.agent.acp.Elicitation.Outcome.DECLINE)
        }
        var open = false
        ApplicationManager.getApplication().invokeAndWait {
          open = com.intellij.openapi.ui.Messages.showYesNoDialog(
            project,
            t("elicit.url.body", "message" to (request.message ?: ""), "url" to url),
            t("elicit.url.title"),
            t("elicit.url.open"),
            t("elicit.url.decline"),
            com.intellij.icons.AllIcons.General.QuestionDialog,
          ) == com.intellij.openapi.ui.Messages.YES
        }
        // Уход во внешний браузер — действие наружу, и в журнале ему место наравне с правкой файла.
        audit?.append(AuditEvent(System.currentTimeMillis(), AuditEvent.Action.ELICITATION, ok = open,
                                 actor = agentActor(), meta = mapOf("mode" to "url", "url" to url)))
        if (!open) {
          systemLine(t("elicit.declined"))
          com.vibe.agent.acp.Elicitation.response(com.vibe.agent.acp.Elicitation.Outcome.DECLINE)
        }
        else {
          com.intellij.ide.BrowserUtil.browse(url)
          // В URL-режиме данных у нас нет: согласие — это и есть весь ответ.
          com.vibe.agent.acp.Elicitation.response(com.vibe.agent.acp.Elicitation.Outcome.ACCEPT)
        }
      }
      com.vibe.agent.acp.Elicitation.Mode.UNKNOWN -> {
        systemLine(t("elicit.unsupported"))
        com.vibe.agent.acp.Elicitation.response(com.vibe.agent.acp.Elicitation.Outcome.DECLINE)
      }
    }
  }

  override fun onRequestPermission(params: JsonObject): JsonElement {
    // Steps of a wave ask at once, each in its own session: the question is the asking step's, with its signals.
    val turn = turns.of(params["sessionId"]?.jsonPrimitive?.contentOrNull)
    val turnSignals = turn.signals
    // The most important of the three events: here a person is needed RIGHT NOW, and they left.
    com.vibe.agent.sound.VibeSoundService.getInstance()
      .play(com.vibe.agent.sound.SoundPolicy.Event.AWAITING_PERMISSION, project)
    val toolCall = params["toolCall"] as? JsonObject
    val permissionCallId = toolCall?.get("toolCallId")?.jsonPrimitive?.contentOrNull
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
    // Трифекта: чтение приватного собственным инструментом агента. До нас доходит не само чтение,
    // а просьба разрешить его, — и это единственное место, где такое чтение вообще видно.
    if (com.vibe.agent.guard.Trifecta.readsPrivateData(hookTool, hookParams?.keys.orEmpty())) {
      turnSignals.add(com.vibe.agent.guard.Trifecta.Signal.PRIVATE_DATA)
    }
    // Трифекта: канал наружу — третий признак. Считается ДО диалога, чтобы человек увидел
    // предупреждение в том же вопросе, а не после того, как разрешил.
    val outbound = command?.let { com.vibe.agent.guard.Trifecta.outboundInLine(it) }
    if (outbound != null) turnSignals.add(com.vibe.agent.guard.Trifecta.Signal.OUTBOUND_CHANNEL)
    val trifecta = com.vibe.agent.guard.Trifecta.complete(turnSignals)
    val base = if (destructive != null)
      t("chat.permission.destructive", "reasons" to destructive.reasons.joinToString(", "), "command" to command.take(DESTRUCTIVE_PREVIEW_LEN), "title" to title)
    else title
    // Три признака за один ход названы человеку словами: по отдельности каждый законен, и молча
    // разрешённое здесь — единственное место, где утечка выглядит как обычная работа.
    val asked = if (trifecta) t("chat.permission.trifecta", "channel" to (outbound ?: ""), "title" to base) else base
    // Two steps may ask at once: the dialog names the one that asks, or «разрешить» answers the wrong one.
    val dialogText = turn.label?.let { t("chat.permission.fromStep", "step" to it, "text" to asked) } ?: asked
    val options = params["options"]?.jsonArray?.map { it.jsonObject } ?: emptyList()
    val chosen = askOnEdt {
      val names = options.map { it["name"]?.jsonPrimitive?.contentOrNull ?: it.getValue("optionId").jsonPrimitive.content }
      // Понижение доверия на ходу, где сошлись все три признака: диалог перестаёт выглядеть
      // рутинным и перестаёт предлагать «да».
      //
      // Запрет здесь по-прежнему НЕ ставится — решение №49 выбрало вопрос, а не блокировку,
      // осознанно: правило, срабатывающее часто и зря, перестают читать. Но вопрос, у которого
      // значок «?» и первая кнопка «разрешить», отвечается не глядя, а именно этот вопрос —
      // единственное место, где утечка выглядит как обычная работа.
      val defaultOption = if (trifecta) indexOfRefusal(options) else 0
      val choice = Messages.showDialog(project, dialogText, t("chat.permission.title"), names.toTypedArray(), defaultOption,
        if (destructive != null || trifecta) Messages.getWarningIcon() else Messages.getQuestionIcon())
      if (choice >= 0) options[choice].getValue("optionId").jsonPrimitive.content else null
    }
    audit?.append(AuditEvent(System.currentTimeMillis(), AuditEvent.Action.PERMISSION, ok = chosen != null, actor = com.vibe.agent.audit.AuditActor.HUMAN,
      callId = permissionCallId, turnId = turnId, sessionId = turnThreadId ?: currentThreadId,
      meta = mapOf("title" to title.take(120), "outcome" to if (chosen != null) "selected" else "cancelled") +
        (if (trifecta) mapOf("trifecta" to (outbound ?: "")) else emptyMap())))
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

  override fun onReadTextFile(params: JsonObject): JsonElement {
    // Прочитанный файл проекта — приватные данные: один из трёх признаков трифекты.
    turns.of(params["sessionId"]?.jsonPrimitive?.contentOrNull).signals.add(com.vibe.agent.guard.Trifecta.Signal.PRIVATE_DATA)
    return fileOps.readTextFile(params)
  }

  override fun onWriteTextFile(params: JsonObject): JsonElement {
    // The boundary is the asking step's: with steps running at once, «the step in force» is not one step.
    val turn = turns.of(params["sessionId"]?.jsonPrimitive?.contentOrNull)
    // The hook, the changed-files list and the audit get the path as it will be written: with the
    // agent's `..` left in, a hook's own path pattern could be walked around the same way.
    val path = params["path"]?.jsonPrimitive?.contentOrNull?.let { fileOps.resolvePath(it).normalized.toString() }
    val resolved = if (path == null) params
                   else JsonObject(params + ("path" to kotlinx.serialization.json.JsonPrimitive(path)))
    // preToolUse hook on the client-controlled write path (WritePreview is the interactive gate;
    // a blocking hook refuses before the diff even appears).
    val preHook = runToolHook(HookEvent.PRE_TOOL_USE, "write_text_file", resolved)
    if (preHook.blocked) {
      systemLine("🪝 ${preHook.agentMessage}")
      throw IllegalStateException(preHook.agentMessage ?: t("chat.write.rejectedByHook"))
    }
    path?.let { turn.changedPaths.add(it) }
    val result = fileOps.writeTextFile(resolved, turn.role, turn.scope)
    audit?.append(AuditEvent(System.currentTimeMillis(), AuditEvent.Action.FS_WRITE, ok = true, actor = agentActor(turn),
      turnId = turnId, sessionId = turnThreadId ?: currentThreadId, files = path?.let { listOf(it.take(ToolCallAudit.MAX_TARGET_LEN)) }))
    return result
  }

  // --- standard ACP terminal/… (non-Claude agents that delegate execution to us) ---

  override fun onCreateTerminal(params: JsonObject): JsonElement {
    if (!VibeAgentSettings.terminalEnabled) throw IllegalStateException(t("chat.terminal.disabled"))
    // A role that only judges cannot run commands either: running a command is how a read-only
    // role writes anyway. The role is the asking step's.
    val turn = turns.of(params["sessionId"]?.jsonPrimitive?.contentOrNull)
    val role = turn.role
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
    // Канал наружу считается и здесь: агент, делегирующий запуск нам, открывает его тем же
    // способом, что и агент, запускающий команды сам.
    if (com.vibe.agent.guard.Trifecta.outboundReason(command, args) != null) {
      turn.signals.add(com.vibe.agent.guard.Trifecta.Signal.OUTBOUND_CHANNEL)
    }
    // Destructive-command gate: same deterministic classifier as VibeIDE, asked before execution.
    val verdict = ShellSafetyAnalyzer.analyzeLine((listOf(command) + args).joinToString(" "))
    if (verdict != null) {
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
      val approved = askOnEdt {
        com.vibe.agent.telegram.ApprovalDialog.ask(
          project, t("chat.destructive.title"), body, request, onPhone, t("chat.destructive.run"))
      }
      audit?.append(AuditEvent(System.currentTimeMillis(), AuditEvent.Action.TERMINAL, ok = approved, actor = com.vibe.agent.audit.AuditActor.HUMAN,
        turnId = turnId, sessionId = turnThreadId ?: currentThreadId,
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
    SwingUtilities.invokeLater { modePicker.setModes(client?.modes); configPicker.setOptions(client?.configOptions) }
  }

  /**
   * The boundary where a process with the person's authority begins: what was started, where,
   * whether it may run commands through us, and which secrets went with it. «Что вообще было
   * запущено» has no other answer — the protocol log is not a journal and does not survive the IDE.
   * The command is masked like any text that may carry a token; secrets are named, never shown.
   */
  private fun auditAgentStart(config: AgentServerConfig, workingDir: String?) {
    val secrets = config.env.values.flatMap { com.vibe.agent.security.SecretRefs.names(it) }.distinct()
    val dir = workingDir?.let { wd -> project.basePath?.let { com.vibe.agent.context.AccessPolicy.relativeTo(wd, it) } ?: wd }
    audit?.append(AuditEvent(System.currentTimeMillis(), AuditEvent.Action.AGENT_START, ok = true,
      actor = com.vibe.agent.audit.AuditActor.IDE,
      meta = buildMap {
        put("agent", config.name)
        put("command", com.vibe.agent.security.SecretPatterns.redact((listOf(config.command) + config.args).joinToString(" "))
          .take(DESTRUCTIVE_PREVIEW_LEN))
        dir?.let { put("dir", it.ifEmpty { "." }) }
        put("terminal", VibeAgentSettings.terminalEnabled.toString())
        if (secrets.isNotEmpty()) put("secrets", secrets.joinToString(","))
      }))
  }

  /** The agent says how the turn is billed: its own label and detail, never the account. */
  override fun onAuthStatus(label: String, detail: String?) {
    systemLine(t("chat.agentAuthStatus", "label" to (if (detail.isNullOrBlank()) label else "$label — $detail")))
  }

  override fun onProcessExit(client: AcpClient, code: Int) {
    synchronized(clientLock) {
      // An exit of a client we already replaced is not ours to react to.
      if (this.client !== client) return
      this.client = null
      clientConfig = null
      threadSessions.clear()
    }
    // The other end of the boundary audited in [auditAgentStart]: an agent that dies mid-run leaves
    // the journal its exit code, not only a line in a feed nobody kept.
    audit?.append(AuditEvent(System.currentTimeMillis(), AuditEvent.Action.AGENT_EXIT, ok = code == 0,
                             actor = com.vibe.agent.audit.AuditActor.IDE, meta = mapOf("code" to code.toString())))
    systemLine(t("chat.processExited", "code" to code))
    SwingUtilities.invokeLater { modePicker.setModes(null); configPicker.setOptions(null) }
    // No finishTurn() here: an idle agent's death must not end an unrelated (e.g. LLM) turn.
    // A turn that WAS talking to this process ends through its failed request futures.
  }

  private companion object {
    /**
     * How long a protocol sign-in may take. It waits for a person — the agent may have opened a
     * browser — rather than for a program, so the handshake's bound would cut it off mid-login.
     */
    const val SIGN_IN_TIMEOUT_MIN = 10L

    /** Ответ агента в поле «Решили»: длинный ответ в диалоге не читается, а правится. */
    const val DECISION_PREFILL_CHARS = 2000

    /** Вопрос — это заголовок; всё длиннее в заголовок и не поместится. */
    const val DECISION_QUESTION_CHARS = 200

    const val CASCADE_COMMAND = "/cascade"

    /** A handoff is a short answer, but it may finish an edit first: several handshake timeouts, not one. */
    const val HANDOFF_TIMEOUT_FACTOR = 3L

    /** UTF-8 spends at most four bytes on a character: a file above limit × 4 × 4 bytes cannot fit the pack. */
    const val MAX_UTF8_BYTES_PER_CHAR = 4
    const val GIT_LIST_TIMEOUT_SEC = 30L

    /** Сколько строк журнала читаем на отчёт: каскад — про недавнее, а не про всю историю проекта. */
    const val CASCADE_JOURNAL_LINES = 5_000

    /** Метка пропуска шага эскалации в журнале: по ней считается окупаемость каскада. */
    const val CASCADE_SKIP = "pipelineEscalationSkipped"
    /** Audit event of a step waiting for its model to leave the price peak. */
    const val OFF_PEAK_WAIT = "pipeline_off_peak_wait"
    /** How often a waiting step checks the clock, Stop and the notification buttons. */
    const val OFF_PEAK_POLL_MS = 1_000L

    /** Сколько ходов помнит счётчик контекстного налога: разговор длиннее — это уже журнал расхода. */
    const val MAX_TRACKED_TURNS = 200

    /** Сколько ответа шага уходит гейту: вердикт выносится по сути, а не по всему транскрипту. */
    const val GATE_ANSWER_CHARS = 4000

    const val SILENCE_CHECK_MS = 30_000
    const val OUTPUT_COMMAND = "/output"
    const val SPEND_COMMAND = "/spend"
    /** Per project: a skill of the same name in another repository is another skill. */
    const val SKILL_APPROVAL_KEY = com.vibe.agent.safety.ApprovalKeys.SKILL

    /** The approved path → hash map, beside the digest: lets the next question name what changed. */
    const val SKILL_APPROVED_FILES_KEY = com.vibe.agent.safety.ApprovalKeys.SKILL_FILES
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
    /** How often the waiting loop wakes up — short enough to notice the deadline, cheap enough to ignore. */
    const val BG_TICK_MS = 2_000L
    const val BG_LIST = "list"
    const val BG_STOP = "stop"
    const val BG_LIST_COMMAND_LEN = 60
    const val MEASURE_TIMEOUT_SEC = 900L
    const val INDEX_COMMAND = "/index"
    const val INDEX_PROGRESS_STEP = 25

    /** One manual is pages long; two of them plus the question still fit a modest window. */
    /** Сколько разделов набора уходит в ход /help: больше — и вопрос человека тонет в цитатах. */
    /** Как часто полоска перечитывает список живых процессов агента. */
    const val COMMANDS_POLL_MS = 3_000

    /** Команда в списке обрезается: полное имя живёт в подсказке. */
    const val COMMAND_LABEL_CHARS = 70

    const val HELP_SECTIONS = 5
    const val HELP_DOC_CHARS = 20_000
    const val COUNCIL_TIMEOUT_MS = 180_000L
    const val GIT_REPORT_LIMIT = 25
    const val PIN_ON = "📌"
    const val PIN_OFF = "📍"
    const val BRANCH_ICON = "⑂"
    const val REWIND_ICON = "↶"
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
    /** Truncation of a command preview shown in a destructive-command confirm dialog. */
    const val DESTRUCTIVE_PREVIEW_LEN = 300

    /** A team's memory lives on a host across the network; a connection that takes longer is one the chat should not wait on */
    val TEAM_MEMORY_CONNECT_TIMEOUT: java.time.Duration = java.time.Duration.ofSeconds(10)
    /** How much of a tool call's arguments the approval dialog shows: enough to recognise the record, not a wall of JSON. */
    const val DIRECT_TOOL_ARGS_PREVIEW = 600
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
