// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.doctor

/**
 * One report that answers «почему не работает» without a conversation.
 *
 * Support for a tool like this is a sequence of questions — какой провайдер, есть ли ключ, включён
 * ли аудит, стоит ли языковой сервер — and every one of them is knowable from the machine. The
 * report gathers them once, so the first message about a problem already contains the answers.
 *
 * Pure: what to ask is the decision, and the value of each line is that it says «нет» as clearly as
 * «да». A diagnostic that lists only what is present never explains anything.
 */
object VibeDiagnosis {
  enum class State { OK, WARN, ABSENT }

  data class Line(val name: String, val state: State, val detail: String = "")

  data class Report(val lines: List<Line>) {
    val problems: List<Line> get() = lines.filter { it.state != State.OK }
  }

  /** Problems first: a report read from the middle is read wrong. */
  fun render(report: Report, labels: Labels): String {
    val ordered = report.lines.sortedByDescending { it.state.ordinal }
    return buildString {
      appendLine(labels.header(report.problems.size, report.lines.size))
      appendLine()
      for (line in ordered) {
        appendLine(mark(line.state) + " " + line.name + (if (line.detail.isBlank()) "" else " — " + line.detail))
      }
      if (report.problems.isEmpty()) {
        appendLine()
        append(labels.allGood)
      }
    }.trimEnd()
  }

  fun mark(state: State): String = when (state) {
    State.OK -> "✔"
    State.WARN -> "⚠"
    State.ABSENT -> "✖"
  }

  interface Labels {
    fun header(problems: Int, total: Int): String
    val allGood: String
  }
}
