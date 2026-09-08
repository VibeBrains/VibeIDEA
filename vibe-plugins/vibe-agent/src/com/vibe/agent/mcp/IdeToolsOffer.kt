// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.mcp

/**
 * Показать агенту, работающему В IDE, инструменты самой IDE.
 
 * Разрыв, который это закрывает: MCP-сервер VibeIDEA писался для ЧУЖОГО агента — Claude Desktop,
 * внешний Claude Code, любой клиент снаружи. А агент, которого IDE запускает сама, получал в
 * `session/new` пустой список серверов, то есть работал в проекте, не видя ни графа импортов, ни
 * поиска по корпусу, ни журнала решений — всего того, ради чего эти инструменты писались.
 * Пользователь мог свести их руками, но для этого надо было знать, что они есть.
 *
 * Почему предложение, а не обязанность: MCP-сервер живёт на том же входе, что HTTP API, а тот
 * выключен по умолчанию осознанно — это дверь, по которой на машине выполняется работа. Включать
 * её за пользователя ради удобства агента значит менять его решение о безопасности; поэтому
 * сервер предлагается, только когда он уже поднят.
 *
 * Транспорт — http, и его надо СПРАШИВАТЬ: по спеке ACP клиент обязан проверить
 * `agentCapabilities.mcpCapabilities.http` до того, как пришлёт такой сервер, а агент, не знающий
 * этого варианта, на нём просто падает разбором параметров.
 *
 * Чистая: адрес, токен и возможность агента приходят снаружи — правило проверяется без сети.
 */
object IdeToolsOffer {
  /** Имя сервера в глазах агента; по нему он называет инструменты в своих сообщениях. */
  const val NAME = "vibeidea"

  /**
   * Запись MCP-сервера для `session/new`, или null — если предлагать нечего или некому.
   *
   * Формат — ровно как в схеме v1: дискриминатор `type`, а `headers` МАССИВ пар `{name, value}`,
   * а не объект (адаптеры разворачивают его сами; объект они не поймут).
   */
  fun httpServer(
    running: Boolean,
    port: Int,
    token: String?,
    agentSupportsHttp: Boolean,
  ): Map<String, Any>? {
    if (!running || !agentSupportsHttp) return null
    if (port <= 0) return null
    // Без токена сервер ответит отказом на первый же запрос: предложить его — значит подарить
    // агенту инструмент, который всегда падает, и научить его больше не пробовать.
    val bearer = token?.takeIf { it.isNotBlank() } ?: return null
    return mapOf(
      "type" to "http",
      "name" to NAME,
      "url" to "http://127.0.0.1:$port/mcp",
      "headers" to listOf(mapOf("name" to "Authorization", "value" to "Bearer $bearer")),
    )
  }

  /** Почему предложить не вышло — одной причиной, чтобы человеку было что чинить. */
  enum class Reason { OFFERED, API_OFF, AGENT_CANNOT_HTTP, NO_TOKEN }

  fun reason(running: Boolean, token: String?, agentSupportsHttp: Boolean): Reason = when {
    !running -> Reason.API_OFF
    !agentSupportsHttp -> Reason.AGENT_CANNOT_HTTP
    token.isNullOrBlank() -> Reason.NO_TOKEN
    else -> Reason.OFFERED
  }
}
