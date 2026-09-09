// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.security

import com.vibe.agent.i18n.VibeI18n.t

/**
 * Guards what goes INTO the model's context.
 *
 * The registry, the audit and the turn checks all watch what the agent *writes*. Nothing watched
 * what it *reads* — and a file is the cheapest way to talk to someone else's agent: it is enough to
 * put a sentence into a README of a library, and every agent that ever reads it hears an
 * instruction. Three classes of problem, three different policies, because the right answer is not
 * the same for all of them:
 *
 * - **Invisible characters** — removed silently. Zero-width spaces and joiners, the soft hyphen and
 *   especially the Unicode tag block (U+E0000–E007F), which renders as *nothing at all* and is the
 *   standard way to hide a prompt inside a line a human reviews and approves. Text that a person
 *   cannot see must never reach a model.
 *   Two exceptions, both because the character is genuinely part of the text: the subdivision-flag
 *   tag sequence (a black flag plus 2-6 tags plus a terminator — the only honest use of that block,
 *   and there are exactly three such flags), and a zero-width joiner standing between non-Latin
 *   letters or emoji. Cutting those defends nobody: it breaks a family emoji into three people,
 *   mangles Arabic and Indic words, and prints «7 invisible characters» over an ordinary README.
 *   A guard that cries on honest files is a guard nobody reads by the time it matters.
 * - **Bidi overrides** — removed. The Trojan Source trick: the file reads one way to a human and
 *   another to a compiler or a model. Source code has no honest use for them.
 * - **Instruction-shaped phrases** — REPORTED, never removed. Documentation, tests and this very
 *   file legitimately contain «ignore previous instructions»; silently rewriting a user's file
 *   content is worse than telling them what was found and letting them look.
 * - **Secrets** — reported, and optionally masked in what we send (never in the file itself).
 *
 * Pure: no IO, no IDE, no settings lookups — the caller decides what to do with the findings.
 */
object ContextSanitizer {
  enum class Kind { INVISIBLE, BIDI, INSTRUCTION, SECRET }

  /**
   * Насколько находка похожа на спрятанную инструкцию, а не на случайность.
   *
   * Один невидимый символ и сорок подряд — это не одна и та же новость, а строка «невидимых
   * символов: 41» одинакова для обеих. Шкала взята у открытого сканера `aid` (MIT,
   * github.com/wunderwuzzi23/aid) и совпадает с независимым порогом YARA-правила Cisco «больше
   * десяти совпадений в документе»: два автора сошлись на десятке, и выдумывать третье число
   * незачем.
   *
   * Считается по САМОМУ ДЛИННОМУ непрерывному прогону, а не по сумме: инструкция, спрятанная
   * теговым блоком, — это связная строка, а не россыпь.
   */
  enum class Severity { LOW, MEDIUM, HIGH, CRITICAL }

  /** 10 подряд — порог, на котором сошлись `aid` и YARA-правило; 40 — «это точно текст». */
  private const val RUN_SUSPICIOUS = 10
  private const val RUN_CRITICAL = 40

  /** Разреженная россыпь тоже считается, но только когда её много. */
  private const val TOTAL_SUSPICIOUS = 100

  fun severityOf(longestRun: Int, total: Int): Severity = when {
    longestRun >= RUN_CRITICAL -> Severity.CRITICAL
    longestRun >= RUN_SUSPICIOUS || total > TOTAL_SUSPICIOUS -> Severity.HIGH
    total >= RUN_SUSPICIOUS -> Severity.MEDIUM
    else -> Severity.LOW
  }

  data class Finding(
    val kind: Kind,
    val detail: String,
    val count: Int = 1,
    val severity: Severity = Severity.LOW,
    /** Самый длинный непрерывный прогон — то число, по которому severity и посчитана. */
    val longestRun: Int = 0,
  )

  data class Result(val text: String, val findings: List<Finding>) {
    val isClean: Boolean get() = findings.isEmpty()
  }

  /**
   * @param maskSecrets replace credential-shaped substrings with a marker in the returned text.
   *                    The file on disk is never touched — this only affects what we transmit.
   */
  fun sanitize(text: String, maskSecrets: Boolean = false): Result {
    if (text.isEmpty()) return Result(text, emptyList())
    val findings = ArrayList<Finding>()

    var invisible = 0
    var bidi = 0
    var run = 0
    var longestRun = 0
    val cleaned = buildString(text.length) {
      var index = 0
      while (index < text.length) {
        val codePoint = text.codePointAt(index)
        val width = Character.charCount(codePoint)

        // Флаг подразделения — единственное честное применение тегового блока. Пропускаем его
        // целиком одним куском: посимвольная проверка разорвала бы последовательность, и
        // 🏴󠁧󠁢󠁳󠁣󠁴󠁿 уехал бы модели как голый чёрный флаг, а человеку — как «7 невидимых символов».
        val flag = flagSequenceLength(text, index)
        if (flag > 0) {
          append(text, index, index + flag)
          index += flag
          run = 0
          continue
        }

        when {
          isInvisible(codePoint) && !isJoinerInScript(text, index) -> {
            invisible++
            run++
            if (run > longestRun) longestRun = run
          }
          isBidiControl(codePoint) -> { bidi++; run = 0 }
          else -> { appendCodePoint(codePoint); run = 0 }
        }
        index += width
      }
    }
    if (invisible > 0) {
      findings.add(Finding(Kind.INVISIBLE, t("sanitizer.invisible"), invisible,
                           severityOf(longestRun, invisible), longestRun))
    }
    if (bidi > 0) findings.add(Finding(Kind.BIDI, t("sanitizer.bidi"), bidi))

    val phrase = SecurityPhrases.INSTRUCTIONS.firstOrNull { it.containsMatchIn(cleaned) }
    if (phrase != null) findings.add(Finding(Kind.INSTRUCTION, t("sanitizer.instruction")))

    val secrets = SecretPatterns.labels(cleaned)
    secrets.forEach { findings.add(Finding(Kind.SECRET, it)) }

    val result = if (maskSecrets && secrets.isNotEmpty()) SecretPatterns.redact(cleaned) else cleaned
    return Result(result, findings)
  }

  /** Note prepended once per message so the model is told, in words, that context is data. */
  val DATA_NOT_INSTRUCTIONS: String get() = t("sanitizer.dataNotInstructions")

  /** U+1F3F4 — чёрный флаг, с которого начинается теговая последовательность подразделения. */
  private const val WAVING_BLACK_FLAG = 0x1F3F4
  private const val TAG_TERMINATOR = 0xE007F
  private const val TAG_PRINTABLE_FIRST = 0xE0020
  private const val TAG_PRINTABLE_LAST = 0xE007E

  /** Флагов подразделений в Unicode ровно три (gbeng/gbsct/gbwls) — код региона в 2–6 тегов. */
  private const val FLAG_TAGS_MIN = 2
  private const val FLAG_TAGS_MAX = 6

  /**
   * Длина легитимной последовательности «флаг + теги + терминатор» в символах, начиная с [index],
   * или 0 — если это не она.
   *
   * Ровно это и отличает честный 🏴󠁧󠁢󠁳󠁣󠁴󠁿 от контрабанды: у флага теговый прогон короткий, начинается
   * СРАЗУ после U+1F3F4 и закрыт U+E007F. Спрятанная инструкция ни одного из трёх условий не
   * выполняет — иначе она была бы длиной в пять букв и стояла за флагом.
   */
  private fun flagSequenceLength(text: String, index: Int): Int {
    if (text.codePointAt(index) != WAVING_BLACK_FLAG) return 0
    var at = index + Character.charCount(WAVING_BLACK_FLAG)
    var tags = 0
    while (at < text.length) {
      val cp = text.codePointAt(at)
      if (cp in TAG_PRINTABLE_FIRST..TAG_PRINTABLE_LAST) {
        tags++
        if (tags > FLAG_TAGS_MAX) return 0
        at += Character.charCount(cp)
        continue
      }
      if (cp == TAG_TERMINATOR && tags in FLAG_TAGS_MIN..FLAG_TAGS_MAX) {
        return at + Character.charCount(cp) - index
      }
      return 0
    }
    return 0
  }

  /**
   * Соединитель нулевой ширины (U+200D) стоит между символами письма, а не ломает слово.
   *
   * ZWJ несёт две несовместимые роли. В эмодзи и в арабице/индийских письменностях он —
   * обязательная часть текста: вырезав его, мы склеим 👨‍👩‍👧 в трёх отдельных человечков и
   * испортим написание слова. В латинице у него честного применения нет, и там он ровно то,
   * ради чего эта проверка написана: способ разорвать `ignore` так, чтобы фильтр не узнал слово,
   * а модель прочла.
   *
   * Поэтому решает окружение: между не-латинскими соседями — оставляем, у границы с ASCII —
   * режем и считаем.
   */
  private fun isJoinerInScript(text: String, index: Int): Boolean {
    if (text.codePointAt(index) != 0x200D) return false
    val before = text.codePointBefore(index.takeIf { it > 0 } ?: return false)
    val afterAt = index + Character.charCount(0x200D)
    if (afterAt >= text.length) return false
    val after = text.codePointAt(afterAt)
    return isScriptOrEmoji(before) && isScriptOrEmoji(after)
  }

  /** Всё, что заведомо не латиница и не пунктуация ASCII: письменности и эмодзи. */
  private fun isScriptOrEmoji(codePoint: Int): Boolean = codePoint >= 0x0590 && !isInvisible(codePoint)

  private fun isInvisible(codePoint: Int): Boolean = when (codePoint) {
    0x00AD -> true                       // soft hyphen
    0x200B, 0x200C, 0x200D -> true       // zero-width space / non-joiner / joiner
    0xFEFF -> true                       // zero-width no-break space (BOM in the middle of text)
    in 0x2060..0x2064 -> true            // word joiner, invisible operators
    in 0xE0000..0xE007F -> true          // tag characters: render as nothing, carry hidden text
    else -> false
  }

  // U+200E/200F (LRM/RLM) are marks, not overrides, and appear in honest bilingual text; the
  // embedding/override/isolate controls below are the ones that reorder a line.
  private fun isBidiControl(codePoint: Int): Boolean = when (codePoint) {
    in 0x202A..0x202E -> true            // LRE, RLE, PDF, LRO, RLO
    in 0x2066..0x2069 -> true            // LRI, RLI, FSI, PDI
    else -> false
  }

}
