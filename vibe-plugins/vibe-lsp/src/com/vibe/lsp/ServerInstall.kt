// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp

/**
 * Running the install command FOR the person — with their click, in their terminal, in front of
 * their eyes.
 *
 * Making a developer copy a command out of a notice, find a terminal and paste it is three steps
 * where there should be one, and every one of them is a place to give up. But installing silently
 * is worse than the copying: this is somebody's machine, and a tool that puts things on it without
 * being asked has to be trusted far more than one that asks.
 *
 * So: one click, the command visible, the output visible, and no `sudo` in anything we offer — a
 * command that asks for a password in a terminal window the person did not open is the shape of a
 * bad surprise.
 */
object ServerInstall {
  /** Commands we refuse to run for the person, however they got into a spec. */
  private val REFUSED = listOf("sudo ", "rm ", "curl | sh", "| sh", "| bash")

  /**
   * Is this command safe to offer as a button?
   *
   * The list is short on purpose: the commands come from OUR catalogue, not from a config file, so
   * this is a guard against our own future carelessness rather than against an attacker.
   */
  fun isOfferable(command: String): Boolean {
    val text = command.trim()
    if (text.isEmpty()) return false
    return REFUSED.none { text.contains(it) }
  }

  /**
   * The command line as a shell invocation.
   *
   * Through a login shell on purpose: the commands we offer use `npm` and `curl`, and a GUI
   * application on macOS inherits neither the shell PATH nor nvm's — the very trap that makes
   * ServerBinaries probe well-known directories by hand.
   */
  fun shellCommand(command: String): List<String> = listOf("/bin/sh", "-lc", command)

  /** The part of the output worth showing when it failed: the end, where the reason usually is. */
  fun failureTail(output: String, limit: Int = 400): String =
    output.trim().takeLast(limit).ifEmpty { "" }
}
