// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.pipelines

/**
 * Потолки одного шага пайплайна: сколько токенов и сколько вызовов инструментов ему позволено.
 *
 * Поля `maxTokens` и `maxSteps` файл принимал с самого начала — и не применял ни разу. Это худший
 * вид отсутствующей возможности: человек написал ограничение, IDE его прочитала, не пожаловалась,
 * и шаг ушёл работать без потолка. Отказ был бы честнее молчания, но потолок здесь выполним:
 * токены приходят с `usage_update`, вызовы инструментов — с `tool_call`.
 *
 * Что потолок делает: прекращает ШАГ, а не прогон. Дальше решает сам пайплайн — у шага может стоять
 * `continueOnFailure`, и тогда следующий выполнится.
 *
 * Чистый: числа приходят снаружи, здесь только сравнение — иначе правило нельзя проверить, не
 * подняв агента.
 */
object StepLimits {
  enum class Verdict {
    /** Внутри потолков (или потолков нет). */
    OK,

    /** Шаг выбрал разрешённые токены. */
    TOKENS,

    /** Шаг сделал больше вызовов инструментов, чем ему позволено. */
    STEPS,
  }

  /**
   * Потолок считается достигнутым по НЕ-СТРОГОМУ превышению: `maxSteps: 5` значит «пять вызовов
   * можно, шестой нельзя». Ноль и отрицательное значат «потолка нет» — так пишут, когда хотят снять
   * ограничение, не удаляя поле.
   */
  fun check(usedTokens: Long, toolCalls: Int, maxTokens: Int?, maxSteps: Int?): Verdict {
    if (maxTokens != null && maxTokens > 0 && usedTokens > maxTokens) return Verdict.TOKENS
    if (maxSteps != null && maxSteps > 0 && toolCalls > maxSteps) return Verdict.STEPS
    return Verdict.OK
  }

  /** Есть ли у шага хоть один действующий потолок — чтобы не считать то, чего никто не спрашивал. */
  fun any(step: PipelineStep): Boolean =
    (step.maxTokens ?: 0) > 0 || (step.maxSteps ?: 0) > 0
}
