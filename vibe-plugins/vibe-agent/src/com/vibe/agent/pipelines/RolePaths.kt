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

  /** A boundary that refuses every write, for a turn that must not change the project. */
  val NOTHING: Scope = Scope(deny = listOf(ANY))

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
    // A route, not a place: `tests/../src/app.kt` matches `tests/**` as text and lands in src.
    // Callers resolve paths first (AgentPaths); whatever still carries dots is refused, not matched.
    if (path.split('/').any { it == "." || it == ".." }) return false
    // Deny rules fold case and Unicode form — on APFS and NTFS `Secrets/` IS `secrets/`; allow rules
    // match exactly, because an exact miss can only err towards refusing.
    val folded = com.vibe.agent.context.AccessPolicy.foldCase(path)
    if (scope.deny.any { matches(folded, com.vibe.agent.context.AccessPolicy.foldCase(it)) }) return false
    if (scope.allow.isEmpty()) return true
    return scope.allow.any { matches(path, it) }
  }

  private fun normalize(path: String): String = path.replace('\\', '/').removePrefix("./").trimStart('/')

  /**
   * The scope a role gets when its step states none.
   *
   * Only `qa` has one: it writes tests, and a tester that «just fixes» the code under test makes the
   * test and the fix one act nobody checked. A step that states its own `paths` replaces this — a
   * project with an unusual test layout says so, and no default can guess every layout.
   */
  // [qa] is the project's `qa` default from `.vibe/roles.json` (RolesFile); the built-in list when absent.
  fun defaultScope(role: String?, qa: Scope = Scope(allow = TEST_PATHS)): Scope = when (role?.trim()?.lowercase()) {
    "qa" -> qa
    else -> Scope()
  }

  /** Where tests live across the stacks this IDE serves: JVM, TS/JS, PHP, Python, Go. The fallback of `roles.json` — keep equal to its seed. */
  // Directories are spelled `**/name/**`, not `name/`: both match at any depth ([matches]), and the explicit form reads
  // the same to someone who does not know the trailing-slash rule. Tests live deep inside modules (`web/__tests__/`).
  val TEST_PATHS: List<String> = listOf(
    "**/test/**", "**/tests/**", "**/testSrc/**", "**/testData/**", "**/__tests__/**", "**/spec/**",
    "*Test.kt", "*Test.java", "*Tests.kt", "*Test.php", "*.test.ts", "*.test.tsx", "*.test.js",
    "*.spec.ts", "*.spec.tsx", "*.spec.js", "test_*.py", "*_test.py", "*_test.go",
  )

  /**
   * The scope a step actually writes under.
   *
   * The step's own ALLOW list replaces the role default; its deny list is added on top of whatever
   * allows. A step with only `denyPaths` used to count as «stated» and dropped the default entirely —
   * a `qa` step with one deny rule could then write anywhere but that rule. A deny narrows, it never
   * widens (the same reading as VibeIDE's `QA_DEFAULT_WRITE_PATHS`, found by comparing the two, 13.09.2026).
   */
  fun effective(role: String?, stated: Scope, qa: Scope = Scope(allow = TEST_PATHS)): Scope =
    if (stated.allow.isNotEmpty()) stated
    else defaultScope(role, qa).let { it.copy(deny = it.deny + stated.deny) }

  /**
   * Whether no path can be writable under both scopes — PROVABLY, not probably.
   *
   * Two steps that run at once and may write the same file race for it, and each one's edit is then checked against
   * nothing but its own boundary. So the proof is conservative, and anything it cannot prove counts as overlap:
   * - a scope without an allow list writes anywhere;
   * - a pattern must start with a literal directory, read by the rules of [matches]: one that opens with a double
   *   star or a wildcard, a bare name like `*.md`, or a directory with a slash only at its end like `docs/` applies at
   *   any depth, and no prefix says where it ends;
   * - two literal prefixes overlap when one is the other or lies inside it, compared with case folded — on APFS
   *   and NTFS `Src/` is `src/`.
   * Deny lists are not used: they only narrow, and ignoring them can only err towards refusing.
   */
  fun provablyDisjoint(a: Scope, b: Scope): Boolean {
    val left = a.allow.map { literalPrefix(it) ?: return false }
    val right = b.allow.map { literalPrefix(it) ?: return false }
    if (left.isEmpty() || right.isEmpty()) return false
    return left.none { l -> right.any { r -> nested(l, r) || nested(r, l) } }
  }

  /** Where [pattern] is anchored, by the rules of [matches]: its leading literal segments, or null when it has none. */
  fun literalPrefix(pattern: String): List<String>? {
    val raw = pattern.trim().replace('\\', '/')
    // `docs/` is a directory at any depth, so it has no anchor; `/docs/` and `web/docs/` are anchored at the root.
    // Read before [normalize], which drops the leading slash that anchors.
    if (raw.endsWith("/") && !directoryAnchored(raw.trimEnd('/'))) return null
    val clean = normalize(raw)
    val anchored = if (clean.endsWith("/")) clean.trimEnd('/') + "/" + ANY else clean
    // No slash inside: the name matches at any depth.
    if (anchored.isEmpty() || '/' !in anchored) return null
    val literal = anchored.split('/').takeWhile { segment -> segment.isNotEmpty() && segment.none { it in WILDCARDS } }
    if (literal.isEmpty() || literal.any { it == "." || it == ".." }) return null
    return literal.map { com.vibe.agent.context.AccessPolicy.foldCase(it) }
  }

  /** A directory pattern, its trailing slash removed, is anchored at the root by a slash of its own; empty is the root. */
  private fun directoryAnchored(directory: String): Boolean = directory.isEmpty() || '/' in directory

  private fun nested(outer: List<String>, inner: List<String>): Boolean =
    outer.size <= inner.size && inner.subList(0, outer.size) == outer

  /** The only wildcards [globToRegex] knows; everything else in a pattern is taken literally. */
  private val WILDCARDS = setOf('*', '?')

  private const val ANY = "**"

  /**
   * A match by the rules of .gitignore: `*` does not cross `/`, `**` does, and a pattern with no slash inside matches a
   * name at any depth (like `node_modules`). A pattern ending in `/` is a directory with everything in it; as in
   * .gitignore, the slash at the end only says «directory», so `docs/` matches `docs` at any depth, while a slash at the
   * start or in the middle anchors it at the root (`/docs/`, `web/docs/`). VibeIDE and our own `.vibe/ignore` read it so.
   */
  fun matches(path: String, pattern: String): Boolean {
    val clean = pattern.trim().ifEmpty { return false }
    if (clean.endsWith("/")) {
      val directory = clean.trimEnd('/')
      return matches(path, if (directoryAnchored(directory)) "$directory/**" else "**/$directory/**")
    }
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
