// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.telegram

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.ui.Messages
import com.vibe.agent.digest.DailyDigestAction
import com.vibe.agent.http.VibeAgentGateway
import com.vibe.agent.i18n.VibeI18n.t
import com.vibe.agent.resilience.ProxySettings
import com.vibe.agent.settings.VibeAgentSettings
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The Telegram bridge: give the agent a task, watch a long run and get the answer without sitting
 * at the computer.
 *
 * The bot is the user's own — created at @BotFather in a minute — so no traffic goes through
 * anything of ours, and the token lives in the OS keychain like any other key.
 *
 * Two rules define the whole thing. An unknown chat can do NOTHING until the owner approves it on
 * the desktop: the first message from a stranger asks for permission and reveals nothing, not even
 * which machine answered. And progress edits ONE message instead of sending a stream: a phone with
 * forty progress notifications is a phone with notifications turned off.
 */
@Service(Service.Level.APP)
class TelegramBridge {
  private val log = logger<TelegramBridge>()
  private val running = AtomicBoolean(false)
  private var offset = 0L

  @Volatile private var http: HttpClient = client()

  fun token(): String? =
    PasswordSafe.instance.get(attributes())?.getPasswordAsString()?.takeIf { it.isNotBlank() }

  fun setToken(token: String?) {
    PasswordSafe.instance.set(attributes(), token?.takeIf { it.isNotBlank() }?.let { Credentials(TOKEN_USER, it) })
  }

  fun allowedChats(): Set<Long> = VibeAgentSettings.telegramChats
    .split(',').mapNotNull { it.trim().toLongOrNull() }.toSet()

  fun allowChat(chatId: Long) {
    VibeAgentSettings.telegramChats = (allowedChats() + chatId).joinToString(",")
  }

  fun start() {
    if (token() == null || !running.compareAndSet(false, true)) return
    http = client()
    Thread({ pollLoop() }, "vibe-telegram").apply { isDaemon = true }.start()
  }

  fun stop() {
    running.set(false)
  }

  fun isRunning(): Boolean = running.get()

  // --- polling ---

  private fun pollLoop() {
    while (running.get()) {
      runCatching { pollOnce() }.onFailure {
        log.warn("telegram poll failed: " + it.message)
        // A failed poll must not turn into a hot loop against someone else's server.
        Thread.sleep(ERROR_PAUSE_MS)
      }
    }
  }

  private fun pollOnce() {
    val token = token() ?: run { running.set(false); return }
    val response = call(token, "getUpdates?timeout=" + LONG_POLL_SEC + "&offset=" + (offset + 1))
    val updates = TelegramProtocol.parseUpdates(response)
    for (update in updates) {
      offset = maxOf(offset, update.updateId)
      handle(token, update)
    }
  }

  private fun handle(token: String, incoming: TelegramProtocol.Incoming) {
    if (!TelegramProtocol.isAllowed(incoming.chatId, allowedChats())) {
      askOwner(incoming)
      send(token, TelegramProtocol.sendMessage(incoming.chatId, t("telegram.notAllowed")))
      return
    }
    when (val command = TelegramProtocol.parseCommand(incoming)) {
      is TelegramProtocol.Command.Projects -> send(token, TelegramProtocol.sendMessage(
        incoming.chatId, projects().joinToString("\n") { it }, projectButtons()))
      is TelegramProtocol.Command.Use -> {
        VibeAgentSettings.telegramProject = command.project
        send(token, TelegramProtocol.sendMessage(incoming.chatId, t("telegram.using", "project" to command.project)))
      }
      is TelegramProtocol.Command.Digest -> {
        val project = targetProject()
        send(token, TelegramProtocol.sendMessage(incoming.chatId,
          if (project == null) t("telegram.noProject") else DailyDigestAction.text(project)))
      }
      is TelegramProtocol.Command.Menu -> send(token, TelegramProtocol.sendMessage(
        incoming.chatId, t("telegram.menu"), menuButtons()))
      is TelegramProtocol.Command.Stop -> {
        // Stopping goes through the same gateway as a task: one door in, one door out.
        runCatching { VibeAgentGateway.getInstance().run(t("telegram.stopTask"), null, false) }
        send(token, TelegramProtocol.sendMessage(incoming.chatId, t("telegram.stopped")))
      }
      is TelegramProtocol.Command.Task -> runTask(token, incoming.chatId, command.text)
      is TelegramProtocol.Command.Approve -> {
        // Whoever answers first wins: the same question may be sitting in a dialog on the desktop,
        // and a second answer must not undo the first.
        val accepted = PendingApprovals.resolve(command.runId, command.approved)
        send(token, TelegramProtocol.sendMessage(incoming.chatId, when {
          !accepted -> t("telegram.approvalGone")
          command.approved -> t("telegram.approved")
          else -> t("telegram.denied")
        }))
      }
      is TelegramProtocol.Command.Unknown -> send(token, TelegramProtocol.sendMessage(
        incoming.chatId, t("telegram.unknown"), menuButtons()))
    }
  }

  /**
   * Runs the task and reports through ONE message that is edited as it goes: forty progress
   * messages on a phone is a phone with this bot muted.
   */
  private fun runTask(token: String, chatId: Long, task: String) {
    val project = targetProject() ?: run {
      send(token, TelegramProtocol.sendMessage(chatId, t("telegram.noProject")))
      return
    }
    val started = send(token, TelegramProtocol.sendMessage(chatId, t("telegram.started", "task" to task.take(200))))
    val messageId = TelegramProtocol.sentMessageId(started)
    ApplicationManager.getApplication().executeOnPooledThread {
      val result = runCatching {
        VibeAgentGateway.getInstance().run(task, null, true)
      }
      val text = result.fold(
        onSuccess = { t("telegram.finished", "task" to task.take(100)) },
        onFailure = { t("telegram.failed", "reason" to (it.message ?: "")) },
      )
      if (messageId != null) send(token, TelegramProtocol.editMessage(chatId, messageId, text), "editMessageText")
      else send(token, TelegramProtocol.sendMessage(chatId, text))
    }
  }

  /**
   * Sends a permission question to every allowed chat.
   *
   * Only when the bridge is actually running: a question sent nowhere would leave the desktop
   * dialog looking like it has a second answer coming, and someone waiting for a phone that never
   * buzzed.
   */
  fun askApproval(request: PendingApprovals.Request, question: String): Boolean {
    val token = token() ?: return false
    if (!isRunning()) return false
    val chats = allowedChats()
    if (chats.isEmpty()) return false
    val buttons = listOf(listOf(
      TelegramProtocol.Button(t("telegram.button.approve"), "approve:" + request.id),
      TelegramProtocol.Button(t("telegram.button.deny"), "deny:" + request.id),
    ))
    chats.forEach { chatId -> send(token, TelegramProtocol.sendMessage(chatId, question, buttons)) }
    return true
  }

  private fun askOwner(incoming: TelegramProtocol.Incoming) {
    val project = targetProject() ?: ProjectManager.getInstance().openProjects.firstOrNull() ?: return
    NotificationGroupManager.getInstance().getNotificationGroup("Vibe Agent")
      .createNotification(
        t("telegram.requestAccess", "chat" to incoming.chatId, "user" to (incoming.fromUsername ?: "?")),
        NotificationType.WARNING,
      )
      .addAction(NotificationAction.createSimple(t("telegram.allow")) { allowChat(incoming.chatId) })
      .notify(project)
  }

  private fun projects(): List<String> =
    ProjectManager.getInstance().openProjects.mapNotNull { it.name }

  private fun projectButtons(): List<List<TelegramProtocol.Button>> =
    projects().map { listOf(TelegramProtocol.Button(it, "use:" + it)) }

  private fun menuButtons(): List<List<TelegramProtocol.Button>> = listOf(
    listOf(TelegramProtocol.Button(t("telegram.button.projects"), "projects"),
           TelegramProtocol.Button(t("telegram.button.digest"), "digest")),
    listOf(TelegramProtocol.Button(t("telegram.button.stop"), "stop")),
  )

  @Suppress("UNUSED_PARAMETER")
  private fun targetProject(): Project? {
    val name = VibeAgentSettings.telegramProject
    val open = ProjectManager.getInstance().openProjects
    return open.firstOrNull { it.name == name } ?: open.firstOrNull()
  }

  // --- http ---

  private fun client(): HttpClient {
    val builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
    // Its own proxy setting: Telegram and the model providers are blocked in different places, and
    // one address for both is the setting that works for nobody.
    runCatching { ProxySettings.parse(VibeAgentSettings.telegramProxy) }.getOrNull()?.let { spec ->
      builder.proxy(java.net.ProxySelector.of(java.net.InetSocketAddress(spec.host, spec.port)))
    }
    return builder.build()
  }

  private fun call(token: String, method: String): String {
    val request = HttpRequest.newBuilder(URI.create(API + token + "/" + method))
      .timeout(Duration.ofSeconds(LONG_POLL_SEC + 15L)).GET().build()
    return http.send(request, HttpResponse.BodyHandlers.ofString()).body()
  }

  private fun send(token: String, payload: kotlinx.serialization.json.JsonObject, method: String = "sendMessage"): String {
    val request = HttpRequest.newBuilder(URI.create(API + token + "/" + method))
      .timeout(Duration.ofSeconds(30))
      .header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
      .build()
    return runCatching { http.send(request, HttpResponse.BodyHandlers.ofString()).body() }.getOrDefault("")
  }

  private fun attributes(): CredentialAttributes =
    CredentialAttributes(com.intellij.credentialStore.generateServiceName("VibeIDEA", "telegram.bot.token"))

  companion object {
    private const val API = "https://api.telegram.org/bot"
    private const val TOKEN_USER = "telegram"
    private const val LONG_POLL_SEC = 30
    private const val ERROR_PAUSE_MS = 5_000L

    fun getInstance(): TelegramBridge = ApplicationManager.getApplication().service()
  }
}

/** Starts the bridge when a project opens, but only when the owner has set a token. */
class TelegramStartup : ProjectActivity {
  override suspend fun execute(project: Project) {
    if (!VibeAgentSettings.telegramEnabled) return
    TelegramBridge.getInstance().start()
  }
}

class VibeTelegramTokenAction : AnAction({ t("telegram.token.action") }) {
  override fun actionPerformed(e: AnActionEvent) {
    val bridge = TelegramBridge.getInstance()
    val current = if (bridge.token() == null) t("telegram.token.absent") else t("telegram.token.present")
    val input = Messages.showPasswordDialog(e.project, t("telegram.token.prompt", "state" to current), t("telegram.token.title"), null)
      ?: return
    bridge.setToken(input.trim())
    if (input.isBlank()) {
      bridge.stop()
      Messages.showInfoMessage(e.project, t("telegram.token.cleared"), t("telegram.token.title"))
      return
    }
    VibeAgentSettings.telegramEnabled = true
    bridge.start()
    Messages.showInfoMessage(e.project, t("telegram.token.saved"), t("telegram.token.title"))
  }
}
