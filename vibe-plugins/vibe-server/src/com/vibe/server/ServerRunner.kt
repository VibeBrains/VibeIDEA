// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.server

import com.vibe.agent.i18n.VibeI18n.t

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
  /**
   * Asked when the port is already taken; null means «спросить некого» — then the conflict is
   * only announced, exactly as before, and the entry starts anyway.
   *
   * A callback rather than a dialog here: the runner must stay usable from a test and from a
   * headless run, and a class that pops up a window cannot be either.
   */
  private val onPortConflict: ((ServerEntry, Int, List<Long>) -> PortConflict.Choice)? = null,
) {
  private val processes = ConcurrentHashMap<String, Process>()
  private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

  fun startAll(entries: List<ServerEntry>) {
    val (waves, excluded) = ServersFile.planStartOrder(entries)
    excluded.forEach { (id, reason) -> onStatus(id, ServerStatus.EXCLUDED, reason) }
    for (wave in waves) {
      val results = wave.map { e -> e to startEntryBlocking(e) }
      if (results.any { !it.second && it.first.kind == "task" }) {
        onLog("stack", t("servers.wave.taskFailed"))
        break
      }
      if (results.any { !it.second }) {
        onLog("stack", t("servers.wave.someFailed"))
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
      onStatus(e.id, ServerStatus.STARTING, t("servers.skipIf.probe"))
      if (runShort(e, e.skipIf) == 0) {
        onStatus(e.id, ServerStatus.SKIPPED, t("servers.skipIf.done"))
        return true
      }
    }
    onStatus(e.id, ServerStatus.STARTING, null)
    // Asked BEFORE starting: a framework that finds its port busy quietly moves to the next one,
    // and a server running somewhere the configuration does not name produces an afternoon of
    // «почему на телефоне ничего нет». Better to say it now than to be helpful and wrong.
    var sessionPort: Int? = null
    e.port?.let { port ->
      if (isPortBusy(port)) {
        val owners = PortConflict.parsePids(runCatching { readOwners(port) }.getOrDefault(""))
          .filter { PortConflict.isSafeToKill(it, ProcessHandle.current().pid()) }
        onLog(e.id, t("servers.portBusy", "port" to port,
                      "owners" to (owners.joinToString(", ").ifEmpty { t("servers.portOwnerUnknown") })))
        when (onPortConflict?.invoke(e, port, owners)) {
          PortConflict.Choice.FREE_PORT -> {
            killOwners(owners)
            // Verified rather than assumed: a process that ignored the signal still holds the port,
            // and starting on top of it would produce the silent move to another port we exist to
            // prevent.
            if (waitUntil(System.currentTimeMillis() + FREE_PORT_GRACE_MS) { !isPortBusy(port) }) {
              onLog(e.id, t("servers.portFreed", "port" to port))
            }
            else {
              onStatus(e.id, ServerStatus.FAILED, t("servers.portStillBusy", "port" to port))
              return false
            }
          }
          PortConflict.Choice.SESSION_PORT -> {
            // For THIS session only: the port named in the configuration is not touched, because a
            // tool that edits the project's config to get past its own warning is worse than the
            // warning.
            val free = PortConflict.sessionPort(port, isFree = { candidate -> !isPortBusy(candidate) })
            if (free == null) {
              onStatus(e.id, ServerStatus.FAILED, t("servers.noFreePort", "port" to port))
              return false
            }
            sessionPort = free
            onLog(e.id, t("servers.sessionPort", "port" to free, "configured" to port))
          }
          PortConflict.Choice.CANCEL -> {
            onStatus(e.id, ServerStatus.SKIPPED, t("servers.portCancelled", "port" to port))
            return false
          }
          null -> Unit   // nobody to ask: say it and start anyway, as before
        }
      }
    }
    val process = try { spawn(e, e.command, sessionPort) }
    catch (ex: Exception) {
      onStatus(e.id, ServerStatus.FAILED, t("servers.spawnFailed", "reason" to ex.message))
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
        onStatus(e.id, if (code == 0) ServerStatus.STOPPED else ServerStatus.FAILED, t("servers.processExited", "code" to code))
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
      onStatus(e.id, ServerStatus.FAILED, t("servers.notReady", "timeout" to e.readyTimeoutMs, "check" to e.effectiveReadyCheck))
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
      // Descendants first: a dev-server is usually a shell that spawned node, and killing only the
      // shell leaves the node holding the port — the zombie everyone meets on the second start.
      p.descendants().forEach { it.destroy() }
      p.destroy()
      if (!p.waitFor(STOP_GRACE_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
        p.descendants().forEach { it.destroyForcibly() }
        p.destroyForcibly()
      }
    }
    e.stopCommand?.let { cmd ->
      // best-effort: failure is not fatal
      runCatching { runShort(e, cmd) }
    }
  }

  /** A TCP connect, not a bind: binding to check would itself take the port for a moment. */
  private fun isPortBusy(port: Int): Boolean = runCatching {
    Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 300); true }
  }.getOrDefault(false)

  private fun readOwners(port: Int): String {
    val process = ProcessBuilder(PortConflict.ownerCommand(port)).redirectErrorStream(true).start()
    val text = process.inputStream.bufferedReader().readText()
    process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
    return text
  }

  /** How long a killed owner is given to actually let go of the port. */
  private val FREE_PORT_GRACE_MS = 3_000L

  /** How long a polite stop is given before it becomes an impolite one. */
  private val STOP_GRACE_MS = 3_000L

  /**
   * Kills the holders, descendants first.
   *
   * A dev-server is usually a shell that spawned node: killing only the shell leaves node holding
   * the port — the zombie everyone meets on the second start.
   */
  private fun killOwners(owners: List<Long>) {
    for (pid in owners) {
      val handle = ProcessHandle.of(pid).orElse(null) ?: continue
      handle.descendants().forEach { it.destroy() }
      handle.destroy()
    }
  }

  private fun spawn(e: ServerEntry, command: String, sessionPort: Int? = null): Process {
    val pb = ProcessBuilder("/bin/sh", "-c", command)
    pb.directory(File(projectBase, e.dir ?: "."))
    pb.redirectErrorStream(true)
    val env = pb.environment()
    // The session port travels as PORT, the variable every dev-server framework reads. Set BEFORE
    // the entry's own env file, so a project that pins PORT itself still wins — its file is a
    // decision, ours is a workaround.
    sessionPort?.let { env["PORT"] = it.toString() }
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
