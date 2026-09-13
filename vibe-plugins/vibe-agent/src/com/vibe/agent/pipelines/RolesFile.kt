// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.pipelines

import com.vibe.agent.i18n.VibeI18n.t
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

/**
 * `.vibe/roles.json` — role defaults shared with VibeIDE through the VibeBrains set (agreed 13.09.2026).
 *
 * A separate file, not a section of `pipelines.json`: VibeIDE calls `qa` from orchestrator delegation
 * too, and a role declared in the pipelines file would make delegation read pipelines.
 *
 * Today one field: `roles.qa.writePaths`, the scope a `qa` step writes under when it states no `paths`.
 * The list used to live as a constant in both products, kept equal by memory alone.
 *
 * Failure never widens the boundary: no file, a broken file or no `qa` entry keep the built-in
 * [RolePaths.TEST_PATHS]; an empty `writePaths: []` refuses every write — an empty allow list read as
 * «no restriction» would turn a typo into cancelling the boundary.
 */
object RolesFile {
  private val json = Json { ignoreUnknownKeys = true }

  fun path(projectBase: String): Path = Path.of(projectBase, ".vibe", "roles.json")

  private val BUILT_IN = RolePaths.Scope(allow = RolePaths.TEST_PATHS)
  private val NOTHING = RolePaths.Scope(deny = listOf("**"))

  /** The scope `qa` gets by default in this project. Pure over the text; [load] adds the disk. */
  fun qaScope(text: String?, onWarning: (String) -> Unit): RolePaths.Scope {
    if (text == null) return BUILT_IN
    val root = runCatching { json.parseToJsonElement(com.vibe.agent.util.VibeJsonc.strip(text)).jsonObject }.getOrElse {
      onWarning(t("roles.warn.unparsed", "reason" to it.message))
      return BUILT_IN
    }
    val qa = ((root["roles"] as? JsonObject)?.get("qa") as? JsonObject) ?: return BUILT_IN
    val raw = qa["writePaths"] ?: return BUILT_IN
    val list = (raw as? JsonArray)?.map { it.jsonPrimitive.contentOrNull?.trim().orEmpty() }
    if (list == null || list.any { it.isEmpty() }) {
      onWarning(t("roles.warn.notList"))
      return BUILT_IN
    }
    if (list.isEmpty()) {
      onWarning(t("roles.warn.emptyWritePaths"))
      return NOTHING
    }
    return RolePaths.Scope(allow = list)
  }

  fun load(projectBase: String?, onWarning: (String) -> Unit): RolePaths.Scope {
    val file = projectBase?.let(::path)?.takeIf { Files.isRegularFile(it) } ?: return BUILT_IN
    val text = runCatching { Files.readString(file) }.getOrElse {
      onWarning(t("roles.warn.unparsed", "reason" to it.message))
      return BUILT_IN
    }
    return qaScope(text, onWarning)
  }
}
