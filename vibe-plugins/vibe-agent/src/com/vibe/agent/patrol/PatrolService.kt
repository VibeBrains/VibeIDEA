// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.patrol

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.util.Alarm
import com.vibe.agent.audit.AuditEvent
import com.vibe.agent.i18n.VibeI18n.t
import com.vibe.agent.ui.VibeNotifications
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Заводит дежурные проверки проекта и приносит поводы.
 *
 * Пробы запускаются в фоне и стоят ноль: это команды проекта, а не запросы к модели. Модель зовётся
 * только по нажатию человека — фоновой расход, о котором узнают из счёта, хуже отсутствующей фичи.
 */
@Service(Service.Level.PROJECT)
class PatrolService(private val project: Project) : Disposable {
  private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
  private val lastRun = HashMap<String, Long>()
  private val firedToday = HashMap<String, Int>()
  private var dayStartedMs = System.currentTimeMillis()

  fun start() {
    schedule()
  }

  private fun schedule() {
    if (project.isDisposed) return
    alarm.addRequest({ runCatching { tick() }; schedule() }, TICK_MS)
  }

  /**
   * Прогнать все проверки немедленно — иначе первую настройку проверяют ожиданием.
   *
   * Расписание при этом не сбрасывается: ручной прогон отвечает на вопрос «работает ли моя проба»,
   * а не подменяет дежурство.
   */
  fun runNow(): List<String> {
    val base = project.basePath ?: return emptyList()
    val text = runCatching { Files.readString(Path.of(base, Patrol.FILE)) }.getOrNull() ?: return emptyList()
    val found = ArrayList<String>()
    for (entry in Patrol.parse(text).entries) {
      if (!entry.active) continue
      val code = runProbe(base, entry)
      if (Patrol.foundWork(code)) {
        found.add(entry.name())
        report(entry, code)
      }
    }
    return found
  }

  private fun tick() {
    val base = project.basePath ?: return
    val text = runCatching { Files.readString(Path.of(base, Patrol.FILE)) }.getOrNull() ?: return
    val now = System.currentTimeMillis()
    // Сутки лимита считаются от первого запуска, а не по календарю: полночь в чужом часовом поясе
    // не должна разом обнулять счётчики у всех.
    if (now - dayStartedMs >= DAY_MS) {
      firedToday.clear()
      dayStartedMs = now
    }
    for (entry in Patrol.parse(text).entries) {
      if (!Patrol.due(entry, lastRun[entry.id], now)) continue
      lastRun[entry.id] = now
      if (!Patrol.withinDailyCap(entry, firedToday[entry.id] ?: 0)) continue
      val code = runProbe(base, entry)
      if (!Patrol.foundWork(code)) continue
      firedToday[entry.id] = (firedToday[entry.id] ?: 0) + 1
      report(entry, code)
    }
  }

  /** @return код возврата, или null — если проба не смогла запуститься (это не повод). */
  private fun runProbe(base: String, entry: Patrol.Entry): Int? = runCatching {
    val process = ProcessBuilder("/bin/sh", "-c", entry.probe)
      .directory(java.io.File(base))
      .redirectErrorStream(true)
      .start()
    if (!process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
      process.destroyForcibly()
      return@runCatching null
    }
    process.exitValue()
  }.getOrNull()

  private fun report(entry: Patrol.Entry, code: Int?) {
    // Причина срабатывания — в журнал: дежурная проверка, о которой нельзя спросить «почему она
    // сработала», через неделю выключается целиком.
    com.vibe.agent.audit.VibeAuditService.getInstance(project).get()?.append(
      AuditEvent(System.currentTimeMillis(), AuditEvent.Action.PATROL, ok = true,
                 actor = com.vibe.agent.audit.AuditActor.IDE,
                 meta = mapOf("patrol" to entry.id, "probe" to entry.probe,
                              "exit" to (code?.toString() ?: "")))
    )
    val notification = NotificationGroupManager.getInstance()
      .getNotificationGroup(VibeNotifications.AGENT)
      .createNotification(t("patrol.found.title", "name" to entry.name()),
                          t("patrol.found.body", "probe" to entry.probe, "exit" to (code ?: 0)),
                          NotificationType.INFORMATION)
    entry.prompt?.let { prompt ->
      notification.addAction(NotificationAction.createSimpleExpiring(t("patrol.found.ask")) {
        // Ход заводится тем же путём, что и внешняя задача по HTTP: своей второй двери у
        // дежурной проверки быть не должно.
        runCatching { com.vibe.agent.http.VibeAgentGateway.getInstance().run(prompt, null, wait = false) }
      })
    }
    notification.notify(project)
  }

  override fun dispose() = Unit

  companion object {
    fun getInstance(project: Project): PatrolService = project.getService(PatrolService::class.java)

    /** Проверяем расписание раз в минуту: интервал самой проверки задаёт человек. */
    const val TICK_MS = 60_000L
    const val PROBE_TIMEOUT_SECONDS = 30L
    const val DAY_MS = 24 * 60 * 60 * 1000L
  }
}

/** Дежурные проверки заводятся с проектом; без файла `.vibe/patrols.json` не делают ничего. */
class PatrolStartup : ProjectActivity {
  override suspend fun execute(project: Project) {
    project.getService(PatrolService::class.java).start()
  }
}
