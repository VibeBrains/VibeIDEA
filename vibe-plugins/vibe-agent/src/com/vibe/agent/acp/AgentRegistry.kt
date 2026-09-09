// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.acp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
  )

  /**
   * Готовая сборка под конкретную ОС и архитектуру: где взять, чем проверить, чем запускать.
   *
   * Мы по-прежнему ничего не скачиваем и не распаковываем — запуск чужого бинаря остаётся решением
   * человека. Но раньше binary-агент не давал ему ВООБЩЕ НИЧЕГО: кнопка «добавить» молчала, а в
   * каталоге на 09.09.2026 таких записей 17 из 40 — Goose, Cursor, opencode, Kimi, Junie и другие.
   * Реестр при этом несёт и адрес архива, и sha256, и команду запуска: пересказать это человеку
   * дешевле, чем заставить его искать то же самое руками, и честнее, чем молчать.
   */
  data class Binary(val target: String, val archive: String, val sha256: String?, val cmd: String?)

  private val json = Json { ignoreUnknownKeys = true; isLenient = true }

  /**
   * @param target как реестр называет машину, для которой читаем каталог ([targetOf]).
   *        Аргумент, а не системное свойство: правило про чужую платформу иначе не проверить.
   */
  fun parse(text: String, target: String? = targetOf(System.getProperty("os.name").orEmpty(),
                                                     System.getProperty("os.arch").orEmpty())): List<Entry> {
    val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return emptyList()
    val agents = root["agents"]?.jsonArray ?: return emptyList()
    return agents.mapNotNull { element ->
      val agent = element as? JsonObject ?: return@mapNotNull null
      val id = agent["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
      val distribution = agent["distribution"]?.jsonObject
      val (delivery, pkg, args) = deliveryOf(distribution)
      Entry(
        id = id,
        name = agent["name"]?.jsonPrimitive?.contentOrNull ?: id,
        version = agent["version"]?.jsonPrimitive?.contentOrNull,
        description = agent["description"]?.jsonPrimitive?.contentOrNull,
        license = agent["license"]?.jsonPrimitive?.contentOrNull,
        website = agent["website"]?.jsonPrimitive?.contentOrNull,
        delivery = delivery,
        pkg = pkg,
        args = args,
        binary = binaryOf(distribution, target),
      )
    }
  }

  private fun deliveryOf(distribution: JsonObject?): Triple<Delivery, String?, List<String>> {
    if (distribution == null) return Triple(Delivery.UNKNOWN, null, emptyList())
    for ((key, kind) in listOf("npx" to Delivery.NPX, "uvx" to Delivery.UVX)) {
      val node = distribution[key]?.jsonObject ?: continue
      val pkg = node["package"]?.jsonPrimitive?.contentOrNull
      val args = node["args"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
      return Triple(kind, pkg, args)
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
    val node = distribution?.get("binary")?.jsonObject?.get(target)?.jsonObject ?: return null
    val archive = node["archive"]?.jsonPrimitive?.contentOrNull ?: return null
    return Binary(
      target = target,
      archive = archive,
      sha256 = node["sha256"]?.jsonPrimitive?.contentOrNull,
      cmd = node["cmd"]?.jsonPrimitive?.contentOrNull,
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

  /** Чего нет в списке проекта: сравнение по имени, потому что id каталога в наш файл не едет. */
  fun newAgents(catalog: List<Entry>, configured: List<AgentServerConfig>): List<Entry> {
    val known = configured.map { it.name.trim().lowercase() }.toSet()
    return catalog.filterNot { it.name.trim().lowercase() in known }
  }
}
