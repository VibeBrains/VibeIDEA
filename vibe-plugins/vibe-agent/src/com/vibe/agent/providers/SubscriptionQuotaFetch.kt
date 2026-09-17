// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Asks each vendor what its subscription has left — once, when a person asks for the spending report.
 *
 * Never polled: every request is one the person made, so a plan that frowns on third-party tools sees no traffic the
 * person did not cause. Redirects are not followed — the request carries the API key, and a redirect could take it to
 * another host.
 */
object SubscriptionQuotaFetch {
  private const val TIMEOUT_MS = 10_000L
  private const val ERROR_BODY_CHARS = 200

  sealed interface Outcome {
    data class Answered(val result: SubscriptionQuota.Result) : Outcome
    /** No answer to read: the network, or an HTTP status the vendor gave without a body we understand. */
    data class Failed(val reason: String) : Outcome
  }

  data class Row(val provider: ProviderEntry, val spec: QuotaSpec, val outcome: Outcome)

  /**
   * Active providers with a `quota` and a key, one request per endpoint and key: an `extends` clone inherits the field,
   * and asking twice for the same plan would say the same thing twice.
   */
  fun targets(providers: List<ProviderEntry>, keyOf: (ProviderEntry) -> String?): List<Triple<ProviderEntry, QuotaSpec, String>> =
    providers.filter { it.active }.mapNotNull { p -> p.quota?.let { q -> keyOf(p)?.takeIf { it.isNotBlank() }?.let { Triple(p, q, it) } } }
      .distinctBy { (_, q, key) -> q.url to key }

  fun fetchAll(providers: List<ProviderEntry>, projectBase: String?): List<Row> {
    val targets = targets(providers) { ApiKeyResolver.resolve(it, projectBase) }
    if (targets.isEmpty()) return emptyList()
    val client = HttpClient.newBuilder()
      .connectTimeout(Duration.ofMillis(TIMEOUT_MS))
      .followRedirects(HttpClient.Redirect.NEVER)
      .build()
    return targets.map { (provider, spec, key) ->
      java.util.concurrent.CompletableFuture.supplyAsync { Row(provider, spec, fetch(client, spec, key)) }
    }.map { it.join() }
  }

  private fun fetch(client: HttpClient, spec: QuotaSpec, key: String): Outcome = try {
    val request = HttpRequest.newBuilder(URI.create(spec.url))
      .timeout(Duration.ofMillis(TIMEOUT_MS))
      .header("Authorization", "Bearer $key")
      .header("Accept", "application/json")
      .GET().build()
    val response = client.send(request, HttpResponse.BodyHandlers.ofString())
    val parsed = SubscriptionQuota.parse(spec.format, response.body().orEmpty())
    when {
      response.statusCode() in 200..299 -> Outcome.Answered(parsed)
      // A vendor that puts its own error in a non-2xx body is still an answer to show.
      parsed is SubscriptionQuota.Result.VendorError -> Outcome.Answered(parsed)
      else -> Outcome.Failed("HTTP ${response.statusCode()} " + response.body().orEmpty().take(ERROR_BODY_CHARS))
    }
  }
  catch (e: Exception) {
    Outcome.Failed(e.message ?: e.javaClass.simpleName)
  }
}
