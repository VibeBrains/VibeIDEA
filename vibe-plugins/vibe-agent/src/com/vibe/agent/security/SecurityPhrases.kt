// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.security

/**
 * Shapes that only make sense as an address to a model — what the context guard looks for in a file
 * before that file reaches the model.
 *
 * Detection DATA, not interface: the patterns match wording inside someone else's repository, so
 * translating them would silence the guard instead of localising it. Kept apart from the messages
 * a person reads so the localisation gate can exempt the patterns without exempting the messages.
 *
 * `(?iU)` on the Russian ones is not decoration: in Java `(?i)` alone folds case for ASCII only, so
 * «Игнорируй» with a capital И slips past a lowercase pattern, and `\b` treats Cyrillic as a
 * non-word character until UNICODE_CHARACTER_CLASS is on. A guard that quietly matches nothing is
 * worse than no guard.
 */
object SecurityPhrases {
  val INSTRUCTIONS: List<Regex> = listOf(
    Regex("(?i)ignore\\s+(all\\s+)?(previous|prior|above)\\s+instructions"),
    Regex("(?i)disregard\\s+(all\\s+)?(previous|prior|above)"),
    Regex("(?i)you\\s+are\\s+now\\s+(a|an|the)\\b"),
    Regex("(?i)new\\s+system\\s+prompt"),
    Regex("(?i)<\\s*(system|assistant)\\s*>"),
    Regex("(?iU)игнорируй\\s+(все\\s+)?(предыдущие|прошлые)\\s+(инструкции|указания)"),
    Regex("(?iU)забудь\\s+(все\\s+)?(предыдущие|прошлые)\\s+(инструкции|указания)"),
    Regex("(?iU)теперь\\s+ты\\s+(—\\s*)?(другой|новый)\\b"),
    Regex("(?iU)системный\\s+промпт\\s*:"),
  )
}
