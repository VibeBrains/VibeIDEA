// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.util

import com.intellij.openapi.diagnostic.logger
import java.net.http.HttpClient

/**
 * Trust and authentication taken from the IDE itself: its certificates and its proxy authenticator
 *
 * A client built from scratch inherits none of what the person configured in the IDE: a corporate root certificate
 * added in settings would not apply to our requests (they fail at the TLS handshake behind such a proxy), and a proxy
 * that asks for a login would not be passed at all. The platform provides both (`PlatformHttpClient`, 2026.3) —
 * exactly the part not worth writing ourselves
 *
 * The route is the caller's: model requests choose their own proxy, and a client without a selector takes the JVM
 * default, which the platform sets to the IDE's own. The authenticator alone changes nothing: it fires only when a
 * proxy actually asks for credentials
 *
 * Gated on the application state: before the application is up there are no services yet, and a missing
 * embellishment must not fail the client — the request just goes as before
 */
object IdeHttpTrust {
  fun apply(builder: HttpClient.Builder) {
    runCatching {
      val app = com.intellij.openapi.application.ApplicationManager.getApplication() ?: return
      if (app.isDisposed) return
      app.getServiceIfCreated(com.intellij.util.net.ssl.CertificateManager::class.java)
        ?.let { builder.sslContext(it.sslContext) }
      builder.authenticator(com.intellij.util.net.JdkProxyProvider.getInstance().authenticator)
    }.onFailure {
      logger<IdeHttpTrust>().warn("could not take the IDE trust settings for an HTTP client: ${it.message}")
    }
  }
}
