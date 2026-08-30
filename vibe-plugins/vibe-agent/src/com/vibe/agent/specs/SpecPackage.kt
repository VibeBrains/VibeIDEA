// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.specs

/**
 * Spec-First: a substantial feature is described before it is written, in two files that answer two
 * different questions.
 *
 * `PRODUCT.md` says what must be TRUE for a person — numbered invariants, so a later argument about
 * behaviour is a reference to a number rather than a memory of a conversation. `TECH.md` says how it
 * is done, grounded in the code: a technical spec that names no file is a wish, and it ages into a
 * lie within a week because nothing connects it to what exists.
 *
 * Both checks below are about exactly those two properties, and nothing else — a spec format with
 * ten required sections is a format people route around.
 */
object SpecPackage {
  const val ROOT = "docs/specs"
  const val PRODUCT = "PRODUCT.md"
  const val TECH = "TECH.md"

  data class Spec(val id: String, val product: String?, val tech: String?)

  data class Finding(val level: Level, val message: String)

  enum class Level { ERROR, WARNING }

  private val NUMBERED = Regex("(?m)^\\s*\\d+[.)]\\s+\\S")
  private val CODE_REFERENCE = Regex("[\\w/.-]+\\.(kt|kts|java|ts|tsx|js|jsx|php|py|go|rs|swift|sql)\\b")

  fun productPath(id: String): String = "$ROOT/$id/$PRODUCT"

  fun techPath(id: String): String = "$ROOT/$id/$TECH"

  /**
   * What is wrong with this spec. Missing files are errors; a present file that fails its one
   * property is a warning — a spec being written is normal, and shouting at a draft teaches people
   * to write the draft somewhere else.
   */
  fun validate(spec: Spec, labels: Labels): List<Finding> = buildList {
    if (spec.product == null) add(Finding(Level.ERROR, labels.noProduct))
    else if (!NUMBERED.containsMatchIn(spec.product)) add(Finding(Level.WARNING, labels.noInvariants))
    if (spec.tech == null) add(Finding(Level.ERROR, labels.noTech))
    else if (!CODE_REFERENCE.containsMatchIn(spec.tech)) add(Finding(Level.WARNING, labels.notGrounded))
  }

  fun invariants(product: String?): List<String> =
    product?.lines()?.map { it.trim() }?.filter { NUMBERED.containsMatchIn(it) } ?: emptyList()

  /** The templates. Short on purpose: a template longer than the spec is a template nobody fills. */
  fun productTemplate(id: String, labels: Labels): String = buildString {
    appendLine("# " + id)
    appendLine()
    appendLine(labels.productHeader)
    appendLine()
    appendLine("1. ")
    appendLine("2. ")
  }

  fun techTemplate(id: String, labels: Labels): String = buildString {
    appendLine("# " + id + " — " + labels.techTitle)
    appendLine()
    appendLine(labels.techHeader)
    appendLine()
    appendLine("- ")
  }

  interface Labels {
    val noProduct: String
    val noTech: String
    val noInvariants: String
    val notGrounded: String
    val productHeader: String
    val techHeader: String
    val techTitle: String
  }
}
