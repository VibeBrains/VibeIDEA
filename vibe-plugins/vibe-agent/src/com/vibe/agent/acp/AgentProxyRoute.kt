// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.acp

import com.intellij.util.net.ProxyConfiguration
import com.intellij.util.net.ProxyCredentialStore
import com.vibe.agent.i18n.VibeI18n.t
import com.vibe.agent.resilience.ProxySettings
import com.vibe.agent.resilience.ProxyTargets
import com.vibe.agent.settings.VibeAgentSettings

/**
 * Which route an external agent gets, the same way model requests choose theirs ([com.vibe.agent.providers.LlmClient]):
 * The agent marked «direct» goes straight out
 * Otherwise our model proxy when it is set, and otherwise the IDE's own proxy
 *
 * The IDE's proxy is handed over only when it is a fixed address: a PAC script or autodetection chooses per request,
 * and an environment variable holds one address for everything, so the agent is told how to set one instead
 */
object AgentProxyRoute {
  fun of(agentName: String, note: (String) -> Unit): AgentProxyEnv.Route {
    // Without the application there are no IDE settings to hand over: the command line and unit tests start agents too
    if (com.intellij.openapi.application.ApplicationManager.getApplication() == null) return AgentProxyEnv.Route.Inherit
    if (VibeAgentSettings.goesDirect(ProxyTargets.agent(agentName))) return AgentProxyEnv.Route.Direct
    val own = VibeAgentSettings.llmProxyUrl.trim()
    if (own.isNotEmpty()) {
      val spec = runCatching { ProxySettings.parse(own) }.getOrNull()
      if (spec == null) {
        note(t("acp.proxy.malformed"))
        return AgentProxyEnv.Route.Inherit
      }
      // The written address as is, credentials included; without a scheme it is HTTP, as the model client reads it
      return AgentProxyEnv.Route.Through(if ("://" in own) own else "http://$own", emptyList())
    }
    return when (val ide = com.intellij.util.net.ProxySettings.getInstance().getProxyConfiguration()) {
      is ProxyConfiguration.StaticProxyConfiguration -> {
        val credentials = runCatching { ProxyCredentialStore.getInstance().getCredentials(ide.host, ide.port) }.getOrNull()
        AgentProxyEnv.Route.Through(
          AgentProxyEnv.url(ide.protocol == ProxyConfiguration.ProxyProtocol.SOCKS, ide.host, ide.port,
                            credentials?.userName, credentials?.getPasswordAsString()),
          ide.exceptions.split(',').map { it.trim() }.filter { it.isNotEmpty() },
        )
      }
      is ProxyConfiguration.ProxyAutoConfiguration, is ProxyConfiguration.AutoDetectProxy -> {
        note(t("acp.proxy.automatic"))
        AgentProxyEnv.Route.Inherit
      }
      else -> AgentProxyEnv.Route.Inherit
    }
  }
}
