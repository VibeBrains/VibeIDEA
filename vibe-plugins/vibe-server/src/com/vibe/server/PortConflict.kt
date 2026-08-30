// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.server

/**
 * A port that is already taken, and the three honest ways out of it.
 *
 * The situation is ordinary: the previous dev-server did not die, or another project is running on
 * the same port. What is NOT acceptable is the usual framework behaviour — silently moving to the
 * next free port. The project's configuration names a port, deploys expect that port, and a server
 * that quietly runs somewhere else produces an afternoon of «почему на телефоне ничего нет».
 *
 * So the choice is given to the person, and the option that changes the project's configuration is
 * not among them: the port in the file stays what it is.
 */
object PortConflict {
  enum class Choice {
    /** Kill whatever holds the port and start ours there. */
    FREE_PORT,
    /** Run on another port FOR THIS SESSION ONLY — the configuration is not touched. */
    SESSION_PORT,
    CANCEL,
  }

  /** Recognises «порт занят» across the shapes tools report it in. */
  fun isPortTaken(message: String?): Boolean {
    val text = message?.lowercase() ?: return false
    return "eaddrinuse" in text || "address already in use" in text ||
      "port is already" in text || "адрес уже используется" in text
  }

  /**
   * The command that names the process holding a TCP port.
   *
   * `lsof` rather than a JVM socket probe: knowing the port is busy is useless, knowing WHOSE it is
   * is what lets a person decide whether killing it is safe.
   */
  fun ownerCommand(port: Int): List<String> = listOf("lsof", "-ti", "tcp:$port")

  /** Parses the pids `lsof -ti` prints, one per line. */
  fun parsePids(output: String): List<Long> =
    output.lines().mapNotNull { it.trim().toLongOrNull() }.distinct()

  /**
   * Is this process safe to kill?
   *
   * Never our own, and never pid 1: killing the init process is not a mistake anyone recovers from,
   * and a tool that can be talked into it should not be handed a port number by a config file.
   */
  fun isSafeToKill(pid: Long, ownPid: Long): Boolean = pid > 1 && pid != ownPid

  /** A free port for this session only — the configuration keeps its own. */
  fun sessionPort(configured: Int, isFree: (Int) -> Boolean, attempts: Int = MAX_ATTEMPTS): Int? {
    for (offset in 1..attempts) {
      val candidate = configured + offset
      if (candidate in 1..65535 && isFree(candidate)) return candidate
    }
    return null
  }

  const val MAX_ATTEMPTS = 20
}
