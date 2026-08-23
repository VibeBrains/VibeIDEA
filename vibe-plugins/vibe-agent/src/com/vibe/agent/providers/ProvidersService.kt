// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import java.nio.file.Files
import java.nio.file.Path

data class ResolvedProvider(
  val entry: ProviderEntry,
  val protocol: String,
  val baseUrl: String,
  val apiKey: String?,
  val isLocal: Boolean,
)

/**
 * Loads and merges `~/.vibe/providers.json` + `<project>/.vibe/providers.json`.
 * The two files are parsed independently — a broken workspace file never
 * disables global providers (VibeIDE contract). Locality is determined by the
 * endpoint host, not by a hardcoded vendor list.
 */
object ProvidersService {
  fun load(projectBase: String?, onWarning: (String) -> Unit): List<ProviderEntry> {
    val global = loadFile(Path.of(System.getProperty("user.home"), ".vibe", "providers.json"), onWarning)
    val workspace = projectBase?.let { loadFile(Path.of(it, ".vibe", "providers.json"), onWarning) } ?: emptyList()
    val merged = ProvidersFile.merge(global, workspace)
    return ProvidersFile.resolveExtends(merged, onWarning).filter { it.active }
  }

  private fun loadFile(path: Path, onWarning: (String) -> Unit): List<ProviderEntry> {
    if (!Files.isRegularFile(path)) return emptyList()
    return try {
      ProvidersFile.resolveExtends(ProvidersFile.parse(Files.readString(path), onWarning), onWarning)
    }
    catch (e: Exception) {
      onWarning("$path не разобран: ${e.message} — провайдеры из этого файла пропущены")
      emptyList()
    }
  }

  fun resolve(entry: ProviderEntry, projectBase: String?, onWarning: (String) -> Unit): ResolvedProvider? {
    val base = entry.baseURL
    if (base.isNullOrBlank()) {
      onWarning("провайдер '${entry.id}': нет baseURL — пропущен")
      return null
    }
    val protocol = when (entry.protocol) {
      "anthropic" -> "anthropic"
      "gemini" -> {
        onWarning("провайдер '${entry.id}': протокол gemini пока не реализован — использую openai-совместимый")
        "openai"
      }
      else -> "openai"
    }
    val host = runCatching { java.net.URI(base).host }.getOrNull() ?: ""
    val key = ApiKeyResolver.resolve(entry, projectBase)
    return ResolvedProvider(
      entry = entry,
      protocol = protocol,
      baseUrl = base,
      apiKey = key,
      isLocal = host == "localhost" || host == "127.0.0.1" || host == "::1",
    )
  }
}
