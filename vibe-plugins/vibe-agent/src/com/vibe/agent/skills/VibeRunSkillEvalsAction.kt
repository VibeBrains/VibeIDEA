// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.skills

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.vibe.agent.i18n.VibeI18n.t
import com.vibe.agent.providers.LlmClient
import com.vibe.agent.providers.ModelEntry
import com.vibe.agent.providers.ProviderEntry
import com.vibe.agent.providers.ProvidersService
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Runs the cases of one skill and reports the verdicts (decision №89).
 *
 * Until a skill is run against a case, «навык работает» is an opinion: a skill is text that steers a
 * model, and the only way to know whether it steers is to ask a model and grade the answer.
 *
 * The run spends the owner's tokens — two calls per case, the model and the judge — so it never
 * starts without a confirmation that names the model and the count, and it can be stopped between
 * cases. The report is written outside the project: the skill directory is hashed for approval, and
 * a file dropped beside the cases would ask for approval again after every run.
 */
class VibeRunSkillEvalsAction : AnAction(t("skills.evals.action.title")) {
  private data class Found(val entry: SkillsStore.Entry, val suite: SkillEvals.Suite)

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    ApplicationManager.getApplication().executeOnPooledThread {
      val found = ArrayList<Found>()
      val broken = ArrayList<String>()
      for (entry in SkillsStore.list(project.basePath)) {
        val file = File(File(entry.dir, SkillEvals.DIR), SkillEvals.FILE)
        when (val read = SkillEvals.read(if (file.isFile) runCatching { file.readText() }.getOrNull() else null)) {
          is SkillEvals.Read.Ok -> found += Found(entry, read.suite)
          // A broken file is named, not skipped: silence here reads as «эвалов нет».
          is SkillEvals.Read.Broken -> broken += t("skills.evals.report.broken", "skill" to entry.pkg.id, "reason" to read.reason)
          SkillEvals.Read.Missing -> Unit
        }
      }
      val target = SkillEvalTarget.defaultModel(project.basePath)
      ApplicationManager.getApplication().invokeLater { begin(project, found, broken, target) }
    }
  }

  private fun begin(project: Project, found: List<Found>, broken: List<String>, target: Pair<ProviderEntry, ModelEntry>?) {
    val title = t("skills.evals.dialog.title")
    if (found.isEmpty()) {
      val where = SkillPackage.SKILLS_DIR + "/<id>/" + SkillEvals.DIR + "/" + SkillEvals.FILE
      val message = t("skills.evals.none", "path" to where) + broken.joinToString("") { "\n" + it }
      Messages.showInfoMessage(project, message, title)
      return
    }
    if (target == null) {
      Messages.showWarningDialog(project, t("skills.evals.noModel"), title)
      return
    }
    val ids = found.map { it.entry.pkg.id }.toTypedArray()
    // The dialog answers with the index of the choice, and -1 when the person closed it.
    val index = Messages.showChooseDialog(project, t("skills.evals.choose"), title, null, ids, ids.first())
    val chosen = found.getOrNull(index) ?: return
    val cases = chosen.suite.cases.size
    // Counted by the same rule the run follows: free cases skip the judge, runs and baseline multiply.
    val confirm = Messages.showYesNoDialog(
      project,
      t("skills.evals.confirm", "cases" to cases, "calls" to SkillEvalRun.calls(chosen.suite), "model" to target.second.id) +
        (if (chosen.suite.runs > 1 || chosen.suite.baseline)
           "\n" + (if (chosen.suite.baseline) t("skills.evals.confirmShape.baseline", "runs" to chosen.suite.runs)
                   else t("skills.evals.confirmShape.noBaseline", "runs" to chosen.suite.runs)) else ""),
      title, null,
    )
    if (confirm == Messages.YES) start(project, chosen, target)
  }

  private fun start(project: Project, found: Found, target: Pair<ProviderEntry, ModelEntry>) {
    val title = t("skills.evals.dialog.title")
    ProgressManager.getInstance().run(object : Task.Backgroundable(project, t("skills.evals.task.title", "skill" to found.entry.pkg.id), true) {
      override fun run(indicator: ProgressIndicator) {
        val resolved = ProvidersService.resolve(target.first, project.basePath) { }
        if (resolved == null) {
          ApplicationManager.getApplication().invokeLater { Messages.showWarningDialog(project, t("skills.evals.noModel"), title) }
          return
        }
        val client = LlmClient(projectBase = project.basePath)
        val total = found.suite.cases.size
        val caller = SkillEvalRun.Caller { messages ->
          val answer = StringBuilder()
          client.chat(resolved, target.second, messages, isCancelled = { indicator.isCanceled }) { delta -> answer.append(delta) }
          answer.toString()
        }
        val report = SkillEvalRun.run(
          found.entry.pkg.id, target.second.id, found.entry.pkg.body, found.suite, caller,
          attachments = { case -> attachmentsOf(found.entry.dir, case) },
          cancelled = { indicator.isCanceled },
          onProgress = { done, case ->
            indicator.fraction = done.toDouble() / total
            indicator.text = t("skills.evals.progress", "done" to done + 1, "total" to total, "case" to case.id)
          },
        )
        val saved = save(report)
        ApplicationManager.getApplication().invokeLater { Messages.showInfoMessage(project, render(report, saved), title) }
      }
    })
  }

  /**
   * Fixtures of a case, read from beside the cases. A path that leaves the skill directory is
   * ignored: a case describes its own skill, and reading the rest of the disk is not its business.
   */
  private fun attachmentsOf(dir: File, case: SkillEvals.Case): Map<String, String> {
    val root = runCatching { File(dir, SkillEvals.DIR).canonicalFile }.getOrNull() ?: return emptyMap()
    val out = LinkedHashMap<String, String>()
    for (path in case.files) {
      val file = runCatching { File(root, path).canonicalFile }.getOrNull() ?: continue
      if (!file.path.startsWith(root.path + File.separator) || !file.isFile) continue
      out[path] = runCatching { file.readText() }.getOrDefault("").take(ATTACHMENT_LIMIT)
    }
    return out
  }

  private fun save(report: SkillEvalRun.Report): File? = runCatching {
    val dir = File(PathManager.getSystemPath(), REPORT_DIR)
    dir.mkdirs()
    val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now())
    val file = File(dir, report.skillId + "-" + stamp + ".json")
    file.writeText(SkillEvalRun.toJson(report).toString())
    file
  }.getOrNull()

  private fun render(report: SkillEvalRun.Report, saved: File?): String = buildString {
    append(t("skills.evals.report.counts", "skill" to report.skillId, "model" to report.model,
             "passed" to report.passed, "failed" to report.failed, "unjudged" to report.unjudged))
    for (result in report.results) {
      append("\n")
      val verdict = result.verdict
      when {
        verdict == null -> append(t("skills.evals.report.unjudgedCase", "case" to result.case.id, "reason" to (result.error ?: "")))
        verdict.passed -> append(t("skills.evals.report.passedCase", "case" to result.case.id))
        else -> append(t("skills.evals.report.failedCase", "case" to result.case.id,
                         "failures" to verdict.failures.joinToString("; ").ifEmpty { verdict.note ?: "" }))
      }
      if (result.attempts.size > 1) {
        append("\n").append(t("skills.evals.report.rate", "rate" to percent(result.rate), "runs" to result.attempts.size))
      }
      result.delta?.let { delta ->
        append("\n").append(t("skills.evals.report.delta", "with" to percent(result.rate), "without" to percent(result.baselineRate),
                               "delta" to (if (delta >= 0) "+" else "") + percent(delta)))
      }
    }
    saved?.let { append("\n\n").append(t("skills.evals.report.saved", "path" to it.path)) }
  }

  private fun percent(value: Double?): String = value?.let { "%.0f%%".format(it * 100) } ?: "—"

  private companion object {
    const val ATTACHMENT_LIMIT = 100_000
    const val REPORT_DIR = "vibe/evals"
  }
}
