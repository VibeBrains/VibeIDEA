// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.design

import com.vibe.agent.i18n.VibeI18n.t

/**
 * Findability: the tenth category, and the only one invisible both on a screenshot and in a preview.
 *
 * A page can be perfect to look at and impossible to find, and nothing about that shows up in a
 * design review — which is why it is usually discovered months later by someone asking why the site
 * gets no traffic.
 *
 * Everything here is computed ON THE PAGE, without a network call and without an external service:
 * a rule that needs a crawler is a rule that cannot run while you are writing the page.
 */
object DesignFindabilityRules {
  /** Search results cut a title around here; longer is not an error, just invisible. */
  const val TITLE_MAX = 60
  const val TITLE_MIN = 10
  const val DESCRIPTION_MIN = 50
  const val DESCRIPTION_MAX = 160

  /** Silent when the page was never asked about itself: «не собирали» is not «пусто». */
  fun all(doc: DocumentSnapshot): List<Finding> {
    if (doc.meta == null) return emptyList()
    return title(doc) + singleH1(doc) + description(doc) + lang(doc) + viewport(doc) + canonical(doc) +
      robots(doc) + charset(doc) + favicon(doc) + openGraph(doc)
  }

  fun title(doc: DocumentSnapshot): List<Finding> {
    val title = meta(doc).title.trim()
    return when {
      title.isEmpty() -> listOf(finding(DesignRuleCatalog.TITLE_MISSING, Severity.ERROR, doc,
        t("design.rule.titleMissing.message"), t("design.rule.titleMissing.why"), ""))
      title.length < TITLE_MIN -> listOf(finding(DesignRuleCatalog.TITLE_TOO_SHORT, Severity.WARNING, doc,
        t("design.rule.titleShort.message", "length" to title.length), t("design.rule.titleShort.why"), title))
      title.length > TITLE_MAX -> listOf(finding(DesignRuleCatalog.TITLE_TOO_LONG, Severity.WARNING, doc,
        t("design.rule.titleLong.message", "length" to title.length, "max" to TITLE_MAX),
        t("design.rule.titleLong.why"), title))
      else -> emptyList()
    }
  }

  /**
   * Exactly one h1. Zero leaves the page without a subject; two make it about two things, and the
   * one a reader is looking for is neither.
   */
  fun singleH1(doc: DocumentSnapshot): List<Finding> = when {
    meta(doc).h1Count == 0 -> listOf(finding(DesignRuleCatalog.H1_MISSING, Severity.ERROR, doc,
      t("design.rule.h1Missing.message"), t("design.rule.h1Missing.why"), ""))
    meta(doc).h1Count > 1 -> listOf(finding(DesignRuleCatalog.H1_MULTIPLE, Severity.WARNING, doc,
      t("design.rule.h1Multiple.message", "count" to meta(doc).h1Count), t("design.rule.h1Multiple.why"),
      meta(doc).h1Count.toString()))
    else -> emptyList()
  }

  fun description(doc: DocumentSnapshot): List<Finding> {
    val text = meta(doc).description.trim()
    return when {
      text.isEmpty() -> listOf(finding(DesignRuleCatalog.DESCRIPTION_MISSING, Severity.WARNING, doc,
        t("design.rule.descriptionMissing.message"), t("design.rule.descriptionMissing.why"), ""))
      text.length < DESCRIPTION_MIN -> listOf(finding(DesignRuleCatalog.DESCRIPTION_TOO_SHORT, Severity.WARNING, doc,
        t("design.rule.descriptionShort.message", "length" to text.length, "min" to DESCRIPTION_MIN),
        t("design.rule.descriptionShort.why"), text))
      text.length > DESCRIPTION_MAX -> listOf(finding(DesignRuleCatalog.DESCRIPTION_TOO_LONG, Severity.WARNING, doc,
        t("design.rule.descriptionLong.message", "length" to text.length, "max" to DESCRIPTION_MAX),
        t("design.rule.descriptionLong.why"), text.take(80)))
      else -> emptyList()
    }
  }

  fun lang(doc: DocumentSnapshot): List<Finding> =
    if (meta(doc).lang.isBlank()) listOf(finding(DesignRuleCatalog.LANG_MISSING, Severity.ERROR, doc,
      t("design.rule.langMissing.message"), t("design.rule.langMissing.why"), ""))
    else emptyList()

  /** No viewport meta means a phone renders a desktop page and scales it down to unreadable. */
  fun viewport(doc: DocumentSnapshot): List<Finding> = when {
    meta(doc).viewportContent.isBlank() -> listOf(finding(DesignRuleCatalog.VIEWPORT_MISSING, Severity.ERROR, doc,
      t("design.rule.viewportMissing.message"), t("design.rule.viewportMissing.why"), ""))
    meta(doc).viewportContent.contains("user-scalable=no") ||
      Regex("maximum-scale=\\s*1(?:\\.0)?\\b").containsMatchIn(meta(doc).viewportContent) ->
      listOf(finding(DesignRuleCatalog.VIEWPORT_BLOCKS_ZOOM, Severity.ERROR, doc,
        t("design.rule.viewportZoom.message"), t("design.rule.viewportZoom.why"), meta(doc).viewportContent))
    else -> emptyList()
  }

  fun canonical(doc: DocumentSnapshot): List<Finding> {
    val canonical = meta(doc).canonical.trim()
    if (canonical.isEmpty()) {
      return listOf(finding(DesignRuleCatalog.CANONICAL_MISSING, Severity.WARNING, doc,
        t("design.rule.canonicalMissing.message"), t("design.rule.canonicalMissing.why"), ""))
    }
    // A relative canonical is worse than none: it resolves differently per host, so the copies
    // point at each other instead of at one address.
    if (!canonical.startsWith("http")) {
      return listOf(finding(DesignRuleCatalog.CANONICAL_RELATIVE, Severity.ERROR, doc,
        t("design.rule.canonicalRelative.message"), t("design.rule.canonicalRelative.why"), canonical))
    }
    return emptyList()
  }

  /** `noindex` on a page one is designing is almost always a leftover from a staging build. */
  fun robots(doc: DocumentSnapshot): List<Finding> =
    if (meta(doc).robots.contains("noindex", ignoreCase = true))
      listOf(finding(DesignRuleCatalog.ROBOTS_NOINDEX, Severity.WARNING, doc,
        t("design.rule.noindex.message"), t("design.rule.noindex.why"), meta(doc).robots))
    else emptyList()

  fun charset(doc: DocumentSnapshot): List<Finding> =
    if (meta(doc).charset.isNotBlank() && !meta(doc).charset.equals("UTF-8", ignoreCase = true))
      listOf(finding(DesignRuleCatalog.CHARSET_NOT_UTF8, Severity.WARNING, doc,
        t("design.rule.charset.message", "charset" to meta(doc).charset), t("design.rule.charset.why"), meta(doc).charset))
    else emptyList()

  fun favicon(doc: DocumentSnapshot): List<Finding> =
    if (meta(doc).faviconHref.isBlank())
      listOf(finding(DesignRuleCatalog.FAVICON_MISSING, Severity.WARNING, doc,
        t("design.rule.favicon.message"), t("design.rule.favicon.why"), ""))
    else emptyList()

  fun openGraph(doc: DocumentSnapshot): List<Finding> =
    if (meta(doc).ogTitle.isBlank())
      listOf(finding(DesignRuleCatalog.OG_TITLE_MISSING, Severity.WARNING, doc,
        t("design.rule.ogTitle.message"), t("design.rule.ogTitle.why"), ""))
    else emptyList()

  /** Callers below run only after [all] established the metadata exists. */
  private fun meta(doc: DocumentSnapshot): PageMeta = doc.meta ?: PageMeta()

  private fun finding(rule: String, severity: Severity, doc: DocumentSnapshot,
                      message: String, why: String, evidence: String) = Finding(
    rule = rule,
    severity = severity,
    message = message,
    why = why,
    selector = "head",
    evidence = evidence,
    ruleClass = DesignRuleCatalog.classOf(rule),
    viewport = doc.viewport,
  )
}
