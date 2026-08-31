// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.doctor

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.vibe.agent.i18n.VibeI18n.t
import com.vibe.agent.providers.ProvidersService
import com.vibe.agent.settings.VibeAgentSettings
import com.vibe.agent.settings.VibeChatSettings
import java.awt.datatransfer.StringSelection
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tools → «VibeIDEA: диагностика».
 *
 * Collects, in one pass, everything one would otherwise be asked in a support conversation. The
 * button copies the report, because the next thing that happens to it is being pasted somewhere.
 */
class VibeDoctorAction : AnAction({ t("doctor.action") }) {
  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    ApplicationManager.getApplication().executeOnPooledThread {
      val report = collect(project)
      val text = VibeDiagnosis.render(report, labels())
      ApplicationManager.getApplication().invokeLater {
        val answer = Messages.showYesNoDialog(project, text, t("doctor.title"), t("doctor.copy"), t("common.close"), null)
        if (answer == Messages.YES) CopyPasteManager.getInstance().setContents(StringSelection(text))
      }
    }
  }

  private fun collect(project: Project): VibeDiagnosis.Report {
    val lines = ArrayList<VibeDiagnosis.Line>()
    val base = project.basePath

    lines.add(VibeDiagnosis.Line(t("doctor.line.build"), VibeDiagnosis.State.OK,
                                 ApplicationInfo.getInstance().fullVersion))
    lines.add(VibeDiagnosis.Line(t("doctor.line.language"), VibeDiagnosis.State.OK,
                                 com.vibe.agent.i18n.VibeI18n.activeCode()))

    val providers = ProvidersService.load(base) { }
    val withKey = providers.count { ProvidersService.resolve(it, base) { }?.apiKey != null }
    val localCount = providers.count { ProvidersService.resolve(it, base) { }?.isLocal == true }
    lines.add(VibeDiagnosis.Line(
      t("doctor.line.providers"),
      if (providers.isEmpty()) VibeDiagnosis.State.ABSENT
      else if (withKey + localCount == 0) VibeDiagnosis.State.WARN else VibeDiagnosis.State.OK,
      t("doctor.detail.providers", "total" to providers.size, "keyed" to withKey, "local" to localCount),
    ))

    val acp = base?.let { Files.exists(Path.of(it, ".vibe", "acp.json")) } ?: false
    val today = java.time.LocalDate.now()
    val notices = com.vibe.agent.providers.ModelSunset.notices(providers, today)
    val retired = notices.count { it.state == com.vibe.agent.providers.ModelSunset.State.RETIRED }
    val soon = notices.filter { it.state == com.vibe.agent.providers.ModelSunset.State.SOON }
    lines.add(VibeDiagnosis.Line(
      t("doctor.line.sunset"),
      when {
        soon.isNotEmpty() -> VibeDiagnosis.State.WARN
        retired > 0 -> VibeDiagnosis.State.ABSENT
        else -> VibeDiagnosis.State.OK
      },
      when {
        soon.isNotEmpty() -> t("doctor.detail.sunsetSoon", "model" to (soon.first().providerId + "/" + soon.first().modelId),
                               "days" to soon.first().daysLeft, "count" to soon.size)
        retired > 0 -> t("doctor.detail.sunsetRetired", "count" to retired)
        else -> t("doctor.detail.sunsetNone")
      },
    ))

    val chain = com.vibe.agent.resilience.FailoverPlan.parseChain(com.vibe.agent.settings.VibeAgentSettings.failoverChain)
    lines.add(VibeDiagnosis.Line(
      t("doctor.line.failover"),
      when {
        chain.isEmpty() -> VibeDiagnosis.State.ABSENT
        com.vibe.agent.resilience.FailoverPlan.isSingleVendor(chain) -> VibeDiagnosis.State.WARN
        else -> VibeDiagnosis.State.OK
      },
      when {
        chain.isEmpty() -> t("doctor.detail.failoverNone")
        com.vibe.agent.resilience.FailoverPlan.isSingleVendor(chain) ->
          t("doctor.detail.failoverOneVendor", "vendor" to chain.first().providerId)
        else -> t("doctor.detail.failoverOk", "count" to chain.size)
      },
    ))

    lines.add(VibeDiagnosis.Line(t("doctor.line.acp"),
                                 if (acp) VibeDiagnosis.State.OK else VibeDiagnosis.State.WARN,
                                 if (acp) ".vibe/acp.json" else t("doctor.detail.acpDefault")))

    // Configs are named individually: «конфиги в порядке» is the answer nobody can act on.
    for (relative in com.vibe.agent.guard.ConfigGuard.FILES) {
      val path = base?.let { Path.of(it, relative) } ?: continue
      if (!Files.isRegularFile(path)) continue
      val findings = runCatching { com.vibe.agent.guard.ConfigGuard.inspect(relative, Files.readString(path)) }
        .getOrDefault(emptyList())
      lines.add(VibeDiagnosis.Line(
        relative,
        when (com.vibe.agent.guard.ConfigGuard.worst(findings)) {
          com.vibe.agent.guard.ConfigGuard.Severity.ERROR -> VibeDiagnosis.State.ABSENT
          com.vibe.agent.guard.ConfigGuard.Severity.WARNING -> VibeDiagnosis.State.WARN
          null -> VibeDiagnosis.State.OK
        },
        findings.joinToString("; ") { it.rule },
      ))
    }

    lines.add(VibeDiagnosis.Line(t("doctor.line.offline"),
                                 if (VibeAgentSettings.offline) VibeDiagnosis.State.WARN else VibeDiagnosis.State.OK,
                                 if (VibeAgentSettings.offline) t("doctor.detail.offlineOn") else ""))
    lines.add(VibeDiagnosis.Line(t("doctor.line.audit"),
                                 if (VibeAgentSettings.auditEnabled) VibeDiagnosis.State.OK else VibeDiagnosis.State.WARN,
                                 if (VibeAgentSettings.auditEnabled) "" else t("doctor.detail.auditOff")))
    lines.add(VibeDiagnosis.Line(t("doctor.line.rag"),
                                 if (VibeAgentSettings.embeddingModel.isBlank()) VibeDiagnosis.State.WARN
                                 else VibeDiagnosis.State.OK,
                                 VibeAgentSettings.embeddingModel.ifBlank { t("doctor.detail.ragOff") }))
    lines.add(VibeDiagnosis.Line(t("doctor.line.sessionLimit"), VibeDiagnosis.State.OK,
                                 if (VibeChatSettings.sessionTokenLimit > 0) VibeChatSettings.sessionTokenLimit.toString()
                                 else t("doctor.detail.noLimit")))

    val rules = com.vibe.agent.context.ProjectContextService.getInstance(project).rules()
    lines.add(VibeDiagnosis.Line(t("doctor.line.rules"),
                                 if (rules.isEmpty()) VibeDiagnosis.State.WARN else VibeDiagnosis.State.OK,
                                 rules.size.toString()))

    val knowledge = com.vibe.agent.knowledge.KnowledgeIndex.getInstance(project).entries()
    lines.add(VibeDiagnosis.Line(t("doctor.line.knowledge"),
                                 if (knowledge.isEmpty()) VibeDiagnosis.State.WARN else VibeDiagnosis.State.OK,
                                 knowledge.size.toString()))
    return VibeDiagnosis.Report(lines)
  }

  private fun labels() = object : VibeDiagnosis.Labels {
    override fun header(problems: Int, total: Int) = t("doctor.header", "problems" to problems, "total" to total)
    override val allGood: String get() = t("doctor.allGood")
  }
}
