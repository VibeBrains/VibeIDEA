// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.providers.chatgpt

import com.sun.net.httpserver.HttpServer
import com.vibe.agent.i18n.VibeI18n.t
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

/**
 * The listener the browser comes back to after a ChatGPT sign-in: `127.0.0.1`, path `/auth/callback`
 *
 * Its own server and not the IDE's built-in one: the vendor accepts exactly `http://127.0.0.1:<port>/auth/callback`,
 * and the built-in server answers under `/api/<service>` only. It starts before the browser opens and stops after one
 * answer — a listener left open would take whatever comes to it next
 */
class ChatGptCallbackServer private constructor(private val server: HttpServer) : AutoCloseable {
  /** The query string the browser brought; completes once */
  val query = CompletableFuture<String>()

  val port: Int get() = server.address.port

  init {
    server.createContext(ChatGptOAuth.CALLBACK_PATH) { exchange ->
      val page = t("chatgpt.callback.page").toByteArray(Charsets.UTF_8)
      exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
      exchange.sendResponseHeaders(OK, page.size.toLong())
      exchange.responseBody.use { it.write(page) }
      query.complete(exchange.requestURI.rawQuery.orEmpty())
    }
    server.executor = Executors.newSingleThreadExecutor { r -> Thread(r, "vibe-chatgpt-callback").apply { isDaemon = true } }
    server.start()
  }

  override fun close() = server.stop(0)

  companion object {
    /** On the vendor's example port, or any free one when it is taken (Codex CLI signs in on the same) */
    fun start(): ChatGptCallbackServer {
      val loopback = InetAddress.getByName(LOOPBACK)
      val server = try {
        HttpServer.create(InetSocketAddress(loopback, ChatGptOAuth.PREFERRED_PORT), BACKLOG)
      }
      catch (_: BindException) {
        HttpServer.create(InetSocketAddress(loopback, 0), BACKLOG)
      }
      return ChatGptCallbackServer(server)
    }

    /** The address and not `localhost`: the vendor refuses the name in a redirect */
    private const val LOOPBACK = "127.0.0.1"
    private const val BACKLOG = 4
    private const val OK = 200
  }
}
