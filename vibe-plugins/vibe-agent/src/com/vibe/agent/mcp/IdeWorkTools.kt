// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.mcp

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.vibe.agent.context.AccessPolicy
import com.vibe.agent.context.AgentPaths
import com.vibe.agent.context.ProjectContextService
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Руки агента: записать файл, поправить кусок, выполнить команду.
 *
 * Зачем: до 18.09.2026 у модели в прямом чате не было ни одного инструмента, который что-то ДЕЛАЕТ
 * — только чтение индексов. «По факту он сейчас на уровне веб-чата» (брат владельца, тот же день):
 * чат, который умеет посоветовать, но не умеет сделать, отличается от браузерной вкладки только
 * тем, что стоит на той же машине, где лежит работа.
 *
 * Права берутся у той же [AccessPolicy], что у остальных каналов: запрет `.vibe/ignore` и границы
 * проекта не должны обходиться сменой инструмента. Спрашивать человека — не наша забота:
 * [DirectChatTools] спрашивает по классу опасности до вызова, а внешний клиент проходит через
 * выключатели MCP.
 */
object IdeWorkTools {
  /** Полный путь по тому, что назвала модель: относительный считается от корня проекта. */
  fun resolve(project: Project, raw: String): String {
    val base = project.basePath
    return if (File(raw).isAbsolute || base == null) raw else File(base, raw).path
  }

  fun mayWrite(project: Project, path: String): Boolean {
    val resolved = AgentPaths.resolve(path) as? AgentPaths.Result.Resolved ?: return false
    return AccessPolicy.mayWrite(resolved.path.canonical.toString(), ProjectContextService.getInstance(project).roots())
  }

  /**
   * Записать файл целиком.
   *
   * Папки создаются по дороге: отказ «нет такой папки» на создании нового файла — это работа,
   * переложенная на человека, и ничего больше. VFS обновляется явно, иначе редактор показывает
   * вчерашний текст, и человек считает, что агент соврал.
   */
  fun write(project: Project, raw: String, content: String): String {
    val path = resolve(project, raw)
    if (!mayWrite(project, path)) return "писать нельзя: $raw"
    val file = File(path)
    file.parentFile?.mkdirs()
    val existed = file.exists()
    // Снимок «до» берётся ДО записи и только здесь: без него кнопка «отклонить» не имеет смысла,
    // а решение о правке агента человек принимает вслепую.
    val before = if (existed) runCatching { file.readText() }.getOrNull() else null
    file.writeText(content)
    AgentEditJournal.getInstance(project).record(path, before, content)
    LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
    return (if (existed) "переписан" else "создан") + ": " + raw + " (" + content.length + " символов)"
  }

  /**
   * Заменить кусок текста в файле.
   *
   * Замена требует ЕДИНСТВЕННОГО совпадения: правка, попавшая в три места вместо одного, ломает
   * файл молча, и увидит это человек, а не модель. Ноль совпадений — тоже отказ, а не тихое «ок»:
   * молчаливый промах выглядит как выполненная работа.
   */
  fun replace(project: Project, raw: String, old: String, new: String): String {
    val path = resolve(project, raw)
    if (!mayWrite(project, path)) return "писать нельзя: $raw"
    val file = File(path)
    if (!file.isFile) return "файла нет: $raw"
    val text = file.readText()
    val count = countOf(text, old)
    if (count == 0) return "в $raw не найдено то, что просили заменить"
    if (count > 1) return "в $raw это встречается $count раз — уточните кусок так, чтобы он был единственным"
    val updated = text.replaceFirst(old, new)
    file.writeText(updated)
    AgentEditJournal.getInstance(project).record(path, text, updated)
    LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
    return "правка внесена: $raw"
  }

  /**
   * Выполнить команду в корне проекта и вернуть её вывод.
   *
   * Оболочкой, а не разбором аргументов: человек и модель пишут команды так, как их пишут в
   * терминале — с конвейерами и кавычками, — и разбирать это самим значит расходиться с тем, что
   * они видят. Потолок времени свой и меньше потолка вызова: инструмент обязан ответить «не
   * уложилась за N секунд» сам, иначе ход обрывается общим сторожем и причина теряется.
   */
  fun run(project: Project, command: String, timeoutSeconds: Int = TIMEOUT_SECONDS): String {
    val ceiling = timeoutSeconds.coerceIn(1, MAX_TIMEOUT_SECONDS)
    val base = project.basePath ?: return "у проекта нет корня — команду негде выполнять"
    val shell = if (com.vibe.agent.util.ExecutableNames.isWindows()) listOf("cmd.exe", "/c", command)
                else listOf("/bin/sh", "-lc", command)
    val process = ProcessBuilder(shell)
      .directory(File(base))
      // Окружение ЛОГИН-ОБОЛОЧКИ, а не процесса IDE. Та же грабля, на которой языковые серверы не
      // находили ноду: приложение, запущенное из Dock, шеллового PATH не наследует, и `npm test`
      // отвечает «command not found» на машине, где npm стоит и работает (18.09.2026, перечитка).
      .apply { environment().putAll(loginEnvironment()) }
      .redirectErrorStream(true)
      .start()
    val output = StringBuilder()
    val reader = Thread {
      process.inputStream.bufferedReader().forEachLine { line ->
        synchronized(output) { if (output.length < OUTPUT_CHARS) output.appendLine(line) }
      }
    }
    reader.isDaemon = true
    reader.start()
    val finished = process.waitFor(ceiling.toLong(), TimeUnit.SECONDS)
    if (!finished) {
      process.destroyForcibly()
      return "команда не уложилась за $ceiling с и остановлена:\n$command\n" + snapshot(output)
    }
    reader.join(JOIN_MS)
    return "код выхода ${process.exitValue()}:\n" + snapshot(output).ifBlank { "(вывода нет)" }
  }

  /**
   * Окружение логин-оболочки, как его видит терминал человека.
   *
   * Платформа считает его один раз за сеанс и помнит, поэтому вызов дешёвый. Пустая карта при
   * отказе — не беда: команда просто пойдёт с окружением IDE, то есть как было до этой правки.
   */
  fun loginEnvironment(): Map<String, String> =
    runCatching { com.intellij.util.EnvironmentUtil.getEnvironmentMap() }.getOrDefault(emptyMap())

  private fun snapshot(output: StringBuilder): String = synchronized(output) {
    val text = output.toString().trim()
    if (text.length < OUTPUT_CHARS) text else text.take(OUTPUT_CHARS) + "\n… вывод обрезан"
  }

  private fun countOf(text: String, part: String): Int {
    if (part.isEmpty()) return 0
    var from = 0
    var found = 0
    while (true) {
      val at = text.indexOf(part, from)
      if (at < 0) return found
      found++
      from = at + part.length
    }
  }

  /**
   * Меньше потолка вызова в [DirectChatTools]: причину обязан называть инструмент, а не сторож.
   *
   * Пять минут, а не полминуты: за полминуты не проходит ни один прогон тестов, ради которого
   * команды и дали. Дольше пяти — просьбой в аргументах, но не бесконечно: ход, ждущий час,
   * человек прерывает сам, и лучше бы он об этом узнал от инструмента.
   */
  private const val TIMEOUT_SECONDS = 300

  /** Потолок, выше которого не поднимает и явная просьба: дальше начинается «висит навсегда». */
  private const val MAX_TIMEOUT_SECONDS = 540

  private const val OUTPUT_CHARS = 20_000
  private const val JOIN_MS = 500L
}
