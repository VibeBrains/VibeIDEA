// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.http

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import java.security.SecureRandom
import java.util.Base64

/**
 * The HTTP API token: generated on first use, stored in the OS keychain.
 *
 * Not in settings on purpose — settings sync between machines and end up on a shared screen during
 * a demo, and this token authorises running code on this computer. It is shown only inside the IDE
 * (the «показать токен» action), never returned over HTTP.
 */
object VibeApiToken {
  private const val TOKEN_BYTES = 32
  private const val KEY = "vibe.httpApi.token"

  private fun attributes(): CredentialAttributes =
    CredentialAttributes(generateServiceName("VibeIDEA HTTP API", KEY))

  /** Current token, or null when none has been issued yet. Reads the keychain — never call on EDT. */
  fun peek(): String? = PasswordSafe.instance.get(attributes())?.getPasswordAsString()?.takeIf { it.isNotEmpty() }

  /** Current token, issuing one on first call. */
  fun getOrCreate(): String = peek() ?: regenerate()

  /** Issues a new token and forgets the previous one — the answer to "the token leaked". */
  fun regenerate(): String {
    val bytes = ByteArray(TOKEN_BYTES).also { SecureRandom().nextBytes(it) }
    val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    PasswordSafe.instance.set(attributes(), Credentials(KEY, token))
    return token
  }

  fun forget() = PasswordSafe.instance.set(attributes(), null)
}
