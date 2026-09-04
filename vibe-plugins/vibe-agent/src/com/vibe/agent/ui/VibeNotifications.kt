// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.ui

/**
 * Идентификаторы групп уведомлений — в одном месте.
 *
 * Та же болезнь, что была у панелей: строка `"Vibe Agent"` написана руками в шести файлах, а
 * `"Vibe Languages"` — в двух. Опечатка в любой из них не ломает сборку и не видна в тестах:
 * платформа просто создаст группу с новым именем, и уведомление уедет мимо настроек, где человек
 * его отключал. Гейт `checkVibeUi.sh` требует, чтобы каждая группа из наших `plugin.xml` была
 * объявлена здесь, а в коде не осталось литералов.
 */
object VibeNotifications {
  const val AGENT = "Vibe Agent"
  const val LANGUAGES = "Vibe Languages"
  const val SERVER = "Vibe Server"
  const val HTTP = "Vibe HTTP"
  const val DATABASE = "Vibe Database"

  val ALL: List<String> = listOf(AGENT, LANGUAGES, SERVER, HTTP, DATABASE)
}
