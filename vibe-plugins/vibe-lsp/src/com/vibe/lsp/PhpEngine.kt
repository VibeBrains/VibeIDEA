// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp

import com.intellij.ide.util.PropertiesComponent

/**
 * Which of the two PHP language servers serves `*.php`.
 *
 * The bundled Phpactor phar is the default everywhere, Windows included. It used to fail there: the
 * phar's own entry point refuses to start without `ext-posix`, an extension no Windows build of PHP
 * has. The requirement turned out to be false — every `posix_*` call in the phar is guarded, and the
 * server answers `initialize` in full without it — so [ServerBinaries.phpactorScript] starts the
 * phar through a launcher that boots its autoloader past that check. So «PHP on Windows» is
 * answered by the server we already ship, not by a second one.
 *
 * Intelephense stays as an explicit choice: some people prefer it, and it runs on Node like the rest
 * of our servers. It is proprietary and freemium, so it is installed on the machine and never
 * shipped by us (the same arrangement as vtsls and ESLint).
 *
 * The choice is stored once per application: a person who prefers a server prefers it for
 * themselves, not for one project.
 */
enum class PhpEngine(val id: String) {
  AUTO("auto"),
  PHPACTOR("phpactor"),
  INTELEPHENSE("intelephense"),
}

object PhpServerChoice {
  private const val KEY = "vibe.lsp.php.engine"

  /**
   * The engine that actually starts. [PhpEngine.AUTO] means the bundled one — on every system.
   *
   * Pure and with the OS as a parameter on purpose: the day the answer depends on the system again,
   * the test that says «Windows gets Phpactor» is where the decision will be visible.
   */
  fun resolve(choice: PhpEngine, @Suppress("UNUSED_PARAMETER") windows: Boolean): PhpEngine = when (choice) {
    PhpEngine.AUTO -> PhpEngine.PHPACTOR
    else -> choice
  }

  fun stored(): PhpEngine {
    val id = PropertiesComponent.getInstance().getValue(KEY).orEmpty()
    return PhpEngine.entries.firstOrNull { it.id == id } ?: PhpEngine.AUTO
  }

  fun store(engine: PhpEngine) = PropertiesComponent.getInstance().setValue(KEY, engine.id, PhpEngine.AUTO.id)

  fun effective(): PhpEngine = resolve(stored(), com.vibe.agent.util.ExecutableNames.isWindows())

  /** The server behind an engine. [PhpEngine.AUTO] is resolved first — it never reaches here. */
  fun specOf(engine: PhpEngine): LspDoctor.ServerSpec = when (resolve(engine, com.vibe.agent.util.ExecutableNames.isWindows())) {
    PhpEngine.INTELEPHENSE -> LspDoctor.INTELEPHENSE
    else -> LspDoctor.PHPACTOR
  }

  fun spec(): LspDoctor.ServerSpec = specOf(stored())
}
