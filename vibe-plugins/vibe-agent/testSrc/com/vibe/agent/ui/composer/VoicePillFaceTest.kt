// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.ui.composer

import com.vibe.agent.voice.VoiceFlow
import com.vibe.agent.voice.VoiceFlow.State
import com.vibe.agent.voice.VoicePulse
import com.intellij.icons.AllIcons
import com.intellij.ui.AnimatedIcon
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.Icon
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The microphone button at each state of a dictation, drawn and measured
 *
 * The pictures are written only when `VIBE_SHOTS` names a folder; the measurements always run
 */
class VoicePillFaceTest {
  /**
   * A solid square standing for an icon of ours
   * The icons themselves are SVG and do not load in a test with no application behind it (they report one pixel),
   * and what is measured here is the pulse, which does not care what the picture is
   */
  private class Square(private val color: Color) : Icon {
    override fun getIconWidth() = SIDE
    override fun getIconHeight() = SIDE
    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
      g.color = color
      g.fillRect(x, y, SIDE, SIDE)
    }
  }

  private var level = 0.0
  private val pill = PillButton(icon = Square(GREY)) { }
  private val face = VoicePillFace(pill, { level }, idleIcon = Square(GREY), recordingIcon = Square(RED))

  private fun <T> onEdt(block: () -> T): T {
    var result: Result<T>? = null
    SwingUtilities.invokeAndWait { result = runCatching(block) }
    return result!!.getOrThrow()
  }

  private fun show(state: State) = onEdt { face.show(state) }

  /** The pill as it is drawn now, on a white ground so that the icon is the only thing in colour */
  private fun render(): BufferedImage = onEdt {
    pill.setSize(pill.preferredSize)
    val image = BufferedImage(pill.width, pill.height, BufferedImage.TYPE_INT_RGB)
    val g = image.createGraphics()
    g.color = Color.WHITE
    g.fillRect(0, 0, image.width, image.height)
    pill.printAll(g)
    g.dispose()
    image
  }

  private fun isRed(rgb: Int): Boolean = rgb == RED.rgb

  /** The width and height of the box that holds everything red in [image], or null when nothing is */
  private fun redBox(image: BufferedImage): Pair<Int, Int>? {
    val reds = coordinates(image).filter { (x, y) -> isRed(image.getRGB(x, y)) }
    if (reds.isEmpty()) return null
    return (reds.maxOf { it.first } - reds.minOf { it.first } + 1) to (reds.maxOf { it.second } - reds.minOf { it.second } + 1)
  }

  private fun inked(image: BufferedImage): Int =
    (0 until image.width).sumOf { x -> (0 until image.height).count { y -> image.getRGB(x, y) != Color.WHITE.rgb } }

  private fun settleTheSmoothing() = Thread.sleep((VoicePulse.SMOOTHING_MS * 8).toLong())

  @Test
  fun `the red icon is red only while recording, and every state has a picture of its own`() {
    val looks = State.entries.associateWith { state ->
      show(state)
      render() to pill.icon
    }
    assertEquals(setOf(State.RECORDING), looks.filter { redBox(it.value.first) != null }.keys)
    // The check mark is a platform icon that is an SVG, and an SVG does not load in a test with no application behind it
    val drawable = looks.filterKeys { it != State.DONE }
    assertTrue(drawable.values.all { inked(it.first) > 0 }, "every state draws something: ${drawable.mapValues { inked(it.value.first) }}")
    val idle = looks.getValue(State.IDLE)
    listOf(State.RECORDING, State.OPENING).forEach { state ->
      assertNotEquals(pixels(idle.first), pixels(looks.getValue(state).first), "$state looks like the idle icon")
    }
    assertTrue(looks.getValue(State.OPENING).second is AnimatedIcon, "opening turns")
    assertTrue(looks.getValue(State.TRANSCRIBING).second is AnimatedIcon, "transcribing turns")
    assertSame(AllIcons.General.GreenCheckmark, looks.getValue(State.DONE).second, "a finished text is a check mark")
    onEdt { face.dispose() }
  }

  @Test
  fun `the tooltip follows the state`() {
    State.entries.forEach {
      show(it)
      assertEquals(VoiceFlow.tooltip(it), pill.toolTipText)
    }
    onEdt { face.dispose() }
  }

  @Test
  fun `a loud voice makes the red icon about four tenths bigger than a silent one, and silence puts it back`() {
    level = 0.0
    show(State.RECORDING)
    settleTheSmoothing()
    val silent = redBox(render())
    assertNotNull(silent)
    level = 1.0
    settleTheSmoothing()
    val loud = redBox(render())
    assertNotNull(loud)
    val growth = loud!!.second.toDouble() / silent!!.second
    assertEquals(SIDE, silent!!.second, "silence draws the icon at its own size")
    assertTrue(growth in 1.3..1.5, "the height grew by $growth: $silent -> $loud")
    level = 0.0
    settleTheSmoothing()
    assertEquals(silent, redBox(render()), "the voice stopped and the icon came back")
    onEdt { face.dispose() }
  }

  @Test
  fun `the swelling takes no more room than the icon had, so the row does not shift`() {
    level = 0.0
    show(State.RECORDING)
    val before = pill.preferredSize
    level = 1.0
    settleTheSmoothing()
    assertEquals(before, pill.preferredSize)
    onEdt { face.dispose() }
  }

  @Test
  fun `leaving the recording stops the pulse and gives the icon its own size`() {
    level = 1.0
    show(State.RECORDING)
    settleTheSmoothing()
    show(State.TRANSCRIBING)
    show(State.IDLE)
    val idle = render()
    assertEquals(null, redBox(idle))
    show(State.RECORDING)
    level = 0.0
    val restart = redBox(render())
    settleTheSmoothing()
    assertEquals(redBox(render()), restart, "a new recording starts from the icon's own size and not from the last voice")
    onEdt { face.dispose() }
  }

  @Test
  fun `the pictures are written when asked for`() {
    val out = System.getenv("VIBE_SHOTS")?.let { File(it) } ?: return
    out.mkdirs()
    val frames = listOf(
      State.IDLE to 0.0, State.OPENING to 0.0, State.RECORDING to 0.0, State.RECORDING to 0.03, State.RECORDING to 0.06,
      State.TRANSCRIBING to 0.0, State.DONE to 0.0,
    )
    val shots = frames.map { (state, voice) ->
      level = voice
      show(state)
      settleTheSmoothing()
      render()
    }
    val zoom = 8
    val sheet = BufferedImage(shots.sumOf { it.width * zoom + zoom }, shots.maxOf { it.height } * zoom, BufferedImage.TYPE_INT_RGB)
    val g = sheet.createGraphics()
    g.color = Color.WHITE
    g.fillRect(0, 0, sheet.width, sheet.height)
    var x = 0
    shots.forEach { shot ->
      g.drawImage(shot.getScaledInstance(shot.width * zoom, shot.height * zoom, java.awt.Image.SCALE_REPLICATE), x, 0, null)
      x += shot.width * zoom + zoom
    }
    g.dispose()
    ImageIO.write(sheet, "png", File(out, "voicePillFaces.png"))
    onEdt { face.dispose() }
  }

  private fun coordinates(image: BufferedImage): List<Pair<Int, Int>> =
    (0 until image.width).flatMap { x -> (0 until image.height).map { y -> x to y } }

  private fun pixels(image: BufferedImage): List<Int> = coordinates(image).map { (x, y) -> image.getRGB(x, y) }

  private companion object {
    const val SIDE = 12
    val RED = Color(0xE0, 0x52, 0x52)
    val GREY = Color(0x6C, 0x70, 0x7E)
  }
}
