// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.ui.composer

import com.vibe.agent.i18n.VibeI18n.t

/**
 * What an ACP agent sent for its modes and options, put into the person's language where the wording is known
 *
 * The agent names everything itself and in English, and the protocol gives no way to ask for another language
 * A string is translated only when the id is known AND the agent sent exactly the English text the table expects:
 * An id means different things to different agents, and a reworded string must not be shown under an old translation
 * Anything else is shown exactly as sent, so an unknown agent or a newer adapter loses nothing but the translation
 *
 * Known wording is what claude-agent-acp advertises: session modes, and the config options mode, model, effort, fast, agent
 * Model names and descriptions come from the model catalogue of the SDK and are never translated
 *
 * Each key is written whole in its branch and never built from the id: the localisation gate finds keys by their text
 */
internal object AcpLabels {
  /** The English text an agent is expected to send, and what is shown instead of it */
  private class Known(val sent: String, val shown: String)

  private fun String.through(known: Known?): String = if (known != null && known.sent == this) known.shown else this

  fun modeName(id: String, sent: String): String = sent.through(
    when (id) {
      "default" -> Known("Manual", t("acp.mode.default"))
      "acceptEdits" -> Known("Accept edits", t("acp.mode.acceptEdits"))
      "plan" -> Known("Plan", t("acp.mode.plan"))
      "auto" -> Known("Auto", t("acp.mode.auto"))
      "bypassPermissions" -> Known("Bypass permissions", t("acp.mode.bypassPermissions"))
      else -> null
    },
  )

  fun modeHint(id: String, sent: String?): String? = sent?.through(
    when (id) {
      "default" -> Known("Always ask before making changes", t("acp.mode.default.hint"))
      "acceptEdits" -> Known("Automatically accept all file edits", t("acp.mode.acceptEdits.hint"))
      "plan" -> Known("Create a plan before making changes", t("acp.mode.plan.hint"))
      "auto" -> Known("Claude handles permission decisions", t("acp.mode.auto.hint"))
      "bypassPermissions" -> Known("Accepts all permissions", t("acp.mode.bypassPermissions.hint"))
      else -> null
    },
  )

  fun optionName(id: String, sent: String): String = sent.through(
    when (id) {
      "mode" -> Known("Mode", t("acp.option.mode"))
      "model" -> Known("Model", t("acp.option.model"))
      "effort" -> Known("Effort", t("acp.option.effort"))
      "fast" -> Known("Fast mode", t("acp.option.fast"))
      "agent" -> Known("Agent", t("acp.option.agent"))
      else -> null
    },
  )

  fun optionHint(id: String, sent: String?): String? = sent?.through(
    when (id) {
      "mode" -> Known("Session permission mode", t("acp.option.mode.hint"))
      "model" -> Known("AI model to use", t("acp.option.model.hint"))
      "effort" -> Known("Available effort levels for this model", t("acp.option.effort.hint"))
      "fast" -> Known("Faster responses on supported models", t("acp.option.fast.hint"))
      "agent" -> Known("Main-thread agent persona", t("acp.option.agent.hint"))
      else -> null
    },
  )

  /** The mode option lists the same modes as the session does, so it goes through the same table */
  fun valueName(optionId: String, valueId: String, sent: String): String = when (optionId) {
    "mode" -> modeName(valueId, sent)
    "effort" -> sent.through(
      when (valueId) {
        "default" -> Known("Default", t("acp.value.effort.default"))
        "low" -> Known("Low", t("acp.value.effort.low"))
        "medium" -> Known("Medium", t("acp.value.effort.medium"))
        "high" -> Known("High", t("acp.value.effort.high"))
        "xhigh" -> Known("Xhigh", t("acp.value.effort.xhigh"))
        "max" -> Known("Max", t("acp.value.effort.max"))
        else -> null
      },
    )
    "fast" -> sent.through(
      when (valueId) {
        "on" -> Known("On", t("acp.value.on"))
        "off" -> Known("Off", t("acp.value.off"))
        else -> null
      },
    )
    "agent" -> sent.through(if (valueId == "default") Known("Default", t("acp.value.agent.default")) else null)
    else -> sent
  }

  fun valueHint(optionId: String, valueId: String, sent: String?): String? = when (optionId) {
    "mode" -> modeHint(valueId, sent)
    "agent" -> sent?.through(
      if (valueId == "default") Known("Standard Claude Code agent", t("acp.value.agent.default.hint")) else null,
    )
    else -> sent
  }

  /** A switch state in words, for the tooltip of the settings pill */
  fun switchState(on: Boolean): String = if (on) t("acp.value.on") else t("acp.value.off")
}
