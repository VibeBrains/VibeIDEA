// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.acp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Каталог ACP-агентов — общий, а не наш.
 *
 * Наш список внешних агентов живёт в наборе сидов и стареет вместе с нами: агент, появившийся
 * вчера, попадёт к пользователю только со следующим выпуском IDE. У протокола с 2026 года есть
 * общий реестр (`cdn.agentclientprotocol.com/registry/v1/latest/registry.json`, Apache-2.0,
 * обновляется кроном ежечасно), который читают встроенными средствами и другие клиенты протокола.
 *
 * **Мы читаем его, но ничего не устанавливаем.** Запуск чужого процесса на машине человека — это
 * решение человека: каталог показывает, что появилось, и предлагает дописать запись; команду он
 * увидит до того, как она выполнится.
 *
 * Чистая: JSON каталога внутрь, записи наружу. Сеть — снаружи.
 */
object AgentRegistry {
  const val URL = "https://cdn.agentclientprotocol.com/registry/v1/latest/registry.json"

  /** Как агент поставляется: по этим ключам собирается команда запуска. */
  enum class Delivery { NPX, UVX, BINARY, UNKNOWN }

  data class Entry(
    val id: String,
    val name: String,
    val version: String?,
    val description: String?,
    val license: String?,
    val website: String?,
    val delivery: Delivery,
    /** Пакет для npx/uvx; у binary — null, такой агент ставится руками. */
    val pkg: String?,
    val args: List<String> = emptyList(),
    /** Что реестр говорит о готовом бинаре под ЭТУ машину; null — сборки под неё нет. */
    val binary: Binary? = null,
    /** Where the source lives — the one address worth checking before running someone else's program. */
    val repository: String? = null,
  )

  /**
   * Готовая сборка под конкретную ОС и архитектуру: где взять, чем проверить, чем запускать.
   *
   * Мы по-прежнему ничего не скачиваем и не распаковываем — запуск чужого бинаря остаётся решением
   * человека. Но раньше binary-агент не давал ему ВООБЩЕ НИЧЕГО: кнопка «добавить» молчала, а в
   * каталоге на 09.09.2026 таких записей 17 из 40 — Goose, Cursor, opencode, Kimi, Junie и другие.
   * Реестр при этом несёт и адрес архива, и sha256, и команду запуска: пересказать это человеку
   * дешевле, чем заставить его искать то же самое руками, и честнее, чем молчать.
   *
   * [sha256] is optional in the registry schema, and nine binary agents ship without it (11.09.2026)
   * — that is said as «nothing to check it with», not hidden behind a dash. [args] and [env] are part
   * of how the build is launched and were not read until 11.09.2026.
   */
  data class Binary(
    val target: String,
    val archive: String,
    val sha256: String?,
    val cmd: String?,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
  )

  private val json = Json { ignoreUnknownKeys = true; isLenient = true }

  /** The runners whose first operand names a package: the key an entry is recognised by. */
  private val RUNNERS = setOf("npx", "uvx", "bunx", "pnpx")

  /**
   * @param target как реестр называет машину, для которой читаем каталог ([targetOf]).
   *        Аргумент, а не системное свойство: правило про чужую платформу иначе не проверить.
   */
  fun parse(text: String, target: String? = targetOf(System.getProperty("os.name").orEmpty(),
                                                     System.getProperty("os.arch").orEmpty())): List<Entry> {
    val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return emptyList()
    val agents = root["agents"] as? JsonArray ?: return emptyList()
    return agents.mapNotNull { element ->
      val agent = element as? JsonObject ?: return@mapNotNull null
      val id = agent.string("id") ?: return@mapNotNull null
      val distribution = agent["distribution"] as? JsonObject
      val (delivery, pkg, args) = deliveryOf(distribution)
      Entry(
        id = id,
        name = agent.string("name") ?: id,
        version = agent.string("version"),
        description = agent.string("description"),
        license = agent.string("license"),
        website = agent.string("website"),
        delivery = delivery,
        pkg = pkg,
        args = args,
        binary = binaryOf(distribution, target),
        repository = agent.string("repository"),
      )
    }
  }

  private fun deliveryOf(distribution: JsonObject?): Triple<Delivery, String?, List<String>> {
    if (distribution == null) return Triple(Delivery.UNKNOWN, null, emptyList())
    for ((key, kind) in listOf("npx" to Delivery.NPX, "uvx" to Delivery.UVX)) {
      val node = distribution[key] as? JsonObject ?: continue
      return Triple(kind, node.string("package"), node.strings("args"))
    }
    if (distribution["binary"] != null) return Triple(Delivery.BINARY, null, emptyList())
    return Triple(Delivery.UNKNOWN, null, emptyList())
  }

  /**
   * Как реестр называет ЭТУ машину: `darwin-aarch64`, `linux-x86_64`, `windows-x86_64` и т.д.
   *
   * Чистая: имена системы и архитектуры — аргументы, иначе правило нельзя проверить на чужой
   * машине, а именно на чужой оно и понадобится.
   */
  fun targetOf(osName: String, osArch: String): String? {
    val os = when {
      osName.startsWith("mac", ignoreCase = true) || osName.contains("darwin", ignoreCase = true) -> "darwin"
      osName.startsWith("win", ignoreCase = true) -> "windows"
      osName.contains("linux", ignoreCase = true) -> "linux"
      else -> return null
    }
    val arch = when (osArch.lowercase()) {
      "aarch64", "arm64" -> "aarch64"
      "x86_64", "amd64" -> "x86_64"
      else -> return null
    }
    return "$os-$arch"
  }

  /** Сборка под [target], если реестр её объявил. */
  private fun binaryOf(distribution: JsonObject?, target: String?): Binary? {
    if (target == null) return null
    val node = (distribution?.get("binary") as? JsonObject)?.get(target) as? JsonObject ?: return null
    val archive = node.string("archive") ?: return null
    return Binary(
      target = target,
      archive = archive,
      sha256 = node.string("sha256"),
      cmd = node.string("cmd"),
      args = node.strings("args"),
      env = (node["env"] as? JsonObject).orEmpty().mapNotNull { (key, value) ->
        (value as? JsonPrimitive)?.contentOrNull?.let { key to it }
      }.toMap(),
    )
  }

  /**
   * Запись `.vibe/agents.json` для этого агента, или null — если каталог не описывает запуска.
   *
   * Двоичная поставка сюда не превращается намеренно: у неё нет команды, которую мы имели бы право
   * подставить, а придуманный путь к чужому бинарю — это запуск неизвестно чего.
   */
  fun toAgentEntry(entry: Entry): AgentServerConfig? = when (entry.delivery) {
    Delivery.NPX -> entry.pkg?.let {
      AgentServerConfig(entry.name, "npx", listOf("-y", it) + entry.args, emptyMap())
    }
    Delivery.UVX -> entry.pkg?.let { AgentServerConfig(entry.name, "uvx", listOf(it) + entry.args, emptyMap()) }
    Delivery.BINARY, Delivery.UNKNOWN -> null
  }

  /**
   * Whether the catalog entry is configured already — by the package it launches, version aside,
   * and by name only where there is no package.
   *
   * By name alone the catalog kept offering the Claude adapter again: the registry calls it «Claude
   * Agent», our seed «Claude Code», and the package is the same (decision №76).
   */
  fun isConfigured(entry: Entry, configured: List<AgentServerConfig>): Boolean {
    val pkg = entry.pkg?.let { withoutVersion(it) }
    if (pkg != null && configured.any { packageOf(it) == pkg }) return true
    val name = entry.name.trim().lowercase()
    return configured.any { it.name.trim().lowercase() == name }
  }

  /** Чего ещё нет среди настроенных агентов. */
  fun newAgents(catalog: List<Entry>, configured: List<AgentServerConfig>): List<Entry> =
    catalog.filterNot { isConfigured(it, configured) }

  /** The package an npx/uvx-style entry launches, without its version; null for anything else. */
  fun packageOf(config: AgentServerConfig): String? {
    val runner = config.command.substringAfterLast('/').substringAfterLast('\\').lowercase()
      .removeSuffix(".cmd").removeSuffix(".exe")
    if (runner !in RUNNERS) return null
    return config.args.firstOrNull { !it.startsWith("-") }?.let { withoutVersion(it) }
  }

  /** `@scope/name@1.2.3` → `@scope/name`, `name==1.2` → `name`, `name@1.2` → `name`; case-folded. */
  fun withoutVersion(spec: String): String {
    val python = spec.substringBefore("==")
    val at = python.lastIndexOf('@')
    return (if (at > 0) python.substring(0, at) else python).trim().lowercase()
  }

  private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

  private fun JsonObject.strings(key: String): List<String> =
    (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
}
