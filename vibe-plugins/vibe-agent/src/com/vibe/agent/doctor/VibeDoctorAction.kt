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

    // Две разные вещи, которые путают чаще всего: версия ПРОДУКТА (её спрашивают в issue) и линия
    // платформы, на которой он собран (по ней считается совместимость плагинов).
    val info = ApplicationInfo.getInstance()
    lines.add(VibeDiagnosis.Line(t("doctor.line.build"), VibeDiagnosis.State.OK,
                                 t("doctor.detail.build", "version" to info.fullVersion, "build" to info.build.asString())))
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

    // «Почему у меня пропала temperature» имеет ровно один полезный ответ: кто это решил.
    // Без строки в докторе причуда — невидимая правка чужого запроса.
    val quirkModel = providers.firstOrNull { it.models.any { m -> m.default } }?.models?.firstOrNull { it.default }?.id
                     ?: providers.firstOrNull()?.models?.firstOrNull()?.id
    if (quirkModel != null) {
      val overrides = com.vibe.agent.providers.ModelQuirksRegistry.rulesFor(base)
      val quirks = com.vibe.agent.providers.ModelQuirks.quirksOf(quirkModel, overrides)
      val source = com.vibe.agent.providers.ModelQuirks.sourceOf(quirkModel, overrides)
      lines.add(VibeDiagnosis.Line(
        t("doctor.line.quirks"),
        VibeDiagnosis.State.OK,
        if (quirks.isEmpty()) t("doctor.detail.quirksNone", "model" to quirkModel)
        else t("doctor.detail.quirks", "model" to quirkModel,
               "list" to quirks.joinToString(", ") { it.name }, "source" to (source ?: "built-in")),
      ))
    }

    // A ceiling one cannot see the distance to is a ceiling one only meets by hitting it.
    val spendLimits = com.vibe.agent.settings.VibeChatSettings.spendLimits()
    if (spendLimits.any) {
      val spend = com.vibe.agent.budget.VibeSpendService.getInstance()
        .entries(com.vibe.agent.budget.SpendCeiling.MONTH_MS)
      val windows = com.vibe.agent.budget.SpendCeiling.check(spend, System.currentTimeMillis(), spendLimits)
      val worst = windows.firstOrNull()
      lines.add(VibeDiagnosis.Line(
        t("doctor.line.spend"),
        when {
          worst == null -> VibeDiagnosis.State.OK
          worst.exceeded -> VibeDiagnosis.State.ABSENT
          worst.ratio >= com.vibe.agent.budget.SpendCeiling.WARN_RATIO -> VibeDiagnosis.State.WARN
          else -> VibeDiagnosis.State.OK
        },
        windows.joinToString("; ") { v ->
          t("doctor.detail.spendWindow", "window" to v.window.id,
            "spent" to "%.2f".format(v.spent), "limit" to "%.2f".format(v.limit))
        },
      ))
    }

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

    // Дежурные проверки: битая запись молчит так же, как её отсутствие, — и это надо различать.
    val patrolText = base?.let { runCatching { Files.readString(Path.of(it, com.vibe.agent.patrol.Patrol.FILE)) }.getOrNull() }
    val patrols = com.vibe.agent.patrol.Patrol.parse(patrolText)
    lines.add(VibeDiagnosis.Line(
      t("doctor.line.patrols"),
      when {
        patrolText == null -> VibeDiagnosis.State.OK
        patrols.problems.isNotEmpty() -> VibeDiagnosis.State.WARN
        else -> VibeDiagnosis.State.OK
      },
      when {
        patrolText == null -> t("doctor.detail.patrolsNone")
        patrols.problems.isNotEmpty() -> t("doctor.detail.patrolsBroken",
                                           "count" to patrols.problems.size,
                                           "where" to patrols.problems.first().where)
        else -> t("doctor.detail.patrolsOk",
                  "total" to patrols.entries.size,
                  "active" to patrols.entries.count { it.active })
      },
    ))

    // Окно контекста: провайдер и конфиг говорят разное — и это никто не замечал.
    val windows = com.vibe.agent.providers.ClaimedContext.notices(
      providers, com.vibe.agent.providers.ClaimedContextRegistry.all())
    lines.add(VibeDiagnosis.Line(
      t("doctor.line.contextWindow"),
      when {
        com.vibe.agent.providers.ClaimedContextRegistry.isEmpty() -> VibeDiagnosis.State.OK
        windows.isEmpty() -> VibeDiagnosis.State.OK
        else -> VibeDiagnosis.State.WARN
      },
      when {
        com.vibe.agent.providers.ClaimedContextRegistry.isEmpty() -> t("doctor.detail.contextNotChecked")
        windows.isEmpty() -> t("doctor.detail.contextAgree")
        else -> {
          val first = windows.first()
          t("doctor.detail.contextMismatch",
            "model" to (first.providerId + "/" + first.modelId),
            "configured" to first.configured,
            "claimed" to first.claimed,
            "count" to windows.size)
        }
      },
    ))

    // Срок годности цены: цену пишет человек, и протухает она молча — весь учёт расхода
    // продолжает считаться по ней и выглядит достоверным.
    val prices = com.vibe.agent.providers.PriceValidity.notices(providers, today)
    val expired = prices.filter { it.state == com.vibe.agent.providers.PriceValidity.State.EXPIRED }
    val expiring = prices.filter { it.state == com.vibe.agent.providers.PriceValidity.State.SOON }
    lines.add(VibeDiagnosis.Line(
      t("doctor.line.priceValidity"),
      when {
        expired.isNotEmpty() -> VibeDiagnosis.State.WARN
        expiring.isNotEmpty() -> VibeDiagnosis.State.WARN
        else -> VibeDiagnosis.State.OK
      },
      when {
        expired.isNotEmpty() -> t("doctor.detail.priceExpired",
                                  "model" to (expired.first().providerId + "/" + expired.first().modelId),
                                  "count" to expired.size)
        expiring.isNotEmpty() -> {
          val soon = expiring.first()
          val line = t("doctor.detail.priceExpiring",
                       "model" to (soon.providerId + "/" + soon.modelId),
                       "days" to soon.daysLeft, "count" to expiring.size)
          // Множитель называется отдельной фразой: решение принимают по нему, а не по дате.
          val model = providers.firstOrNull { it.id == soon.providerId }
            ?.models?.firstOrNull { it.id == soon.modelId }
          val factor = com.vibe.agent.providers.PriceValidity.inputFactor(model?.pricing, soon.after)
          if (factor == null) line
          else line + " " + t("doctor.detail.priceAfter",
                              "factor" to String.format(java.util.Locale.ROOT, "%.1f", factor))
        }
        else -> t("doctor.detail.priceValidityNone")
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
    // Корпус: решения и входящее. Пусто — это WARN, а не ошибка: проект может их не вести, но
    // молчать об этом нельзя — «агент не знает наших решений» выглядит как поломка агента.
    val store = com.vibe.agent.decisions.DecisionStore.getInstance(project)
    val decisions = store.entries()
    val lost = store.orphans()
    lines.add(VibeDiagnosis.Line(
      t("doctor.line.decisions"),
      if (decisions.isEmpty() || lost.isNotEmpty()) VibeDiagnosis.State.WARN else VibeDiagnosis.State.OK,
      when {
        lost.isNotEmpty() -> t("doctor.detail.orphans", "files" to lost.joinToString())
        decisions.isEmpty() -> t("doctor.detail.noDecisions")
        else -> decisions.size.toString()
      }))

    val inbox = com.vibe.agent.ingest.IngestStore.getInstance(project)
    val orphans = inbox.orphans()
    lines.add(VibeDiagnosis.Line(t("doctor.line.inbox"),
                                 if (orphans.isEmpty()) VibeDiagnosis.State.OK else VibeDiagnosis.State.WARN,
                                 if (orphans.isEmpty()) inbox.entries().size.toString()
                                 else t("doctor.detail.orphans", "files" to orphans.joinToString())))
    return VibeDiagnosis.Report(lines)
  }

  private fun labels() = object : VibeDiagnosis.Labels {
    override fun header(problems: Int, total: Int) = t("doctor.header", "problems" to problems, "total" to total)
    override val allGood: String get() = t("doctor.allGood")
  }
}
