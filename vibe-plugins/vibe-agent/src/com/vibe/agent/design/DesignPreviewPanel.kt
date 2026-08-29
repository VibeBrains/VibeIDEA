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
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
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
  private val status = JBLabel("Откройте страницу и нажмите «Замерить»").apply { foreground = JBColor.GRAY }
  private val results = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); isOpaque = false }
  private val browser: JBCefBrowser? = if (JBCefApp.isSupported()) JBCefBrowser() else null

  init {
    border = JBUI.Borders.empty(6)
    val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
      isOpaque = false
      add(JBLabel("Адрес:"))
      add(urlField)
      add(ActionLink("Открыть") { open() })
      add(ActionLink("Замерить") { measure() })
    }
    val header = JPanel().apply {
      layout = BoxLayout(this, BoxLayout.Y_AXIS)
      isOpaque = false
      add(toolbar.apply { alignmentX = Component.LEFT_ALIGNMENT })
      add(status.apply { alignmentX = Component.LEFT_ALIGNMENT })
    }
    add(header, BorderLayout.NORTH)

    val center = JPanel(BorderLayout())
    if (browser != null) {
      Disposer.register(this, browser)
      center.add(browser.component, BorderLayout.CENTER)
    }
    else {
      center.add(JBLabel("<html>Встроенный браузер (JCEF) недоступен в этой сборке — замерить страницу нечем.</html>")
                   .apply { foreground = JBColor.GRAY }, BorderLayout.CENTER)
    }
    center.add(VibeScroll.pane(results).apply { preferredSize = Dimension(360, 0) }, BorderLayout.EAST)
    add(center, BorderLayout.CENTER)
  }

  private fun open() {
    val browser = browser ?: return
    val url = urlField.text.trim().ifEmpty { return }
    status.text = "Открываю $url…"
    browser.loadURL(url)
  }

  /**
   * Measures the page twice. The desktop pass runs at whatever width the panel has; the mobile pass
   * resizes the component to 390 px, waits for the layout to settle and measures again.
   */
  private fun measure() {
    val browser = browser ?: return
    status.text = "Замер (десктоп)…"
    collect(browser, Viewport.DESKTOP) { desktop ->
      val originalWidth = browser.component.width
      SwingUtilities.invokeLater {
        browser.component.preferredSize = Dimension(MOBILE_WIDTH_PX, browser.component.height)
        browser.component.size = Dimension(MOBILE_WIDTH_PX, browser.component.height)
        browser.component.revalidate()
        status.text = "Замер (телефон, ${MOBILE_WIDTH_PX}px)…"
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
      SwingUtilities.invokeLater { status.text = "Сборщик снимка не найден в ресурсах плагина" }
      return
    }
    DesignBridge.evaluate(browser, script) { text ->
      onReady(text?.let { DesignSnapshotCodec.parse(it, viewport) })
    }
  }

  private fun report(snapshots: List<DocumentSnapshot>) {
    if (snapshots.isEmpty()) {
      SwingUtilities.invokeLater { status.text = "Страница не ответила: замер не состоялся" }
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
      if (findings.isEmpty()) results.add(hint("Находок нет."))
      else findings.forEach { results.add(row(it)) }
      results.revalidate(); results.repaint()
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
      finding.acceptedReason != null -> "принято"
      finding.ruleClass == RuleClass.FLOOR -> "пол"
      else -> "стиль"
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
             " · " + if (finding.viewport == Viewport.MOBILE) "телефон" else "десктоп"))
    add(hint(finding.why))
  }

  override fun dispose() {}

  private companion object {
    /** VibeIDE parity: the width at which mobile layouts actually break. */
    const val MOBILE_WIDTH_PX = 390

    const val RELAYOUT_PAUSE_MS = 400L

    val COLLECTOR: String? by lazy {
      DesignPreviewPanel::class.java.getResourceAsStream("/design/collect.js")?.bufferedReader()?.readText()
    }
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
