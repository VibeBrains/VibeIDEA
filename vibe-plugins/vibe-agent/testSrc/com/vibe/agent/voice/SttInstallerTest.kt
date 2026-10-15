// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.voice

import com.vibe.agent.voice.SttInstaller.Outcome
import com.vibe.agent.voice.SttInstaller.Stage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A download either leaves a complete, checked model in the folder or nothing at all */
class SttInstallerTest {
  private val root: Path = Files.createTempDirectory("vibe-stt-install-")
  private val store = SttModelStore(root)

  @AfterTest
  fun cleanUp() {
    root.toFile().deleteRecursively()
  }

  private fun zip(vararg entries: Pair<String, String>): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zip ->
      entries.forEach { (name, text) ->
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.toByteArray())
        zip.closeEntry()
      }
    }
    return out.toByteArray()
  }

  private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

  private fun archive(id: String, bytes: ByteArray, sha: String = sha(bytes), size: Long = bytes.size.toLong()) =
    SttCatalog.Archive(id, "dir-$id", "https://mirror/$id.zip", sha, size, listOf("model.onnx", "tokens.txt"), null)

  private val good = zip("dir-a/model.onnx" to "weights", "dir-a/tokens.txt" to "tokens", "dir-a/LICENSE" to "MIT")

  private fun fetchOf(vararg bodies: Pair<String, ByteArray>) =
    SttInstaller.Fetch { url, consume -> consume(ByteArrayInputStream(bodies.toMap().getValue(url))) }

  @Test
  fun `a good archive lands complete, its progress reaches the whole size, and nothing is left beside it`() {
    val a = archive("a", good)
    val progress = mutableListOf<Long>()
    val outcome = SttInstaller.install(store, listOf(a), fetchOf(a.url to good), { done, _ -> progress += done }) { false }
    assertEquals(Outcome.Done, outcome)
    assertTrue(store.installed(a))
    assertEquals("weights", Files.readString(store.folderOf(a).resolve("model.onnx")))
    assertEquals(a.sizeBytes, progress.last())
    assertEquals(listOf("dir-a"), Files.list(root).use { s -> s.map { it.fileName.toString() }.filter { !it.startsWith(".") }.toList() })
  }

  @Test
  fun `bytes that do not match the checksum are not unpacked`() {
    val a = archive("a", good, sha = "0".repeat(64))
    val outcome = SttInstaller.install(store, listOf(a), fetchOf(a.url to good), { _, _ -> }) { false }
    assertEquals(Stage.CHECKSUM, assertIs<Outcome.Failed>(outcome).stage)
    assertFalse(Files.exists(store.folderOf(a)))
  }

  @Test
  fun `a body shorter than the catalogue says is a broken download, even when the server called it complete`() {
    val a = archive("a", good, size = good.size + 1L)
    val outcome = SttInstaller.install(store, listOf(a), fetchOf(a.url to good), { _, _ -> }) { false }
    assertEquals(Stage.CHECKSUM, assertIs<Outcome.Failed>(outcome).stage)
  }

  @Test
  fun `an archive without the files the catalogue lists is refused, and the folder stays empty`() {
    val partial = zip("dir-a/model.onnx" to "weights")
    val a = archive("a", partial)
    val outcome = SttInstaller.install(store, listOf(a), fetchOf(a.url to partial), { _, _ -> }) { false }
    val failed = assertIs<Outcome.Failed>(outcome)
    assertEquals(Stage.FILES, failed.stage)
    assertEquals("tokens.txt", failed.reason)
    assertFalse(Files.exists(store.folderOf(a)))
  }

  @Test
  fun `an entry that would land outside the folder fails the whole archive`() {
    val evil = zip("dir-a/model.onnx" to "weights", "dir-a/tokens.txt" to "t", "../../escaped.txt" to "x")
    val a = archive("a", evil)
    val outcome = SttInstaller.install(store, listOf(a), fetchOf(a.url to evil), { _, _ -> }) { false }
    assertEquals(Stage.UNPACK, assertIs<Outcome.Failed>(outcome).stage)
    assertFalse(Files.exists(root.resolve("escaped.txt")))
    assertFalse(Files.exists(root.resolve(".staging").resolve("escaped.txt")))
    assertFalse(Files.exists(store.folderOf(a)))
  }

  @Test
  fun `the network failing is a download that stopped, named with its reason`() {
    val a = archive("a", good)
    val outcome = SttInstaller.install(store, listOf(a), { _, _ -> throw IOException("connection reset") }, { _, _ -> }) { false }
    val failed = assertIs<Outcome.Failed>(outcome)
    assertEquals(Stage.DOWNLOAD, failed.stage)
    assertEquals("connection reset", failed.reason)
  }

  @Test
  fun `a cancel stops the download and leaves no part of it`() {
    val a = archive("a", good)
    val outcome = SttInstaller.install(store, listOf(a), fetchOf(a.url to good), { _, _ -> }) { true }
    assertEquals(Outcome.Cancelled, outcome)
    assertFalse(Files.exists(store.folderOf(a)))
    assertFalse(Files.exists(root.resolve(".download").resolve("a.zip.part")))
  }

  @Test
  fun `an archive already in place is not downloaded again, and its bytes count as done`() {
    val a = archive("a", good)
    SttInstaller.install(store, listOf(a), fetchOf(a.url to good), { _, _ -> }) { false }
    val otherZip = zip("dir-b/model.onnx" to "w", "dir-b/tokens.txt" to "t")
    val b = archive("b", otherZip)
    val asked = mutableListOf<String>()
    val progress = mutableListOf<Pair<Long, Long>>()
    val fetch = SttInstaller.Fetch { url, consume -> asked += url; consume(ByteArrayInputStream(otherZip)) }
    assertEquals(Outcome.Done, SttInstaller.install(store, listOf(a, b), fetch, { done, total -> progress += done to total }) { false })
    assertEquals(listOf(b.url), asked)
    assertEquals(a.sizeBytes to a.sizeBytes + b.sizeBytes, progress.first())
    assertEquals(a.sizeBytes + b.sizeBytes, progress.last().first)
  }
}
