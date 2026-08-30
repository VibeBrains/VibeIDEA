// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.design

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.vibe.agent.i18n.VibeI18n.t
import com.vibe.agent.settings.VibeAgentSettings

/**
 * Tools → «VibeIDEA: проверка дизайн-контура»: whether the machinery can work here at all.
 *
 * Use it when `design_review` says the page is out of reach, when accepted drift does not seem to
 * apply, or before telling someone the design side is set up.
 */
class VibeDesignDoctorAction : AnAction({ t("designDoctor.action") }) {
  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    ApplicationManager.getApplication().executeOnPooledThread {
      val report = collect(project)
      ApplicationManager.getApplication().invokeLater {
        Messages.showInfoMessage(project, DesignDoctor.render(report, labels()), t("designDoctor.title"))
      }
    }
  }

  private fun collect(project: Project): DesignDoctor.Report {
    val context = DesignContextFile.load(project.basePath)
    val files = listOfNotNull(
      context?.productPath?.toString(), context?.designPath?.toString(),
      context?.componentsPath?.toString(), context?.uiKitPath?.toString(),
    )
    // The measurement is asked for with a short timeout: this is a diagnosis, not a review, and a
    // doctor that hangs for thirty seconds is one nobody runs.
    val measurement = DesignMeasurementService.getInstance(project).measure(PROBE_TIMEOUT_MS)
    val accepted = context?.acceptedDrift?.map { it.ruleId }.orEmpty()
    return DesignDoctor.Report(
      contextFiles = files,
      pageReachable = measurement.findings != null,
      unreachableReason = measurement.reason,
      totalRules = DesignRuleCatalog.ALL.size,
      floorRules = DesignRuleCatalog.ALL.count { DesignRuleCatalog.isFloor(it) },
      acceptedDrift = accepted,
      unknownDrift = DesignDoctor.unknownDrift(accepted, DesignRuleCatalog.ALL.toSet()),
      hookMode = VibeAgentSettings.designMode,
    )
  }

  private fun labels() = object : DesignDoctor.Labels {
    override val noContext: String get() = t("designDoctor.noContext")
    override val pageReachable: String get() = t("designDoctor.pageReachable")
    override fun context(files: List<String>) = t("designDoctor.context", "files" to files.joinToString(", "))
    override fun pageUnreachable(reason: String?) = t("designDoctor.pageUnreachable", "reason" to (reason ?: ""))
    override fun rules(total: Int, floor: Int, style: Int) =
      t("designDoctor.rules", "total" to total, "floor" to floor, "style" to style)
    override fun drift(count: Int) = t("designDoctor.drift", "count" to count)
    override fun unknownDrift(ids: List<String>) = t("designDoctor.unknownDrift", "ids" to ids.joinToString(", "))
    override fun hook(mode: String) = t("designDoctor.hook", "mode" to mode)
  }

  private companion object {
    const val PROBE_TIMEOUT_MS = 3_000L
  }
}
