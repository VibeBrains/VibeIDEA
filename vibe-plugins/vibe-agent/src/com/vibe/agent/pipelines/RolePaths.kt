// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.pipelines

/**
 * Куда шагу пайплайна разрешено писать — не «кем он назван», а «во что попадает».
 *
 * [RoleRights] отвечает булевым: пишет роль или не пишет. Этого хватает ревьюеру, который не пишет
 * вовсе, и не хватает ровно там, где пайплайн из ролей и заводят: «этот шаг пишет тесты», «этот
 * пишет документацию». Без границы обе роли пишут куда угодно, и разделение остаётся пожеланием в
 * тексте задачи — то есть соблюдается, пока модель не решит иначе.
 *
 * Проверено 09.09.2026, что готового образца в индустрии почти нет: у GitHub Copilot роли
 * разграничены ИНСТРУМЕНТАМИ (`tools`, `excludedTools`), у VS Code параллельные агенты разведены
 * git-worktree'ами, у OpenHands `paths` в скилле — это триггер активации, а не право («Skills guide
 * the agent's behavior; they do not grant permissions»). Единственная система с правами на пути —
 * Claude Code, и её правила здесь и повторены: синтаксис gitignore, порядок **deny → allow**,
 * решает ПЕРВОЕ совпадение, специфичность порядок не меняет.
 *
 * Оттуда же взят их урок про имена: правило вида «Write(документы)» у них принимается и никогда
 * не проверяется, потому что по путям смотрят только `Edit` и `Read`, — то есть человек пишет
 * ограничение, видит, что оно принято, и не имеет ограничения. У нас точка записи одна (`fs/write`),
 * так что грабля не воспроизводится; правило названо здесь, чтобы не завестись при второй точке.
 *
 * Чистый: пути внутрь, вердикт наружу. Ни файловой системы, ни проекта.
 */
object RolePaths {
  /**
   * Ограничение шага: что разрешено и что запрещено, оба списка в синтаксисе gitignore.
   *
   * Пусто и там, и там — ограничения нет. Это важнее, чем кажется: шаг без `paths` обязан писать
   * куда угодно, иначе добавление поля в один шаг молча запретило бы запись всем остальным.
   */
  data class Scope(val allow: List<String> = emptyList(), val deny: List<String> = emptyList()) {
    val stated: Boolean get() = allow.isNotEmpty() || deny.isNotEmpty()
  }

  /**
   * Можно ли шагу писать в [relativePath] (путь от корня проекта, разделители `/`).
   *
   * Запреты сильнее разрешений и проверяются первыми — как у Claude Code. Разрешения без запретов
   * работают как белый список: перечислили каталог тестов — значит только туда.
   */
  // Примеры шаблонов вынесены в строчные комментарии намеренно: блочные комментарии в Kotlin
  // ВКЛАДЫВАЮТСЯ, и последовательность «слэш-звёздочка-звёздочка» внутри KDoc открывает вложенный
  // комментарий — файл после этого не компилируется, а ошибка указывает на его конец.
  //   allow = ["tests/**"]              — только дерево тестов
  //   allow = ["docs/**", "*.md"]       — доки и любые markdown-файлы на любом уровне
  //   deny  = ["**/secrets/**"]         — запрет сильнее любого разрешения
  fun mayWrite(relativePath: String, scope: Scope): Boolean {
    if (!scope.stated) return true
    val path = normalize(relativePath)
    if (scope.deny.any { matches(path, it) }) return false
    if (scope.allow.isEmpty()) return true
    return scope.allow.any { matches(path, it) }
  }

  private fun normalize(path: String): String = path.replace('\\', '/').removePrefix("./").trimStart('/')

  /**
   * Совпадение по правилу gitignore: `*` не переходит через `/`, `**` переходит, шаблон без `/`
   * внутри проверяется на любом уровне (как `node_modules` в .gitignore), шаблон, кончающийся на
   * `/`, означает каталог со всем содержимым.
   */
  fun matches(path: String, pattern: String): Boolean {
    val clean = pattern.trim().ifEmpty { return false }
    if (clean.endsWith("/")) return matches(path, clean.trimEnd('/') + "/**")
    // Шаблон без разделителя относится к имени на любой глубине — это правило .gitignore, и люди
    // пишут `*.md`, имея в виду именно его.
    if (!clean.contains('/')) {
      return Regex(globToRegex(clean)).matches(path.substringAfterLast('/')) ||
             Regex(globToRegex("**/$clean")).matches(path)
    }
    return Regex(globToRegex(clean.removePrefix("/"))).matches(path)
  }

  private fun globToRegex(glob: String): String {
    val out = StringBuilder("^")
    var i = 0
    while (i < glob.length) {
      when (val c = glob[i]) {
        '*' ->
          if (i + 1 < glob.length && glob[i + 1] == '*') {
            // `a/**/b` обязан совпадать и с `a/b`: иначе `docs/**` не покрывает сам `docs`.
            if (i + 2 < glob.length && glob[i + 2] == '/') { out.append("(?:.*/)?"); i += 2 }
            else out.append(".*").also { i++ }
          }
          else out.append("[^/]*")
        '?' -> out.append("[^/]")
        '.', '(', ')', '+', '|', '^', '$', '@', '%', '{', '}', '[', ']', '\\' -> out.append('\\').append(c)
        else -> out.append(c)
      }
      i++
    }
    return out.append('$').toString()
  }
}
