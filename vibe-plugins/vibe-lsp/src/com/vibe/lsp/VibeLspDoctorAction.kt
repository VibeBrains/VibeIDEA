// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages
import com.vibe.agent.i18n.VibeI18n.t

/**
 * Tools → "VibeIDEA: языковые серверы": says, in one dialog, whether the servers that do the
 * actual work are installed, and how to install the ones that are not.
 */
class VibeLspDoctorAction : AnAction({ t("lsp.doctor.action") }) {
  override fun actionPerformed(e: AnActionEvent) {
    val checks = LspDoctor.check()
    val debuggers = LspDoctor.check(
      LspDoctor.DEBUG_ADAPTERS,
      resolve = LspDoctor::adapterOwnPath,
      bundled = LspDoctor::adapterBundledPath,
    )
    val report = buildString {
      appendLine(t("lsp.doctor.intro"))
      appendLine()
      for (check in checks) {
        appendLine(line(check))
        if (!check.installed) appendLine("    " + check.spec.installCommand)
        // A bundled phar without an interpreter is a server that cannot start, and saying
        // «встроен» while it silently fails would be the same silence we exist to remove.
        val runtime = LspDoctor.runtimeFor(check.spec)
        if (check.source == LspDoctor.Source.BUNDLED && runtime != null && ServerBinaries.find(runtime) == null) {
          appendLine("    " + t("lsp.doctor.needsRuntime", "runtime" to runtime))
        }
      }
      appendLine()
      appendLine(t("lsp.doctor.debuggers"))
      for (check in debuggers) {
        appendLine(
          if (check.installed) t("lsp.doctor.installed", "server" to check.spec.displayName, "path" to check.path)
          else t("lsp.doctor.missing", "server" to check.spec.displayName, "binary" to check.spec.binary)
        )
        if (!check.installed) appendLine("    " + check.spec.installCommand)
      }
      appendLine()
      appendLine(t("lsp.doctor.debugSetup"))
      if ((checks + debuggers).any { !it.installed }) {
        appendLine()
        appendLine(t("lsp.doctor.restartNote"))
      }
    }
    Messages.showInfoMessage(e.project, report, t("lsp.doctor.title"))
  }

  /** Says WHOSE server is running: the person's own, or the one we ship. */
  private fun line(check: LspDoctor.Check): String = when (check.source) {
    LspDoctor.Source.OWN -> t("lsp.doctor.installed", "server" to check.spec.displayName, "path" to check.path)
    LspDoctor.Source.BUNDLED -> t("lsp.doctor.bundled", "server" to check.spec.displayName, "path" to check.path)
    LspDoctor.Source.ABSENT -> t("lsp.doctor.missing", "server" to check.spec.displayName, "binary" to check.spec.binary)
  }
}
