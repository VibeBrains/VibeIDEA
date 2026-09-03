// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.db

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Подключения проекта: `.vibe/dataSources.json`.
 *
 * Файлом, а не настройкой IDE, по той же причине, что и запросы `.http`: подключения — часть
 * проекта, они меняются вместе с ним и должны приезжать новому разработчику вместе с клоном.
 *
 * **Пароля в файле нет и быть не может.** Файл живёт в git, а пароль в git — это утечка с
 * отложенным сроком. Он хранится в связке ключей системы (PasswordSafe платформы), и в файле
 * остаётся только имя пользователя.
 *
 * Чистый: текст внутрь, описания наружу.
 */
object DataSources {
  const val FILE = ".vibe/dataSources.json"

  /** Драйвер, который умеет открыть это подключение. */
  enum class Kind { POSTGRES, MYSQL, MARIADB, SQLITE, H2, OTHER }

  data class DataSource(
    val id: String,
    val name: String,
    val url: String,
    val user: String?,
    /** Путь к jar с драйвером JDBC, если он не на classpath IDE. */
    val driverPath: String?,
    /** Класс драйвера — нужен, когда jar не объявляет сервис (старые драйверы). */
    val driverClass: String?,
  ) {
    val kind: Kind get() = kindOf(url)
  }

  data class Problem(val where: String, val trouble: Trouble)

  /** Что не так — кодом; фразу собирает интерфейс. */
  enum class Trouble { NOT_AN_OBJECT, NO_ID, NO_URL, DUPLICATE_ID, PASSWORD_IN_FILE }

  data class Parsed(val sources: List<DataSource>, val problems: List<Problem>)

  private val json = Json { ignoreUnknownKeys = true; isLenient = true }

  fun kindOf(url: String): Kind {
    val lower = url.lowercase()
    return when {
      lower.startsWith("jdbc:postgresql:") -> Kind.POSTGRES
      lower.startsWith("jdbc:mysql:") -> Kind.MYSQL
      lower.startsWith("jdbc:mariadb:") -> Kind.MARIADB
      lower.startsWith("jdbc:sqlite:") -> Kind.SQLITE
      lower.startsWith("jdbc:h2:") -> Kind.H2
      else -> Kind.OTHER
    }
  }

  /**
   * Разбор файла. Битая запись пропускается с указанием места, остальные подключения остаются
   * рабочими: файл правят руками, и опечатка в одном подключении не должна отключать все.
   */
  fun parse(text: String?): Parsed {
    if (text.isNullOrBlank()) return Parsed(emptyList(), emptyList())
    val root = runCatching { json.parseToJsonElement(text) }.getOrNull()
      ?: return Parsed(emptyList(), listOf(Problem("", Trouble.NOT_AN_OBJECT)))
    val array = when (root) {
      is JsonArray -> root
      is JsonObject -> root["dataSources"] as? JsonArray ?: return Parsed(emptyList(), listOf(Problem("", Trouble.NOT_AN_OBJECT)))
      else -> return Parsed(emptyList(), listOf(Problem("", Trouble.NOT_AN_OBJECT)))
    }
    val sources = ArrayList<DataSource>()
    val problems = ArrayList<Problem>()
    val seen = HashSet<String>()
    for ((index, element) in array.withIndex()) {
      val obj = element as? JsonObject
      if (obj == null) { problems.add(Problem("#$index", Trouble.NOT_AN_OBJECT)); continue }
      fun str(key: String): String? = (obj[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
      val id = str("id")
      val url = str("url")
      val where = id ?: str("name") ?: "#$index"
      if (id == null) { problems.add(Problem(where, Trouble.NO_ID)); continue }
      if (url == null) { problems.add(Problem(where, Trouble.NO_URL)); continue }
      if (!seen.add(id)) { problems.add(Problem(where, Trouble.DUPLICATE_ID)); continue }
      // Пароль в файле — не «поле, которое мы не поддерживаем», а находка: он уже в репозитории.
      if (obj.containsKey("password")) problems.add(Problem(where, Trouble.PASSWORD_IN_FILE))
      sources.add(
        DataSource(
          id = id,
          name = str("name") ?: id,
          url = url,
          user = str("user"),
          driverPath = str("driverPath"),
          driverClass = str("driverClass"),
        )
      )
    }
    return Parsed(sources, problems)
  }

  /**
   * Адрес без пароля — для показа человеку и для логов.
   *
   * Пароль попадает в URL чаще, чем хотелось бы: его туда пишут прямо в строке подключения, и
   * тогда он утекает в каждое сообщение об ошибке.
   */
  fun maskUrl(url: String): String =
    url.replace(Regex("(?i)(password=)([^&;]*)"), "$1***")
      .replace(Regex("//([^/:@]+):([^@]+)@"), "//$1:***@")
}
