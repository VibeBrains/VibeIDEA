// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.graph

/**
 * Откуда пришло значение: цепочка объявлений через файлы.
 *
 * Идея перенята у надстройки с площадки плагинов («проследить, откуда пришло значение, хоп за
 * хопом»), реализация своя — идеи не охраняются, чужой код охраняется. Нам она нужна по своей
 * причине: агент, который видит только текущий файл, объясняет «что написано» и ДОДУМЫВАЕТ
 * «откуда», а это самый дорогой вид уверенной ошибки.
 *
 * Разбор ТЕКСТОВЫЙ и не притворяется разбором языка: PSI для TypeScript в открытой платформе нет
 * вовсе, а обещать точность, которой нет, хуже, чем назвать догадку догадкой. Поэтому каждый шаг
 * цепочки помечен тем, чем он найден: объявление в этом же файле, импорт, параметр конструктора.
 *
 * Чистая: текст внутрь, шаги наружу — без файловой системы и без индексов, поэтому правило
 * проверяется тестом, а не открытым проектом.
 */
object SymbolTrace {
  enum class Kind {
    /** `const x = …`, `let x`, `var x`, поле класса, `function x(`, `class x`. */
    DECLARATION,

    /** Пришло из другого файла: `import { x } from '…'`, `use A\B\x;`, `require('…')`. */
    IMPORT,

    /** Внедрено в конструктор — самый частый способ «откуда» в Angular и в PHP. */
    INJECTED,

    /** Параметр функции или метода. */
    PARAMETER,
  }

  data class Step(val kind: Kind, val line: Int, val text: String, val from: String? = null)

  /**
   * Все места в ЭТОМ файле, откуда имя могло появиться, в порядке строк.
   *
   * Не «первое подходящее»: имя бывает объявлено в одном месте и переопределено в другом, и выбор
   * между ними — это решение, которое должен принимать тот, кто видит задачу, а не регулярка.
   */
  fun stepsIn(text: String, name: String): List<Step> {
    if (name.isBlank()) return emptyList()
    val word = Regex.escape(name)
    val patterns = listOf(
      // Объявления значений и функций: TS/JS, PHP, Kotlin, Python — намеренно одной пачкой.
      Kind.DECLARATION to Regex("""^\s*(?:export\s+)?(?:const|let|var|val|function|fun|class|interface|type|def)\s+$word\b"""),
      // Поле класса с типом: `private foo: Bar` и `public readonly foo = …`.
      Kind.DECLARATION to Regex("""^\s*(?:public|private|protected|readonly|static|\s)*$word\s*[:=]"""),
      Kind.IMPORT to Regex("""^\s*import\b.*\b$word\b.*\bfrom\b"""),
      Kind.IMPORT to Regex("""^\s*import\s+$word\b"""),
      Kind.IMPORT to Regex("""^\s*use\s+[\\\w]*\\?$word\s*;"""),
      Kind.IMPORT to Regex("""\b(?:const|let|var)\s+\{?[^=]*\b$word\b[^=]*\}?\s*=\s*require\("""),
      // Внедрение в конструктор: `constructor(private x: Service)` и PHP `__construct(private X $x)`.
      Kind.INJECTED to Regex("""constructor\s*\([^)]*\b$word\b"""),
      Kind.INJECTED to Regex("""__construct\s*\([^)]*\b$word\b"""),
      Kind.PARAMETER to Regex("""^\s*(?:function|fun|def)\s+\w+\s*\([^)]*\b$word\b"""),
    )
    val steps = ArrayList<Step>()
    text.lineSequence().forEachIndexed { index, line ->
      for ((kind, pattern) in patterns) {
        if (pattern.containsMatchIn(line)) {
          steps += Step(kind, index + 1, line.trim(), if (kind == Kind.IMPORT) moduleOf(line) else null)
          break
        }
      }
    }
    return steps
  }

  /**
   * Откуда именно импортировано — путь модуля из строки импорта.
   *
   * Без него шаг «пришло импортом» отвечает «не отсюда» и не отвечает «а откуда»: цепочка
   * обрывается там, где становится интересной.
   */
  fun moduleOf(line: String): String? {
    QUOTED.find(line)?.let { return it.groupValues[1] }
    // PHP: `use App\Service\Mailer;` — модуль это само полное имя без последнего сегмента.
    USE_STATEMENT.find(line)?.let { match ->
      val full = match.groupValues[1]
      return full.substringBeforeLast('\\', "").takeIf { it.isNotEmpty() } ?: full
    }
    return null
  }

  private val QUOTED = Regex("""['"]([^'"]+)['"]""")
  private val USE_STATEMENT = Regex("""^\s*use\s+([\\\w]+)\s*;""")
}
