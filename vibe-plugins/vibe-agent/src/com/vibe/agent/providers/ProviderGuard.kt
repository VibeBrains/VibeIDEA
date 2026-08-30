// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import com.vibe.agent.i18n.VibeI18n.t

import java.net.URI

data class GuardFinding(val providerId: String, val ruleId: String, val severity: String, val message: String)

/**
 * Config Guard for providers.json, VibeIDE rules verbatim:
 * provider-endpoint-non-https (critical), provider-endpoint-raw-ip (high),
 * provider-hardcoded-secret (critical: userinfo in baseURL / literal secret in headers or query).
 * http://localhost and 127.0.0.1 are legitimate (local proxy) and never flagged.
 * Pure function, no I/O — findings are reported once, deduplicated by signature.
 */
object ProviderGuard {
  private val SECRET_SHAPED = Regex("(sk-[A-Za-z0-9]{8,}|AKIA[A-Z0-9]{12,}|(api[_-]?key|token|secret)\\s*[=:]\\s*\\S{8,})", RegexOption.IGNORE_CASE)
  private val RAW_IP = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

  fun scan(providers: List<ProviderEntry>): List<GuardFinding> {
    val findings = ArrayList<GuardFinding>()
    for (p in providers.filter { it.active }) {
      val url = p.baseURL ?: continue
      val uri = runCatching { URI(url) }.getOrNull()
      val host = uri?.host ?: ""
      val local = host == "localhost" || host == "127.0.0.1" || host == "::1"
      if (uri?.scheme == "http" && !local) {
        findings.add(GuardFinding(p.id, "provider-endpoint-non-https", "critical", t("guard.notHttps", "id" to p.id, "url" to url)))
      }
      if (!local && RAW_IP.matches(host)) {
        findings.add(GuardFinding(p.id, "provider-endpoint-raw-ip", "high", t("guard.rawIp", "id" to p.id, "url" to url)))
      }
      if (!uri?.userInfo.isNullOrBlank()) {
        findings.add(GuardFinding(p.id, "provider-hardcoded-secret", "critical", t("guard.credsInUrl", "id" to p.id)))
      }
      (p.headers.values + p.query.values).forEach { v ->
        if (SECRET_SHAPED.containsMatchIn(v)) {
          findings.add(GuardFinding(p.id, "provider-hardcoded-secret", "critical", t("guard.secretLiteral", "id" to p.id)))
        }
      }
    }
    return findings.distinctBy { it.providerId + it.ruleId + it.message }
  }
}
