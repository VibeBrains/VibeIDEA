// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.preview

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.vibe.agent.i18n.VibeI18n.t
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Graphics
import java.awt.image.BufferedImage
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants

/** The preview address shown as a QR code, plus the text for the case where the camera is busy. */
class PhoneAddressDialog(project: Project, private val url: String) : DialogWrapper(project) {
  init {
    title = t("preview.phone.title")
    setOKButtonText(t("preview.phone.copy"))
    init()
  }

  override fun createCenterPanel(): JComponent {
    val panel = JPanel(BorderLayout(0, JBUI.scale(8)))
    val image = QrCode.image(url)
    val body = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    if (image != null) {
      body.add(QrView(image).apply { alignmentX = Component.CENTER_ALIGNMENT })
      body.add(Box.createVerticalStrut(JBUI.scale(8)))
    }
    body.add(JBLabel(url, SwingConstants.CENTER).apply { alignmentX = Component.CENTER_ALIGNMENT })
    body.add(Box.createVerticalStrut(JBUI.scale(4)))
    body.add(JBLabel(t("preview.phone.hint")).apply {
      alignmentX = Component.CENTER_ALIGNMENT
      foreground = com.intellij.ui.JBColor.GRAY
    })
    panel.add(body, BorderLayout.CENTER)
    return panel
  }

  /** Painted at the exact module grid: scaling a QR code with interpolation blurs it into a code that will not scan. */
  private class QrView(private val image: BufferedImage) : JComponent() {
    init {
      preferredSize = Dimension(image.width, image.height)
      maximumSize = preferredSize
    }

    override fun paintComponent(g: Graphics) {
      g.drawImage(image, 0, 0, null)
    }
  }
}
