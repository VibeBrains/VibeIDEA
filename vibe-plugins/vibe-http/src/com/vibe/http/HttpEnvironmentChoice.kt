// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.http

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.Project
import java.nio.file.Path

/**
 * Какое окружение выбрано в этом проекте.
 *
 * Хранится, а не живёт в комбобоксе панели: выбор «прод» терялся при перезапуске IDE, и человек
 * отправлял следующий запрос в dev, будучи уверенным в обратном. Плюс выбор нужен не только
 * панели — по нему работают подсказки и подсветка неподставленных переменных в редакторе.
 *
 * Хранилище проектное: окружения описаны в файлах проекта, и «прод» одного репозитория не имеет
 * отношения к «прод» другого.
 */
object HttpEnvironmentChoice {
  private const val KEY = "vibe.http.environment"

  fun get(project: Project): String? =
    PropertiesComponent.getInstance(project).getValue(KEY)?.takeIf { it.isNotBlank() }

  fun set(project: Project, name: String?) =
    PropertiesComponent.getInstance(project).setValue(KEY, name)

  /**
   * Переменные, видимые запросу в этом файле: окружение плюс переменные самого файла.
   *
   * Переменные файла сильнее окружения — их для того рядом с запросом и пишут. Собрано в одном
   * месте, чтобы панель, подсказки и подсветка не разошлись в том, что считается известным.
   */
  fun variables(project: Project, fileDir: Path?, fileText: String?): Map<String, String> {
    val root = project.basePath?.let { runCatching { Path.of(it) }.getOrNull() }
    val environments = HttpEnvironments.read(HttpEnvironments.locate(fileDir, root))
    val chosen = get(project) ?: HttpEnvironments.choose(environments.keys, null)
    val fromFile = fileText?.let { HttpRequestFile.parse(it).variables }.orEmpty()
    return environments[chosen].orEmpty() + fromFile
  }

  /** Все имена окружений файла — для выбора в панели. */
  fun names(project: Project, fileDir: Path?): List<String> {
    val root = project.basePath?.let { runCatching { Path.of(it) }.getOrNull() }
    return HttpEnvironments.read(HttpEnvironments.locate(fileDir, root)).keys.sorted()
  }

  /** Динамические значения `{{$uuid}}` и соседи — известны всегда, окружения им не нужны. */
  val DYNAMIC: List<String> = listOf("uuid", "timestamp", "randomInt")
}
