// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.watch

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SceneFramesTest {
  private val ffmpeg6 = """
    [Parsed_showinfo_2 @ 0x1] n:   0 pts:      0 pts_time:0       pos:       48 fmt:yuvj420p sar:1/1 s:1280x720
    [Parsed_showinfo_2 @ 0x1] n:   1 pts:  92000 pts_time:3.83333 pos:   118784 fmt:yuvj420p sar:1/1 s:1280x720
  """.trimIndent()

  private val ffmpeg8 = """
    [Parsed_showinfo_2 @ 0x1] n:   0 pts:      0 pts_time:0       pos:       48 fmt:yuvj420p sar:1/1 s=1280x720
    [Parsed_showinfo_2 @ 0x1] n:   1 pts:  92000 pts_time:12.5    pos:   118784 fmt:yuvj420p sar:1/1 s=1280x720
  """.trimIndent()

  @Test
  fun `both ffmpeg majors parse — the difference is one character`() {
    // A parser written for one major silently returns zero frames on the other, which looks exactly
    // like "в видео нет смен сцен". VibeIDE's live smoke caught this as a production bug.
    assertEquals(listOf(0, 1), SceneFrames.parseShowinfo(ffmpeg6).map { it.index })
    assertEquals(listOf(0, 1), SceneFrames.parseShowinfo(ffmpeg8).map { it.index })
    assertEquals(12.5, SceneFrames.parseShowinfo(ffmpeg8).last().timestampSec)
  }

  @Test
  fun `lines that are not showinfo are ignored`() {
    assertTrue(SceneFrames.parseShowinfo("frame= 12 fps=0.0 q=3.0 size=N/A time=00:00:04").isEmpty())
    assertTrue(SceneFrames.parseShowinfo("").isEmpty())
  }

  @Test
  fun `the filter always anchors the first frame`() {
    // Without the anchor a static screencast produces nothing at all.
    assertTrue(SceneFrames.filter(0.3, 720).contains("eq(n,0)"))
    assertTrue(SceneFrames.filter(0.3, 720).contains("gt(scene,0.3)"))
    assertTrue(SceneFrames.filter(0.3, 720).contains("scale=-2:720"))
  }

  @Test
  fun `too few frames means the threshold, not the video, is wrong`() {
    assertTrue(SceneFrames.needsRetry(listOf(SceneFrames.Frame(0, 0.0))))
    assertFalse(SceneFrames.needsRetry((0..4).map { SceneFrames.Frame(it, it.toDouble()) }))
  }

  @Test
  fun `thinning spreads frames ACROSS TIME, not by index`() {
    // Scene changes cluster in edited passages: taking the first N returns the intro and misses
    // the entire second half.
    val frames = listOf(0.0, 1.0, 2.0, 3.0, 4.0, 100.0, 200.0).mapIndexed { i, t -> SceneFrames.Frame(i, t) }
    val kept = SceneFrames.thin(frames, 3)
    assertEquals(3, kept.size)
    assertEquals(0.0, kept.first().timestampSec)
    assertEquals(200.0, kept.last().timestampSec)
  }

  @Test
  fun `thinning keeps everything when there is room, and survives degenerate input`() {
    val frames = (0..3).map { SceneFrames.Frame(it, it.toDouble()) }
    assertEquals(frames, SceneFrames.thin(frames, 10))
    assertTrue(SceneFrames.thin(emptyList(), 5).isEmpty())
    assertTrue(SceneFrames.thin(frames, 0).isEmpty())
    // All frames at the same timestamp: no crash, just a prefix.
    val same = (0..5).map { SceneFrames.Frame(it, 1.0) }
    assertEquals(2, SceneFrames.thin(same, 2).size)
  }
}
