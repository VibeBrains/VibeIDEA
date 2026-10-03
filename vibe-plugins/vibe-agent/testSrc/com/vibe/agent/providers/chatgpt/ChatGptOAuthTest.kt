// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.providers.chatgpt

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The sign-in as the vendor describes it for an open client: what the URL carries, what comes back, how refusals end */
class ChatGptOAuthTest {
  private val attempt = ChatGptOAuth.Attempt("st", "nn", "dBjftJeZ4CVP-mJ92K9xC2ezbyoNlZaFDcxNfAnmRDE", "http://127.0.0.1:1455/auth/callback")

  private fun params(url: String): Map<String, String> = URI(url).rawQuery.split('&').associate {
    URLDecoder.decode(it.substringBefore('='), StandardCharsets.UTF_8) to URLDecoder.decode(it.substringAfter('='), StandardCharsets.UTF_8)
  }

  @Test
  fun `the challenge is the verifier's SHA-256 in base64url without padding`() {
    // Computed independently with Python's hashlib
    assertEquals("ZsbHS-HGeviYVwf8N99ONIMfsFokhh-ObKThvbKGvY0", ChatGptOAuth.challenge(attempt.verifier))
  }

  @Test
  fun `the first sign-in registers the client, a later one names it and skips the account choice`() {
    val first = params(ChatGptOAuth.authorizeUrl(attempt, "urn:uuid:h", clientId = null, idTokenHint = "old"))
    assertEquals("dynamic_agent_client", first["client_id"])
    assertEquals("VibeIDEA", first["agent_name_hint"])
    assertNull(first["id_token_hint"], "у регистрации прошлого токена нет")
    assertEquals("urn:uuid:h", first["ext_agent_host_id"])
    assertEquals("S256", first["code_challenge_method"])
    assertEquals("https://api.openai.com/v1", first["resource"])
    assertTrue("chatgpt.tokens.use.direct" in first.getValue("scope").split(' '))
    assertEquals("http://127.0.0.1:1455/auth/callback", first["redirect_uri"])
    val again = params(ChatGptOAuth.authorizeUrl(attempt, "urn:uuid:h", clientId = "oaiapp_1", idTokenHint = "old"))
    assertEquals("oaiapp_1", again["client_id"])
    assertEquals("old", again["id_token_hint"])
    assertFalse("agent_name_hint" in again, "имя приложения — только при регистрации")
  }

  @Test
  fun `the callback is this attempt's only by its state`() {
    assertEquals(ChatGptOAuth.Callback.Code("c", "oaiapp_9"), ChatGptOAuth.callback("code=c&state=st&client_id=oaiapp_9", attempt))
    assertEquals(ChatGptOAuth.Callback.Code("c", null), ChatGptOAuth.callback("state=st&code=c", attempt))
    assertIs<ChatGptOAuth.Callback.Denied>(ChatGptOAuth.callback("error=access_denied&state=st", attempt))
    assertEquals(ChatGptOAuth.Callback.Foreign, ChatGptOAuth.callback("code=c&state=other", attempt))
    assertEquals(ChatGptOAuth.Callback.Foreign, ChatGptOAuth.callback("state=st", attempt))
  }

  @Test
  fun `the exchange repeats the redirect and the resource, the refresh names no scope`() {
    val exchange = ChatGptOAuth.exchangeForm("oaiapp_1", "c", attempt)
    assertTrue("redirect_uri=http%3A%2F%2F127.0.0.1%3A1455%2Fauth%2Fcallback" in exchange)
    assertTrue("code_verifier=" in exchange && "resource=" in exchange)
    val refresh = ChatGptOAuth.refreshForm("oaiapp_1", "r")
    assertTrue("grant_type=refresh_token" in refresh)
    assertFalse("scope" in refresh)
  }

  @Test
  fun `a refresh that cannot recover asks for a sign-in, a passing one does not`() {
    assertEquals(ChatGptOAuth.RefreshFailure.SIGN_IN_AGAIN, ChatGptOAuth.refreshFailure(400, """{"error":"refresh_token_reused"}"""))
    assertEquals(ChatGptOAuth.RefreshFailure.CLIENT, ChatGptOAuth.refreshFailure(401, """{"error":"invalid_client"}"""))
    assertEquals(ChatGptOAuth.RefreshFailure.TRANSIENT, ChatGptOAuth.refreshFailure(429, "slow down"))
    assertEquals(ChatGptOAuth.RefreshFailure.TRANSIENT, ChatGptOAuth.refreshFailure(503, ""))
  }

  @Test
  fun `the plan is spent only with its scope granted`() {
    val tokens = ChatGptOAuth.tokens(kotlinx.serialization.json.Json.parseToJsonElement(
      """{"access_token":"a","refresh_token":"r","id_token":"i","expires_in":3600,"scope":"openid email"}""").let { it as kotlinx.serialization.json.JsonObject })!!
    assertFalse(tokens.mayUsePlan)
  }
}
