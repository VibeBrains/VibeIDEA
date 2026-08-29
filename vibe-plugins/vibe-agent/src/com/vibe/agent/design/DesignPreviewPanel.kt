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

  /**
   * The click channel of the overlay. Created ONCE and kept: a per-draw query would leak a handler
   * on every measurement, and the page would end up calling a function that no longer exists.
   */
  private val pickQuery: JBCefJSQuery? = browser?.let { JBCefJSQuery.create(it as com.intellij.ui.jcef.JBCefBrowserBase) }

  init {
    border = JBUI.Borders.empty(6)
    val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
      isOpaque = false
      add(JBLabel(t("design.label.address")))
      add(urlField)
      add(ActionLink(t("design.action.open")) { open() })
      add(ActionLink(t("design.action.measure")) { measure() })
      add(ActionLink(t("design.action.overlay")) { toggleOverlay() })
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
             (finding.acceptedReason?.let { " · причина: $it" } ?: "") +
             " · " + if (finding.viewport == Viewport.MOBILE) t("design.viewport.mobile") else t("design.viewport.desktop")))
    add(hint(finding.why))
  }

  override fun dispose() {}

  private companion object {
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
