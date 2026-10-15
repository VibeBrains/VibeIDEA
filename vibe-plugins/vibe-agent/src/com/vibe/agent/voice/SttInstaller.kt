// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.voice

import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Downloading the archives of a profile into a [SttModelStore], each one checked before it counts
 *
 * An archive is downloaded beside the store, its SHA256 compared with the catalogue's, unpacked into a staging folder,
 * checked for the files the catalogue lists, and only then moved into place in one step
 * So a folder of the store is either complete or absent: a download cut by the network, a wrong file from a mirror,
 * a disk that filled up halfway, all leave nothing a recognizer could try to load and crash on
 * The network is behind [Fetch], so the whole path runs in a test against bytes made there
 */
object SttInstaller {
  fun interface Fetch {
    /** Opens [url] and hands its body to [consume] while it is open; throws [IOException] when there is no answer worth reading */
    fun read(url: String, consume: (InputStream) -> Unit)
  }

  /** Where an installation stopped */
  enum class Stage { DOWNLOAD, CHECKSUM, UNPACK, FILES, DISK }

  sealed interface Outcome {
    data object Done : Outcome
    data object Cancelled : Outcome

    /** [archive] is the id of the archive that failed; [reason] is what the system said */
    data class Failed(val stage: Stage, val archive: String, val reason: String) : Outcome
  }

  private const val BUFFER_BYTES = 64 * 1024
  private const val DOWNLOAD_DIR = ".download"
  private const val STAGING_DIR = ".staging"
  private const val PART_SUFFIX = ".zip.part"

  /**
   * Installs [archives] one after another; those already in place are skipped and their bytes counted as done
   *
   * @param progress bytes done and bytes in all, called often; off the UI thread
   * @param cancelled read between reads of the network; true leaves the store as it was before the archive in hand
   */
  fun install(
    store: SttModelStore,
    archives: List<SttCatalog.Archive>,
    fetch: Fetch,
    progress: (done: Long, total: Long) -> Unit,
    cancelled: () -> Boolean,
  ): Outcome {
    val total = archives.sumOf { it.sizeBytes }
    var base = 0L
    for (archive in archives) {
      if (!store.installed(archive)) {
        val outcome = installOne(store, archive, fetch, { done -> progress(base + done, total) }, cancelled)
        if (outcome != Outcome.Done) return outcome
      }
      base += archive.sizeBytes
      progress(base, total)
    }
    return Outcome.Done
  }

  private fun installOne(
    store: SttModelStore,
    archive: SttCatalog.Archive,
    fetch: Fetch,
    progress: (Long) -> Unit,
    cancelled: () -> Boolean,
  ): Outcome {
    val part = store.root.resolve(DOWNLOAD_DIR).resolve(archive.id + PART_SUFFIX)
    val staging = store.root.resolve(STAGING_DIR).resolve(archive.id)
    try {
      try {
        Files.createDirectories(part.parent)
        Files.deleteIfExists(part)
      }
      catch (e: IOException) {
        return Outcome.Failed(Stage.DISK, archive.id, reasonOf(e))
      }
      download(archive, part, fetch, progress, cancelled)?.let { return it }
      try {
        deleteTree(staging)
        Files.createDirectories(staging)
        unpack(part, staging)
      }
      catch (e: IOException) {
        return Outcome.Failed(Stage.UNPACK, archive.id, reasonOf(e))
      }
      val unpacked = staging.resolve(archive.dir)
      val absent = archive.files.filterNot { Files.isRegularFile(unpacked.resolve(it)) }
      if (absent.isNotEmpty()) return Outcome.Failed(Stage.FILES, archive.id, absent.joinToString(", "))
      return try {
        val target = store.folderOf(archive)
        deleteTree(target)
        move(unpacked, target)
        Outcome.Done
      }
      catch (e: IOException) {
        Outcome.Failed(Stage.DISK, archive.id, reasonOf(e))
      }
    }
    finally {
      runCatching { Files.deleteIfExists(part) }
      runCatching { deleteTree(staging) }
    }
  }

  /** Null when [part] holds the archive with the right checksum */
  private fun download(
    archive: SttCatalog.Archive,
    part: Path,
    fetch: Fetch,
    progress: (Long) -> Unit,
    cancelled: () -> Boolean,
  ): Outcome? {
    val digest = MessageDigest.getInstance("SHA-256")
    var received = 0L
    var gaveUp = false
    try {
      fetch.read(archive.url) { body ->
        Files.newOutputStream(part).use { out ->
          val buffer = ByteArray(BUFFER_BYTES)
          while (!gaveUp) {
            if (cancelled()) {
              gaveUp = true
              break
            }
            val count = body.read(buffer)
            if (count < 0) break
            out.write(buffer, 0, count)
            digest.update(buffer, 0, count)
            received += count
            // A server that sends more than the catalogue says is not the file of the catalogue, and the bar must not pass 100
            progress(minOf(received, archive.sizeBytes))
          }
        }
      }
    }
    catch (e: IOException) {
      return Outcome.Failed(Stage.DOWNLOAD, archive.id, reasonOf(e))
    }
    if (gaveUp) return Outcome.Cancelled
    val sum = digest.digest().joinToString("") { "%02x".format(it) }
    if (received != archive.sizeBytes || sum != archive.sha256) {
      return Outcome.Failed(Stage.CHECKSUM, archive.id, "$received / ${archive.sizeBytes}, sha256 $sum")
    }
    return null
  }

  /** Unpacks [zip] into [into]; an entry that would land outside it is an archive that is not ours, and fails the whole */
  internal fun unpack(zip: Path, into: Path) {
    val root = into.toAbsolutePath().normalize()
    ZipInputStream(Files.newInputStream(zip)).use { input ->
      while (true) {
        val entry = input.nextEntry ?: break
        val target = root.resolve(entry.name).normalize()
        if (!target.startsWith(root) || target == root) throw IOException("entry outside the archive folder: ${entry.name}")
        if (entry.isDirectory) {
          Files.createDirectories(target)
        }
        else {
          Files.createDirectories(target.parent)
          Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
        }
      }
    }
  }

  private fun move(from: Path, to: Path) {
    Files.createDirectories(to.parent)
    try {
      Files.move(from, to, StandardCopyOption.ATOMIC_MOVE)
    }
    catch (_: AtomicMoveNotSupportedException) {
      Files.move(from, to)
    }
  }

  private fun deleteTree(path: Path) {
    if (!Files.exists(path)) return
    Files.walk(path).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
  }
}
