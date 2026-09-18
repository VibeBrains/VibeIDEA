// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.mcp

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.vibe.agent.terminal.AgentTerminalService

/**
 * Долгие команды агента — в фоне, с именем, по которому за ними можно вернуться.
 *
 * Зачем, если команда и так выполняется: потолок времени есть у любого хода, и он обязан быть —
 * иначе ход висит навсегда. Но дев-сервер, наблюдающий за файлами, и сборка на десять минут в этот
 * потолок не помещаются НИКОГДА, и без фона агент их запускать просто не может. Он и не запускал:
 * пробовал, получал «не уложилась» и делал вывод, что инструмент сломан.
 *
 * Фоновая команда не ждёт: она возвращает имя, а вывод и код выхода спрашиваются потом. Это тот же
 * порядок, которым пользуется человек, когда запускает сборку в одном окне терминала и продолжает
 * работать в другом.
 *
 * Число живых команд ограничено: агент, забывший остановить десяток дев-серверов, съедает машину
 * молча — и виноватым выглядит компьютер.
 */
@Service(Service.Level.PROJECT)
class AgentCommands(private val project: Project) : com.intellij.openapi.Disposable {
  private val terminals = AgentTerminalService(project.basePath)
  private val started = java.util.concurrent.ConcurrentHashMap<String, String>()

  data class Snapshot(val output: String, val finished: Boolean, val exitCode: Int?, val truncated: Boolean)

  /** Имя запущенной команды, или отказ словами. */
  fun start(command: String, env: Map<String, String>): Result<String> {
    // Кончившиеся команды мест не занимают: без этой уборки восемь ЗАВЕРШЁННЫХ прогонов тестов
    // закрывали запуск девятого, и выглядело это как «инструмент перестал работать».
    forgetFinished()
    if (started.size >= MAX_LIVE) {
      return Result.failure(IllegalStateException(MAX_LIVE.toString()))
    }
    return runCatching {
      val shell = if (com.vibe.agent.util.ExecutableNames.isWindows()) listOf("cmd.exe", "/c", command)
                  else listOf("/bin/sh", "-lc", command)
      val id = terminals.create(shell.first(), shell.drop(1), env, project.basePath, OUTPUT_LIMIT)
      started[id] = command
      id
    }
  }

  fun commandOf(id: String): String? = started[id]

  fun snapshot(id: String): Snapshot? {
    val output = terminals.output(id) ?: return null
    return Snapshot(output.output, output.finished, output.exitCode, output.truncated)
  }

  /** Остановить: процесс гасится вместе с потомками — оболочка без них оставила бы сервер жить. */
  fun stop(id: String): Boolean {
    val killed = terminals.kill(id)
    if (killed) started.remove(id)
    return killed
  }

  /** Всё, что сейчас запущено: имя и сама команда — иначе через час не вспомнить, что где. */
  fun running(): Map<String, String> = started.toMap()

  /** Только живые: полоска над вводом показывает человеку то, что реально крутится на его машине. */
  fun alive(): Map<String, String> = started.filterKeys { terminals.output(it)?.finished == false }

  /**
   * Забыть кончившиеся.
   *
   * Их вывод остаётся доступным, пока о нём спрашивают по имени, — но место в счётчике живых они
   * не держат: счётчик отвечает на вопрос «сколько процессов сейчас на машине», а не «сколько
   * команд агент запустил за сессию».
   */
  private fun forgetFinished() {
    started.keys.toList().forEach { id ->
      if (terminals.output(id)?.finished == true) started.remove(id)
    }
  }

  /**
   * Закрытие проекта гасит всё, что агент запустил.
   *
   * Иначе дев-сервер, поднятый агентом, переживает закрытие окна и держит порт: человек закрыл
   * проект, а машина продолжает работать за него — и найти это можно только в диспетчере задач.
   */
  override fun dispose() {
    terminals.disposeAll()
    started.clear()
  }

  companion object {
    /** Больше восьми живых команд — это не работа, а забытые процессы. */
    const val MAX_LIVE = 8

    /** Потолок вывода на команду: дев-сервер пишет бесконечно, и память не резиновая. */
    private const val OUTPUT_LIMIT = 2L * 1024 * 1024

    fun getInstance(project: Project): AgentCommands = project.service()
  }
}
