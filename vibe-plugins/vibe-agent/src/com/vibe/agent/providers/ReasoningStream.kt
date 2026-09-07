// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Рассуждение модели, приехавшее в потоке, — отдельно от ответа.
 *
 * До 07.09.2026 наш разбор потока читал только текст ответа: у Anthropic-протокола — `delta.text`,
 * у OpenAI — `delta.content`. Рассуждающая модель на этих проводах сначала думает и лишь потом
 * отвечает, поэтому в чате она **молчала ровно столько, сколько думала**, и это выглядело как
 * зависание — тем более обидно, что панель уже умеет показывать мысли сворачиваемым блоком (её
 * пишет ACP-агент через `agent_thought_chunk`).
 *
 * Поле называется у всех по-разному, и это не догадка: у Anthropic это `thinking_delta`
 * (`delta.thinking`), у OpenAI-совместимых китайских эндпоинтов — `reasoning_content` в дельте
 * (DeepSeek, GLM), у некоторых шлюзов — просто `reasoning`. Потеря этого поля при переводе между
 * протоколами — известный дефект чужих прокси
 * ([litellm#29518](https://github.com/BerriAI/litellm/issues/29518)): рассуждение молча исчезает,
 * и клиент не показывает ничего.
 *
 * Чистая: событие потока внутрь, кусок рассуждения наружу.
 */
object ReasoningStream {
  /** Написания, под которыми ездит рассуждение в дельте OpenAI-совместимого потока. */
  private val OPENAI_FIELDS = listOf("reasoning_content", "reasoning")

  /** Кусок рассуждения из события Anthropic-потока, или null. */
  fun fromAnthropicEvent(event: JsonObject): String? {
    if (event["type"]?.jsonPrimitive?.contentOrNull != "content_block_delta") return null
    val delta = event["delta"]?.jsonObject ?: return null
    // Тип события проверяем, но не требуем: часть совместимых эндпоинтов шлёт `thinking` без
    // собственного `type`, и отказ из-за отсутствующего поля потерял бы всё рассуждение целиком.
    val type = delta["type"]?.jsonPrimitive?.contentOrNull
    if (type != null && type != "thinking_delta" && type != "reasoning_delta") return null
    return delta["thinking"]?.jsonPrimitive?.contentOrNull
      ?: delta["reasoning"]?.jsonPrimitive?.contentOrNull
  }

  /** Кусок рассуждения из чанка OpenAI-совместимого потока, или null. */
  fun fromOpenAiChunk(chunk: JsonObject): String? {
    val delta = chunk["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("delta")?.jsonObject ?: return null
    for (field in OPENAI_FIELDS) {
      delta[field]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }?.let { return it }
    }
    return null
  }

  /** Кусок рассуждения из события потока Gemini: часть с пометкой `thought`. */
  fun fromGeminiEvent(event: JsonObject): String? {
    val parts = event["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
      ?.get("content")?.jsonObject?.get("parts")?.jsonArray ?: return null
    for (element in parts) {
      val part = element.jsonObject
      if (part["thought"]?.jsonPrimitive?.contentOrNull == "true" || part["thought"]?.toString() == "true") {
        part["text"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }?.let { return it }
      }
    }
    return null
  }

  /** Текст ответа Gemini — то же место, но БЕЗ пометки `thought`: иначе мысль уедет в ответ. */
  fun answerFromGeminiEvent(event: JsonObject): String? {
    val parts = event["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
      ?.get("content")?.jsonObject?.get("parts")?.jsonArray ?: return null
    for (element in parts) {
      val part = element.jsonObject
      val isThought = part["thought"]?.jsonPrimitive?.contentOrNull == "true" || part["thought"]?.toString() == "true"
      if (isThought) continue
      part["text"]?.jsonPrimitive?.contentOrNull?.let { return it }
    }
    return null
  }
}
