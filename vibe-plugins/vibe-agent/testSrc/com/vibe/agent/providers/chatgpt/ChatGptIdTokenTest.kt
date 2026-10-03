// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.providers.chatgpt

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The ID token is believed only with the vendor's signature, its issuer, this client, an expiry ahead and this nonce */
class ChatGptIdTokenTest {
  private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
  private val url64 = Base64.getUrlEncoder().withoutPadding()
  private val now = 1_800_000_000L

  private val jwks = JsonObject(mapOf("keys" to JsonArray(listOf(JsonObject(mapOf(
    "kty" to JsonPrimitive("RSA"), "kid" to JsonPrimitive("k1"),
    "n" to JsonPrimitive(url64.encodeToString((keys.public as RSAPublicKey).modulus.toByteArray().dropWhile { it == 0.toByte() }.toByteArray())),
    "e" to JsonPrimitive(url64.encodeToString((keys.public as RSAPublicKey).publicExponent.toByteArray())),
  ))))))

  private fun token(payload: String, header: String = """{"alg":"RS256","kid":"k1"}"""): String {
    val signed = url64.encodeToString(header.toByteArray()) + "." + url64.encodeToString(payload.toByteArray())
    val signature = Signature.getInstance("SHA256withRSA").run {
      initSign(keys.private)
      update(signed.toByteArray())
      sign()
    }
    return signed + "." + url64.encodeToString(signature)
  }

  private fun payload(aud: String = "oaiapp_1", exp: Long = now + 600, nonce: String = "nn", iss: String = "https://auth.openai.com") =
    """{"iss":"$iss","aud":["$aud"],"sub":"user-1","email":"me@example.com","exp":$exp,"nonce":"$nonce"}"""

  @Test
  fun `a token signed by the vendor for this client and attempt is believed`() {
    val claims = ChatGptIdToken.verify(token(payload()), jwks, "oaiapp_1", "nn", now)
    assertEquals("user-1", claims?.sub)
    assertEquals("me@example.com", claims?.email)
  }

  @Test
  fun `anything that does not hold makes it a stranger's`() {
    assertNull(ChatGptIdToken.verify(token(payload(aud = "oaiapp_2")), jwks, "oaiapp_1", "nn", now), "чужой клиент")
    assertNull(ChatGptIdToken.verify(token(payload(nonce = "other")), jwks, "oaiapp_1", "nn", now), "чужая попытка")
    assertNull(ChatGptIdToken.verify(token(payload(exp = now - 3600)), jwks, "oaiapp_1", "nn", now), "просрочен")
    assertNull(ChatGptIdToken.verify(token(payload(iss = "https://evil.example")), jwks, "oaiapp_1", "nn", now), "чужой выпускающий")
    assertNull(ChatGptIdToken.verify(token(payload(), header = """{"alg":"none"}"""), jwks, "oaiapp_1", "nn", now), "без подписи")
    val parts = token(payload()).split('.')
    val forged = parts[0] + "." + url64.encodeToString(payload(aud = "oaiapp_1").replace("user-1", "user-2").toByteArray()) + "." + parts[2]
    assertNull(ChatGptIdToken.verify(forged, jwks, "oaiapp_1", "nn", now), "подменённое содержимое")
  }
}
