// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.design

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parses what the page reported into the snapshot the rules judge.
 *
 * Everything here is defensive on purpose: the JSON comes from a page the user opened, which may be
 * anyone's site. A missing field means "not measured" and the rules stay silent about it, so an
 * absent value must never become a zero that looks like a measurement. A malformed element is
 * dropped rather than allowed to abort the whole report — one strange node on a page is normal.
 */
object DesignSnapshotCodec {
  private val json = Json { ignoreUnknownKeys = true }

  fun parse(text: String, viewport: Viewport): DocumentSnapshot? {
    val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
    val elements = (root["elements"] as? JsonArray).orEmpty().mapNotNull { element(it as? JsonObject) }
    val headings = (root["headings"] as? JsonArray).orEmpty().mapNotNull { item ->
      val obj = item as? JsonObject ?: return@mapNotNull null
      HeadingSnapshot(
        tag = obj.str("tag") ?: return@mapNotNull null,
        text = obj.str("text").orEmpty(),
        fontSizePx = obj.num("fontSizePx"),
      )
    }
    return DocumentSnapshot(
      url = root.str("url").orEmpty(),
      viewportWidthPx = root.num("viewportWidthPx"),
      viewportHeightPx = root.num("viewportHeightPx"),
      viewport = viewport,
      documentScrollWidthPx = root.num("documentScrollWidthPx"),
      elements = elements,
      headings = headings,
      meta = meta(root["meta"] as? JsonObject),
    )
  }

  /** No meta block means the collector did not look; null says that, empty strings would not. */
  private fun meta(obj: JsonObject?): PageMeta? {
    if (obj == null) return null
    return PageMeta(
      title = obj.str("title").orEmpty(),
      description = obj.str("description").orEmpty(),
      lang = obj.str("lang").orEmpty(),
      viewportContent = obj.str("viewportContent").orEmpty(),
      canonical = obj.str("canonical").orEmpty(),
      h1Count = obj.num("h1Count").toInt(),
      robots = obj.str("robots").orEmpty(),
      ogTitle = obj.str("ogTitle").orEmpty(),
      faviconHref = obj.str("faviconHref").orEmpty(),
      charset = obj.str("charset").orEmpty(),
    )
  }

  private fun element(obj: JsonObject?): ElementSnapshot? {
    if (obj == null) return null
    val selector = obj.str("selector") ?: return null
    val tag = obj.str("tag") ?: return null
    return ElementSnapshot(
      selector = selector,
      parentId = obj["parentId"]?.jsonPrimitive?.intOrNull ?: -1,
      tag = tag,
      text = obj.str("text").orEmpty(),
      classes = obj.strings("classes"),
      childTags = obj.strings("childTags"),
      fontSizePx = obj.num("fontSizePx"),
      lineHeightPx = obj.num("lineHeightPx"),
      letterSpacingPx = obj.num("letterSpacingPx"),
      fontFamily = obj.str("fontFamily").orEmpty(),
      fontWeight = obj["fontWeight"]?.jsonPrimitive?.intOrNull ?: 400,
      fontStyle = obj.str("fontStyle") ?: "normal",
      textTransform = obj.str("textTransform") ?: "none",
      color = obj.rgb("color") ?: Rgb(0, 0, 0),
      backgroundColor = obj.rgb("backgroundColor") ?: Rgb(255, 255, 255),
      ownBackgroundAlpha = obj.num("ownBackgroundAlpha"),
      backgroundImage = obj.str("backgroundImage").orEmpty(),
      backgroundClip = obj.str("backgroundClip").orEmpty(),
      boxShadow = obj.str("boxShadow").orEmpty(),
      backdropFilter = obj.str("backdropFilter").orEmpty(),
      borderRadiusPx = obj.num("borderRadiusPx"),
      animationName = obj.str("animationName").orEmpty(),
      animationTimingFunction = obj.str("animationTimingFunction").orEmpty(),
      animationDurationMs = obj.num("animationDurationMs"),
      transitionProperty = obj.str("transitionProperty").orEmpty(),
      position = obj.str("position") ?: "static",
      zIndex = obj["zIndex"]?.jsonPrimitive?.intOrNull ?: 0,
      overflowX = obj.str("overflowX") ?: "visible",
      overflowY = obj.str("overflowY") ?: "visible",
      widthPx = obj.num("widthPx"),
      heightPx = obj.num("heightPx"),
      leftPx = obj.num("leftPx"),
      topPx = obj.num("topPx"),
      scrollWidthPx = obj.num("scrollWidthPx"),
      clientWidthPx = obj.num("clientWidthPx"),
      imgSrc = obj.str("imgSrc").orEmpty(),
      imgNaturalWidthPx = obj.num("imgNaturalWidthPx"),
      svgShapeCount = obj["svgShapeCount"]?.jsonPrimitive?.intOrNull ?: 0,
      textLineCount = obj["textLineCount"]?.jsonPrimitive?.intOrNull ?: 0,
      linesEndingWithShortWord = obj["linesEndingWithShortWord"]?.jsonPrimitive?.intOrNull ?: 0,
      lastLineWordCount = obj["lastLineWordCount"]?.jsonPrimitive?.intOrNull ?: 0,
      interactive = obj.bool("interactive"),
      outlineStyle = obj.str("outlineStyle") ?: "none",
      outlineWidthPx = obj.num("outlineWidthPx"),
      hasFocusRule = obj.bool("hasFocusRule"),
      hasHoverRule = obj.bool("hasHoverRule"),
      disabled = obj.bool("disabled"),
      // Absent means the collector did not say — treat as "could not look" only when it says so.
      styleRulesUnreadable = obj.bool("styleRulesUnreadable"),
      accessibleName = obj.str("accessibleName").orEmpty(),
      isFormField = obj.bool("isFormField"),
      inputType = obj.str("inputType").orEmpty(),
      hasPlaceholder = obj.bool("hasPlaceholder"),
      hasAltAttribute = obj.bool("hasAltAttribute"),
      ariaInvalid = obj.bool("ariaInvalid"),
      describedByText = obj.str("describedByText").orEmpty(),
      isRequiredField = obj.bool("isRequiredField"),
    )
  }

  private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }
  private fun JsonObject.num(key: String): Double = this[key]?.jsonPrimitive?.doubleOrNull ?: 0.0
  private fun JsonObject.bool(key: String): Boolean = this[key]?.jsonPrimitive?.booleanOrNull ?: false
  private fun JsonObject.strings(key: String): List<String> =
    runCatching { this[key]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } }.getOrNull().orEmpty()

  private fun JsonObject.rgb(key: String): Rgb? {
    val array = runCatching { this[key]?.jsonArray }.getOrNull() ?: return null
    if (array.size < 3) return null
    val channels = array.mapNotNull { it.jsonPrimitive.intOrNull }
    if (channels.size < 3) return null
    return Rgb(channels[0], channels[1], channels[2])
  }
}
