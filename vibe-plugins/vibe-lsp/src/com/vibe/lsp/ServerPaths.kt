// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp

import com.intellij.ide.util.PropertiesComponent
import java.nio.file.Files
import java.nio.file.Path

/**
 * Where a person told us their own language server is.
 *
 * «Свой сервер сильнее встроенного» has been true only for servers that happen to be on PATH — and
 * the projects that most need their own version are exactly the ones that keep it out of the way:
 * in `vendor/bin`, in a container-shared folder, in a checkout built from source. For those the
 * rule silently did not apply, and the bundled copy quietly won.
 *
 * The pure part is here so the decision is testable without settings, a project or a filesystem;
 * the storage is a single application-level property per server, because a person who built a
 * server from source did it for themselves, not for one project.
 */
object ServerPaths {
  private const val KEY_PREFIX = "vibe.lsp.path."

  /** Servers and adapters whose location can be overridden, keyed by the id the doctor uses. */
  val OVERRIDABLE: List<String> = listOf(
    LspDoctor.VTSLS.id,
    LspDoctor.PHPACTOR.id,
    LspDoctor.CSS.id,
    LspDoctor.ESLINT.id,
  )

  fun get(serverId: String): String =
    PropertiesComponent.getInstance().getValue(KEY_PREFIX + serverId).orEmpty()

  fun set(serverId: String, path: String) =
    PropertiesComponent.getInstance().setValue(KEY_PREFIX + serverId, path.trim(), "")

  /**
   * The override for this server, or null when there is none.
   *
   * [usable] is injected so the check is testable; by default a path counts only when it points at
   * something executable that exists. A path that no longer works is NOT silently ignored — the
   * caller reports it, because a setting that stopped applying without a word is worse than one
   * that never applied: the person keeps debugging the server instead of the setting.
   */
  fun overrideFor(
    serverId: String,
    stored: String = get(serverId),
    usable: (String) -> Boolean = { Files.isExecutable(Path.of(it)) },
  ): String? {
    val text = stored.trim()
    if (text.isEmpty()) return null
    return text.takeIf(usable)
  }

  /** A stored path that is set but no longer usable — the one state worth complaining about. */
  fun broken(
    serverId: String,
    stored: String = get(serverId),
    usable: (String) -> Boolean = { Files.isExecutable(Path.of(it)) },
  ): String? {
    val text = stored.trim()
    if (text.isEmpty()) return null
    return text.takeIf { !usable(it) }
  }
}
