// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.ui.composer

import com.intellij.icons.AllIcons
import com.intellij.ui.AnimatedIcon
import com.vibe.agent.ui.VibeIcons
import com.vibe.agent.voice.VoiceFlow
import com.vibe.agent.voice.VoicePulse
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import javax.swing.Icon
import javax.swing.Timer

/**
 * What the microphone button looks like at each state of a dictation, so the person tells them apart without the mouse over it
 *
 * Idle is the grey microphone, opening and transcribing are a turning spinner, a finished text is a check mark for a moment,
 * and recording is the red microphone that swells and shrinks with the voice
 * The swelling follows [level], the loudness of the very audio that is being recorded, smoothed so it flows instead of jumping
 * The timer runs only while recording: a button that repaints itself for nothing keeps the IDE awake
 */
class VoicePillFace(
  private val pill: PillButton,
  private val level: () -> Double,
  private val idleIcon: Icon = VibeIcons.MIC,
  private val recordingIcon: Icon = VibeIcons.MIC_ON,
) {
  private val spinner = AnimatedIcon.Default()
  private val smoother = VoicePulse.Smoother()
  private var scale = 1.0
  private var timer: Timer? = null

  /** The picture and the tooltip for [state]; UI thread */
  fun show(state: VoiceFlow.State) {
    stopTimer()
    pill.toolTipText = VoiceFlow.tooltip(state)
    pill.icon = when {
      VoiceFlow.spinning(state) -> spinner
      VoiceFlow.micOn(state) -> PulseIcon(recordingIcon) { scale }.also { startTimer() }
      state == VoiceFlow.State.DONE -> AllIcons.General.GreenCheckmark
      else -> idleIcon
    }
    pill.repaint()
  }

  /** The panel is closing */
  fun dispose() = stopTimer()

  private fun startTimer() {
    smoother.reset()
    scale = 1.0
    timer = Timer(VoicePulse.FRAME_MS) {
      scale = VoicePulse.scale(smoother.next(VoicePulse.level(level()), VoicePulse.FRAME_MS.toLong()))
      pill.repaint()
    }.also { it.start() }
  }

  private fun stopTimer() {
    timer?.stop()
    timer = null
    scale = 1.0
  }

  /** [base] drawn [scale] times its size around its centre, taking the room of the unscaled icon so the row does not shift */
  private class PulseIcon(private val base: Icon, private val scale: () -> Double) : Icon {
    override fun getIconWidth(): Int = base.iconWidth
    override fun getIconHeight(): Int = base.iconHeight

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
      val g2 = g.create() as Graphics2D
      try {
        val k = scale()
        g2.translate(x + iconWidth / 2.0, y + iconHeight / 2.0)
        g2.scale(k, k)
        g2.translate(-iconWidth / 2.0, -iconHeight / 2.0)
        base.paintIcon(c, g2, 0, 0)
      }
      finally {
        g2.dispose()
      }
    }
  }
}
