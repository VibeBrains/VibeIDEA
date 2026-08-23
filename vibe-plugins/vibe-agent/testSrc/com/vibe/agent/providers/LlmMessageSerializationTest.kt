// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class LlmMessageSerializationTest {
  private val png = ImagePart("image/png", "AAAA")
  private val jpeg = ImagePart("image/jpeg", "BBBB")
  private val plain = ChatMessage("user", "hi")
  private val withImages = ChatMessage("user", "look", listOf(png, jpeg))
  private val imagesOnly = ChatMessage("user", "  ", listOf(png))

  private fun json(text: String): JsonObject = Json.parseToJsonElement(text) as JsonObject

  @Test
  fun `openai without images is a plain string content`() {
    assertEquals(json("""{"role":"user","content":"hi"}"""), LlmMessages.openAi(plain))
  }

  @Test
  fun `openai with images is text part followed by data-url image parts`() {
    val expected = json("""
      {"role":"user","content":[
        {"type":"text","text":"look"},
        {"type":"image_url","image_url":{"url":"data:image/png;base64,AAAA"}},
        {"type":"image_url","image_url":{"url":"data:image/jpeg;base64,BBBB"}}
      ]}""")
    assertEquals(expected, LlmMessages.openAi(withImages))
  }

  @Test
  fun `anthropic without images is a plain string content`() {
    assertEquals(json("""{"role":"user","content":"hi"}"""), LlmMessages.anthropic(plain))
  }

  @Test
  fun `anthropic with images puts image blocks before the text block`() {
    val expected = json("""
      {"role":"user","content":[
        {"type":"image","source":{"type":"base64","media_type":"image/png","data":"AAAA"}},
        {"type":"image","source":{"type":"base64","media_type":"image/jpeg","data":"BBBB"}},
        {"type":"text","text":"look"}
      ]}""")
    assertEquals(expected, LlmMessages.anthropic(withImages))
  }

  @Test
  fun `gemini without images is a single text part`() {
    assertEquals(json("""{"role":"user","parts":[{"text":"hi"}]}"""), LlmMessages.gemini(plain))
  }

  @Test
  fun `gemini with images appends inlineData parts`() {
    val expected = json("""
      {"role":"user","parts":[
        {"text":"look"},
        {"inlineData":{"mimeType":"image/png","data":"AAAA"}},
        {"inlineData":{"mimeType":"image/jpeg","data":"BBBB"}}
      ]}""")
    assertEquals(expected, LlmMessages.gemini(withImages))
  }

  @Test
  fun `gemini maps assistant role to model`() {
    assertEquals(json("""{"role":"model","parts":[{"text":"ok"}]}"""), LlmMessages.gemini(ChatMessage("assistant", "ok")))
  }

  @Test
  fun `blank text next to images is dropped in every protocol`() {
    assertEquals(
      json("""{"role":"user","content":[{"type":"image_url","image_url":{"url":"data:image/png;base64,AAAA"}}]}"""),
      LlmMessages.openAi(imagesOnly),
    )
    assertEquals(
      json("""{"role":"user","content":[{"type":"image","source":{"type":"base64","media_type":"image/png","data":"AAAA"}}]}"""),
      LlmMessages.anthropic(imagesOnly),
    )
    assertEquals(
      json("""{"role":"user","parts":[{"inlineData":{"mimeType":"image/png","data":"AAAA"}}]}"""),
      LlmMessages.gemini(imagesOnly),
    )
  }
}
