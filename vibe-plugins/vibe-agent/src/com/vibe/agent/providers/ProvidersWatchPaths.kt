// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

/**
 * Pure path matcher for the provider-config watcher: which file-system events mean
 * «the provider registry (or its keys) may have changed». Kept free of VFS so the
 * rules are unit-testable: `providers.json`, jsonc/json files of the `providers`
 * dir and `.env` under `<project>/.vibe` or `~/.vibe`.
 */
object ProvidersWatchPaths {
  /** The quirk catalogue a person maintains themselves; watched for the same reason as the rest. */
  const val QUIRKS_FILE = "modelQuirks.json"

  fun matches(path: String, projectBase: String?, home: String): Boolean {
    val p = path.replace('\\', '/')
    val roots = listOfNotNull(projectBase, home).map { it.replace('\\', '/').trimEnd('/') + "/.vibe" }
    for (root in roots) {
      val rel = when {
        p == root -> ""
        p.startsWith("$root/") -> p.removePrefix("$root/")
        else -> continue
      }
      if (rel == "providers.json" || rel == ".env" || rel == QUIRKS_FILE) return true
      if (rel.startsWith("providers/") && (rel.endsWith(".jsonc") || rel.endsWith(".json"))) return true
      // A create/delete/rename of the providers dir itself also changes the registry.
      if (rel == "providers") return true
    }
    return false
  }
}
