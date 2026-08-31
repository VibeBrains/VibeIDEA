// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.design

/**
 * Phrases the detector LOOKS FOR on a measured page.
 *
 * These are detection DATA, not interface text: they match Russian and English wording inside
 * somebody else's page, and translating them would break the search rather than localise it. They
 * live in their own file precisely so the localisation gate can exempt exactly this — a file that
 * mixed patterns with the messages a person reads would hide the untranslated messages behind the
 * exemption, which is what happened before this split.
 */
object DesignPhrases {
  /** Words that promise everything and say nothing — the copy a generator reaches for. */
  val MARKETING: List<Regex> = listOf(
    Regex("(?iU)революцион"),
    Regex("(?iU)непревзойд"),
    Regex("(?iU)лучш(ий|ая|ее) в мире"),
    Regex("(?i)game.?chang"),
    Regex("(?i)revolutionar"),
    Regex("(?i)seamless"),
    Regex("(?i)cutting.?edge"),
  )

  /**
   * Field names that name a value the browser already knows, keyed by the autocomplete token.
   *
   * Matched against somebody else's markup in both languages — detection data, exactly like the
   * marketing patterns above, and for the same reason kept out of the string catalogue: translating
   * a search pattern breaks the search instead of localising it.
   */
  val AUTOFILLABLE: Map<String, Regex> = mapOf(
    "email" to Regex("(?iU)(e-?mail|почт|мейл)"),
    "tel" to Regex("(?iU)(phone|tel|телефон|моб)"),
    "name" to Regex("(?iU)(full-?name|fio|имя|фамил|фио)"),
    "street-address" to Regex("(?iU)(address|street|адрес|улиц)"),
    "postal-code" to Regex("(?iU)(zip|postal|индекс)"),
    "cc-number" to Regex("(?iU)(card-?number|карт)"),
  )

  /** Field names whose value has its own keyboard on a phone, keyed by the input type. */
  val TYPED_INPUT: Map<String, Regex> = mapOf(
    "email" to Regex("(?iU)(e-?mail|почт|мейл)"),
    "tel" to Regex("(?iU)(phone|tel|телефон)"),
    "url" to Regex("(?iU)(url|site|сайт|ссылк)"),
  )
}
