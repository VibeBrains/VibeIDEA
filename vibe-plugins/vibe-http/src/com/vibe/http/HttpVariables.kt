// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.http

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Окружения и подстановка `{{переменных}}`.
 *
 * Окружение — лучшее, что есть в Postman: один и тот же запрос ходит в dev, staging и прод, и
 * меняется только выбранное окружение. Мы берём идею и меняем хранилище: не облако, а два файла
 * рядом с запросами — `http-client.env.json` (общий, едет в git) и `http-client.private.env.json`
 * (секреты, в .gitignore). Приватный сильнее общего, потому что именно в нём лежит настоящий токен.
 *
 * Чистый: файлы читает вызывающий, сюда приходит их текст.
 */
object HttpVariables {
  private val json = Json { ignoreUnknownKeys = true; isLenient = true }

  /** Имена файлов окружений — те же, что у соседних инструментов, чтобы файл был переносим. */
  const val SHARED_FILE = "http-client.env.json"
  const val PRIVATE_FILE = "http-client.private.env.json"

  /** Что подставлять не удалось: имя переменной и место. Пустая строка вместо неё — худший ответ. */
  data class Unresolved(val name: String)

  data class Substitution(val text: String, val unresolved: List<Unresolved>)

  /**
   * Окружения из текста файла: `{"dev": {"host": "..."}}`. Битый JSON — пустая карта, а не падение:
   * человек правит файл руками, и половина правки не должна ронять список запросов.
   */
  fun environments(text: String?): Map<String, Map<String, String>> {
    if (text.isNullOrBlank()) return emptyMap()
    val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return emptyMap()
    return root.mapNotNull { (name, value) ->
      val obj = value as? JsonObject ?: return@mapNotNull null
      name to obj.mapValues { (_, v) -> (v as? JsonPrimitive)?.content ?: v.toString() }
    }.toMap()
  }

  /** Приватное окружение перекрывает общее по ключу: токен лежит именно там. */
  fun merge(shared: Map<String, Map<String, String>>, private: Map<String, Map<String, String>>): Map<String, Map<String, String>> {
    val names = shared.keys + private.keys
    return names.associateWith { (shared[it].orEmpty()) + (private[it].orEmpty()) }
  }

  private val PLACEHOLDER = Regex("\\{\\{([^{}]+)}}")

  /**
   * Подстановка. Неразрешённая переменная ОСТАЁТСЯ в тексте как `{{name}}` и попадает в
   * [Substitution.unresolved]: видимая скобка называет ошибку, а молчаливое удаление её прячет —
   * запрос уйдёт на `https:///users` и ответит непонятно чем.
   *
   * [dynamic] отвечает за `{{$uuid}}` и подобные; передаётся снаружи, чтобы тест был повторяемым.
   */
  fun substitute(
    text: String,
    variables: Map<String, String>,
    dynamic: (String) -> String? = { null },
  ): Substitution {
    val missing = LinkedHashSet<String>()
    val result = PLACEHOLDER.replace(text) { match ->
      val name = match.groupValues[1].trim()
      val value = if (name.startsWith("$")) dynamic(name.removePrefix("$")) else variables[name]
      if (value == null) {
        missing.add(name)
        match.value
      }
      else value
    }
    return Substitution(result, missing.map(::Unresolved))
  }

  /**
   * Подстановка во весь запрос сразу: адрес, заголовки и тело.
   *
   * Отдельным методом, а не тремя вызовами у каждого потребителя: забытая подстановка в теле —
   * это отправленный на сервер литерал `{{token}}`, и увидят его в чужих логах.
   */
  fun apply(
    request: HttpRequestFile.Request,
    variables: Map<String, String>,
    dynamic: (String) -> String? = { null },
  ): Pair<HttpRequestFile.Request, List<Unresolved>> {
    val missing = LinkedHashSet<Unresolved>()
    fun sub(value: String): String = substitute(value, variables, dynamic).also { missing += it.unresolved }.text
    val body = when (val b = request.body) {
      is HttpRequestFile.Body.Inline -> HttpRequestFile.Body.Inline(sub(b.text))
      is HttpRequestFile.Body.FromFile -> HttpRequestFile.Body.FromFile(sub(b.path))
      null -> null
    }
    val applied = request.copy(
      target = sub(request.target),
      headers = request.headers.map { HttpRequestFile.Header(it.name, sub(it.value)) },
      body = body,
    )
    return applied to missing.toList()
  }
}
