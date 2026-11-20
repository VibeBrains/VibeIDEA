// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.product

/**
 * Where the product lives on the network — the single place these addresses are spelled.
 *
 * Pure constants and pure string building, so the update channel can be tested without an IDE and
 * so a rename of the repository is a one-line change instead of a grep.
 */
object VibeProductUrls {
  const val REPO_OWNER = "VibeBrains"
  const val REPO_NAME = "VibeIDEA"
  const val REPO = "https://github.com/$REPO_OWNER/$REPO_NAME"

  /** The branch that is always releasable — the only branch an update channel may read. */
  const val RELEASE_BRANCH = "main"

  /** Path of the update metadata inside the repository; the release scripts write this file. */
  const val UPDATES_PATH = "updates/updates.xml"

  /**
   * Raw file on the release branch: no server of ours, no CDN, nothing to keep alive. GitHub serves
   * it with the right cache headers, and a release commit is the natural moment the file changes.
   */
  const val UPDATES_URL = "https://raw.githubusercontent.com/$REPO_OWNER/$REPO_NAME/$RELEASE_BRANCH/$UPDATES_PATH"

  const val RELEASES_URL = "$REPO/releases"
  const val LATEST_RELEASE_URL = "$RELEASES_URL/latest"

  /** The author's site: the address in the signature at the bottom of every product of the family. */
  const val AUTHOR_SITE = "https://borodatych.ru"

  /** What the signature shows as link text — the domain itself, so the line reads as an address. */
  const val AUTHOR_SITE_TEXT = "borodatych.ru"

  fun releaseUrl(tag: String): String = "$RELEASES_URL/tag/$tag"

  /** New issue with the environment description pre-filled — what «Submit a Bug Report» should open for a fork. */
  fun newIssueUrl(description: String): String =
    "$REPO/issues/new?body=" + java.net.URLEncoder.encode(description, Charsets.UTF_8)
}
