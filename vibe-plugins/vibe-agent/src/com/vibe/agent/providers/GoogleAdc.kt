// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.providers

import com.vibe.agent.i18n.VibeI18n.t
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Duration
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * `"auth": "google-adc"`: a Google OAuth token from Application Default Credentials, sent as Bearer.
 *
 * The vendor pays for the model through the person's Google Cloud account, and the access token lives
 * about an hour. It is therefore fetched when a request is built and cached only until shortly before
 * it expires — a token copied into the file would be a secret in git and dead an hour later.
 *
 * Both shapes of the ADC file are read: `authorized_user` (the one `gcloud auth application-default
 * login` writes) refreshes the token with its refresh token, `service_account` signs a JWT assertion
 * with the key from the file. No Google SDK is added: the two requests are plain OAuth.
 */
object GoogleAdc {
  const val AUTH = AuthSpec.GOOGLE_ADC
  const val DEFAULT_TOKEN_URI = "https://oauth2.googleapis.com/token"
  const val SCOPE = "https://www.googleapis.com/auth/cloud-platform"

  /** Refresh this long before the stated expiry, so a request never starts with a token that dies in flight. */
  const val EXPIRY_SKEW_MS = 60_000L

  private const val TOKEN_LIFETIME_SECONDS = 3600L
  private const val GRANT_JWT = "urn:ietf:params:oauth:grant-type:jwt-bearer"
  private const val CREDENTIALS_NAME = "application_default_credentials.json"

  private val json = Json { ignoreUnknownKeys = true }
  private val http: HttpClient by lazy {
    HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
  }

  /** Tokens by credentials file: several providers may share one ADC account. */
  private val cache = ConcurrentHashMap<String, CachedToken>()

  private data class CachedToken(val value: String, val expiresAtMs: Long) {
    fun validAt(nowMs: Long): Boolean = fresh(expiresAtMs, nowMs)
  }

  /** A token is used only while it outlives the skew: one that dies in flight turns into a failed request. */
  internal fun fresh(expiresAtMs: Long, nowMs: Long): Boolean = nowMs < expiresAtMs - EXPIRY_SKEW_MS

  /** The credentials ADC itself would use: the variable first, the per-platform default file second. */
  fun credentialsPath(env: Map<String, String>, home: String, windows: Boolean): Path? {
    env["GOOGLE_APPLICATION_CREDENTIALS"]?.takeIf { it.isNotBlank() }?.let { return Path.of(it) }
    val dir = if (windows) env["APPDATA"]?.takeIf { it.isNotBlank() }?.let { Path.of(it, "gcloud") }
              else Path.of(home, ".config", "gcloud")
    return dir?.resolve(CREDENTIALS_NAME)?.takeIf { Files.isRegularFile(it) }
  }

  /**
   * A valid access token, or null with a warning in the chat journal.
   *
   * Null is «no token», never an empty string: the request then carries no Authorization at all,
   * and the provider is reported as missing its key instead of being asked with a broken header.
   */
  fun accessToken(providerId: String, onWarning: (String) -> Unit): String? {
    val path = credentialsPath(System.getenv(), System.getProperty("user.home").orEmpty(), isWindows())
    if (path == null) {
      onWarning(t("providers.warn.adcMissing", "id" to providerId))
      return null
    }
    val key = path.toString()
    val now = System.currentTimeMillis()
    cache[key]?.takeIf { it.validAt(now) }?.let { return it.value }
    synchronized(this) {
      cache[key]?.takeIf { it.validAt(now) }?.let { return it.value }
      val credentials = runCatching { Files.readString(path) }.getOrNull()?.let { parseCredentials(it) }
      if (credentials == null) {
        onWarning(t("providers.warn.adcFailed", "id" to providerId, "reason" to "credentials file is not readable"))
        return null
      }
      val token = runCatching { fetch(credentials) }.getOrElse { e ->
        onWarning(t("providers.warn.adcFailed", "id" to providerId, "reason" to (e.message ?: "OAuth request failed")))
        return null
      }
      cache[key] = token
      return token.value
    }
  }

  private fun fetch(credentials: Credentials): CachedToken {
    val nowMs = System.currentTimeMillis()
    val (uri, body) = tokenRequest(credentials, nowMs / 1000) ?: error("credentials lack the fields for a token request")
    val request = HttpRequest.newBuilder(URI.create(uri))
      .timeout(Duration.ofSeconds(30))
      .header("Content-Type", "application/x-www-form-urlencoded")
      .POST(HttpRequest.BodyPublishers.ofString(body))
      .build()
    val response = http.send(request, HttpResponse.BodyHandlers.ofString())
    if (response.statusCode() !in 200..299) error("HTTP " + response.statusCode() + ": " + response.body().take(200))
    val root = json.parseToJsonElement(response.body()).jsonObject
    val value = root["access_token"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: error("response carries no access_token")
    val lifetime = root["expires_in"]?.jsonPrimitive?.longOrNull ?: TOKEN_LIFETIME_SECONDS
    return CachedToken(value, nowMs + lifetime * 1000)
  }

  /** The token endpoint and the form body for this credential shape; null when the file lacks a needed field. */
  internal fun tokenRequest(credentials: Credentials, nowSeconds: Long): Pair<String, String>? = when (credentials.type) {
    AUTHORIZED_USER -> {
      val refresh = credentials.refreshToken ?: return null
      val clientId = credentials.clientId ?: return null
      val fields = mutableListOf(
        "grant_type" to "refresh_token",
        "client_id" to clientId,
        "refresh_token" to refresh,
      )
      credentials.clientSecret?.let { fields += "client_secret" to it }
      credentials.tokenUri to form(fields)
    }
    SERVICE_ACCOUNT -> {
      val assertion = assertion(credentials, nowSeconds) ?: return null
      credentials.tokenUri to form(listOf("grant_type" to GRANT_JWT, "assertion" to assertion))
    }
    else -> null
  }

  /** The signed JWT assertion of a service account, or null when the key or the e-mail is missing. */
  internal fun assertion(credentials: Credentials, nowSeconds: Long): String? {
    val pem = credentials.privateKey ?: return null
    val email = credentials.clientEmail ?: return null
    val header = buildJsonObject { put("alg", "RS256"); put("typ", "JWT") }
    val claims = buildJsonObject {
      put("iss", email)
      put("scope", SCOPE)
      put("aud", credentials.tokenUri)
      put("iat", nowSeconds)
      put("exp", nowSeconds + TOKEN_LIFETIME_SECONDS)
    }
    return signJwt(pem, header, claims)
  }

  /** RS256 signature over the two base64url parts, as the assertion the token endpoint expects. */
  internal fun signJwt(pem: String, header: JsonObject, claims: JsonObject): String? {
    val key = privateKey(pem) ?: return null
    val unsigned = base64Url(header.toString().toByteArray(StandardCharsets.UTF_8)) + "." +
                   base64Url(claims.toString().toByteArray(StandardCharsets.UTF_8))
    val signature = runCatching {
      Signature.getInstance("SHA256withRSA").run {
        initSign(key)
        update(unsigned.toByteArray(StandardCharsets.UTF_8))
        sign()
      }
    }.getOrNull() ?: return null
    return unsigned + "." + base64Url(signature)
  }

  internal fun parseCredentials(text: String): Credentials? {
    val o = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
    val type = o["type"]?.jsonPrimitive?.contentOrNull?.trim() ?: return null
    return Credentials(
      type = type,
      clientId = o["client_id"]?.jsonPrimitive?.contentOrNull,
      clientSecret = o["client_secret"]?.jsonPrimitive?.contentOrNull,
      refreshToken = o["refresh_token"]?.jsonPrimitive?.contentOrNull,
      clientEmail = o["client_email"]?.jsonPrimitive?.contentOrNull,
      privateKey = o["private_key"]?.jsonPrimitive?.contentOrNull,
      tokenUri = o["token_uri"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: DEFAULT_TOKEN_URI,
    )
  }

  internal data class Credentials(
    val type: String,
    val clientId: String? = null,
    val clientSecret: String? = null,
    val refreshToken: String? = null,
    val clientEmail: String? = null,
    val privateKey: String? = null,
    val tokenUri: String = DEFAULT_TOKEN_URI,
  )

  internal fun privateKey(pem: String): PrivateKey? = runCatching {
    val body = pem.replace(Regex("-----[^-]+-----"), "").replace(Regex("\\s"), "")
    val der = Base64.getDecoder().decode(body)
    KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der))
  }.getOrNull()

  private fun form(fields: List<Pair<String, String>>): String =
    fields.joinToString("&") { (k, v) -> encode(k) + "=" + encode(v) }

  private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

  private fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

  private fun isWindows(): Boolean = System.getProperty("os.name").orEmpty().lowercase().contains("windows")

  internal const val AUTHORIZED_USER = "authorized_user"
  internal const val SERVICE_ACCOUNT = "service_account"
}
