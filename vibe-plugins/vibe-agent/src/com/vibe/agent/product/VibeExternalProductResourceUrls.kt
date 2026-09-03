// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.product

import com.intellij.platform.ide.customization.ExternalProductResourceUrls
import com.intellij.util.Url
import com.intellij.util.Urls

/**
 * The addresses the platform asks for — update metadata, download page, bug reports — pointed at
 * this product instead of at the upstream vendor.
 *
 * Registered as the application service with `overrides="true"`: this is the one extension point the
 * platform offers for exactly this, so the update channel needs no patch in platform code. Before
 * this class the fork inherited the upstream implementation, which meant «Submit a Bug Report»
 * opened the vendor's tracker for a bug in our code and the update check asked the vendor's site
 * about a product it has never heard of.
 *
 * What is deliberately null: YouTube, the shortcuts PDF, «Getting Started» — we have no such
 * material, and a menu item leading to somebody else's is the kind of borrowed branding the gate
 * exists to catch. Help stays with the platform's own pages: the editor, the VCS and the run
 * configurations ARE the platform's, and their documentation is accurate for them.
 */
class VibeExternalProductResourceUrls : ExternalProductResourceUrls {
  override val updateMetadataUrl: Url
    get() = Urls.newFromEncoded(VibeProductUrls.UPDATES_URL)

  /**
   * No patches: an incremental patch needs a build farm that produces deltas between versions, and
   * a button that says «обновить» while downloading nothing would be a lie. The update dialog
   * therefore offers the download page, which is the honest shape of what we can do.
   */
  override val downloadPageUrl: Url
    get() = Urls.newFromEncoded(VibeProductUrls.LATEST_RELEASE_URL)

  override val whatIsNewPageUrl: Url
    get() = Urls.newFromEncoded(VibeProductUrls.LATEST_RELEASE_URL)

  override val bugReportUrl: ((description: String) -> Url)
    get() = { description -> Urls.newFromEncoded(VibeProductUrls.newIssueUrl(description)) }
}
