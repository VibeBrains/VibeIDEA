// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.tasks

/**
 * The task combo in the main toolbar is on by default, but only once.
 *
 * The platform shows the combo only for a project with a non-default task, a private tracker server,
 * or the "Always display the task combo" setting, which is off by default.
 * So in a fresh project the toolbar slot stays empty and the feature looks missing.
 * We turn the setting on the first time this settings profile meets our build.
 * After that the person decides: a combo switched off by hand is never switched back on.
 *
 * Pure: state in, decision out.
 */
object TaskComboDefault {
  enum class Action {
    /** Turn the setting on and remember that the default was applied. */
    ENABLE,

    /** Leave the setting as it is: already applied here, or the person already has it on. */
    LEAVE_ALONE,
  }

  fun decide(alreadyApplied: Boolean, alwaysDisplayAlready: Boolean): Action = when {
    alreadyApplied -> Action.LEAVE_ALONE
    alwaysDisplayAlready -> Action.LEAVE_ALONE
    else -> Action.ENABLE
  }
}
