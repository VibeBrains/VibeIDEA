// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.providers.chatgpt

import com.vibe.agent.i18n.VibeI18n.t

/**
 * The plan route's refusals in words a person can act on (developers.openai.com/siwc/token-sharing-open-source/
 * errors-and-recovery, checked 01.10.2026)
 *
 * The codes come in an `error` object, as `response.failed` mid-stream, or before the stream as `{"detail": …}`;
 * the message keeps what a report to the vendor needs: the code itself and the request id
 * The vendor never switches the payment by itself, and neither does this: a limit is said, not routed around
 */
object ChatGptErrors {
  /** What a raw failure [message] of the route means, or null when it names none of the route's codes */
  fun describe(message: String?, requestId: String?): String? {
    val text = message ?: return null
    val code = CODES.firstOrNull { text.contains(it) } ?: return null
    val said = when (code) {
      NOT_ELIGIBLE -> t("chatgpt.error.notEligible")
      LIMIT -> t("chatgpt.error.limit", "url" to ChatGptOAuth.USAGE_URL)
      UNAVAILABLE, USER_UNAVAILABLE -> t("chatgpt.error.unavailable")
      UNSUPPORTED -> t("chatgpt.error.unsupported", "param" to (PARAM.find(text)?.groupValues?.get(1) ?: "?"))
      ROUTE -> t("chatgpt.error.route")
      INVALID_USER, SCOPE, CONTEXT -> t("chatgpt.error.signInAgain")
      else -> return null
    }
    return said + " (" + code + (requestId?.let { ", request id $it" } ?: "") + ")"
  }

  /** The limit is the plan's own: the next request fails the same way, and the person decides what to do */
  fun isLimit(message: String?): Boolean = message?.contains(LIMIT) == true

  private const val NOT_ELIGIBLE = "subscription_sharing_user_not_eligible"
  private const val LIMIT = "subscription_sharing_usage_limit_exceeded"
  private const val UNAVAILABLE = "subscription_sharing_usage_unavailable"
  private const val UNSUPPORTED = "subscription_sharing_unsupported_capability"
  private const val ROUTE = "subscription_sharing_route_not_supported"
  private const val INVALID_USER = "subscription_sharing_invalid_user"
  private const val USER_UNAVAILABLE = "subscription_sharing_user_unavailable"
  private const val SCOPE = "chatpass_v2_scope_not_authorized"
  private const val CONTEXT = "chatpass_v2_invalid_authorization_context"
  private val CODES = listOf(NOT_ELIGIBLE, LIMIT, UNAVAILABLE, UNSUPPORTED, ROUTE, INVALID_USER, USER_UNAVAILABLE, SCOPE, CONTEXT)
  private val PARAM = Regex("\"param\"\\s*:\\s*\"([^\"]+)\"")
}
