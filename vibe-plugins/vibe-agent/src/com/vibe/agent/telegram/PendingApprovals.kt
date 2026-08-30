// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.telegram

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * Permission requests that can be answered from somewhere other than the keyboard.
 *
 * A destructive command stops the run with a modal dialog, and that is right — but it also means a
 * long unattended run silently waits for a person who left the room. With the bridge running, the
 * same question goes to the phone, and whichever side answers first wins.
 *
 * There is deliberately NO timeout. A refusal by timeout would mean the phone decides differently
 * from the keyboard depending on how fast someone walked back, and «оно само отменилось» is the
 * worst possible explanation for a stopped run.
 */
object PendingApprovals {
  data class Request(val id: String, val description: String, val answer: CompletableFuture<Boolean>)

  private val requests = ConcurrentHashMap<String, Request>()
  private var counter = 0

  @Synchronized
  fun open(description: String): Request {
    val id = "ask-" + (++counter)
    val request = Request(id, description, CompletableFuture())
    requests[id] = request
    return request
  }

  /** Answers a request; returns false when it is unknown or already decided elsewhere. */
  fun resolve(id: String, approved: Boolean): Boolean {
    val request = requests.remove(id) ?: return false
    return request.answer.complete(approved)
  }

  /** Called when the dialog answered first: the phone must stop waiting for a decided question. */
  fun close(id: String) {
    requests.remove(id)
  }

  fun pending(): List<Request> = requests.values.toList()

  fun isPending(id: String): Boolean = requests.containsKey(id)
}
