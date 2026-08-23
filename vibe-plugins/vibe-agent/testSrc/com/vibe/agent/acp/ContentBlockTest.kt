// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.acp

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ContentBlockTest {
  @Test
  fun textBlock() {
    val json = ContentBlock.Text("hello").toJson()
    assertEquals(setOf("type", "text"), json.keys)
    assertEquals("text", json.getValue("type").jsonPrimitive.content)
    assertEquals("hello", json.getValue("text").jsonPrimitive.content)
  }

  @Test
  fun imageBlock() {
    val json = ContentBlock.Image(data = "AAAA", mimeType = "image/png").toJson()
    assertEquals(setOf("type", "data", "mimeType"), json.keys)
    assertEquals("image", json.getValue("type").jsonPrimitive.content)
    assertEquals("AAAA", json.getValue("data").jsonPrimitive.content)
    assertEquals("image/png", json.getValue("mimeType").jsonPrimitive.content)
  }

  @Test
  fun resourceLinkBlockWithMimeType() {
    val json = ContentBlock.ResourceLink(uri = "file:///a.kt", name = "a.kt", mimeType = "text/x-kotlin").toJson()
    assertEquals(setOf("type", "uri", "name", "mimeType"), json.keys)
    assertEquals("resource_link", json.getValue("type").jsonPrimitive.content)
    assertEquals("file:///a.kt", json.getValue("uri").jsonPrimitive.content)
    assertEquals("a.kt", json.getValue("name").jsonPrimitive.content)
    assertEquals("text/x-kotlin", json.getValue("mimeType").jsonPrimitive.content)
  }

  @Test
  fun resourceLinkBlockOmitsNullMimeType() {
    val json = ContentBlock.ResourceLink(uri = "file:///a.kt", name = "a.kt").toJson()
    assertEquals(setOf("type", "uri", "name"), json.keys)
    assertFalse("mimeType" in json)
  }

  @Test
  fun resourceBlockWithMimeType() {
    val json = ContentBlock.Resource(uri = "file:///a.kt", text = "fun a() {}", mimeType = "text/x-kotlin").toJson()
    assertEquals(setOf("type", "resource"), json.keys)
    assertEquals("resource", json.getValue("type").jsonPrimitive.content)
    val resource = json.getValue("resource").jsonObject
    assertEquals(setOf("uri", "text", "mimeType"), resource.keys)
    assertEquals("file:///a.kt", resource.getValue("uri").jsonPrimitive.content)
    assertEquals("fun a() {}", resource.getValue("text").jsonPrimitive.content)
    assertEquals("text/x-kotlin", resource.getValue("mimeType").jsonPrimitive.content)
  }

  @Test
  fun resourceBlockOmitsNullMimeType() {
    val resource = ContentBlock.Resource(uri = "file:///a.kt", text = "x").toJson().getValue("resource").jsonObject
    assertEquals(setOf("uri", "text"), resource.keys)
    assertFalse("mimeType" in resource)
  }
}
