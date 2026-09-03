// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp

import com.intellij.ide.util.PropertiesComponent
import com.vibe.agent.util.ExecutableNames

/**
 * Which of the two PHP language servers serves `*.php` on this machine.
 *
 * PHP is the only language where one server cannot cover every system. The Phpactor phar we ship
 * is built with a hard requirement on `ext-posix`, and that extension does not exist in ANY Windows
 * build of PHP — it is a wrapper over POSIX system calls, not a package someone forgot to compile.
 * So on Windows Phpactor does not fail to be found, it fails to be possible, and the doctor telling
 * people to install it was sending them to spend an evening on something that cannot work
 * (найдено на живой машине 02–03.09.2026).
 *
 * Intelephense runs on Node, therefore everywhere. Its free tier covers exactly the hole Windows
 * has now — completion, go to definition, find references, diagnostics, hover, formatting; the paid
 * tier adds refactoring. It is proprietary, so it is installed on the machine and never shipped by
 * us, the same arrangement as vtsls and ESLint.
 *
 * The choice is explicit and not only automatic: on Linux somebody will prefer Intelephense, and
 * under WSL somebody will point us at their own Phpactor through the path field that already
 * exists. [AUTO] is what people get without an opinion, and it is a decision about the system, not
 * about the person.
 */
enum class PhpEngine(val id: String) {
  AUTO("auto"),
  PHPACTOR("phpactor"),
  INTELEPHENSE("intelephense"),
}

object PhpServerChoice {
  private const val KEY = "vibe.lsp.php.engine"

  /**
   * The engine that actually starts, given the choice and the system.
   *
   * Pure: the OS arrives as a parameter, so «что будет на Windows» is answered by a test on macOS
   * rather than by a machine somebody has to go and find.
   */
  fun resolve(choice: PhpEngine, windows: Boolean): PhpEngine = when (choice) {
    PhpEngine.AUTO -> if (windows) PhpEngine.INTELEPHENSE else PhpEngine.PHPACTOR
    else -> choice
  }

  /** An explicit choice is honoured even when it cannot work: it is theirs, and the doctor says so. */
  fun stored(): PhpEngine {
    val id = PropertiesComponent.getInstance().getValue(KEY).orEmpty()
    return PhpEngine.entries.firstOrNull { it.id == id } ?: PhpEngine.AUTO
  }

  fun store(engine: PhpEngine) = PropertiesComponent.getInstance().setValue(KEY, engine.id, PhpEngine.AUTO.id)

  fun effective(): PhpEngine = resolve(stored(), ExecutableNames.isWindows())

  /** The server behind an engine. [PhpEngine.AUTO] is resolved first — it never reaches here. */
  fun specOf(engine: PhpEngine): LspDoctor.ServerSpec = when (resolve(engine, ExecutableNames.isWindows())) {
    PhpEngine.INTELEPHENSE -> LspDoctor.INTELEPHENSE
    else -> LspDoctor.PHPACTOR
  }

  fun spec(): LspDoctor.ServerSpec = specOf(stored())

  /**
   * Whether this choice cannot work on this system.
   *
   * Said out loud rather than silently swapped: a person who chose Phpactor on Windows and got
   * Intelephense would conclude our setting does nothing. The setting works — the choice does not.
   */
  fun impossibleHere(engine: PhpEngine, windows: Boolean): Boolean =
    windows && resolve(engine, windows) == PhpEngine.PHPACTOR
}
