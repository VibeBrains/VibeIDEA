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
import com.vibe.agent.acp.IdeFileOps
import com.vibe.agent.checkpoints.CheckpointService
import com.vibe.agent.design.DesignContextFile
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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

  private val agents: List<AgentServerConfig> = AcpConfig.load { systemLine("[конфиг] $it") }
  @Volatile private var providers: List<ProviderEntry> = ProvidersService.load(project.basePath) { systemLine("[providers] $it") }
  /** Models declared in providers.json (before any catalog fetch) — they get the «providers.json» mark. */
  private val staticModelIds: Map<String, Set<String>> = providers.associate { p -> p.id to p.models.map { it.id }.toSet() }
  private val llmClient = LlmClient()
  private val llmCancel = java.util.concurrent.atomic.AtomicBoolean(false)
  private val fileOps = IdeFileOps(project)
  @Volatile private var client: AcpClient? = null
  @Volatile private var clientConfig: AgentServerConfig? = null
  /** Guards check-then-act on [client]: ensureClient (pooled), onProcessExit (exit thread), dispose (EDT). */
  private val clientLock = Any()
  private val checkpoints: CheckpointService? = project.basePath?.let { CheckpointService(it) }
  @Volatile private var stepBuffer: StringBuilder? = null
  @Volatile private var currentAgentMessage: AgentMessage? = null
  private val changedPaths = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

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
    systemLine("Vibe Agent готов. Агенты: ${agents.joinToString { it.name }}; провайдеры: ${providers.joinToString { it.name }.ifEmpty { "нет" }}.")
    systemLine("Ключи провайдеров: Settings → Tools → VibeIDEA → Провайдеры (или .vibe/.env). Реестры: ${AcpConfig.configPath()}, ~/.vibe/providers.json.")
    ProviderGuard.scan(providers).forEach { f -> systemLine("[guard:${f.severity}] ${f.message}") }
    rebuildTargets()
    fetchProviderModels()
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
    // The store is app-wide: an untagged thread can be open in another window too.
    if (!history.tryBeginTurn(threadId)) {
      systemLine("[тред] занят другим окном — дождитесь завершения его хода")
      return false
    }
    turnInFlight.set(true)
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
    if (disposed) return
    SwingUtilities.invokeLater {
      if (disposed) return@invokeLater
      composer.busy = false
      // Queued notes belong to the thread whose turn just ended, not to whichever tab is open now.
      composer.queue.drain()?.let { merged ->
        if (!startTurn(merged, endedThreadId ?: currentThreadId)) composer.restoreDraft(merged)
      }
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
    checkpoints?.create("сообщение: ${text.take(48)}")?.let { checkpointLine(it) }
    val design = DesignContextFile.load(project.basePath)
    val fullPrompt = if (design != null) DesignContextFile.promptBlock(design) + "\n" + text else text
    val c = ensureClient(t.config)
    SwingUtilities.invokeLater {
      modePicker.setModes(c.modes)
      composer.setImagesAllowed(c.capabilities?.image != false, NO_IMAGE_AGENT)
    }
    if (images.isNotEmpty() && c.capabilities?.image != true) systemLine("[агент] не принимает изображения — отправлено без вложений")
    val blocks = ContextSerializer.acpBlocks(fullPrompt, loaded, images, c.capabilities)
    c.prompt(blocks).whenComplete { result, error ->
      val secs = (System.currentTimeMillis() - startedAt) / 1000.0
      if (error != null) {
        // Whatever streamed before the failure stays in the transcript.
        finishAgentBubble(secs, "ошибка")
        systemLine("[ошибка] ${error.message}")
      }
      else {
        val stop = result?.jsonObject?.get("stopReason")?.jsonPrimitive?.contentOrNull
        finishAgentBubble(secs, stop)
      }
      finishTurn()
    }
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
      AcpClient(config, project.basePath, this).also {
        it.start()
        client = it
        clientConfig = config
      }
    }
    try {
      fresh.initializeAndOpenSession().get(HANDSHAKE_TIMEOUT_SEC, TimeUnit.SECONDS)
    }
    catch (e: TimeoutException) {
      synchronized(clientLock) {
        fresh.stop()
        if (client === fresh) { client = null; clientConfig = null }
      }
      throw IllegalStateException("агент не ответил на initialize/session/new за $HANDSHAKE_TIMEOUT_SEC с — проверьте команду и ACP-флаг в ${AcpConfig.configPath()}")
    }
    systemLine("[агент] сессия открыта" + (fresh.modes?.let { m -> " · режим: ${m.available.firstOrNull { it.id == m.currentModeId }?.name ?: m.currentModeId}" } ?: ""))
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
    val text = proseArea()
    val meta = metaLabel("Агент · ${now()}", right = false)
    val row: JPanel = object : ChatRow(BorderLayout(0, JBUI.scale(2))) {
      // better than the original: cap the text column so lines stay readable in a wide panel
      override fun getMaximumSize(): java.awt.Dimension =
        java.awt.Dimension(JBUI.scale(720), preferredSize.height)
    }.apply {
      border = JBUI.Borders.empty(4, 4, 8, 4)
      add(text, BorderLayout.CENTER)
      add(meta, BorderLayout.SOUTH)
    }
    fun append(s: String) { text.append(s) }
    fun finish(seconds: Double, suffix: String?) {
      meta.text = "Агент · ${now()} · ${"%.1f".format(seconds)} с${suffix?.let { " · $it" } ?: ""}"
    }
  }

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
    val m = currentAgentMessage
    currentAgentMessage = null
    SwingUtilities.invokeLater {
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
    /** A mute agent binary must not hold the composer busy forever. */
    const val HANDSHAKE_TIMEOUT_SEC = 60L
    const val KEY_OPEN_TABS = "vibe.chat.openTabs"
    /** Images ride the wire only for this many most recent user messages (cost + poison control). */
    const val MAX_IMAGE_HISTORY_MESSAGES = 4
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
