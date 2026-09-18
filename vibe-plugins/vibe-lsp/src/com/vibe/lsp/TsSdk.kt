// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp

import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

/**
 * Где лежит `typescript/lib` — каталог, без которого часть серверов не стартует вовсе.
 *
 * Не удобство и не оптимизация: сервер Astro отвечает на `initialize` ошибкой `-32603` «The
 * `typescript.tsdk` init option is required», то есть без этого пути язык не работает совсем
 * (проверено прямым запросом к серверу 18.09.2026). Сервер Vue стартует и без него, но типы
 * тогда выводит чужим экземпляром TypeScript, а не тем, которым собирается проект.
 *
 * Порядок тот же, что у Angular и у всех остальных серверов: СПЕРВА TypeScript проекта, потом наш.
 * Проект, закрепивший свою версию, должен обслуживаться своей — наша копия стареет вместе с
 * выпуском IDE, а его версия меняется его решением.
 *
 * Null означает «TypeScript не найден нигде». Это честный конец: сервер скажет о нём сам, а
 * подставить несуществующий путь значило бы заменить внятную ошибку молчанием.
 */
object TsSdk {
  private const val LIB = "lib"

  /** Каталог `typescript/lib` проекта, если он там есть. */
  fun projectLib(projectBase: String?): Path? {
    val base = projectBase?.takeIf { it.isNotBlank() } ?: return null
    val path = runCatching { Path.of(base, "node_modules", "typescript", LIB) }.getOrNull() ?: return null
    return path.takeIf { isSdk(it) }
  }

  /** Тот же каталог в нашем наборе серверов. */
  fun bundledLib(): Path? = ServerBinaries.bundledTypescriptLib()?.takeIf { isSdk(it) }

  /** Путь для `initializationOptions.typescript.tsdk`, или null — если TypeScript не найден. */
  fun tsdk(projectBase: String?): String? = (projectLib(projectBase) ?: bundledLib())?.toString()

  /**
   * Каталог считается SDK только по наличию `tsserver.js`.
   *
   * Пустая папка `typescript/lib` остаётся после неудачной установки пакета, и путь к ней сервер
   * принимает молча, а падает потом — на первом запросе, сообщением про другое.
   */
  private fun isSdk(path: Path): Boolean = Files.isRegularFile(path.resolve("tsserver.js"))
}

/**
 * `initializationOptions` с путём к TypeScript — в форме, которую ждут серверы Vue и Astro.
 *
 * Null, когда TypeScript не найден нигде: отправить поле с пустым значением хуже, чем не отправить
 * его вовсе — сервер примет пустую строку за указание и упадёт позже, сообщением про другое.
 */
internal fun tsdkOptions(projectBase: String?): JsonObject? {
  val tsdk = TsSdk.tsdk(projectBase) ?: return null
  return JsonObject().apply {
    add("typescript", JsonObject().apply { add("tsdk", JsonPrimitive(tsdk)) })
  }
}
