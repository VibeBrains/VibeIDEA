// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import java.nio.file.Files
import java.nio.file.Path

/**
 * API key resolution, VibeIDE priority: apiKeyRef (OS secure storage) →
 * `.vibe/.env` (workspace, then `~/.vibe/.env`; minimal dotenv subset, no
 * interpolation) → OS environment via apiKeyEnv. The key never lives in
 * providers.json and never reaches the transcript.
 */
object ApiKeyResolver {
  fun attributes(ref: String): CredentialAttributes =
    CredentialAttributes(generateServiceName("VibeIDEA Providers", ref))

  fun storedKey(provider: ProviderEntry): String? =
    PasswordSafe.instance.getPassword(attributes(provider.apiKeyRef ?: provider.id))

  fun storeKey(provider: ProviderEntry, key: String?) {
    PasswordSafe.instance.setPassword(attributes(provider.apiKeyRef ?: provider.id), key?.ifBlank { null })
  }

  fun resolve(provider: ProviderEntry, projectBase: String?): String? {
    storedKey(provider)?.let { return it }
    provider.apiKeyEnv?.let { envName ->
      dotEnv(projectBase)[envName]?.let { return it }
      System.getenv(envName)?.let { return it }
    }
    return null
  }

  /** Workspace `.vibe/.env` overrides `~/.vibe/.env` per variable. */
  internal fun dotEnv(projectBase: String?): Map<String, String> {
    val result = HashMap<String, String>()
    readEnvFile(Path.of(System.getProperty("user.home"), ".vibe", ".env"), result)
    projectBase?.let { readEnvFile(Path.of(it, ".vibe", ".env"), result) }
    return result
  }

  private fun readEnvFile(path: Path, into: MutableMap<String, String>) {
    if (!Files.isRegularFile(path)) return
    for (line in Files.readAllLines(path)) {
      val trimmed = line.trim()
      if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
      val eq = trimmed.indexOf('=')
      if (eq <= 0) continue
      into[trimmed.substring(0, eq).trim()] = trimmed.substring(eq + 1).trim().removeSurrounding("\"")
    }
  }
}
