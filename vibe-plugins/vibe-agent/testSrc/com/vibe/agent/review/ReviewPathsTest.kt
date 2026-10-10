// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.review

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** The journal and the editor name a file in their own ways; the review matches them by one key */
class ReviewPathsTest {
  @Test
  fun `spellings of one path give one key`() {
    val key = ReviewPaths.key("/project/src/a.kt")
    assertEquals(key, ReviewPaths.key("/project/src/../src/a.kt"))
    assertEquals(key, ReviewPaths.key("/project//src/./a.kt"))
  }

  @Test
  fun `different files give different keys`() {
    assertNotEquals(ReviewPaths.key("/project/src/a.kt"), ReviewPaths.key("/project/src/b.kt"))
    assertNotEquals(ReviewPaths.key("/project/src/a.kt"), ReviewPaths.key("/other/src/a.kt"))
  }
}
