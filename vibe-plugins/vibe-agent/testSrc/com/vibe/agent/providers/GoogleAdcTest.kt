// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.providers

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `"auth": "google-adc"`: no key in the file, a Google OAuth token from Application Default Credentials,
 * refreshed shortly before it expires and sent as a Bearer. The network is not touched here — the two
 * OAuth shapes and the signed assertion are pure functions.
 */
class GoogleAdcTest {
  private val authorized = """
    {"type":"authorized_user","client_id":"cid","client_secret":"secret","refresh_token":"refresh"}
  """.trimIndent()

  private val serviceAccount = """
    {"type":"service_account","client_email":"bot@example.iam.gserviceaccount.com",
     "private_key":"KEY","token_uri":"https://oauth2.example/token"}
  """.trimIndent()

  @Test
  fun `an authorized_user file refreshes with its refresh token`() {
    val credentials = assertNotNull(GoogleAdc.parseCredentials(authorized))
    assertEquals(GoogleAdc.AUTHORIZED_USER, credentials.type)
    assertEquals(GoogleAdc.DEFAULT_TOKEN_URI, credentials.tokenUri)
    val (uri, body) = assertNotNull(GoogleAdc.tokenRequest(credentials, 1000))
    assertEquals(GoogleAdc.DEFAULT_TOKEN_URI, uri)
    assertTrue(body.contains("grant_type=refresh_token"), body)
    assertTrue(body.contains("refresh_token=refresh"), body)
    assertTrue(body.contains("client_id=cid"), body)
    assertTrue(body.contains("client_secret=secret"), body)
  }

  @Test
  fun `a service_account file signs a JWT assertion for the token endpoint`() {
    val pair = rsa()
    val credentials = assertNotNull(GoogleAdc.parseCredentials(serviceAccount)).copy(privateKey = pem(pair))
    val (uri, body) = assertNotNull(GoogleAdc.tokenRequest(credentials, 1000))
    assertEquals("https://oauth2.example/token", uri)
    assertTrue(body.contains("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer"), body)
    assertTrue(body.contains("assertion="), body)
    val assertion = credentials.let { GoogleAdc.assertion(it, 1000) }
    assertNotNull(assertion)
    assertEquals(3, assertion.split('.').size)
    val claims = decode(assertion.split('.')[1])
    assertTrue(claims.contains("bot@example.iam.gserviceaccount.com"), claims)
    assertTrue(claims.contains(GoogleAdc.SCOPE), claims)
    assertTrue(claims.contains("\"exp\":4600"), claims)
  }

  @Test
  fun `the assertion signature verifies with the account key`() {
    val pair = rsa()
    val credentials = assertNotNull(GoogleAdc.parseCredentials(serviceAccount)).copy(privateKey = pem(pair))
    val assertion = assertNotNull(GoogleAdc.assertion(credentials, 1000))
    val parts = assertion.split('.')
    assertEquals(false, parts.any { it.isEmpty() }, "base64url parts carry no padding")
    val signed = (parts[0] + "." + parts[1]).toByteArray()
    val verifier = Signature.getInstance("SHA256withRSA").apply {
      initVerify(pair.public)
      update(signed)
    }
    assertTrue(verifier.verify(Base64.getUrlDecoder().decode(parts[2])))
    assertTrue(decode(parts[0]).contains("RS256"))
  }

  @Test
  fun `a token is used only while it outlives the skew`() {
    val now = 1_000_000L
    assertTrue(GoogleAdc.fresh(now + GoogleAdc.EXPIRY_SKEW_MS + 1, now))
    assertEquals(false, GoogleAdc.fresh(now + GoogleAdc.EXPIRY_SKEW_MS, now))
    assertEquals(false, GoogleAdc.fresh(now, now))
  }

  @Test
  fun `the credentials path follows the variable and the platform default`() {
    val home = Files.createTempDirectory("vibe-adc-home")
    val explicit = Files.createTempFile("vibe-adc", ".json")
    assertEquals(explicit, GoogleAdc.credentialsPath(mapOf("GOOGLE_APPLICATION_CREDENTIALS" to explicit.toString()),
                                                      home.toString(), windows = false))
    assertNull(GoogleAdc.credentialsPath(emptyMap(), home.toString(), windows = false))
    val unix = home.resolve(".config").resolve("gcloud")
    Files.createDirectories(unix)
    val unixFile = Files.writeString(unix.resolve("application_default_credentials.json"), authorized)
    assertEquals(unixFile, GoogleAdc.credentialsPath(emptyMap(), home.toString(), windows = false))
    val appData = Files.createTempDirectory("vibe-adc-appdata")
    val winDir = appData.resolve("gcloud")
    Files.createDirectories(winDir)
    val winFile = Files.writeString(winDir.resolve("application_default_credentials.json"), authorized)
    assertEquals(winFile, GoogleAdc.credentialsPath(mapOf("APPDATA" to appData.toString()), home.toString(), windows = true))
  }

  @Test
  fun `the auth is known, sends a Bearer and takes no key from any source`() {
    val warnings = mutableListOf<String>()
    val entry = ProvidersFile.parse(
      """{"providers":[{"id":"vertex","baseURL":"https://vertex.example/v1","auth":"google-adc"}]}""") { warnings += it }
      .single()
    assertEquals(AuthSpec.GOOGLE_ADC, entry.auth.type)
    assertTrue(warnings.isEmpty(), "warnings=$warnings")
    assertEquals(mapOf("Authorization" to "Bearer token"), ProviderAuth.placement(entry.declaredAuth, "token", "openai").headers)
    assertNull(ApiKeyResolver.resolve(entry, null))
    assertNull(ApiKeyResolver.resolveQuietly(entry, null))
    assertTrue(AuthSpec.KNOWN.contains(AuthSpec.GOOGLE_ADC))
  }

  private fun rsa(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

  private fun pem(pair: KeyPair): String =
    "-----BEGIN PRIVATE KEY-----\n" + Base64.getEncoder().encodeToString(pair.private.encoded) + "\n-----END PRIVATE KEY-----"

  private fun decode(part: String): String = String(Base64.getUrlDecoder().decode(part))
}
