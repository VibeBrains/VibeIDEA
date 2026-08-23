// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.server

import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.PatternSyntaxException

enum class ServerStatus { IDLE, STARTING, RUNNING, STOPPED, FAILED, EXCLUDED, SKIPPED, DONE }

/**
 * Runs the declared stack: waves are sequential, entries inside a wave start in
 * parallel; dependents wait for READINESS (not mere spawn). A task must exit 0
 * before its dependents start. "A spawned process is not a ready process".
 * Commands run through the system shell — that is the documented contract.
 */
class ServerRunner(
  private val projectBase: String,
  private val onStatus: (String, ServerStatus, String?) -> Unit,
  private val onLog: (String, String) -> Unit,
) {
  private val processes = ConcurrentHashMap<String, Process>()
  private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

  fun startAll(entries: List<ServerEntry>) {
    val (waves, excluded) = ServersFile.planStartOrder(entries)
    excluded.forEach { (id, reason) -> onStatus(id, ServerStatus.EXCLUDED, reason) }
    for (wave in waves) {
      val results = wave.map { e -> e to startEntryBlocking(e) }
      if (results.any { !it.second && it.first.kind == "task" }) {
        onLog("stack", "волна остановлена: task-предусловие провалилось; зависимые не стартуют")
        break
      }
      if (results.any { !it.second }) {
        onLog("stack", "в волне есть провалы: их зависимые не стартуют")
        // remaining waves that depend on failed ids will fail their readiness — stop conservatively
        break
      }
    }
  }

  fun startOne(entries: List<ServerEntry>, id: String) {
    startAll(ServersFile.selectWithDependencies(entries, id))
  }

  /** Returns true when the entry is READY (or skipped). Blocking; call from a pooled thread. */
  private fun startEntryBlocking(e: ServerEntry): Boolean {
    if (e.skipIf != null) {
      onStatus(e.id, ServerStatus.STARTING, "skipIf-проба")
      if (runShort(e, e.skipIf) == 0) {
        onStatus(e.id, ServerStatus.SKIPPED, "skipIf: уже сделано")
        return true
      }
    }
    onStatus(e.id, ServerStatus.STARTING, null)
    val process = try { spawn(e, e.command) }
    catch (ex: Exception) {
      onStatus(e.id, ServerStatus.FAILED, "запуск: ${ex.message}")
      return false
    }
    processes[e.id] = process
    val readyByLog = java.util.concurrent.CompletableFuture<Boolean>()
    Thread({
      process.inputStream.bufferedReader().forEachLine { line ->
        onLog(e.id, line)
        val pattern = e.readyPattern
        if (e.effectiveReadyCheck == "log" && pattern != null && !readyByLog.isDone) {
          try { if (Regex(pattern).containsMatchIn(line)) readyByLog.complete(true) }
          catch (_: PatternSyntaxException) { readyByLog.complete(false) }
        }
      }
    }, "vibe-server-${e.id}-out").apply { isDaemon = true }.start()
    Thread({
      val code = process.waitFor()
      processes.remove(e.id, process)
      if (e.kind == "service") {
        // Own stop sets STOPPED before killing, so an unexpected death is distinguishable.
        onStatus(e.id, if (code == 0) ServerStatus.STOPPED else ServerStatus.FAILED, "процесс завершился (код $code)")
      }
    }, "vibe-server-${e.id}-exit").apply { isDaemon = true }.start()

    val deadline = System.currentTimeMillis() + e.readyTimeoutMs
    val ready: Boolean = when (e.effectiveReadyCheck) {
      "spawn" -> true
      "exit" -> {
        val finished = process.waitFor(e.readyTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        finished && process.exitValue() == 0
      }
      "log" -> try { readyByLog.get(e.readyTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: Exception) { false }
      "http" -> waitUntil(deadline) { httpReady(e) }
      else -> waitUntil(deadline) { portReady(e) }   // "port"
    }
    if (ready) {
      onStatus(e.id, if (e.kind == "task") ServerStatus.DONE else ServerStatus.RUNNING, null)
    }
    else {
      onStatus(e.id, ServerStatus.FAILED, "не готов за ${e.readyTimeoutMs} мс (${e.effectiveReadyCheck})")
    }
    return ready
  }

  fun stopAll(entries: List<ServerEntry>) {
    entries.forEach { stopEntry(it) }
  }

  fun stopEntry(e: ServerEntry) {
    val p = processes.remove(e.id)
    if (p != null) {
      onStatus(e.id, ServerStatus.STOPPED, null) // before killing: expected death
      p.destroy()
    }
    e.stopCommand?.let { cmd ->
      // best-effort: failure is not fatal
      runCatching { runShort(e, cmd) }
    }
  }

  private fun spawn(e: ServerEntry, command: String): Process {
    val pb = ProcessBuilder("/bin/sh", "-c", command)
    pb.directory(File(projectBase, e.dir ?: "."))
    pb.redirectErrorStream(true)
    val env = pb.environment()
    if (e.pathPrepend.isNotEmpty()) {
      env["PATH"] = e.pathPrepend.joinToString(File.pathSeparator) + File.pathSeparator + (env["PATH"] ?: "")
    }
    e.envFile?.let { ef ->
      val f = Path.of(projectBase, e.dir ?: ".", ef)
      if (Files.isRegularFile(f)) {
        for (line in Files.readAllLines(f)) {
          val t = line.trim()
          if (t.isEmpty() || t.startsWith("#")) continue
          val eq = t.indexOf('=')
          if (eq > 0) env[t.substring(0, eq).trim()] = t.substring(eq + 1).trim().removeSurrounding("\"")
        }
      }
    }
    env.putAll(e.env)
    return pb.start()
  }

  private fun runShort(e: ServerEntry, command: String): Int {
    val p = spawn(e, command)
    Thread({ p.inputStream.bufferedReader().forEachLine { onLog("${e.id}::probe", it) } }, "vibe-server-probe").apply { isDaemon = true }.start()
    return if (p.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)) p.exitValue() else { p.destroy(); -1 }
  }

  private fun waitUntil(deadlineMs: Long, probe: () -> Boolean): Boolean {
    while (System.currentTimeMillis() < deadlineMs) {
      if (probe()) return true
      Thread.sleep(500)
    }
    return false
  }

  private fun portReady(e: ServerEntry): Boolean {
    val port = e.port ?: return false
    return try {
      Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 1000); true }
    }
    catch (_: Exception) { false }
  }

  private fun httpReady(e: ServerEntry): Boolean {
    val port = e.port ?: return false
    return try {
      val r = http.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port${e.readyPath}")).timeout(Duration.ofSeconds(3)).GET().build(),
        HttpResponse.BodyHandlers.discarding(),
      )
      r.statusCode() < 500
    }
    catch (_: Exception) { false }
  }
}
