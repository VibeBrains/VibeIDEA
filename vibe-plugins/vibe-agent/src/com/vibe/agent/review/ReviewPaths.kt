// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.review

import java.nio.file.InvalidPathException
import java.nio.file.Path

/** Two spellings of one path are one file: `..`, a doubled separator and the separator style do not make a different file */
object ReviewPaths {
  fun key(path: String): String = try {
    Path.of(path).normalize().toString()
  }
  catch (_: InvalidPathException) {
    path
  }
}
