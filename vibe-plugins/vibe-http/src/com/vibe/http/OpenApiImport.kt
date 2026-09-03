// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.http

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Файл запросов из описания OpenAPI.
 *
 * Самый частый способ узнать чужой API — открыть его спецификацию, и самая скучная работа после
 * этого — перепечатать двадцать эндпоинтов руками. Здесь она делается один раз: на входе JSON
 * OpenAPI, на выходе готовый текст `.http` с адресом сервера в переменной, заголовками из
 * параметров и болванкой тела.
 *
 * Чистый: текст внутрь, текст наружу. Ни сети, ни файлов — спецификацию читает вызывающий.
 *
 * YAML не поддерживаем осознанно: свой разбор YAML — это новая зависимость или свой парсер, а
 * `openapi.json` отдаёт любой генератор и любой Swagger UI («Download» рядом со схемой).
 */
object OpenApiImport {
  private val json = Json { ignoreUnknownKeys = true; isLenient = true }

  private val METHODS = listOf("get", "post", "put", "patch", "delete", "head", "options", "trace")

  data class Endpoint(
    val method: String,
    val path: String,
    val summary: String?,
    /** Обязательные параметры пути — их подставляем как `{{имя}}`, чтобы запрос был рабочим. */
    val pathParams: List<String>,
    val queryParams: List<String>,
    val headerParams: List<String>,
    val hasBody: Boolean,
    val bodyContentType: String?,
    val needsAuth: Boolean,
  )

  data class Spec(val title: String?, val serverUrl: String?, val endpoints: List<Endpoint>)

  fun parse(text: String): Spec? {
    val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
    if (root["openapi"] == null && root["swagger"] == null) return null
    val title = ((root["info"] as? JsonObject)?.get("title") as? JsonPrimitive)?.contentOrNull
    val server = ((root["servers"] as? JsonArray)?.firstOrNull() as? JsonObject)
      ?.get("url")?.let { (it as? JsonPrimitive)?.contentOrNull }
      ?: ((root["host"] as? JsonPrimitive)?.contentOrNull?.let { "https://$it" })
    val paths = root["paths"] as? JsonObject ?: return Spec(title, server, emptyList())
    val endpoints = ArrayList<Endpoint>()
    for ((path, value) in paths) {
      val operations = value as? JsonObject ?: continue
      for (method in METHODS) {
        val operation = operations[method] as? JsonObject ?: continue
        val parameters = (operation["parameters"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        fun named(where: String) = parameters
          .filter { ((it["in"] as? JsonPrimitive)?.contentOrNull) == where }
          .mapNotNull { (it["name"] as? JsonPrimitive)?.contentOrNull }
        val body = operation["requestBody"] as? JsonObject
        val content = (body?.get("content") as? JsonObject)?.keys?.firstOrNull()
        endpoints.add(
          Endpoint(
            method = method.uppercase(),
            path = path,
            summary = (operation["summary"] as? JsonPrimitive)?.contentOrNull,
            pathParams = named("path"),
            queryParams = named("query"),
            headerParams = named("header"),
            hasBody = body != null,
            bodyContentType = content,
            // Требование авторизации на операции или на всём документе — повод положить заголовок.
            needsAuth = operation["security"] != null || root["security"] != null,
          )
        )
      }
    }
    return Spec(title, server, endpoints)
  }

  /**
   * Текст файла `.http` по спецификации.
   *
   * Параметры пути становятся `{{переменными}}`, а не остаются в фигурных скобках OpenAPI:
   * `/users/{id}` в файле — это `/users/{{id}}`, и запрос сразу выполним, стоит задать `id`
   * в окружении. Иначе адрес уходил бы на сервер с литералом `{id}`.
   */
  fun toHttpFile(
    spec: Spec,
    hostVariable: String = "host",
    /** Шапка приходит из интерфейса: чистый модуль не сочиняет фраз (правило проекта). */
    header: String? = null,
    serverNote: (String) -> String = { it },
  ): String = buildString {
    append("### ").append(spec.title ?: "API").append('\n')
    header?.lineSequence()?.forEach { append("# ").append(it).append('\n') }
    spec.serverUrl?.let { append("# ").append(serverNote(it)).append('\n') }
    append('\n')
    for (endpoint in spec.endpoints) {
      append("### ").append(endpoint.summary ?: "${endpoint.method} ${endpoint.path}").append('\n')
      append(endpoint.method).append(' ').append("{{").append(hostVariable).append("}}")
      append(pathWithVariables(endpoint.path))
      if (endpoint.queryParams.isNotEmpty()) {
        append('?').append(endpoint.queryParams.joinToString("&") { "$it={{$it}}" })
      }
      append('\n')
      if (endpoint.needsAuth) append("Authorization: Bearer {{token}}\n")
      endpoint.bodyContentType?.let { append("Content-Type: ").append(it).append('\n') }
      for (header in endpoint.headerParams) append(header).append(": {{").append(header).append("}}\n")
      if (endpoint.hasBody) append("\n{\n}\n")
      append('\n')
    }
  }

  /** `/users/{id}/posts/{postId}` → `/users/{{id}}/posts/{{postId}}`. */
  fun pathWithVariables(path: String): String =
    Regex("\\{([^{}]+)}").replace(path) { "{{" + it.groupValues[1] + "}}" }
}
