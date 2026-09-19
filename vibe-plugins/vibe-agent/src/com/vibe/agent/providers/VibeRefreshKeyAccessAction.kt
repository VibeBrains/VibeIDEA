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
      var moved = 0
      var missing = 0
      // Читаем и пишем по очереди, а не пачкой: пароль спрашивается на чтение, и человек должен
      // видеть, за какой ключ его спрашивают — окна связки называют запись по имени.
      providers.forEach { provider ->
        val key = runCatching { ApiKeyResolver.storedKey(provider) }.getOrNull()
        if (key == null) {
          missing++
        }
        else {
          runCatching { ApiKeyResolver.restoreKey(provider, key) }.onSuccess { moved++ }
        }
      }
      ApplicationManager.getApplication().invokeLater {
        Messages.showInfoMessage(project, message(moved, missing), t("keys.refresh.title"))
      }
    }
  }

  private fun message(moved: Int, missing: Int): String =
    if (moved == 0) t("keys.refresh.none", "checked" to missing)
    else t("keys.refresh.done", "moved" to moved)
}
