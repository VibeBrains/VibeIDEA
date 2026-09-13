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

    // Where the agents come from: the project's `.vibe/agents.json` and the machine's
    // `~/.jetbrains/acp.json` (AcpConfig). Until 11.09.2026 this line looked at `.vibe/acp.json`, a
    // file nothing has read since 09.09 — the doctor vouched for a dead path.
    val agentSources = listOfNotNull(
      base?.let { com.vibe.agent.acp.AcpConfig.projectPath(it) }?.takeIf { Files.isRegularFile(it) }?.let { ".vibe/agents.json" },
      com.vibe.agent.acp.AcpConfig.configPath().takeIf { Files.isRegularFile(it) }?.let { "~/.jetbrains/acp.json" },
    )
    val agentWarnings = ArrayList<String>()
    val agents = com.vibe.agent.acp.AcpConfig.load(base) { agentWarnings.add(it) }
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
        expired.isNotEmpty() -> {
          val gone = expired.first()
          t("doctor.detail.priceExpired",
            "model" to (gone.providerId + "/" + gone.modelId),
            "count" to expired.size) + priceSource(gone)
        }
        expiring.isNotEmpty() -> {
          val soon = expiring.first()
          val line = t("doctor.detail.priceExpiring",
                       "model" to (soon.providerId + "/" + soon.modelId),
                       "days" to soon.daysLeft, "count" to expiring.size)
          // Множитель называется отдельной фразой: решение принимают по нему, а не по дате.
          val model = providers.firstOrNull { it.id == soon.providerId }
            ?.models?.firstOrNull { it.id == soon.modelId }
          val factor = com.vibe.agent.providers.PriceValidity.inputFactor(model?.pricing, soon.after)
          val withFactor =
            if (factor == null) line
            else line + " " + t("doctor.detail.priceAfter",
                                "factor" to String.format(java.util.Locale.ROOT, "%.1f", factor))
          withFactor + priceSource(soon)
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

    // Видит ли агент, запущенный В IDE, инструменты самой IDE. Раньше ответ был «нет, никогда», и
    // об этом нельзя было узнать: пустой список серверов уезжал молча.
    val apiService = com.vibe.agent.http.VibeHttpApiService.getInstance()
    val apiRunning = apiService.isRunning
    lines.add(VibeDiagnosis.Line(
      t("doctor.line.ideTools"),
      if (apiRunning) VibeDiagnosis.State.OK else VibeDiagnosis.State.ABSENT,
      if (apiRunning) t("doctor.detail.ideToolsOn", "port" to apiService.port)
      else t("doctor.detail.ideToolsOff"),
    ))
    // The shared memory the agents get: installed and answering, or why not.
    val memory = com.vibe.agent.mcp.MemoryServerOffer.resolve()
    lines.add(VibeDiagnosis.Line(
      t("doctor.line.memory"),
      if (memory.reason == com.vibe.agent.mcp.MemoryServerOffer.Reason.OFFERED) VibeDiagnosis.State.OK else VibeDiagnosis.State.ABSENT,
      when (memory.reason) {
        com.vibe.agent.mcp.MemoryServerOffer.Reason.OFFERED -> t("doctor.detail.memoryOn", "path" to memory.path.toString())
        com.vibe.agent.mcp.MemoryServerOffer.Reason.NOT_INSTALLED -> t("doctor.detail.memoryMissing", "path" to memory.path.toString())
        com.vibe.agent.mcp.MemoryServerOffer.Reason.NOT_RUNNING -> t("doctor.detail.memoryBroken", "path" to memory.path.toString())
      },
    ))

    // Голос: чем расшифровывается и идёт ли расшифровка во время записи. Иначе «кнопка не
    // работает» выясняется первой же заметкой, а причин у этого три разных.
    val voiceModel = com.vibe.agent.settings.VibeAgentSettings.voiceModelPath
    val voiceLive = com.vibe.agent.voice.VoiceServer.configOf(voiceModel, null)
    val voiceOnce = com.vibe.agent.voice.VoiceTranscription.find(voiceModel, wav = true)
    lines.add(VibeDiagnosis.Line(
      t("doctor.line.voice"),
      when {
        voiceLive != null || voiceOnce != null -> VibeDiagnosis.State.OK
        com.vibe.agent.voice.VoiceTranscription.needsModel(voiceModel) -> VibeDiagnosis.State.WARN
        else -> VibeDiagnosis.State.ABSENT
      },
      when {
        voiceLive != null -> t("doctor.detail.voiceLive", "model" to java.io.File(voiceLive.model).name)
        voiceOnce != null -> t("doctor.detail.voiceOnce", "tool" to java.io.File(voiceOnce.binary).name)
        com.vibe.agent.voice.VoiceTranscription.needsModel(voiceModel) -> t("doctor.detail.voiceNoModel")
        else -> t("doctor.detail.voiceNone")
      },
    ))

    // Not «is it up» but «does it answer by the spec»: the unit suites see the MCP server and the
    // HTTP policy separately, never the listener between them, and a header lost there breaks every
    // client while every test stays green.
    if (apiRunning) {
      val port = apiService.port
      val probe = com.vibe.agent.mcp.McpSelfProbe.run(port, runCatching { com.vibe.agent.http.VibeApiToken.peek() }.getOrNull())
      val version = com.vibe.agent.mcp.McpProtocol.VERSION_2026
      val (state, detail) = when (probe) {
        com.vibe.agent.mcp.McpSelfProbe.Result.Ok ->
          VibeDiagnosis.State.OK to t("doctor.detail.mcpProbeOk", "port" to port, "version" to version)
        com.vibe.agent.mcp.McpSelfProbe.Result.NoToken ->
          VibeDiagnosis.State.WARN to t("doctor.detail.mcpProbeNoToken")
        is com.vibe.agent.mcp.McpSelfProbe.Result.Unreachable ->
          VibeDiagnosis.State.ABSENT to t("doctor.detail.mcpProbeUnreachable", "port" to port, "reason" to probe.reason)
        is com.vibe.agent.mcp.McpSelfProbe.Result.WrongAnswer -> VibeDiagnosis.State.WARN to when (probe.check) {
          com.vibe.agent.mcp.McpSelfProbe.Check.DISCOVER ->
            t("doctor.detail.mcpProbeDiscover", "status" to probe.status, "version" to version)
          com.vibe.agent.mcp.McpSelfProbe.Check.HEADER_MISMATCH ->
            t("doctor.detail.mcpProbeMismatch", "status" to probe.status, "code" to (probe.code?.toString() ?: "—"))
        }
      }
      lines.add(VibeDiagnosis.Line(t("doctor.line.mcpProbe"), state, detail))

      // Silence here used to look exactly like «all is fine»: the server answered, and nobody could
      // tell whether it was handing out writes in a project nobody trusted.
      val trusted = com.intellij.ide.trustedProjects.TrustedProjects.isProjectTrusted(project)
      val write = VibeAgentSettings.mcpAllowWrite
      val execute = VibeAgentSettings.mcpAllowExecute
      val (accessState, accessDetail) = when {
        !trusted -> VibeDiagnosis.State.WARN to t("doctor.detail.mcpAccessUntrusted")
        write && execute -> VibeDiagnosis.State.OK to t("doctor.detail.mcpAccessAll")
        execute -> VibeDiagnosis.State.OK to t("doctor.detail.mcpAccessExecute")
        write -> VibeDiagnosis.State.OK to t("doctor.detail.mcpAccessWrite")
        else -> VibeDiagnosis.State.OK to t("doctor.detail.mcpAccessReadOnly")
      }
      lines.add(VibeDiagnosis.Line(t("doctor.line.mcpAccess"), accessState, accessDetail))
    }

    lines.add(VibeDiagnosis.Line(
      t("doctor.line.acp"),
      if (agentWarnings.isNotEmpty() || agentSources.isEmpty()) VibeDiagnosis.State.WARN else VibeDiagnosis.State.OK,
      when {
        // A broken entry is skipped with a warning in the chat; here it is named once more, because
        // an agent that «just did not appear» is exactly what nobody thinks to look for.
        agentWarnings.isNotEmpty() -> t("doctor.detail.acpBroken",
                                        "files" to agentSources.joinToString().ifEmpty { t("doctor.detail.acpDefault") },
                                        "count" to agentWarnings.size, "reason" to agentWarnings.first())
        agentSources.isEmpty() -> t("doctor.detail.acpDefault")
        else -> agentSources.joinToString()
      },
    ))

    // Готовность внешних агентов: чем запускать и чем платить. Оба ответа знаемы заранее, и оба
    // иначе выясняются в худший момент — «агент не запустился» и счёт в конце месяца.
    val agentNotices = com.vibe.agent.acp.AgentReadiness.check(
      agents,
      onPath = { com.vibe.agent.acp.AcpClient.isAvailable(it) },
      env = { System.getenv(it) },
    )
    val missingRuntime = agentNotices.filter { it.kind == com.vibe.agent.acp.AgentReadiness.Kind.RUNTIME_MISSING }
    val payingTwice = agentNotices.filter { it.kind == com.vibe.agent.acp.AgentReadiness.Kind.SUBSCRIPTION_OVERRIDDEN }
    lines.add(VibeDiagnosis.Line(
      t("doctor.line.agentRuntime"),
      if (missingRuntime.isEmpty()) VibeDiagnosis.State.OK else VibeDiagnosis.State.ABSENT,
      if (missingRuntime.isEmpty()) t("doctor.detail.agentRuntimeOk")
      else t("doctor.detail.agentRuntimeMissing",
             "agent" to missingRuntime.first().agent,
             "command" to missingRuntime.first().detail,
             "count" to missingRuntime.size),
    ))
    if (payingTwice.isNotEmpty()) {
      lines.add(VibeDiagnosis.Line(
        t("doctor.line.agentBilling"),
        VibeDiagnosis.State.WARN,
        // The variable that actually decides, in Claude Code's own order — not always the API key.
        t("doctor.detail.agentBillingKey", "agent" to payingTwice.first().agent,
          "variable" to payingTwice.first().detail),
      ))
    }
    // Asked of the program that owns the login, by exit code only: its output names the account.
    if (agents.any { com.vibe.agent.acp.AgentReadiness.usesClaude(it) }) {
      val login = com.vibe.agent.acp.ClaudeLogin.check(
        onPath = { com.vibe.agent.acp.AcpClient.isAvailable(it) },
        run = { com.vibe.agent.acp.ClaudeLogin.exitCode(it) },
      )
      lines.add(VibeDiagnosis.Line(
        t("doctor.line.claudeLogin"),
        if (login == com.vibe.agent.acp.ClaudeLogin.State.LOGGED_IN) VibeDiagnosis.State.OK else VibeDiagnosis.State.WARN,
        when (login) {
          com.vibe.agent.acp.ClaudeLogin.State.LOGGED_IN -> t("doctor.detail.claudeLoginOk")
          com.vibe.agent.acp.ClaudeLogin.State.LOGGED_OUT -> t("doctor.detail.claudeLoginOut")
          com.vibe.agent.acp.ClaudeLogin.State.UNKNOWN ->
            t("doctor.detail.claudeLoginUnknown", "seconds" to com.vibe.agent.acp.ClaudeLogin.TIMEOUT.seconds)
          com.vibe.agent.acp.ClaudeLogin.State.NOT_INSTALLED -> t("doctor.detail.claudeLoginMissing")
        },
      ))
    }

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

  /**
   * Где вендор объявил цену — хвостом к строке про срок, если это записано в файле.
   *
   * «Сверьтесь с вендором» без адреса — совет, который откладывают: искать надо то, что однажды уже
   * нашли и записали рядом с ценой. Пусто, когда источник не назван: выдумывать ссылку нельзя.
   */
  private fun priceSource(notice: com.vibe.agent.providers.PriceValidity.Notice): String =
    notice.note?.takeIf { it.isNotBlank() }?.let { " · " + t("price.source", "note" to it) } ?: ""

  private fun labels() = object : VibeDiagnosis.Labels {
    override fun header(problems: Int, total: Int) = t("doctor.header", "problems" to problems, "total" to total)
    override val allGood: String get() = t("doctor.allGood")
  }
}
