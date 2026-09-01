// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelQuirksFileTest {
  private fun rules(text: String, warnings: MutableList<String> = ArrayList()): List<ModelQuirks.Rule> =
    ModelQuirksFile.rules(ModelQuirksFile.parse(text, "modelQuirks.json") { warnings.add(it) })

  @Test
  fun `no file leaves the built-in answer untouched`() {
    // The distribution must behave exactly as before for someone who never heard of this file.
    assertEquals(
      ModelQuirks.quirksOf("gpt-5", overrides = emptyList()),
      ModelQuirks.quirksOf("gpt-5", overrides = rules("""{"models": []}""")),
    )
  }

  @Test
  fun `the file wins over a built-in rule for the models it matches`() {
    val over = rules("""{"models": [{"match": "^gpt-5", "quirks": ["NO_STREAMING"]}]}""")
    // Replaced, not added to: the built-in rule said NO_SAMPLING and MAX_COMPLETION_TOKENS.
    assertEquals(setOf(ModelQuirks.Quirk.NO_STREAMING), ModelQuirks.quirksOf("gpt-5", over))
    // And a model the file says nothing about keeps the built-in answer.
    assertTrue(ModelQuirks.Quirk.NO_SAMPLING in ModelQuirks.quirksOf("o3-mini", over))
  }

  @Test
  fun `an empty list is how a wrong built-in rule is switched off`() {
    // Half the point of the file: a vendor that fixed its API must not need an IDE release to be
    // believed. Without this spelling there would be no way to say "stop rewriting my request".
    val over = rules("""{"models": [{"match": "^gpt-5", "quirks": []}]}""")
    assertEquals(emptySet(), ModelQuirks.quirksOf("gpt-5", over))
    assertEquals("modelQuirks.json", ModelQuirks.sourceOf("gpt-5", over))
  }

  @Test
  fun `the nearer scope has the last word`() {
    // Project entries are passed first, and the first match wins.
    val over = ModelQuirksFile.rules(
      ModelQuirksFile.parse("""{"models": [{"match": "^gpt-5", "quirks": []}]}""", "project") { } +
        ModelQuirksFile.parse("""{"models": [{"match": "^gpt-5", "quirks": ["NO_STOP"]}]}""", "global") { }
    )
    assertEquals(emptySet(), ModelQuirks.quirksOf("gpt-5", over))
  }

  @Test
  fun `a broken entry is skipped and the rest of the file still works`() {
    val warnings = ArrayList<String>()
    val over = rules(
      """
      {"models": [
        {"quirks": ["NO_STOP"]},
        {"match": "^(", "quirks": []},
        {"match": "^my-model", "quirks": ["NO_SAMPLING"]}
      ]}
      """,
      warnings,
    )
    assertEquals(setOf(ModelQuirks.Quirk.NO_SAMPLING), ModelQuirks.quirksOf("my-model", over))
    assertEquals(2, warnings.size, warnings.toString())
    assertTrue(warnings.any { "match" in it }, warnings.toString())  // ключ каталога подставляет паттерн
  }

  @Test
  fun `an entry without quirks is refused, because silence is not the same as none`() {
    val warnings = ArrayList<String>()
    val over = rules("""{"models": [{"match": "^gpt-5", "note": "надо разобраться"}]}""", warnings)
    assertTrue(over.isEmpty())
    // The built-in answer therefore stands — an entry meaning nothing must not disable anything.
    assertTrue(ModelQuirks.Quirk.NO_SAMPLING in ModelQuirks.quirksOf("gpt-5", over))
    assertTrue(warnings.single().contains("quirks"), warnings.toString())
  }

  @Test
  fun `an unknown quirk name costs the person only that word`() {
    // A newer IDE may know a quirk this one does not; one unknown word must not void the file.
    val warnings = ArrayList<String>()
    val over = rules("""{"models": [{"match": "^m", "quirks": ["NO_TELEPATHY", "NO_STOP"]}]}""", warnings)
    assertEquals(setOf(ModelQuirks.Quirk.NO_STOP), ModelQuirks.quirksOf("m1", over))
    assertTrue(warnings.single().contains("NO_TELEPATHY"), warnings.toString())
  }

  @Test
  fun `a file that does not parse at all says so`() {
    val warnings = ArrayList<String>()
    val over = rules("""{"models": [ """, warnings)
    assertTrue(over.isEmpty())
    assertEquals(1, warnings.size)
    // Silence here would leave someone editing a file nobody reads.
    // Текст идёт из каталога строк; проверяем факт предупреждения, а не его формулировку —
    // решение по тексту строки запрещено правилом локализации.
    assertTrue(warnings.single().isNotBlank(), warnings.toString())
  }

  @Test
  fun `comments and trailing commas are allowed, like everywhere else in vibe`() {
    val over = rules(
      """
      {
        // мой gpt-5 больше не ругается на temperature
        "models": [
          {"match": "^gpt-5", "quirks": [],},
        ],
      }
      """
    )
    assertEquals(emptySet(), ModelQuirks.quirksOf("gpt-5", over))
  }

  @Test
  fun `the source of the answer is nameable`() {
    // «Почему у меня пропала temperature» has exactly one useful answer: who decided that.
    assertEquals("built-in", ModelQuirks.sourceOf("gpt-5", emptyList()))
    assertNull(ModelQuirks.sourceOf("llama-3", emptyList()))
    assertEquals("modelQuirks.json", ModelQuirks.sourceOf("llama-3", rules("""{"models":[{"match":"llama","quirks":[]}]}""")))
  }

  @Test
  fun `the watcher notices the file`() {
    assertTrue(ProvidersWatchPaths.matches("/p/.vibe/modelQuirks.json", "/p", "/home/x"))
    assertTrue(ProvidersWatchPaths.matches("/home/x/.vibe/modelQuirks.json", "/p", "/home/x"))
    assertTrue(!ProvidersWatchPaths.matches("/p/modelQuirks.json", "/p", "/home/x"))
  }
}
