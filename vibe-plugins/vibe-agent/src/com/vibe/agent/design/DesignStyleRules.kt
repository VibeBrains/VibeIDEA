// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.design

import kotlin.math.abs

/**
 * The tells of a machine-made page.
 *
 * None of these is a defect: a gradient headline is a choice, a glass card is a choice. They are
 * listed because together they are the look a model reaches for when nobody told it what the
 * product looks like — and a product that looks generated reads as one nobody designed.
 *
 * Because they are taste and not law, they NEVER block a turn, and a project may declare any of
 * them its own identity with a stated reason. The reason matters more than the acceptance: a month
 * later it is the only thing that explains why the page looks like this.
 */
object DesignStyleRules {
  /** The hue band people mean by «фиолетовый AI-градиент». */
  private const val PURPLE_HUE_FROM = 255.0
  private const val PURPLE_HUE_TO = 290.0
  private const val PURPLE_MIN_SATURATION = 0.35

  /** A radius past this stops being a rounding and becomes a shape. */
  private const val EXTREME_RADIUS_PX = 40.0

  /** Cards that repeat this many times with the same child structure read as a template. */
  private const val CLONE_THRESHOLD = 3

  /** Timing functions that overshoot — the bounce a page does not need. */
  private val OVERSHOOT = listOf("cubic-bezier(0.68", "back", "elastic", "bounce")

  /** Properties whose animation moves the layout of everything around them. */
  private val LAYOUT_PROPERTIES = listOf("width", "height", "top", "left", "right", "bottom", "margin", "padding")

  private val MARKETING = listOf(
    Regex("(?iU)революцион"), Regex("(?iU)непревзойд"), Regex("(?iU)лучш(ий|ая|ее) в мире"),
    Regex("(?i)game.?chang"), Regex("(?i)revolutionar"), Regex("(?i)seamless"), Regex("(?i)cutting.?edge"),
  )

  fun all(doc: DocumentSnapshot): List<Finding> =
    gradientText(doc) + glow(doc) + glass(doc) + purple(doc) + eyebrow(doc) + clones(doc) +
    radiusDrift(doc) + extremeRadius(doc) + animatedLayout(doc) + overshoot(doc) +
    hangingPreposition(doc) + orphanWord(doc) + marketing(doc) + hoverResponse(doc)

  fun gradientText(doc: DocumentSnapshot): List<Finding> = doc.elements.mapNotNull { element ->
    if (element.text.isBlank()) return@mapNotNull null
    if (!element.backgroundClip.contains("text")) return@mapNotNull null
    if (!element.backgroundImage.contains("gradient")) return@mapNotNull null
    DesignFloorRules.finding(
      DesignRuleCatalog.GRADIENT_TEXT, Severity.HINT, element, doc,
      message = "Заголовок залит градиентом",
      why = "Градиентный текст — самая узнаваемая примета сгенерированной страницы; он же хуже читается.",
      evidence = element.backgroundImage.take(80),
    )
  }

  fun glow(doc: DocumentSnapshot): List<Finding> = doc.elements.mapNotNull { element ->
    val shadow = element.boxShadow
    if (shadow.isBlank() || shadow == "none") return@mapNotNull null
    // A glow is a shadow with no offset: light coming from nowhere.
    if (!Regex("0px 0px").containsMatchIn(shadow)) return@mapNotNull null
    DesignFloorRules.finding(
      DesignRuleCatalog.GLOW_INSTEAD_OF_SHADOW, Severity.HINT, element, doc,
      message = "Свечение вместо тени",
      why = "Тень без смещения означает источник света ниоткуда — предмет перестаёт стоять на плоскости.",
      evidence = shadow.take(80),
    )
  }

  fun glass(doc: DocumentSnapshot): List<Finding> = doc.elements.mapNotNull { element ->
    if (!element.backdropFilter.contains("blur")) return@mapNotNull null
    DesignFloorRules.finding(
      DesignRuleCatalog.GLASSMORPHISM, Severity.HINT, element, doc,
      message = "Эффект «стекла» (размытие под слоем)",
      why = "Стекло почти всегда снижает контраст текста на слое и дорого стоит при прокрутке.",
      evidence = element.backdropFilter.take(60),
    )
  }

  fun purple(doc: DocumentSnapshot): List<Finding> {
    val colored = doc.elements.filter { it.ownBackgroundAlpha > 0.5 && DesignColor.saturation(it.backgroundColor) >= PURPLE_MIN_SATURATION }
    if (colored.size < 3) return emptyList()
    val purple = colored.filter { DesignColor.hue(it.backgroundColor) in PURPLE_HUE_FROM..PURPLE_HUE_TO }
    // Not "purple exists" but "purple is the palette": a single accent is a choice, not a tell.
    if (purple.size * 2 < colored.size) return emptyList()
    val worst = purple.first()
    return listOf(
      DesignFloorRules.finding(
        DesignRuleCatalog.PURPLE_PALETTE, Severity.HINT, worst, doc,
        message = "Палитра держится на фиолетовом (${purple.size} из ${colored.size} цветных поверхностей)",
        why = "Фиолетово-синий градиент — палитра по умолчанию у генераторов; продукт с ней не отличить от соседнего.",
        evidence = "оттенок ${DesignFloorRules.format(DesignColor.hue(worst.backgroundColor))}°",
      )
    )
  }

  fun eyebrow(doc: DocumentSnapshot): List<Finding> = doc.elements.mapNotNull { element ->
    if (element.text.isBlank() || element.text.length > 40) return@mapNotNull null
    if (element.textTransform != "uppercase" && element.letterSpacingPx < 1.0) return@mapNotNull null
    if (element.borderRadiusPx < 8.0 || element.ownBackgroundAlpha < 0.5) return@mapNotNull null
    if (element.fontSizePx > 16.0) return@mapNotNull null
    DesignFloorRules.finding(
      DesignRuleCatalog.EYEBROW_CHIP, Severity.HINT, element, doc,
      message = "Чип-надпись над заголовком",
      why = "Капслок в пилюле перед заголовком — штамп лендинга-заготовки, смысла он не несёт.",
      evidence = "«${element.text.take(40)}», радиус ${DesignFloorRules.format(element.borderRadiusPx)}px",
    )
  }

  fun clones(doc: DocumentSnapshot): List<Finding> {
    val groups = doc.elements
      .filter { it.childTags.isNotEmpty() && it.ownBackgroundAlpha > 0.3 }
      .groupBy { it.parentId to it.childTags.joinToString(",") }
    return groups.values.mapNotNull { siblings ->
      if (siblings.size < CLONE_THRESHOLD) return@mapNotNull null
      val sizes = siblings.map { it.widthPx to it.heightPx }.distinct()
      if (sizes.size > 1) return@mapNotNull null
      DesignFloorRules.finding(
        DesignRuleCatalog.CLONED_CARDS, Severity.HINT, siblings.first(), doc,
        message = "${siblings.size} одинаковых карточек подряд",
        why = "Карточки-клоны одинакового размера с одинаковой структурой — заполнитель, а не содержание.",
        evidence = "структура ${siblings.first().childTags.joinToString(",")}",
      )
    }
  }

  fun radiusDrift(doc: DocumentSnapshot): List<Finding> {
    val radii = doc.elements.map { it.borderRadiusPx }.filter { it > 0 }.distinct().sorted()
    if (radii.size <= 3) return emptyList()
    val element = doc.elements.first { it.borderRadiusPx > 0 }
    return listOf(
      DesignFloorRules.finding(
        DesignRuleCatalog.RADIUS_SCALE_DRIFT, Severity.HINT, element, doc,
        message = "Разнобой скруглений: ${radii.size} разных радиусов на странице",
        why = "Шкала радиусов — часть системы; пять близких значений читаются как случайность, а не решение.",
        evidence = radii.joinToString(", ") { DesignFloorRules.format(it) + "px" },
      )
    )
  }

  fun extremeRadius(doc: DocumentSnapshot): List<Finding> = doc.elements.mapNotNull { element ->
    if (element.borderRadiusPx < EXTREME_RADIUS_PX) return@mapNotNull null
    // A pill button and a circular avatar are legitimate: the radius is half the height there.
    if (element.heightPx > 0 && element.borderRadiusPx >= element.heightPx / 2 - 1) return@mapNotNull null
    DesignFloorRules.finding(
      DesignRuleCatalog.EXTREME_RADIUS, Severity.HINT, element, doc,
      message = "Скругление ${DesignFloorRules.format(element.borderRadiusPx)}px",
      why = "Радиус такого размера перестаёт быть скруглением и становится формой — обычно случайно.",
      evidence = "${DesignFloorRules.format(element.borderRadiusPx)}px при высоте ${DesignFloorRules.format(element.heightPx)}px",
    )
  }

  fun animatedLayout(doc: DocumentSnapshot): List<Finding> = doc.elements.mapNotNull { element ->
    val property = element.transitionProperty.lowercase()
    if (property.isBlank() || property == "none") return@mapNotNull null
    val offender = LAYOUT_PROPERTIES.firstOrNull { property.contains(it) } ?: return@mapNotNull null
    DesignFloorRules.finding(
      DesignRuleCatalog.ANIMATED_LAYOUT_PROPERTY, Severity.HINT, element, doc,
      message = "Анимируется свойство раскладки: $offender",
      why = "Каждый кадр пересчитывает положение соседей — анимация дёргается, а вокруг всё прыгает.",
      evidence = element.transitionProperty.take(80),
    )
  }

  fun overshoot(doc: DocumentSnapshot): List<Finding> = doc.elements.mapNotNull { element ->
    val timing = (element.animationTimingFunction + " " + element.transitionProperty).lowercase()
    if (OVERSHOOT.none { timing.contains(it) }) return@mapNotNull null
    DesignFloorRules.finding(
      DesignRuleCatalog.OVERSHOOT_ANIMATION, Severity.HINT, element, doc,
      message = "Анимация с перелётом",
      why = "Отскок уместен в игре и мешает в инструменте: он задерживает то, ради чего нажали.",
      evidence = element.animationTimingFunction.take(60),
    )
  }

  fun hangingPreposition(doc: DocumentSnapshot): List<Finding> = doc.elements.mapNotNull { element ->
    // Measured line breaking, not a guess from the source: only the page knows where the line broke.
    if (element.textLineCount < 2 || element.linesEndingWithShortWord <= 0) return@mapNotNull null
    DesignFloorRules.finding(
      DesignRuleCatalog.HANGING_PREPOSITION, Severity.HINT, element, doc,
      message = "Висячий предлог: строк с коротким словом на конце — ${element.linesEndingWithShortWord}",
      why = "Предлог, оторванный от своего слова, спотыкает чтение — в русском наборе это правило, а не вкус.",
      evidence = "${element.linesEndingWithShortWord} из ${element.textLineCount} строк",
    )
  }

  fun orphanWord(doc: DocumentSnapshot): List<Finding> = doc.elements.mapNotNull { element ->
    if (element.textLineCount < 2 || element.lastLineWordCount != 1) return@mapNotNull null
    DesignFloorRules.finding(
      DesignRuleCatalog.ORPHAN_WORD, Severity.HINT, element, doc,
      message = "Одинокое слово на последней строке",
      why = "Слово, повисшее под абзацем, выглядит обрывком — читатель ищет продолжение.",
      evidence = "строк ${element.textLineCount}, в последней 1 слово",
    )
  }

  fun marketing(doc: DocumentSnapshot): List<Finding> = doc.elements.mapNotNull { element ->
    if (element.text.isBlank()) return@mapNotNull null
    val hit = MARKETING.firstOrNull { it.containsMatchIn(element.text) } ?: return@mapNotNull null
    DesignFloorRules.finding(
      DesignRuleCatalog.MARKETING_PROMISE, Severity.HINT, element, doc,
      message = "Маркетинговое обещание в тексте",
      why = "Слова вроде «революционный» ничего не сообщают о продукте и читаются как заполнитель.",
      evidence = "«" + hit.find(element.text)?.value.orEmpty() + "»",
    )
  }

  fun hoverResponse(doc: DocumentSnapshot): List<Finding> = doc.elements.mapNotNull { element ->
    if (!element.interactive || element.disabled) return@mapNotNull null
    if (element.styleRulesUnreadable) return@mapNotNull null
    if (element.hasHoverRule) return@mapNotNull null
    // A hint, never a defect: on a touch device there is no hover at all, so this is taste.
    DesignFloorRules.finding(
      DesignRuleCatalog.NO_HOVER_RESPONSE, Severity.HINT, element, doc,
      message = "Нет отклика на наведение",
      why = "На мыши отклик подтверждает, что элемент живой; на касании его нет вовсе — потому подсказка, а не дефект.",
      evidence = "правила :hover не найдено",
    )
  }

  internal fun close(a: Double, b: Double, tolerance: Double = 0.5): Boolean = abs(a - b) <= tolerance
}
