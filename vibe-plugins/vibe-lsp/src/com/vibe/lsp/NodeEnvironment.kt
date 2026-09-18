// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp

import com.redhat.devtools.lsp4ij.server.ProcessStreamConnectionProvider

/**
 * Окружение процесса языкового сервера: интерпретатор ноды первым в `PATH`.
 *
 * Абсолютного пути к `node` в команде мало. Сервер ESLint внутри себя зовёт `node` и `npx` по имени,
 * и в GUI-приложении на macOS, где `PATH` состоит из четырёх системных папок, эти вызовы падают уже
 * внутри сервера — то есть не «сервер не стартовал», а «стартовал и молчит».
 *
 * One place rather than three `init` blocks with the same two lines: the day the rule changes it
 * must change for every server at once.
 */
internal object NodeEnvironment {
  fun applyTo(provider: ProcessStreamConnectionProvider, workingDirectory: String?) {
    val environment = NodeRuntime.childEnvironment(workingDirectory)
    if (environment.isEmpty()) return
    provider.userEnvironmentVariables = environment
    provider.isIncludeSystemEnvironmentVariables = true
  }
}
