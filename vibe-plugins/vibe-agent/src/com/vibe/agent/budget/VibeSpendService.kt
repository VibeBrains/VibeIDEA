// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.budget

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path

/**
 * Application-level spend: it follows the person, not the repository.
 *
 * In the IDE system path rather than in `.vibe`: what one spent is nobody else's business, it must
 * not appear in a diff, and a colleague cloning the branch has no use for it. Written on a debounce
 * because an update arrives with every streamed chunk and the file is a convenience, not a journal.
 */
@Service(Service.Level.APP)
class VibeSpendService {
  private val log = logger<VibeSpendService>()
  private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
  private val store = SpendLedger.Store()

  @Volatile private var loaded = false
  @Volatile private var dirtySinceMs = 0L

  @Synchronized
  fun record(role: String?, target: String, tokens: Long, costAmount: Double?, costCurrency: String?) {
    if (tokens <= 0 && costAmount == null) return
    ensureLoaded()
    store.add(SpendLedger.Entry(System.currentTimeMillis(), role, target, tokens, costAmount, costCurrency))
    dirtySinceMs = System.currentTimeMillis()
    save()
  }

  @Synchronized
  fun entries(windowMs: Long = SpendLedger.DAY_MS): List<SpendLedger.Entry> {
    ensureLoaded()
    return SpendLedger.within(store.snapshot(), System.currentTimeMillis(), windowMs)
  }

  /** Tokens this role has spent inside the window — the number a budget is checked against. */
  fun spentByRole(role: String?, windowMs: Long = SpendLedger.DAY_MS): Long =
    SpendLedger.tokensOf(entries(windowMs), role)

  @Synchronized
  fun clear() {
    store.clear()
    save()
  }

  private fun ensureLoaded() {
    if (loaded) return
    loaded = true
    val path = file()
    if (!Files.exists(path)) return
    runCatching {
      val root = json.parseToJsonElement(Files.readString(path)).jsonObject
      (root["entries"] as? JsonArray)?.forEach { element ->
        val e = element as? JsonObject ?: return@forEach
        store.add(SpendLedger.Entry(
          atMs = e["at"]?.jsonPrimitive?.longOrNull ?: return@forEach,
          role = e["role"]?.jsonPrimitive?.contentOrNull,
          target = e["target"]?.jsonPrimitive?.contentOrNull ?: "",
          tokens = e["tokens"]?.jsonPrimitive?.longOrNull ?: 0,
          costAmount = e["cost"]?.jsonPrimitive?.doubleOrNull,
          costCurrency = e["currency"]?.jsonPrimitive?.contentOrNull,
        ))
      }
    }.onFailure { log.warn("spend.json could not be read: ${it.message}") }
  }

  private fun save() {
    runCatching {
      val path = file()
      Files.createDirectories(path.parent)
      // Only the last week is kept: older entries answer no question the report asks.
      val kept = SpendLedger.within(store.snapshot(), System.currentTimeMillis(), 7 * SpendLedger.DAY_MS)
      Files.writeString(path, json.encodeToString(JsonObject.serializer(), buildJsonObject {
        put("version", VERSION)
        put("entries", JsonArray(kept.map { entry ->
          buildJsonObject {
            put("at", entry.atMs)
            entry.role?.let { put("role", it) }
            put("target", entry.target)
            put("tokens", entry.tokens)
            entry.costAmount?.let { put("cost", it) }
            entry.costCurrency?.let { put("currency", it) }
          }
        }))
      }))
    }.onFailure { log.warn("spend.json could not be written: ${it.message}") }
  }

  private fun file(): Path = Path.of(PathManager.getSystemPath(), "vibe", "spend.json")

  companion object {
    private const val VERSION = 1

    fun getInstance(): VibeSpendService = com.intellij.openapi.application.ApplicationManager.getApplication().service()
  }
}
