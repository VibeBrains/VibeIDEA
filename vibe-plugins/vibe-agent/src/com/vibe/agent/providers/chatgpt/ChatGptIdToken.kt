// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers.chatgpt

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.math.BigInteger
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

/**
 * The ID token of a ChatGPT sign-in, checked the way the vendor asks: the RS256 signature by its published keys,
 * then the issuer, the audience (the issued client id), the expiry and this attempt's nonce
 * The verified `sub` is the account's identity; the e-mail is shown, never trusted to identify
 *
 * Pure: the token, the key set and the expectations in, the claims out — or null when anything does not hold
 */
object ChatGptIdToken {
  data class Claims(val sub: String, val email: String?, val audience: List<String>, val issuer: String, val expiresAt: Long, val nonce: String?)

  /** The token's claims without any check: for a stored token whose signature was checked when it came */
  fun claims(jwt: String): Claims? {
    val parts = jwt.split('.')
    if (parts.size != 3) return null
    val payload = runCatching { Json.parseToJsonElement(String(URL64.decode(parts[1]))).jsonObject }.getOrNull() ?: return null
    fun s(key: String) = (payload[key] as? JsonPrimitive)?.contentOrNull
    val audience = when (val aud = payload["aud"]) {
      is JsonArray -> aud.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
      is JsonPrimitive -> listOfNotNull(aud.contentOrNull)
      else -> emptyList()
    }
    return Claims(s("sub") ?: return null, s("email"), audience, s("iss").orEmpty(),
                  (payload["exp"] as? JsonPrimitive)?.longOrNull ?: 0, s("nonce"))
  }

  /** The claims of [jwt] when its signature verifies against [jwks] and every expectation holds; null otherwise */
  fun verify(jwt: String, jwks: JsonObject, clientId: String, nonce: String, nowSec: Long): Claims? {
    val parts = jwt.split('.')
    if (parts.size != 3) return null
    val header = runCatching { Json.parseToJsonElement(String(URL64.decode(parts[0]))).jsonObject }.getOrNull() ?: return null
    if ((header["alg"] as? JsonPrimitive)?.contentOrNull != RS256) return null
    val kid = (header["kid"] as? JsonPrimitive)?.contentOrNull
    val keys = (jwks["keys"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    val key = keys.firstOrNull { kid != null && (it["kid"] as? JsonPrimitive)?.contentOrNull == kid } ?: keys.singleOrNull() ?: return null
    if (!signatureHolds(parts, key)) return null
    val claims = claims(jwt) ?: return null
    return claims.takeIf {
      it.issuer == ChatGptOAuth.ISSUER && clientId in it.audience && it.expiresAt + CLOCK_SKEW_SEC > nowSec && it.nonce == nonce
    }
  }

  private fun signatureHolds(parts: List<String>, jwk: JsonObject): Boolean = runCatching {
    val n = BigInteger(1, URL64.decode((jwk["n"] as JsonPrimitive).content))
    val e = BigInteger(1, URL64.decode((jwk["e"] as JsonPrimitive).content))
    val publicKey = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(n, e))
    Signature.getInstance("SHA256withRSA").run {
      initVerify(publicKey)
      update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII))
      verify(URL64.decode(parts[2]))
    }
  }.getOrDefault(false)

  private const val RS256 = "RS256"

  /** A few minutes of disagreement between this machine's clock and the vendor's */
  private const val CLOCK_SKEW_SEC = 300L
  private val URL64 = Base64.getUrlDecoder()
}
