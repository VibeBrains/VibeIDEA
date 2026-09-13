// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.skills

import com.intellij.openapi.application.ModernApplicationStarter
import com.vibe.agent.providers.LlmClient
import com.vibe.agent.providers.ProvidersService
import java.io.File
import kotlin.system.exitProcess

/**
 * `vibe-evals <project-path> <skill-id> [--threshold 0..1]` — the skill evals without a window.
 *
 * The menu action asks a person and shows a dialog; a gate on agent behaviour needs a process that
 * prints a report and exits with a code a script can compare. Same cases, same model choice
 * ([SkillEvalTarget]) and same run ([SkillEvalRun]) as the action, so a number from CI describes what
 * the person would see by hand.
 *
 * The report goes to stdout as JSON; diagnostics go to stderr, so `> report.json` stays parseable.
 * Exit codes are [SkillEvalsCli]'s: 0 met the threshold, 1 below it, 2 could not run.
 */
internal class VibeSkillEvalsStarter : ModernApplicationStarter() {
  override suspend fun start(args: List<String>) {
    val options = when (val parsed = SkillEvalsCli.parse(args)) {
      is SkillEvalsCli.Parsed.Ok -> parsed.options
      is SkillEvalsCli.Parsed.Error -> fail(parsed.message)
    }
    val entry = SkillsStore.list(options.projectPath).firstOrNull { it.pkg.id == options.skillId }
      ?: fail("skill not found: ${options.skillId} in ${options.projectPath}")
    val file = File(File(entry.dir, SkillEvals.DIR), SkillEvals.FILE)
    val suite = when (val read = SkillEvals.read(if (file.isFile) file.readText() else null)) {
      is SkillEvals.Read.Ok -> read.suite
      is SkillEvals.Read.Broken -> fail("evals.json is broken: ${read.reason}")
      SkillEvals.Read.Missing -> fail("no ${SkillEvals.DIR}/${SkillEvals.FILE} in skill ${options.skillId}")
    }
    val target = SkillEvalTarget.defaultModel(options.projectPath) ?: fail("no active model in .vibe/providers")
    val resolved = ProvidersService.resolve(target.first, options.projectPath) { System.err.println("[providers] $it") }
      ?: fail("provider ${target.first.id} cannot be resolved")
    val client = LlmClient(projectBase = options.projectPath)
    System.err.println("vibe-evals: ${options.skillId}, ${suite.cases.size} cases, ${SkillEvalRun.calls(suite)} calls, model ${target.second.id}")
    val report = SkillEvalRun.run(options.skillId, target.second.id, entry.pkg.body, suite,
      caller = { messages ->
        val answer = StringBuilder()
        client.chat(resolved, target.second, messages) { delta -> answer.append(delta) }
        answer.toString()
      },
      onProgress = { done, case -> System.err.println("  case ${done + 1}/${suite.cases.size}: ${case.id}") },
    )
    println(SkillEvalRun.toJson(report))
    val code = SkillEvalsCli.exitCode(report, options.threshold)
    System.err.println("vibe-evals: score ${"%.2f".format(SkillEvalsCli.score(report))}, threshold ${options.threshold}, exit $code")
    exitProcess(code)
  }

  private fun fail(message: String): Nothing {
    System.err.println("vibe-evals: $message")
    System.err.println(SkillEvalsCli.USAGE)
    exitProcess(SkillEvalsCli.EXIT_ERROR)
  }
}
