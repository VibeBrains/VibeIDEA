// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.vibe.agent.i18n.VibeI18n.t

/**
 * Перечитать и переписать ключи провайдеров, чтобы связка ключей перестала спрашивать пароль.
 *
 * Откуда берётся вопрос. Связка выдаёт доступ к записи КОНКРЕТНОМУ приложению — по его подписи, и
 * список доступа хранится у каждой записи отдельно. Ключи, сохранённые прежними сборками (без
 * подписи и с прежним идентификатором), помнят то приложение, а не нынешнее, — поэтому macOS
 * спрашивает пароль при каждом чтении такой записи. Сборки тут ни при чём: у установленного
 * приложения и у новой сборки требование подписи совпадает дословно, то есть система считает их
 * одним приложением (сверено 18.09.2026).
 *
 * Что делает это действие: читает каждый ключ, УДАЛЯЕТ запись и создаёт её заново. Чтение старой
 * записи спросит пароль — один раз на ключ, и это неизбежно; созданная заново запись принадлежит
 * нам, и дальше связка молчит, пока цело удостоверение подписи.
 *
 * Удаление здесь — не перестраховка, а суть (найдено 19.09.2026, вопросы вернулись после действия).
 * Список доступа заполняется при СОЗДАНИИ записи, а платформа на macOS поверх существующей зовёт
 * правку на месте (`SecKeychainItemModifyContent`), которая список не трогает. Прежняя версия
 * действия писала поверх — то есть не делала ничего из обещанного, и это было записано в её же
 * комментарии как сделанное. Разбор — [ApiKeyResolver.restoreKey].
 *
 * Почему одним действием, а не «нажимайте „Разрешать всегда“, когда спросит»: спрашивает она
 * посреди работы, по одному ключу, в разное время — и человек, которому мешают пятый раз за день,
 * перестаёт читать, что именно у него спрашивают. Лучше один раз, когда он сам этого захотел.
 */
class VibeRefreshKeyAccessAction : DumbAwareAction() {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    ApplicationManager.getApplication().executeOnPooledThread {
      val providers = ProvidersService.load(project.basePath) { }
      // Сперва подбираем то, что могло остаться от прерванного захода: ключ в страховочной записи
      // человеку не виден, он видит только переставшего работать провайдера.
      val recovered = runCatching { ApiKeyResolver.recoverLeftovers(providers) }.getOrDefault(0)
      var moved = 0
      var failed = 0
      var missing = 0
      // Читаем и пишем по очереди, а не пачкой: пароль спрашивается на чтение, и человек должен
      // видеть, за какой ключ его спрашивают — окна связки называют запись по имени.
      providers.forEach { provider ->
        val key = runCatching { ApiKeyResolver.storedKey(provider) }.getOrNull()
        if (key == null) {
          missing++
        }
        else {
          val ok = runCatching { ApiKeyResolver.restoreKey(provider, key) }.getOrDefault(false)
          if (ok) moved++ else failed++
        }
      }
      ApplicationManager.getApplication().invokeLater {
        Messages.showInfoMessage(project, message(moved, missing, failed, recovered), t("keys.refresh.title"))
      }
    }
  }

  private fun message(moved: Int, missing: Int, failed: Int, recovered: Int): String = buildString {
    // Неудача называется отдельной строкой, а не растворяется в числе переписанных: провайдер, чей
    // ключ не записался, перестанет работать, и человек должен узнать об этом здесь, а не потом.
    if (recovered > 0) appendLine(t("keys.refresh.recovered", "recovered" to recovered))
    append(if (moved == 0) t("keys.refresh.none", "checked" to missing) else t("keys.refresh.done", "moved" to moved))
    if (failed > 0) { appendLine(); append(t("keys.refresh.failed", "failed" to failed)) }
  }
}
