// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.security

import com.vibe.agent.i18n.VibeI18n.t

import com.vibe.agent.settings.VibeAgentSettings

/**
 * A word of warning the first time the agent is used in a repository this IDE has not seen before.
 *
 * Cloning someone else's project and pointing an agent at it is the ordinary case, not an exotic
 * one — and a foreign repository is exactly where an instruction addressed to the agent may be
 * waiting (in a README, a test fixture, a comment). The platform's own «trust this project?»
 * dialog answers a different question — whether to run build scripts — so it does not cover this.
 *
 * Deliberately a NOTICE, not a gate: nothing is blocked, nothing is asked. A dialog here would be
 * clicked away in a second and would only teach people to dismiss dialogs.
 */
object ForeignProjectNotice {
  /** Pure decision so the rule is testable: shown once per project path, and only while enabled. */
  fun shouldWarn(projectPath: String?, known: Set<String>, enabled: Boolean): Boolean {
    if (!enabled) return false
    val path = projectPath?.takeIf { it.isNotBlank() } ?: return false
    return path !in known
  }

  val TEXT: String get() = t("security.foreignProject.notice")

  /** Remembers the project so the notice does not repeat; returns true when it was shown now. */
  fun noticeOnce(projectPath: String?): Boolean {
    if (!shouldWarn(projectPath, VibeAgentSettings.knownProjects, VibeAgentSettings.warnForeignProject)) return false
    val path = projectPath ?: return false
    VibeAgentSettings.knownProjects = VibeAgentSettings.knownProjects + path
    return true
  }
}
