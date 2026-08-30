// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.design

import com.vibe.agent.i18n.VibeI18n.t

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/**
 * Lets the turn gate ask the design panel to measure — and, just as importantly, say WHY it cannot.
 *
 * A detector that silently reports nothing is indistinguishable from a clean page, and that is the
 * failure mode worth designing against: the run would end with «дизайн-замечаний нет» while nothing
 * was ever measured. So the answer is either findings or a stated reason.
 */
@Service(Service.Level.PROJECT)
class DesignMeasurementService {
  fun interface Measurer {
    /** Blocking measurement of both viewports; null when the page could not be read. */
    fun measure(timeoutMs: Long): List<Finding>?
  }

  @Volatile private var measurer: Measurer? = null

  fun register(measurer: Measurer) { this.measurer = measurer }

  fun unregister(measurer: Measurer) { if (this.measurer === measurer) this.measurer = null }

  /** Findings, or the reason there are none — never silence that reads as "all good". */
  fun measure(timeoutMs: Long): Result {
    val current = measurer ?: return Result(null, t("design.measure.noPanel"))
    val findings = current.measure(timeoutMs) ?: return Result(null, t("design.measure.noAnswer"))
    return Result(findings, null)
  }

  data class Result(val findings: List<Finding>?, val reason: String?)

  companion object {
    fun getInstance(project: Project): DesignMeasurementService = project.service()
  }
}
