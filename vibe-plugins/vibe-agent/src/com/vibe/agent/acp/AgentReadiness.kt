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
 * **Не тем платят.** Адаптер Claude Code выбирает оплату по переменным окружения и только потом
 * подхватывает вход Claude Code. То есть переменная в окружении МОЛЧА побеждает подписку: человек
 * работает, думая, что тратит лимиты плана, а платит за токены. Это документированное поведение
 * (code.claude.com/docs/en/authentication, порядок сверен 11.09.2026), а не наша догадка, и заметить
 * его самому нельзя ничем: разница видна только в счёте в конце месяца.
 *
 * Чистая целиком: наличие файла на PATH и содержимое окружения приходят функциями. Иначе правило
 * нельзя проверить на машине, где ни того, ни другого нет.
 */
object AgentReadiness {
  enum class Kind {
    /** Команды записи нет на машине — запускать нечем. */
    RUNTIME_MISSING,

    /** Переменная в окружении перебьёт подписку: платить будете не с неё; [Notice.detail] — какая. */
    SUBSCRIPTION_OVERRIDDEN,
  }

  data class Notice(val agent: String, val kind: Kind, val detail: String)

  /** The variable that decides it most often: the key people export for a direct chat with the model. */
  const val ANTHROPIC_KEY = "ANTHROPIC_API_KEY"

  /**
   * What takes a Claude Code turn off the subscription, in the order Claude Code consults it: a cloud
   * provider switch, then a bearer token, then an API key. After them come `apiKeyHelper` (Claude
   * Code's own settings, invisible from here) and `CLAUDE_CODE_OAUTH_TOKEN` — the subscription itself,
   * which is not an override.
   */
  val BILLING_OVERRIDES: List<String> = listOf(
    "CLAUDE_CODE_USE_BEDROCK", "CLAUDE_CODE_USE_VERTEX", "CLAUDE_CODE_USE_FOUNDRY",
    "ANTHROPIC_AUTH_TOKEN", ANTHROPIC_KEY,
  )

  /** Как узнать адаптер Claude Code среди чужих записей — по имени пакета, а не по имени записи. */
  private const val CLAUDE_ADAPTER = "claude-agent-acp"

  /** A cloud switch is a flag: these values leave it off. */
  private val SWITCH_OFF = setOf("0", "false", "no", "off")
  private const val CLOUD_SWITCH_PREFIX = "CLAUDE_CODE_USE_"

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
      if (usesClaude(agent)) {
        billedBy(agent, env)?.let { add(Notice(agent.name, Kind.SUBSCRIPTION_OVERRIDDEN, it)) }
      }
    }
  }

  /** Запись зовёт адаптер Claude Code — значит вопрос «чем платим» к ней относится. */
  fun usesClaude(agent: AgentServerConfig): Boolean =
    agent.args.any { it.contains(CLAUDE_ADAPTER) } || agent.command.contains(CLAUDE_ADAPTER)

  /**
   * The variable that will decide the billing, or null — the subscription pays.
   *
   * The entry's own `env` wins over the IDE's environment, as in the launched process, and an empty
   * value there is a deliberate removal ([AgentEnvironment]): the advice in our own seed must not
   * raise a warning.
   */
  fun billedBy(agent: AgentServerConfig, env: (String) -> String?): String? =
    BILLING_OVERRIDES.firstOrNull { name ->
      val value = agent.env[name] ?: env(name)
      when {
        value.isNullOrBlank() -> false
        name.startsWith(CLOUD_SWITCH_PREFIX) -> value.trim().lowercase() !in SWITCH_OFF
        else -> true
      }
    }
}
