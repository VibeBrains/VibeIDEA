// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers.chatgpt

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Sign in with ChatGPT for an open-source client: the protocol, without the network
 *
 * The person's own ChatGPT Plus or Pro plan pays for the model; no API key is involved. An open client registers itself
 * at the person's first sign-in (`dynamic_agent_client`), and the vendor issues the client id then — there is no
 * application to register beforehand (developers.openai.com/siwc/token-sharing-open-source, checked 01.10.2026)
 *
 * Only the loopback flow exists: a browser, PKCE S256 and a listener on `http://127.0.0.1:<port>/auth/callback`,
 * where only the port may vary; the vendor offers no device flow
 *
 * Pure: URLs, forms and the reading of answers, testable without a browser
 */
object ChatGptOAuth {
  const val ISSUER = "https://auth.openai.com"
  const val AUTHORIZE = "$ISSUER/api/accounts/authorize"
  const val TOKEN = "$ISSUER/api/accounts/oauth/token"
  const val REVOKE = "$ISSUER/api/accounts/oauth/revoke"
  const val JWKS = "$ISSUER/.well-known/jwks.json"

  /** The audience of the access token and the base of inference */
  const val RESOURCE = "https://api.openai.com/v1"

  /** The scope that lets the client spend the plan; a valid ID token alone does not */
  const val PLAN_SCOPE = "chatgpt.tokens.use.direct"
  const val SCOPE = "openid profile email offline_access resource.invoke $PLAN_SCOPE"

  /** The registration entry point of the first sign-in; never kept as the client's id */
  const val DYNAMIC_CLIENT = "dynamic_agent_client"

  /** The application's name at registration: the same in every installation */
  const val AGENT_NAME = "VibeIDEA"

  const val CALLBACK_PATH = "/auth/callback"

  /** The port of the vendor's example; Codex CLI signs in on it too, so a busy one gives way to a free one */
  const val PREFERRED_PORT = 1455

  /** Where the person manages what the plan spends; the vendor offers no endpoint for what is left */
  const val USAGE_URL = "https://chatgpt.com/settings/usage"

  /** One sign-in attempt: fresh state, nonce and PKCE verifier, and the redirect it was started with */
  data class Attempt(val state: String, val nonce: String, val verifier: String, val redirectUri: String)

  fun attempt(port: Int, random: SecureRandom = SecureRandom()): Attempt =
    Attempt(token(random), token(random), token(random, VERIFIER_BYTES), "http://127.0.0.1:$port$CALLBACK_PATH")

  /** The PKCE challenge: base64url of the verifier's SHA-256, without padding */
  fun challenge(verifier: String): String =
    URL64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII)))

  /**
   * The authorization URL: the first sign-in registers the client ([clientId] null), a later one names the issued client
   * and the last ID token, which skips the account choice and the consent screen
   */
  fun authorizeUrl(attempt: Attempt, hostId: String, clientId: String?, idTokenHint: String?): String {
    val params = linkedMapOf(
      "response_type" to "code",
      "client_id" to (clientId ?: DYNAMIC_CLIENT),
      "redirect_uri" to attempt.redirectUri,
      "scope" to SCOPE,
      "resource" to RESOURCE,
      "state" to attempt.state,
      "nonce" to attempt.nonce,
      "code_challenge" to challenge(attempt.verifier),
      "code_challenge_method" to "S256",
      "ext_agent_host_id" to hostId,
    )
    if (clientId == null) params["agent_name_hint"] = AGENT_NAME
    if (clientId != null && idTokenHint != null) params["id_token_hint"] = idTokenHint
    return "$AUTHORIZE?" + form(params)
  }

  sealed interface Callback {
    /** The code to exchange; a new registration also brings the issued [clientId] */
    data class Code(val code: String, val clientId: String?) : Callback

    /** The person declined, or the vendor refused, with its code */
    data class Denied(val error: String) : Callback

    /** Not this attempt's answer: a missing or foreign state, no code */
    object Foreign : Callback
  }

  /** What the browser brought to the callback, checked against [attempt]'s state */
  fun callback(query: String, attempt: Attempt): Callback {
    val params = query.split('&').filter { it.isNotEmpty() }.associate { pair ->
      val key = URLDecoder.decode(pair.substringBefore('='), StandardCharsets.UTF_8)
      key to URLDecoder.decode(pair.substringAfter('=', ""), StandardCharsets.UTF_8)
    }
    if (params["state"] != attempt.state) return Callback.Foreign
    params["error"]?.let { return Callback.Denied(it) }
    val code = params["code"]?.takeIf { it.isNotEmpty() } ?: return Callback.Foreign
    return Callback.Code(code, params["client_id"]?.takeIf { it.isNotEmpty() })
  }

  fun exchangeForm(clientId: String, code: String, attempt: Attempt): String = form(linkedMapOf(
    "grant_type" to "authorization_code", "client_id" to clientId, "code" to code,
    "code_verifier" to attempt.verifier, "redirect_uri" to attempt.redirectUri, "resource" to RESOURCE))

  /** A refresh names no scope: the vendor keeps the granted one */
  fun refreshForm(clientId: String, refreshToken: String): String = form(linkedMapOf(
    "grant_type" to "refresh_token", "client_id" to clientId, "refresh_token" to refreshToken, "resource" to RESOURCE))

  fun revokeForm(clientId: String, refreshToken: String): String = form(linkedMapOf(
    "token" to refreshToken, "token_type_hint" to "refresh_token", "client_id" to clientId))

  /** A token answer: [scopes] is what was granted, which may lack [PLAN_SCOPE] */
  data class Tokens(
    val access: String,
    val refresh: String?,
    val idToken: String?,
    val expiresInSec: Long,
    val scopes: Set<String>,
    val earliestRefreshAt: Long?,
  ) {
    val mayUsePlan: Boolean get() = PLAN_SCOPE in scopes
  }

  fun tokens(answer: JsonObject): Tokens? {
    fun s(key: String) = (answer[key] as? JsonPrimitive)?.contentOrNull
    val access = s("access_token") ?: return null
    return Tokens(
      access = access,
      refresh = s("refresh_token"),
      idToken = s("id_token"),
      expiresInSec = (answer["expires_in"] as? JsonPrimitive)?.longOrNull ?: DEFAULT_ACCESS_LIFETIME_SEC,
      scopes = s("scope").orEmpty().split(' ').filter { it.isNotEmpty() }.toSet(),
      earliestRefreshAt = (answer["earliest_refresh_at"] as? JsonPrimitive)?.longOrNull,
    )
  }

  enum class RefreshFailure {
    /** The refresh token is gone for good: the person signs in again, with the client id kept */
    SIGN_IN_AGAIN,

    /** The client itself is refused: the configuration is wrong, signing in again would not help */
    CLIENT,

    /** The network or the vendor for a moment: the tokens stay, the refresh is tried again later */
    TRANSIENT,
  }

  /** How a failed refresh ends, by the vendor's code (token reference, errors and recovery) */
  fun refreshFailure(status: Int, body: String): RefreshFailure = when {
    SIGN_IN_AGAIN_CODES.any { body.contains(it) } -> RefreshFailure.SIGN_IN_AGAIN
    body.contains("invalid_client") -> RefreshFailure.CLIENT
    status == TOO_MANY_REQUESTS -> RefreshFailure.TRANSIENT
    status in 400..499 -> RefreshFailure.SIGN_IN_AGAIN
    else -> RefreshFailure.TRANSIENT
  }

  private val SIGN_IN_AGAIN_CODES = listOf(
    "invalid_grant", "invalid_refresh_token", "token_expired", "refresh_token_expired",
    "refresh_token_invalidated", "refresh_token_reused",
  )

  /** An access token lives an hour by the token reference; the answer's own `expires_in` wins */
  private const val DEFAULT_ACCESS_LIFETIME_SEC = 3600L
  private const val TOO_MANY_REQUESTS = 429
  private const val TOKEN_BYTES = 32
  private const val VERIFIER_BYTES = 48
  private val URL64 = Base64.getUrlEncoder().withoutPadding()

  private fun token(random: SecureRandom, bytes: Int = TOKEN_BYTES): String = ByteArray(bytes).also(random::nextBytes).let(URL64::encodeToString)

  private fun form(params: Map<String, String>): String = params.entries.joinToString("&") {
    URLEncoder.encode(it.key, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(it.value, StandardCharsets.UTF_8)
  }
}
