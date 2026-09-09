// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.defaults

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * `.vibe/local/` — то, что IDE производит сама и что не принадлежит репозиторию.
 *
 * Внутри `.vibe` есть ровно одна граница, у которой есть смысл, и это не «конфиги против
 * остального»: конфигурация там вся. Граница проходит между **тем, что человек пишет и коммитит**
 * (провайдеры, хуки, пайплайны, навыки, правила) и **тем, что пишет IDE на этой машине** — журнал
 * аудита, чекпоинты, прогоны, планы, отметка сеялки. Второе перечислено в засеваемом `.gitignore`
 * поштучно, то есть граница существовала и раньше — просто была видна только гейту git, а человеку
 * приходилось узнавать её списком имён.
 *
 * Что сюда НЕ попало и почему:
 * - **`.vibe/.env`** — его пишет человек, а не IDE. Он машинно-локальный и в git не едет, но это
 *   конфигурация, а не производная; переносить его значило бы молча потерять ключи у всех, у кого
 *   он уже лежит, — и провайдеры «сломались» бы без единого сообщения.
 * - Каталог провайдеров, навыки, правила, промпты — всё это коммитится вместе с проектом.
 *
 * **Уже лежащие файлы переносятся, а не бросаются.** Чекпоинты — это то, чем работает `/undo`, а
 * журнал аудита нельзя восстановить ничем; «начнём в новом месте с чистого листа» здесь означает
 * тихую потерю. Перенос делается один раз, при первом обращении, и только если на новом месте
 * ещё пусто.
 */
object VibeLocal {
  const val DIR = "local"

  /** Каталог рантайма внутри уже известного `.vibe`. */
  fun dirIn(vibeDir: Path): Path = vibeDir.resolve(DIR)

  /** То же от корня проекта — форма, удобная вызывающим, у которых на руках `basePath`. */
  fun dir(projectBase: String): Path = dirIn(Path.of(projectBase, ".vibe"))

  /**
   * Путь рантайм-файла в новом месте, с одноразовым переносом старого.
   *
   * Молча: человек не просил переезда и не должен о нём думать. Не получилось перенести (файл
   * занят, прав нет) — работаем со старым путём, потому что отказать в записи журнала хуже, чем
   * оставить его на прежнем месте.
   */
  fun fileIn(vibeDir: Path, name: String): Path {
    val fresh = dirIn(vibeDir).resolve(name)
    if (Files.exists(fresh)) return fresh
    val legacy = vibeDir.resolve(name)
    runCatching { Files.createDirectories(fresh.parent) }.onFailure { return legacy }
    if (Files.exists(legacy)) {
      runCatching { Files.move(legacy, fresh, StandardCopyOption.ATOMIC_MOVE) }
        .recoverCatching { Files.move(legacy, fresh) }
        .onFailure { return legacy }
    }
    return fresh
  }

  fun file(projectBase: String, name: String): Path = fileIn(Path.of(projectBase, ".vibe"), name)

  /**
   * Соседи файла с тем же основанием — ротированные копии журнала (`audit.2026-09-09.jsonl.gz`).
   *
   * Переносятся вместе с ним: журнал, у которого архивы остались в другой папке, отвечает на
   * вопрос «что было неделю назад» тишиной, и это худший ответ из возможных.
   */
  fun migrateSiblings(projectBase: String, prefix: String, suffix: String) {
    val old = Path.of(projectBase, ".vibe")
    val fresh = dirIn(old)
    val stream = runCatching {
      Files.newDirectoryStream(old) { it.fileName.toString().let { n -> n.startsWith(prefix) && n.endsWith(suffix) } }
    }.getOrNull() ?: return
    stream.use { paths ->
      for (path in paths) {
        val target = fresh.resolve(path.fileName)
        if (Files.exists(target)) continue
        runCatching { Files.createDirectories(fresh) }
        runCatching { Files.move(path, target) }
      }
    }
  }
}
