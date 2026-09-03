// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.intellij.build

/**
 * The build number a VibeIDEA distribution carries, derived from the product version.
 *
 * Why it cannot stay `263.SNAPSHOT`: the platform parses `SNAPSHOT` as `Integer.MAX_VALUE`, so an
 * installed snapshot considers itself newer than every number an update channel could ever publish,
 * and the update check offers nothing — forever. A release therefore needs a real, monotonic number.
 *
 * The number is `<platform line>.<major*10000 + minor*100 + patch>`: `0.3.1` → `263.301`,
 * `0.4.10` → `263.410`, `1.0.0` → `263.10000`. Monotonic as long as minor and patch stay below 100,
 * which a check enforces rather than assumes. The platform line stays in front because plugin
 * compatibility ranges (`since-build`) are written against it.
 *
 * Pure; the caller decides where the version comes from.
 */
object VibeBuildNumber {
  private val VERSION = Regex("""^(\d+)\.(\d+)\.(\d+)$""")

  fun of(productVersion: String, platformLine: String): String {
    val m = VERSION.matchEntire(productVersion.trim())
      ?: error("VibeIDEA version must be MAJOR.MINOR.PATCH, got '$productVersion'")
    val (major, minor, patch) = m.destructured.toList().map { it.toInt() }
    require(minor < 100 && patch < 100) { "minor and patch must stay below 100 to keep build numbers monotonic: '$productVersion'" }
    return "$platformLine.${major * 10_000 + minor * 100 + patch}"
  }

  /** `263` out of `263.SNAPSHOT` — the platform line the fork is on. */
  fun platformLine(snapshotBuildNumber: String): String = snapshotBuildNumber.substringBefore('.')

  /** The `full="…"` attribute of the application info — where the product version lives. */
  fun productVersion(applicationInfoXml: String): String =
    Regex("""full="([^"]+)"""").find(applicationInfoXml)?.groupValues?.get(1)
      ?: error("no full=\"…\" version attribute in the application info")
}
