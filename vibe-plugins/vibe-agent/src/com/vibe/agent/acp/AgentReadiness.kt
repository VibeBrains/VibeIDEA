// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.acp

/**
 * Что известно про запись внешнего агента ДО того, как её запустят.
 *
 * Две разные беды, обе тихие, обе выясняются слишком поздно.
 *
 * **Нечем запускать.** Запись говорит `npx`, а на машине нет Node. Процесс не стартует, и человек
 * видит «агент не запустился» — сообщение, по которому невозможно догадаться, что ставить надо не
 * агента, а рантайм. Ровно этот урок был выучен на языковых серверах (решение №34: «доктор обязан
 * называть источник сервера и недостающий рантайм — иначе „встроен“ однажды будет означать „есть
 * файл, который нечем запустить“»), и он один в один переносится на агентов. Сами рантаймы мы, как
 * и там, не поставляем и не качаем — другие клиенты протокола для агентов из общего реестра качают
 * Node и Python сами, мы этот путь не берём, — но назвать недостающее обязаны.
 *
 * **Не тем платят.** Адаптер Claude Code берёт `ANTHROPIC_API_KEY` из окружения, а если его нет —
 * подхватывает вход Claude Code. То есть ключ в окружении МОЛЧА побеждает подписку: человек
 * работает, думая, что тратит лимиты плана, а платит за токены. Это документированное поведение
 * (code.claude.com/docs/en/costs — «unset ANTHROPIC_API_KEY», проверено 09.09.2026), а не наша
 * догадка, и заметить его самому нельзя ничем: разница видна только в счёте в конце месяца.
 *
 * Чистая целиком: наличие файла на PATH и содержимое окружения приходят функциями. Иначе правило
 * нельзя проверить на машине, где ни того, ни другого нет.
 */
object AgentReadiness {
  enum class Kind {
    /** Команды записи нет на машине — запускать нечем. */
    RUNTIME_MISSING,

    /** Ключ в окружении перебьёт подписку: платить будете за токены. */
    SUBSCRIPTION_OVERRIDDEN,
  }

  data class Notice(val agent: String, val kind: Kind, val detail: String)

  /** Переменная, которая решает, чем оплачен ход внешнего агента Anthropic. */
  const val ANTHROPIC_KEY = "ANTHROPIC_API_KEY"

  /** Как узнать адаптер Claude Code среди чужих записей — по имени пакета, а не по имени записи. */
  private const val CLAUDE_ADAPTER = "claude-agent-acp"

  /**
   * @param onPath умеет ли машина запустить такую команду (поиск по PATH и типовым каталогам).
   * @param env значение переменной окружения процесса IDE — того самого, что унаследует агент.
   */
  fun check(
    agents: List<AgentServerConfig>,
    onPath: (String) -> Boolean,
    env: (String) -> String?,
  ): List<Notice> = buildList {
    for (agent in agents) {
      if (!onPath(agent.command)) {
        add(Notice(agent.name, Kind.RUNTIME_MISSING, agent.command))
      }
      if (usesClaudeSubscriptionPath(agent) && keyWins(agent, env)) {
        add(Notice(agent.name, Kind.SUBSCRIPTION_OVERRIDDEN, ANTHROPIC_KEY))
      }
    }
  }

  /** Запись зовёт адаптер Claude Code — значит вопрос «чем платим» к ней относится. */
  private fun usesClaudeSubscriptionPath(agent: AgentServerConfig): Boolean =
    agent.args.any { it.contains(CLAUDE_ADAPTER) } || agent.command.contains(CLAUDE_ADAPTER)

  /**
   * Долетит ли ключ до дочернего процесса.
   *
   * Пустая строка в `env` записи — это ОСОЗНАННОЕ снятие ключа (`"ANTHROPIC_API_KEY": ""`), и
   * именно так мы советуем поступать в сиде. Поэтому пустое значение считается ответом «нет», а не
   * отсутствием ответа: иначе совет из нашего же образца приводил бы к предупреждению.
   */
  private fun keyWins(agent: AgentServerConfig, env: (String) -> String?): Boolean {
    val own = agent.env[ANTHROPIC_KEY]
    if (own != null) return own.isNotBlank()
    return !env(ANTHROPIC_KEY).isNullOrBlank()
  }
}
