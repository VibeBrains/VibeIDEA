// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers.chatgpt

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.PathManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * The ChatGPT accounts this IDE signed in with: who, under which issued client, with which grant — and the secrets apart
 *
 * The index is not secret and lives in the IDE's config directory, outside any project and any sync: the host id and
 * each account's client id must survive a sign-out (the vendor asks to keep them, so the next sign-in skips consent)
 * The refresh and ID tokens are secret and live in the system keychain, one entry per account; the access token lives
 * in memory only ([ChatGptSession]), so the keychain is touched once a run and at rotation, not once an hour
 */
object ChatGptAccounts {
  data class Account(
    val label: String,
    val email: String?,
    val sub: String,
    val clientId: String,
    /** The account granted the plan's scope: without it the sign-in stands, the plan is not spent */
    val mayUsePlan: Boolean,
    /** The tokens are gone (signed out, or the vendor refused them): the next use needs a sign-in */
    val signedOut: Boolean,
  )

  data class Secret(val refreshToken: String, val idToken: String?)

  @Synchronized
  fun all(): List<Account> = read().accounts

  /** The account [label] names, or the only one there is; null when there is none or the choice is ambiguous */
  @Synchronized
  fun find(label: String?): Account? {
    val accounts = read().accounts
    return if (label != null) accounts.firstOrNull { it.label == label } else accounts.singleOrNull()
  }

  /** This installation's host id, made once before the first sign-in: `urn:uuid:` — opaque, not a credential */
  @Synchronized
  fun hostId(): String {
    val index = read()
    index.hostId?.let { return it }
    val made = "urn:uuid:" + UUID.randomUUID()
    write(index.copy(hostId = made))
    return made
  }

  /** Whether the one-time «you are using your ChatGPT plan» notice was shown */
  @Synchronized
  fun noticeShown(): Boolean = read().noticeShown

  @Synchronized
  fun markNoticeShown() = write(read().copy(noticeShown = true))

  @Synchronized
  fun save(account: Account, secret: Secret) {
    val index = read()
    write(index.copy(accounts = index.accounts.filterNot { it.clientId == account.clientId } + account))
    PasswordSafe.instance.set(attributes(account.clientId), Credentials(account.sub, encode(secret)))
  }

  /** The secret of [clientId]; reads the keychain, so only on a request the person made */
  fun secret(clientId: String): Secret? {
    val stored = PasswordSafe.instance.getPassword(attributes(clientId)) ?: return null
    return runCatching {
      val o = Json.parseToJsonElement(stored).jsonObject
      Secret((o[REFRESH] as JsonPrimitive).content, (o[ID_TOKEN] as? JsonPrimitive)?.contentOrNull)
    }.getOrNull()
  }

  /** A rotated refresh token is written before the old one could be used again: it is spent once */
  fun rotate(account: Account, secret: Secret) {
    PasswordSafe.instance.set(attributes(account.clientId), Credentials(account.sub, encode(secret)))
  }

  /** The tokens are gone; the account, its client id and the host id stay for the next sign-in */
  @Synchronized
  fun signOut(clientId: String) {
    PasswordSafe.instance.set(attributes(clientId), null)
    val index = read()
    write(index.copy(accounts = index.accounts.map { if (it.clientId == clientId) it.copy(signedOut = true) else it }))
  }

  private data class Index(val hostId: String?, val noticeShown: Boolean, val accounts: List<Account>)

  private fun file(): Path = Path.of(PathManager.getConfigPath(), DIR, FILE)

  private fun read(): Index {
    val o = runCatching { Json.parseToJsonElement(Files.readString(file())).jsonObject }.getOrNull()
      ?: return Index(null, false, emptyList())
    fun JsonObject.s(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    val accounts = (o["accounts"] as? JsonArray).orEmpty().mapNotNull { entry ->
      val a = entry as? JsonObject ?: return@mapNotNull null
      Account(a.s("label") ?: return@mapNotNull null, a.s("email"), a.s("sub") ?: return@mapNotNull null,
              a.s("clientId") ?: return@mapNotNull null, (a["mayUsePlan"] as? JsonPrimitive)?.booleanOrNull ?: false,
              (a["signedOut"] as? JsonPrimitive)?.booleanOrNull ?: false)
    }
    return Index(o.s("hostId"), (o["noticeShown"] as? JsonPrimitive)?.booleanOrNull ?: false, accounts)
  }

  /** Written whole to a neighbour and moved over: a crash mid-write leaves the old index, not half of the new one */
  private fun write(index: Index) {
    val target = file()
    Files.createDirectories(target.parent)
    val text = buildJsonObject {
      index.hostId?.let { put("hostId", it) }
      if (index.noticeShown) put("noticeShown", true)
      put("accounts", JsonArray(index.accounts.map { a ->
        buildJsonObject {
          put("label", a.label)
          a.email?.let { put("email", it) }
          put("sub", a.sub)
          put("clientId", a.clientId)
          put("mayUsePlan", a.mayUsePlan)
          if (a.signedOut) put("signedOut", true)
        }
      }))
    }.toString()
    val temp = target.resolveSibling("$FILE.tmp")
    Files.writeString(temp, text)
    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
  }

  private fun attributes(clientId: String) = CredentialAttributes(generateServiceName(SERVICE, clientId))

  private fun encode(secret: Secret): String = buildJsonObject {
    put(REFRESH, secret.refreshToken)
    secret.idToken?.let { put(ID_TOKEN, it) }
  }.toString()

  private const val SERVICE = "VibeIDEA ChatGPT"
  private const val DIR = "vibe"
  private const val FILE = "chatgpt.json"
  private const val REFRESH = "refreshToken"
  private const val ID_TOKEN = "idToken"
}
