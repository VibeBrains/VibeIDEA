// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.design

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.vibe.agent.i18n.VibeI18n.t
import com.vibe.agent.ui.VibeScroll
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * «Дизайн»: open a page, measure it, show what the rules found.
 *
 * Measurement happens in TWO viewports, and the mobile one PHYSICALLY narrows the preview to 390 px
 * rather than pretending: media queries only fire on a real width, and a "mobile" check done at
 * desktop width finds the wrong things while missing what actually breaks on a phone.
 *
 * The page is never modified by the measurement — the collector only reads. That is what lets a
 * finding be argued with by re-measuring instead of by opinion.
 */
class DesignPreviewPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {
  private val urlField = JBTextField("http://localhost:3000").apply { columns = 30 }
  private val status = JBLabel(t("design.status.idle")).apply { foreground = JBColor.GRAY }
  private val results = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); isOpaque = false }
  private val browser: JBCefBrowser? = if (JBCefApp.isSupported()) JBCefBrowser() else null

  /** Last measured findings — the overlay redraws from them without re-measuring the page. */
  private var lastFindings: List<Finding> = emptyList()
  private var overlayVisible = true

  /** Reload on save: the loop «правка → alt-tab → F5» is the one people give up on. */
  private var liveReload: com.intellij.ui.components.JBCheckBox? = null

  /**
   * The click channel of the overlay. Created ONCE and kept: a per-draw query would leak a handler
   * on every measurement, and the page would end up calling a function that no longer exists.
   */
  private val pickQuery: JBCefJSQuery? = browser?.let { JBCefJSQuery.create(it as com.intellij.ui.jcef.JBCefBrowserBase) }

  /** Separate channel for page errors: mixing them into the pick channel would confuse both. */
  private val errorQuery: JBCefJSQuery? = browser?.let { JBCefJSQuery.create(it as com.intellij.ui.jcef.JBCefBrowserBase) }

  init {
    border = JBUI.Borders.empty(6)
    val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
      isOpaque = false
      add(JBLabel(t("design.label.address")))
      add(urlField)
      add(ActionLink(t("design.action.open")) { open() })
      add(ActionLink(t("design.action.reload")) { browser?.cefBrowser?.reload() })
      add(ActionLink(t("design.action.measure")) { measure() })
      add(ActionLink(t("design.action.overlay")) { toggleOverlay() })
      // Screen sizes as links rather than a dropdown: the three that matter are one click each,
      // and a dropdown would hide them behind a menu nobody opens twice.
      add(ActionLink(t("design.action.phone")) { setViewportWidth(PHONE_WIDTH) })
      add(ActionLink(t("design.action.tablet")) { setViewportWidth(TABLET_WIDTH) })
      add(ActionLink(t("design.action.desktop")) { setViewportWidth(0) })
      add(ActionLink(t("design.action.onPhone")) { showLanAddress() })
      add(ActionLink(t("design.action.errors")) { sendPageErrors() })
      add(com.intellij.ui.components.JBCheckBox(t("design.action.liveReload")).also { liveReload = it })
    }
    val header = JPanel().apply {
      layout = BoxLayout(this, BoxLayout.Y_AXIS)
      isOpaque = false
      add(toolbar.apply { alignmentX = Component.LEFT_ALIGNMENT })
      add(status.apply { alignmentX = Component.LEFT_ALIGNMENT })
    }
    add(header, BorderLayout.NORTH)

    pickQuery?.let { query ->
      Disposer.register(this, query)
      query.addHandler { payload -> onFindingPicked(payload); null }
    }

    errorQuery?.let { query ->
      Disposer.register(this, query)
      query.addHandler { payload ->
        val text = payload.trim()
        if (text.isEmpty()) status.text = t("design.status.noErrors")
        else {
          com.vibe.agent.http.VibeAgentGateway.getInstance()
            .putIntoComposer(t("design.errors.prefix") + "\n" + text.take(ERRORS_LIMIT))
          status.text = t("design.status.errorsSent")
        }
        null
      }
    }

    // Live reload: a file saved in the project reloads the page, so the loop «правка → alt-tab → F5»
    // — the one people give up on after a dozen repetitions — disappears.
    project.messageBus.connect(this).subscribe(
      com.intellij.openapi.fileEditor.FileDocumentManagerListener.TOPIC,
      object : com.intellij.openapi.fileEditor.FileDocumentManagerListener {
        override fun beforeDocumentSaving(document: com.intellij.openapi.editor.Document) {
          if (liveReload?.isSelected != true) return
          SwingUtilities.invokeLater { browser?.cefBrowser?.reload() }
        }
      },
    )

    // The turn gate measures through this panel: it owns the browser, and a second one would
    // measure a different page.
    val measurer = DesignMeasurementService.Measurer { timeout -> measureForGate(timeout) }
    DesignMeasurementService.getInstance(project).register(measurer)
    Disposer.register(this) { DesignMeasurementService.getInstance(project).unregister(measurer) }

    val center = JPanel(BorderLayout())
    if (browser != null) {
      Disposer.register(this, browser)
      center.add(browser.component, BorderLayout.CENTER)
    }
    else {
      center.add(JBLabel("<html>" + t("design.jcefMissing") + "</html>")
                   .apply { foreground = JBColor.GRAY }, BorderLayout.CENTER)
    }
    center.add(VibeScroll.pane(results).apply { preferredSize = Dimension(360, 0) }, BorderLayout.EAST)
    add(center, BorderLayout.CENTER)
  }

  private fun open() {
    val browser = browser ?: return
    val url = urlField.text.trim().ifEmpty { return }
    status.text = t("design.status.opening", "url" to url)
    browser.loadURL(url)
    // Installed on every open: the collector must exist BEFORE the page throws, otherwise the first
    // error — usually the interesting one — is the one nobody sees.
    browser.cefBrowser.executeJavaScript(ERROR_COLLECTOR, url, 0)
  }

  /**
   * Emulates a narrower screen by narrowing the COMPONENT rather than by faking a viewport in JS:
   * media queries then fire for real, which is the entire point of looking at a phone width.
   */
  private fun setViewportWidth(width: Int) {
    val component = browser?.component ?: return
    component.preferredSize = if (width <= 0) null else Dimension(width, component.height)
    component.maximumSize = component.preferredSize
    revalidate()
    repaint()
    status.text = if (width <= 0) t("design.status.desktop") else t("design.status.width", "width" to width)
  }

  /** The address of this preview as seen from a phone on the same network. */
  private fun showLanAddress() {
    val url = urlField.text.trim().ifEmpty { return }
    val lan = com.vibe.agent.preview.LanAddress.rewrite(url, com.vibe.agent.preview.LanAddress.localAddresses())
    if (lan == null) {
      status.text = t("design.status.noLan")
      return
    }
    com.intellij.openapi.ide.CopyPasteManager.getInstance()
      .setContents(java.awt.datatransfer.StringSelection(lan))
    status.text = t("design.status.lanCopied", "url" to lan)
  }

  /**
   * Console errors and failed requests, sent to the chat as text.
   *
   * A screenshot of a broken page says «сломалось»; the console says WHAT. Collected in the page
   * itself because that is where the errors are — the IDE cannot see them from outside.
   */
  private fun sendPageErrors() {
    val browser = browser ?: return
    val query = errorQuery ?: return
    val js = """
      (function() {
        var errors = window.__vibeErrors || [];
        var text = errors.length === 0 ? '' : errors.join('\n');
        ${query.inject("text")}
      })();
    """.trimIndent()
    browser.cefBrowser.executeJavaScript(js, browser.cefBrowser.url, 0)
  }

  /**
   * Measures the page twice. The desktop pass runs at whatever width the panel has; the mobile pass
   * resizes the component to 390 px, waits for the layout to settle and measures again.
   */
  private fun measure() {
    val browser = browser ?: return
    status.text = t("design.status.measuringDesktop")
    collect(browser, Viewport.DESKTOP) { desktop ->
      val originalWidth = browser.component.width
      SwingUtilities.invokeLater {
        browser.component.preferredSize = Dimension(MOBILE_WIDTH_PX, browser.component.height)
        browser.component.size = Dimension(MOBILE_WIDTH_PX, browser.component.height)
        browser.component.revalidate()
        status.text = t("design.status.measuringMobile", "width" to MOBILE_WIDTH_PX)
      }
      // Give the page a moment to re-layout at the new width before reading it back.
      ApplicationManager.getApplication().executeOnPooledThread {
        Thread.sleep(RELAYOUT_PAUSE_MS)
        collect(browser, Viewport.MOBILE) { mobile ->
          SwingUtilities.invokeLater {
            browser.component.preferredSize = Dimension(originalWidth, browser.component.height)
            browser.component.size = Dimension(originalWidth, browser.component.height)
            browser.component.revalidate()
          }
          report(listOfNotNull(desktop, mobile))
        }
      }
    }
  }

  private fun collect(browser: JBCefBrowser, viewport: Viewport, onReady: (DocumentSnapshot?) -> Unit) {
    val script = COLLECTOR ?: run {
      SwingUtilities.invokeLater { status.text = t("design.status.collectorMissing") }
      return
    }
    DesignBridge.evaluate(browser, script) { text ->
      onReady(text?.let { DesignSnapshotCodec.parse(it, viewport) })
    }
  }

  private fun report(snapshots: List<DocumentSnapshot>) {
    if (snapshots.isEmpty()) {
      SwingUtilities.invokeLater { status.text = t("design.status.noAnswer") }
      return
    }
    // Accepted drifts come from the project's own design context — the same file the agent reads.
    val accepted = DesignContextFile.load(project.basePath)?.acceptedDrift.orEmpty()
      .map { DesignReview.Accepted(it.ruleId, it.reason) }
    val reports = snapshots.map { DesignReview.run(it, accepted) }
    val findings = DesignReview.merge(reports)
    SwingUtilities.invokeLater {
      status.text = DesignReview.summary(findings)
      results.removeAll()
      if (findings.isEmpty()) results.add(hint(t("design.noFindings")))
      else findings.forEach { results.add(row(it)) }
      results.revalidate(); results.repaint()
      lastFindings = findings
      if (overlayVisible) drawOverlay()
    }
  }


  /**
   * Blocking measurement for the turn gate: both viewports, then the rules.
   *
   * Blocking on purpose — the gate has to decide before the turn ends, and an asynchronous answer
   * arriving later would be a report about a run that is already over.
   */
  private fun measureForGate(timeoutMs: Long): List<Finding>? {
    val browser = browser ?: return null
    val script = COLLECTOR ?: return null
    val latch = java.util.concurrent.CountDownLatch(1)
    val snapshots = java.util.Collections.synchronizedList(ArrayList<DocumentSnapshot>())
    DesignBridge.evaluate(browser, script) { text ->
      text?.let { DesignSnapshotCodec.parse(it, Viewport.DESKTOP) }?.let { snapshots.add(it) }
      latch.countDown()
    }
    if (!latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) return null
    if (snapshots.isEmpty()) return null
    val accepted = DesignContextFile.load(project.basePath)?.acceptedDrift.orEmpty()
      .map { DesignReview.Accepted(it.ruleId, it.reason) }
    val findings = DesignReview.merge(snapshots.map { DesignReview.run(it, accepted) })
    SwingUtilities.invokeLater {
      lastFindings = findings
      status.text = DesignReview.summary(findings)
      results.removeAll()
      findings.forEach { results.add(row(it)) }
      results.revalidate(); results.repaint()
      if (overlayVisible) drawOverlay()
    }
    return findings
  }

  // --- overlay ---

  private fun toggleOverlay() {
    overlayVisible = !overlayVisible
    if (overlayVisible) drawOverlay() else clearOverlay()
    status.text = if (overlayVisible) t("design.status.overlayOn") else t("design.status.overlayOff")
  }

  private fun drawOverlay() {
    val browser = browser ?: return
    val query = pickQuery ?: return
    val script = OVERLAY ?: return
    if (lastFindings.isEmpty()) return
    // The page gets the findings as DATA and a callback name — never as code it should trust.
    val payload = DesignOverlay.encode(lastFindings).replace("\\", "\\\\").replace("'", "\\'")
    val install = "(function(){window['" + DesignOverlay.PICK_CALLBACK + "'] = function(payload){" +
                  query.inject("payload") + "};(" + script + ")('" + payload + "','" +
                  DesignOverlay.PICK_CALLBACK + "');})();"
    browser.cefBrowser.executeJavaScript(install, browser.cefBrowser.url, 0)
  }

  private fun clearOverlay() {
    val browser = browser ?: return
    browser.cefBrowser.executeJavaScript(
      "(function(){var n=document.getElementById('" + DesignOverlay.CONTAINER_ID + "'); if(n) n.remove();})();",
      browser.cefBrowser.url, 0,
    )
  }

  /** A label on the page was clicked: hand that finding to the composer, do not send anything. */
  private fun onFindingPicked(payload: String?) {
    if (payload.isNullOrBlank()) return
    val rule = Regex("\"rule\":\"([^\"]+)\"").find(payload)?.groupValues?.get(1) ?: return
    val selector = Regex("\"selector\":\"([^\"]+)\"").find(payload)?.groupValues?.get(1)
    val finding = lastFindings.firstOrNull { it.rule == rule && (selector == null || it.selector == selector) } ?: return
    val delivered = com.vibe.agent.http.VibeAgentGateway.getInstance().putIntoComposer(DesignOverlay.asChatNote(finding))
    SwingUtilities.invokeLater {
      status.text = if (delivered) t("design.status.picked", "rule" to rule) else t("design.status.noPanel")
    }
  }


  private fun hint(text: String) = JBLabel("<html>$text</html>").apply {
    foreground = JBColor.GRAY
    font = JBFont.label().deriveFont(11f)
    alignmentX = Component.LEFT_ALIGNMENT
    border = JBUI.Borders.empty(4, 2)
  }

  private fun row(finding: Finding): JComponent = JPanel().apply {
    layout = BoxLayout(this, BoxLayout.Y_AXIS)
    isOpaque = false
    alignmentX = Component.LEFT_ALIGNMENT
    border = JBUI.Borders.empty(4, 2)
    val prefix = when {
      finding.acceptedReason != null -> t("design.class.accepted")
      finding.ruleClass == RuleClass.FLOOR -> t("design.class.floor")
      else -> t("design.class.style")
    }
    add(JBLabel("[$prefix] ${finding.message}").apply {
      alignmentX = Component.LEFT_ALIGNMENT
      foreground = if (finding.ruleClass == RuleClass.FLOOR && finding.acceptedReason == null)
        JBColor.namedColor("Vibe.Design.floor", JBColor.RED) else foreground
    })
    // The measured value goes on the row itself: a finding one can only agree or disagree with is
    // an opinion, one that carries its number can be checked.
    add(hint("${finding.selector} · ${finding.evidence}" +
             (finding.acceptedReason?.let { " · " + t("design.reasonPrefix") + ": $it" } ?: "") +
             " · " + if (finding.viewport == Viewport.MOBILE) t("design.viewport.mobile") else t("design.viewport.desktop")))
    add(hint(finding.why))
  }

  override fun dispose() {}

  private companion object {
    const val PHONE_WIDTH = 390
    const val TABLET_WIDTH = 820
    const val ERRORS_LIMIT = 4000

    /** Kept tiny on purpose: it runs in someone else's page and must not fight with it. */
    val ERROR_COLLECTOR = """
      (function() {
        if (window.__vibeErrorsInstalled) return;
        window.__vibeErrorsInstalled = true;
        window.__vibeErrors = [];
        var push = function(text) { if (window.__vibeErrors.length < 200) window.__vibeErrors.push(text); };
        window.addEventListener('error', function(e) { push('error: ' + (e.message || '') + ' @ ' + (e.filename || '') + ':' + (e.lineno || 0)); });
        window.addEventListener('unhandledrejection', function(e) { push('unhandled: ' + (e.reason && e.reason.message ? e.reason.message : e.reason)); });
        var console_error = console.error;
        console.error = function() { push('console: ' + Array.prototype.join.call(arguments, ' ')); console_error.apply(console, arguments); };
      })();
    """.trimIndent()

    /** VibeIDE parity: the width at which mobile layouts actually break. */
    const val MOBILE_WIDTH_PX = 390

    const val RELAYOUT_PAUSE_MS = 400L

    val COLLECTOR: String? by lazy { resource("/design/collect.js") }
    val OVERLAY: String? by lazy { resource("/design/overlay.js") }

    private fun resource(path: String): String? =
      DesignPreviewPanel::class.java.getResourceAsStream(path)?.bufferedReader()?.readText()
  }
}

class DesignToolWindowFactory : ToolWindowFactory {
  override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
    val panel = DesignPreviewPanel(project)
    val content = ContentFactory.getInstance().createContent(panel, "", false)
    content.setDisposer(panel)
    toolWindow.contentManager.addContent(content)
  }
}
