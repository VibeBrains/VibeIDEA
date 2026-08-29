// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.http

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The security surface of a feature that runs code on the owner's machine — hence tested per rule. */
class HttpApiPolicyTest {
  private val token = "секрет-токен"

  private fun request(
    method: String = "POST",
    path: String = "/run",
    auth: String? = "Bearer секрет-токен",
    host: String? = "127.0.0.1:7391",
    loopback: Boolean = true,
    body: String = """{"task":"собери проект"}""",
    length: Int = body.toByteArray().size,
  ) = HttpApiPolicy.Request(method, path, auth, host, loopback, length, body)

  private fun refusal(request: HttpApiPolicy.Request, token: String? = this.token): HttpApiPolicy.Decision.Refuse =
    assertIs<HttpApiPolicy.Decision.Refuse>(HttpApiPolicy.decide(request, token))

  @Test
  fun `health is a route of its own`() {
    val decision = HttpApiPolicy.decide(request(method = "GET", path = "/health", body = ""), token)
    assertIs<HttpApiPolicy.Decision.Health>(decision)
  }

  @Test
  fun `a run carries task, session and wait`() {
    val decision = HttpApiPolicy.decide(
      request(body = """{"task":"  собери  ","sessionId":"t-1","wait":true}"""), token)
    val run = assertIs<HttpApiPolicy.Decision.Run>(decision)
    assertEquals("собери", run.task)
    assertEquals("t-1", run.sessionId)
    assertTrue(run.wait)
  }

  @Test
  fun `wait defaults to false and a blank session is treated as absent`() {
    val run = assertIs<HttpApiPolicy.Decision.Run>(
      HttpApiPolicy.decide(request(body = """{"task":"x","sessionId":"   "}"""), token))
    assertFalse(run.wait)
    assertEquals(null, run.sessionId)
  }

  @Test
  fun `a request from another machine is refused with 403`() {
    assertEquals(403, refusal(request(loopback = false)).code)
  }

  @Test
  fun `a foreign Host is refused with 403 — this is the DNS rebinding defence`() {
    assertEquals(403, refusal(request(host = "evil.example.com")).code)
    assertEquals(403, refusal(request(host = null)).code)
  }

  @Test
  fun `localhost, 127_0_0_1 and IPv6 pass, with or without a port`() {
    for (host in listOf("localhost", "LOCALHOST", "127.0.0.1", "127.0.0.1:7391", "[::1]", "[::1]:7391", "localhost:80")) {
      assertTrue(HttpApiPolicy.isLocalHost(host), host)
    }
    for (host in listOf("evil.com", "127.0.0.1.evil.com", "", "192.168.1.5")) {
      assertFalse(HttpApiPolicy.isLocalHost(host), host)
    }
  }

  @Test
  fun `no token configured is our fault — 503 before any 401`() {
    assertEquals(503, refusal(request(auth = null), token = null).code)
  }

  @Test
  fun `a missing, malformed or wrong token is 401`() {
    assertEquals(401, refusal(request(auth = null)).code)
    assertEquals(401, refusal(request(auth = "секрет-токен")).code)          // no scheme
    assertEquals(401, refusal(request(auth = "Basic секрет-токен")).code)
    assertEquals(401, refusal(request(auth = "Bearer чужой")).code)
    assertEquals(401, refusal(request(auth = "Bearer секрет-токе")).code)    // prefix of the real one
  }

  @Test
  fun `the Bearer scheme is case-insensitive, the token is not`() {
    assertTrue(HttpApiPolicy.isAuthorized("bearer $token", token))
    assertTrue(HttpApiPolicy.isAuthorized("BEARER  $token  ", token))
    assertFalse(HttpApiPolicy.isAuthorized("Bearer ${token.uppercase()}", token))
  }

  @Test
  fun `an oversized body is refused before it is parsed`() {
    val big = HttpApiPolicy.MAX_BODY_BYTES + 1
    assertEquals(413, refusal(request(body = "{}", length = big)).code)
  }

  @Test
  fun `unknown routes are 404, and the method matters`() {
    assertEquals(404, refusal(request(method = "GET", path = "/run")).code)
    assertEquals(404, refusal(request(method = "POST", path = "/health")).code)
    assertEquals(404, refusal(request(path = "/../etc/passwd")).code)
  }

  @Test
  fun `a body that is not an object, or has no task, is 400`() {
    assertEquals(400, refusal(request(body = "не json")).code)
    assertEquals(400, refusal(request(body = "[1,2,3]")).code)
    assertEquals(400, refusal(request(body = "{}")).code)
    assertEquals(400, refusal(request(body = """{"task":"   "}""")).code)
  }

  @Test
  fun `refusal order — origin, then host, then token, then size`() {
    // A caller from outside must not learn whether the token was right.
    assertEquals(403, refusal(request(loopback = false, auth = "Bearer чужой", host = "evil.com")).code)
    assertEquals(403, refusal(request(host = "evil.com", auth = "Bearer чужой")).code)
    assertEquals(401, refusal(request(auth = "Bearer чужой", length = HttpApiPolicy.MAX_BODY_BYTES + 1)).code)
  }
}
