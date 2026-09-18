// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.mcp

/**
 * Команды, которые спрашиваются ВСЕГДА — даже на автопилоте.
 *
 * Дыра, которую это закрывает: автопилот включён по умолчанию и разрешает выполнять что угодно без
 * вопроса. Для `npm test` это ровно то, что нужно, а для `rm -rf ~` — способ потерять рабочий день
 * молча. Между «спрашивать про каждую команду» (и приучить человека жать «да» не читая) и «не
 * спрашивать никогда» есть третье: спрашивать про ТО, ЧТО НЕЛЬЗЯ ОТМЕНИТЬ.
 *
 * Список не про «опасные слова», а про необратимость. Запуск тестов, сборка, `git status` — обычная
 * работа, и вопрос о них обесценивает вопрос вообще. Удаление, форматирование, запись в устройство,
 * выключение машины, переписывание чужой истории и «скачай и выполни» — вещи, после которых
 * «отклонить» не существует.
 *
 * Чистый разбор строки, поэтому правило проверяется тестом, а не запуском команды. Он намеренно
 * ГРУБЫЙ: пропустить опасное хуже, чем лишний раз спросить про безобидное.
 */
object ShellRisk {
  /** Почему команда требует вопроса; null — обычная работа. */
  fun dangerOf(command: String): String? {
    val text = command.lowercase()
    for ((pattern, reason) in RULES) {
      if (pattern.containsMatchIn(text)) return reason
    }
    return null
  }

  fun isDangerous(command: String): Boolean = dangerOf(command) != null

  /**
   * Пары «как узнать → чем это плохо».
   *
   * Причина показывается человеку в диалоге: «команда удаляет файлы» отвечает на вопрос «почему
   * меня вообще спросили» быстрее, чем перечитывание самой команды.
   */
  private val RULES: List<Pair<Regex, String>> = listOf(
    Regex("""\brm\s+(-[a-z]*\s+)*-?[a-z]*[rf]""") to "removal",
    Regex("""\brmdir\b|\bunlink\b""") to "removal",
    Regex("""\bmkfs\b|\bdiskutil\s+(erase|partition)|\bformat\b""") to "disk",
    Regex("""\bdd\b[^|]*\bof=""") to "disk",
    Regex("""\bshutdown\b|\breboot\b|\bhalt\b""") to "power",
    // Классическая вилка-бомба: `:(){ :|:& };:` — её узнают по форме, а не по имени.
    Regex(""":\s*\(\s*\)\s*\{""") to "fork-bomb",
    Regex("""\bsudo\b|\bsu\s+-""") to "root",
    Regex("""\bchmod\s+(-[a-z]+\s+)*777\b|\bchown\s+-r\b""") to "rights",
    // «Скачай и выполни» — чужой код, который никто не читал.
    Regex("""\b(curl|wget)\b[^|;&]*[|]\s*(ba)?sh""") to "remote-script",
    Regex("""\bgit\s+push\b[^|;&]*(--force|-f)\b""") to "history",
    Regex("""\bgit\s+(reset\s+--hard|clean\s+-[a-z]*f)""") to "history",
    Regex("""\bnpm\s+publish\b|\bcargo\s+publish\b|\btwine\s+upload\b""") to "publish",
    Regex("""\bdocker\s+(system\s+prune|volume\s+rm)\b""") to "removal",
    Regex("""\bkill\s+-9\s+-1\b|\bpkill\s+-9\b""") to "processes",
    // Перенаправление в файл устройства и запись поверх дисков.
    Regex("""\s>\s*/dev/(sd|disk|nvme)""") to "disk",
  )
}
