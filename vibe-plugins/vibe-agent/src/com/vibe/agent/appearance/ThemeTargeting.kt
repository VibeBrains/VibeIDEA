// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.appearance

import com.intellij.ide.ui.TargetUIType
import com.intellij.ide.ui.laf.UiThemeProviderListManager
import com.intellij.ui.ExperimentalUI

/**
 * Темы, на которые эта IDE может переключиться ПРЯМО СЕЙЧАС, без перезапуска.
 *
 * Признак — `targetUi` темы, а не флаг `restartRequired`, и это стоило отдельной ошибки. Флаг
 * выглядит ровно тем, что нужно, но это отдельный атрибут регистрации со значением по умолчанию
 * `false`: у классических тем платформы (`Darcula`, `IntelliJ`) в `intellij.platform.ide.impl.xml`
 * стоит только `targetUi="classic"`, и `isRestartRequired()` у них **ложь**. Фильтр по флагу,
 * выпущенный в 0.6.24, не отсекал ничего — владелец справедливо сказал, что тема не починена.
 *
 * Что происходит без фильтра: переход между классическим и новым интерфейсом требует перезапуска,
 * а `LafManager.updateUI()` этого не знает — он падает на первом же `ToolbarComboButton`
 * («no ComponentUI class for…») и бросает обход окон на середине. Часть интерфейса остаётся в
 * прежней теме: окно проекта тёмное, диалог светлый, кнопки разложены по чужим метрикам.
 *
 * Список берётся у самой платформы (`UiThemeProviderListManager`) — непубличный API, запись в
 * FORK_CHANGES.md. Своего списка не заводим: он разошёлся бы с платформой на первой же новой теме.
 */
object ThemeTargeting {
  /** Тип интерфейса, в котором IDE работает сейчас. */
  private fun current(): TargetUIType = if (ExperimentalUI.isNewUI()) TargetUIType.NEW else TargetUIType.CLASSIC

  /**
   * Идентификаторы тем, применимых без перезапуска.
   *
   * Пустой ответ означает «спросить не удалось» — в этом случае звать фильтр нельзя: спрятать ВСЕ
   * темы страшнее, чем показать лишнюю.
   */
  fun switchableIds(): Set<String> =
    runCatching {
      UiThemeProviderListManager.getInstance().getThemeListForTargetUI(current()).map { it.id }.toSet()
    }.getOrDefault(emptySet())

  /**
   * Можно ли переключиться на эту тему без перезапуска.
   *
   * Неизвестный ответ трактуется как «можно»: запрет по незнанию убрал бы из списка всё.
   */
  fun switchable(id: String): Boolean {
    val ids = switchableIds()
    return ids.isEmpty() || id in ids
  }
}
