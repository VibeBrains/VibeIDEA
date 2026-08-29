// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.design

import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery

/**
 * Runs the collector inside the page and brings its answer back.
 *
 * JCEF evaluates JavaScript one way (fire and forget) and returns values another (a query the page
 * calls). So the collector's result is handed to a generated query function rather than returned:
 * there is no synchronous "evaluate and give me the value" in this API, and pretending otherwise
 * ends in a silent null.
 */
object DesignBridge {
  /** Snapshots of a large page run into hundreds of kilobytes — chunking is not needed, but a cap is. */
  private const val MAX_RESULT_CHARS = 8 * 1024 * 1024

  fun evaluate(browser: JBCefBrowser, script: String, onResult: (String?) -> Unit) {
    val query = JBCefJSQuery.create(browser as com.intellij.ui.jcef.JBCefBrowserBase)
    query.addHandler { text ->
      // One-shot: the handler is removed with the query as soon as the answer arrives, otherwise a
      // second measurement would deliver its result to every previous listener as well.
      try {
        onResult(text?.takeIf { it.isNotBlank() && it.length <= MAX_RESULT_CHARS })
      }
      finally {
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater { query.dispose() }
      }
      null
    }
    val wrapped = """
      (function () {
        try {
          var result = $script;
          ${query.inject("result")}
        } catch (e) {
          ${query.inject("''")}
        }
      })();
    """.trimIndent()
    browser.cefBrowser.executeJavaScript(wrapped, browser.cefBrowser.url, 0)
  }
}
