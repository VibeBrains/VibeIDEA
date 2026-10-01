// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers.chatgpt

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.diagnostic.logger
import com.vibe.agent.i18n.VibeI18n.t
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * The live side of a ChatGPT sign-in: signing in through the browser, a valid access token for each request, signing out
 *
 * An access token lives an hour and is kept in memory only; a refresh rotates the refresh token, so refreshes of one
 * account run one at a time and the new token is stored before anything else could use the old one
 * A request in the background takes only what is in memory: the keychain asks for a password, and a question nobody
 * started would come out of nowhere ([accessToken] with `quiet`)
 */
object ChatGptSession {
  sealed interface SignIn {
    data class Done(val account: ChatGptAccounts.Account, val firstTime: Boolean) : SignIn
    data class Failed(val reason: String) : SignIn
  }

  private data class Access(val token: String, val expiresAtMs: Long)

  private val access = ConcurrentHashMap<String, Access>()
  private val secrets = ConcurrentHashMap<String, ChatGptAccounts.Secret>()
  private val locks = ConcurrentHashMap<String, Any>()

  /**
   * Signs in through the browser: as a new account, or again as [existing], which keeps its issued client
   * Blocks until the browser comes back or [timeoutSec] passes; call it off the EDT
   */
  fun signIn(existing: ChatGptAccounts.Account?, timeoutSec: Long = SIGN_IN_TIMEOUT_SEC): SignIn {
    val hostId = ChatGptAccounts.hostId()
    val hint = existing?.let { runCatching { ChatGptAccounts.secret(it.clientId)?.idToken }.getOrNull() }
    ChatGptCallbackServer.start().use { server ->
      val attempt = ChatGptOAuth.attempt(server.port)
      BrowserUtil.browse(ChatGptOAuth.authorizeUrl(attempt, hostId, existing?.clientId, hint))
      val query = runCatching { server.query.get(timeoutSec, TimeUnit.SECONDS) }.getOrNull()
                  ?: return SignIn.Failed(t("chatgpt.signIn.timeout"))
      val code = when (val callback = ChatGptOAuth.callback(query, attempt)) {
        is ChatGptOAuth.Callback.Denied -> return SignIn.Failed(t("chatgpt.signIn.denied", "error" to callback.error))
        ChatGptOAuth.Callback.Foreign -> return SignIn.Failed(t("chatgpt.signIn.foreign"))
        is ChatGptOAuth.Callback.Code -> callback
      }
      // A new registration brings its client id; a repeated sign-in may not, and must not bring another one
      if (existing != null && code.clientId != null && code.clientId != existing.clientId) return SignIn.Failed(t("chatgpt.signIn.foreign"))
      val clientId = code.clientId ?: existing?.clientId ?: return SignIn.Failed(t("chatgpt.signIn.noClient"))
      val answer = post(ChatGptOAuth.TOKEN, ChatGptOAuth.exchangeForm(clientId, code.code, attempt))
      if (answer.statusCode() != OK) return SignIn.Failed(t("chatgpt.signIn.exchange", "status" to answer.statusCode()))
      val tokens = json(answer.body())?.let(ChatGptOAuth::tokens) ?: return SignIn.Failed(t("chatgpt.signIn.exchange", "status" to answer.statusCode()))
      val idToken = tokens.idToken ?: return SignIn.Failed(t("chatgpt.signIn.idToken"))
      val jwks = json(get(ChatGptOAuth.JWKS).body()) ?: return SignIn.Failed(t("chatgpt.signIn.idToken"))
      val claims = ChatGptIdToken.verify(idToken, jwks, clientId, attempt.nonce, System.currentTimeMillis() / MS)
                   ?: return SignIn.Failed(t("chatgpt.signIn.idToken"))
      val refresh = tokens.refresh ?: return SignIn.Failed(t("chatgpt.signIn.exchange", "status" to answer.statusCode()))
      val firstTime = ChatGptAccounts.all().isEmpty()
      val account = ChatGptAccounts.Account(
        label = existing?.label ?: claims.email ?: claims.sub, email = claims.email, sub = claims.sub, clientId = clientId,
        mayUsePlan = tokens.mayUsePlan, signedOut = false)
      val secret = ChatGptAccounts.Secret(refresh, idToken)
      ChatGptAccounts.save(account, secret)
      secrets[clientId] = secret
      access[clientId] = Access(tokens.access, expiry(tokens.expiresInSec))
      return SignIn.Done(account, firstTime)
    }
  }

  /**
   * A valid access token of the account [label] names (or the only one), refreshed when it ran out; null when there is
   * no account, it is signed out, it may not spend the plan, or [quiet] and nothing is in memory
   */
  fun accessToken(label: String?, quiet: Boolean): String? {
    val account = ChatGptAccounts.find(label)?.takeIf { !it.signedOut && it.mayUsePlan } ?: return null
    valid(account.clientId)?.let { return it }
    if (quiet) return null
    synchronized(locks.getOrPut(account.clientId) { Any() }) {
      valid(account.clientId)?.let { return it }
      return refresh(account)
    }
  }

  /** Revokes the refresh token and forgets the tokens; false when the vendor did not confirm the revocation */
  fun signOut(account: ChatGptAccounts.Account): Boolean {
    val secret = secrets[account.clientId] ?: runCatching { ChatGptAccounts.secret(account.clientId) }.getOrNull()
    val revoked = secret?.let {
      runCatching { post(ChatGptOAuth.REVOKE, ChatGptOAuth.revokeForm(account.clientId, it.refreshToken)).statusCode() == OK }.getOrDefault(false)
    } ?: true
    access.remove(account.clientId)
    secrets.remove(account.clientId)
    ChatGptAccounts.signOut(account.clientId)
    return revoked
  }

  private fun valid(clientId: String): String? =
    access[clientId]?.takeIf { it.expiresAtMs - EARLY_MS > System.currentTimeMillis() }?.token

  private fun refresh(account: ChatGptAccounts.Account): String? {
    val secret = secrets[account.clientId] ?: ChatGptAccounts.secret(account.clientId)?.also { secrets[account.clientId] = it } ?: return null
    val answer = runCatching { post(ChatGptOAuth.TOKEN, ChatGptOAuth.refreshForm(account.clientId, secret.refreshToken)) }.getOrElse {
      logger<ChatGptSession>().warn("ChatGPT token refresh did not reach the vendor: ${it.message}")
      return null
    }
    if (answer.statusCode() != OK) {
      val failure = ChatGptOAuth.refreshFailure(answer.statusCode(), answer.body())
      logger<ChatGptSession>().warn("ChatGPT token refresh refused: status=${answer.statusCode()}, outcome=$failure")
      if (failure == ChatGptOAuth.RefreshFailure.SIGN_IN_AGAIN) {
        access.remove(account.clientId)
        secrets.remove(account.clientId)
        ChatGptAccounts.signOut(account.clientId)
      }
      return null
    }
    val tokens = json(answer.body())?.let(ChatGptOAuth::tokens) ?: return null
    val rotated = ChatGptAccounts.Secret(tokens.refresh ?: secret.refreshToken, tokens.idToken ?: secret.idToken)
    if (rotated != secret) {
      ChatGptAccounts.rotate(account, rotated)
      secrets[account.clientId] = rotated
    }
    access[account.clientId] = Access(tokens.access, expiry(tokens.expiresInSec))
    return tokens.access
  }

  private fun expiry(expiresInSec: Long) = System.currentTimeMillis() + expiresInSec * MS

  private val http: HttpClient by lazy {
    HttpClient.newBuilder().proxy(ProxySelector.getDefault()).connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SEC)).build()
  }

  private fun post(url: String, form: String): HttpResponse<String> = http.send(
    HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SEC))
      .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form)).build(),
    HttpResponse.BodyHandlers.ofString())

  private fun get(url: String): HttpResponse<String> = http.send(
    HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SEC)).GET().build(), HttpResponse.BodyHandlers.ofString())

  private fun json(text: String): JsonObject? = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()

  /** The person signs in in a browser and may take a while; past this the attempt ends and its listener closes */
  private const val SIGN_IN_TIMEOUT_SEC = 300L

  /** A token is renewed this long before it runs out, so a request does not start with one about to die */
  private const val EARLY_MS = 120_000L
  private const val MS = 1000L
  private const val OK = 200
  private const val CONNECT_TIMEOUT_SEC = 20L
  private const val REQUEST_TIMEOUT_SEC = 60L
}
