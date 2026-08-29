// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.watch

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchProbeTest {
  @Test
  fun `remote with only audio formats is audio`() {
    val dump = """{"formats":[{"vcodec":"none"},{"vcodec":"none"}]}"""
    assertTrue(WatchProbe.remoteIsAudioOnly(dump, WatchInput.Kind.UNKNOWN))
  }

  @Test
  fun `positive evidence of video ALWAYS beats the name hint`() {
    // A remote .ogg carrying Theora silently lost its video when the hint was a co-decider.
    val dump = """{"formats":[{"vcodec":"theora"},{"vcodec":"none"}]}"""
    assertFalse(WatchProbe.remoteIsAudioOnly(dump, WatchInput.Kind.AUDIO))
  }

  @Test
  fun `an uninformative probe lets the name break the tie, and only then`() {
    val dump = """{"formats":[{}]}"""
    assertTrue(WatchProbe.remoteIsAudioOnly(dump, WatchInput.Kind.AUDIO))
    assertFalse(WatchProbe.remoteIsAudioOnly(dump, WatchInput.Kind.VIDEO))
    assertFalse(WatchProbe.remoteIsAudioOnly("не json", WatchInput.Kind.VIDEO))
  }

  @Test
  fun `a local file with a real video stream is video`() {
    val banner = """
      Input #0, mov,mp4, from 'a.mp4':
        Stream #0:0(und): Video: h264 (avc1), yuv420p, 1280x720
        Stream #0:1(und): Audio: aac, 48000 Hz, stereo
    """.trimIndent()
    assertEquals(false, WatchProbe.localIsAudioOnly(banner))
  }

  @Test
  fun `cover art is not a picture to watch`() {
    val banner = """
      Input #0, mp3, from 'a.mp3':
        Stream #0:0: Audio: mp3, 44100 Hz, stereo
        Stream #0:1: Video: mjpeg (attached pic), yuvj420p, 600x600
    """.trimIndent()
    assertEquals(true, WatchProbe.localIsAudioOnly(banner))
  }

  @Test
  fun `an unreadable file returns null so the caller can quote ffmpeg instead of inventing a reason`() {
    assertNull(WatchProbe.localIsAudioOnly("a.mp4: Invalid data found when processing input"))
  }
}
