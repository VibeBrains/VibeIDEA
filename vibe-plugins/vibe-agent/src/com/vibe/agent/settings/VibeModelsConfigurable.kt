// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.settings

import com.intellij.ide.DataManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.options.ex.Settings
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.util.ui.JBUI
import com.vibe.agent.i18n.VibeI18n.t
import com.vibe.agent.providers.ApiKeyResolver
import com.vibe.agent.providers.LlmClient
import com.vibe.agent.providers.ModelCatalogCache
import com.vibe.agent.providers.ProviderEntry
import com.vibe.agent.providers.ProvidersChangeListener
import com.vibe.agent.providers.ProvidersService
import com.vibe.agent.providers.ResolvedProvider
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * Models: one collapsed card per provider that has a key, and one dashed card at the bottom for those still waiting for one
 *
 * Which model is switched on is the same decision as before ([ModelVisibility]); this page only groups and draws it
 *
 * Catalogs are pulled from the providers' APIs, so the page must be able to REBUILD: a key typed on the Providers page
 * turns an empty block into a hundred models, and waiting for an IDE restart to see that is not an answer
 * Hence three refresh paths: the shared [ProvidersChangeListener] topic (a key was applied next door),
 * the link of the page for every catalog, and the link of a block for its own provider
 * Switches that are not applied yet survive a rebuild
 */
// NoScroll: the page scrolls itself (see VibeProvidersConfigurable)
// The platform wrapper would add a horizontal scrollbar sized by the unwrapped widths of the labels
class VibeModelsConfigurable(private val project: Project) : Configurable, Configurable.NoScroll {
  /** A provider with the answer to "is there a key" and the quiet resolution that gave it */
  private class Assessment(val provider: ProviderEntry, val resolved: ResolvedProvider?, val verdict: ModelsPage.KeyVerdict)

  private var view: ModelsPageView? = null
  private var uiDisposable: Disposable? = null

  /** Held for the time of our own publication of the topic: Apply must not restart this page */
  private var selfPublishing = false

  /** Number of the latest build: a registry read for an older one is dropped */
  private var buildSeq = 0

  /** The latest catalog request per provider: an answer to an older one is dropped */
  private val latestRequest = HashMap<String, Int>()
  private var requestCounter = 0

  override fun getDisplayName(): String = t("settings.models.title")

  override fun createComponent(): JComponent {
    val page = ModelsPageView(
      isHidden = { provider, model, defaultHidden -> ModelVisibility.isHidden(provider, model, defaultHidden) },
      actions = object : ModelsPageView.Actions {
        override fun refreshAll() = rebuild(announce = true)
        override fun refreshOne(providerId: String) = this@VibeModelsConfigurable.refreshOne(providerId)
        override fun enterKey() = openProviders()
      },
    )
    view = page

    // A key applied on the Providers page publishes this topic: rebuild instead of showing yesterday's empty blocks until the next restart
    val disposable = Disposer.newDisposable("VibeModelsConfigurable")
    uiDisposable = disposable
    project.messageBus.connect(disposable).subscribe(ProvidersChangeListener.TOPIC, ProvidersChangeListener {
      // The publisher delivers synchronously, so the flag is checked HERE and not in invokeLater
      // Otherwise our own Apply would restart the page and ask every catalog again
      if (selfPublishing) return@ProvidersChangeListener
      SwingUtilities.invokeLater { if (!project.isDisposed) rebuild() }
    })

    rebuild()
    return JPanel(BorderLayout(0, JBUI.scale(PAGE_GAP))).apply {
      border = JBUI.Borders.empty(PAGE_PADDING)
      add(page.header, BorderLayout.NORTH)
      add(SettingsUi.page(page.content), BorderLayout.CENTER)
    }
  }

  /**
   * Read the registry, sort the providers by key, draw the cards, ask the ones with a key for their catalogs
   *
   * The sorting asks the keychain, which is I/O, so it happens off the EDT and the page draws when it is done
   * A page that is already drawn stays as it is meanwhile: a refresh must not blank the screen
   *
   * [announce] is a person's own request for fresh catalogs: every block says it is asking, instead of keeping the cached line
   * until the answer replaces it, because a click that changes nothing on the screen reads as a click that did nothing
   */
  private fun rebuild(announce: Boolean = false) {
    val page = view ?: return
    val seq = ++buildSeq
    if (!page.rendered) page.showNote(t("settings.models.reading"))
    ApplicationManager.getApplication().executeOnPooledThread {
      val assessed: List<Assessment>
      val cache: Map<String, ModelCatalogCache.Entry>
      try {
        assessed = ProvidersService.load(project.basePath) { }.map { assess(it) }
        cache = ModelCatalogCache.load()
      }
      catch (e: Exception) {
        // A page left saying "reading" for good would be silence about a failure
        logger<VibeModelsConfigurable>().warn("could not read the providers for the Models page", e)
        val reason = e.message ?: e.javaClass.simpleName
        SwingUtilities.invokeLater {
          if (view === page && seq == buildSeq) page.showNote(t("settings.models.readFailed", "reason" to reason))
        }
        return@executeOnPooledThread
      }
      SwingUtilities.invokeLater {
        if (view !== page || seq != buildSeq || project.isDisposed) return@invokeLater
        present(page, assessed, cache, announce)
      }
    }
  }

  /**
   * Whether the provider waits for a key, by the test the page always used for its old "no key" line
   *
   * That test is a quiet resolution (the keychain is not read for a background job), see [ModelsPage.verdict]
   */
  private fun assess(provider: ProviderEntry): Assessment {
    val resolved = ProvidersService.resolve(provider, project.basePath, quiet = true) { }
    val quietMissing = resolved == null || resolved.missingKey
    return Assessment(provider, resolved, ModelsPage.verdict(quietMissing) { ApiKeyResolver.keyPresence(provider) })
  }

  private fun present(page: ModelsPageView, assessed: List<Assessment>, cache: Map<String, ModelCatalogCache.Entry>, announce: Boolean) {
    val grouping = ModelsPage.group(assessed.map { ModelsPage.Assessed(it.provider.id, it.provider.name, it.verdict) })
    val byId = assessed.associateBy { it.provider.id }
    fun blockOf(a: ModelsPage.Assessed): ModelsPageView.ProviderBlock {
      val p = byId.getValue(a.id).provider
      return ModelsPageView.ProviderBlock(p.id, p.name, p.models.map { m ->
        ModelsPageView.Declared(m.id, ModelRows.label(m.name, m.id, ModelRows.badges(m, custom = true)))
      })
    }
    page.render(grouping.keyed.map(::blockOf), grouping.waiting.map(::blockOf))

    val toAsk = ArrayList<Pair<ProviderEntry, ResolvedProvider>>()
    for (a in grouping.keyed.map { byId.getValue(it.id) }) {
      val p = a.provider
      // The cached catalog first: the page must not be empty while the network works
      val entry = cache[p.id]?.takeIf { it.fingerprint == ModelCatalogCache.fingerprint(p) }
      if (entry != null) page.addCatalog(p.id, entry.modelIds)
      val fetchEnabled = p.modelsFetch?.enabled != false
      val cached = entry?.let { ModelsPage.Source.Catalog(it.modelIds.size, it.fetchedAtMs) }
      // Only a provider whose key was found quietly is asked here: the others wait for a person's own request
      val resolved = a.resolved.takeIf { fetchEnabled && a.verdict == ModelsPage.KeyVerdict.USABLE }
      val source = if (announce && resolved != null) ModelsPage.Source.Asking else ModelsPage.initialSource(fetchEnabled, a.verdict, cached)
      page.setSource(p.id, source)
      if (resolved != null) toAsk += p to resolved
    }
    requestCatalogs(page, toAsk)
  }

  /**
   * Ask ONE provider for its catalog again
   * A person's own request, so unlike the background refresh it may read the key from the keychain
   */
  private fun refreshOne(providerId: String) {
    val page = view ?: return
    page.setSource(providerId, ModelsPage.Source.Asking)
    ApplicationManager.getApplication().executeOnPooledThread {
      val provider = ProvidersService.load(project.basePath) { }.firstOrNull { it.id == providerId }
      val resolved = provider?.let { ProvidersService.resolve(it, project.basePath, quiet = false) { } }
      SwingUtilities.invokeLater {
        if (view !== page || project.isDisposed) return@invokeLater
        // Gone from the registry, or no key after all: the page regroups itself instead of leaving a card that cannot be refreshed
        if (provider == null || resolved == null || resolved.missingKey) rebuild() else requestCatalogs(page, listOf(provider to resolved))
      }
    }
  }

  /**
   * Pull provider model catalogs off the EDT, all at once: a walk one after another paid the timeout of every dead provider
   *
   * Only a successful answer is written to the cache, so a 401 never erases yesterday's catalog
   */
  private fun requestCatalogs(page: ModelsPageView, targets: List<Pair<ProviderEntry, ResolvedProvider>>) {
    if (targets.isEmpty()) return
    val tickets = targets.associate { (p, _) -> p.id to nextRequest(p.id) }
    ApplicationManager.getApplication().executeOnPooledThread {
      val llm = LlmClient.forCatalog()
      val fresh = java.util.Collections.synchronizedMap(LinkedHashMap<String, ModelCatalogCache.Entry>())
      val jobs = targets.map { (p, resolved) ->
        val ticket = tickets.getValue(p.id)
        ApplicationManager.getApplication().executeOnPooledThread {
          val models = try {
            llm.listModels(resolved, p.modelsFetch?.url)
          }
          catch (e: Exception) {
            answer(page, p.id, ticket) { ModelsPage.Source.Failed(e.message?.take(REASON_CHARS) ?: e.javaClass.simpleName) }
            return@executeOnPooledThread
          }
          val entry = ModelCatalogCache.entryOf(p, models, System.currentTimeMillis())
          fresh[p.id] = entry
          val ids = models.map { it.id }
          answer(page, p.id, ticket) {
            val added = page.addCatalog(p.id, ids)
            if (ids.isEmpty()) ModelsPage.Source.EmptyCatalog else ModelsPage.Source.Catalog(ids.size, entry.fetchedAtMs, added)
          }
        }
      }
      jobs.forEach { runCatching { it.get() } }
      ModelCatalogCache.put(fresh)
    }
  }

  private fun nextRequest(providerId: String): Int {
    val ticket = ++requestCounter
    latestRequest[providerId] = ticket
    return ticket
  }

  /**
   * Hand an answer to the page, unless a newer request for the same provider or a newer page has come since
   *
   * invokeLater, never invokeAndWait: the page lives in a modal dialog
   * A pooled thread blocking on the EDT there is a deadlock waiting to happen
   */
  private fun answer(page: ModelsPageView, providerId: String, ticket: Int, source: () -> ModelsPage.Source) {
    SwingUtilities.invokeLater {
      if (view !== page || latestRequest[providerId] != ticket) return@invokeLater
      page.setSource(providerId, source())
    }
  }

  /**
   * Show the Providers page, where keys are entered
   *
   * Through the settings dialog that is already open, and not a second one
   * Two dialogs over each other are a way to lose what is not applied in the first
   */
  private fun openProviders() {
    val settings = view?.content?.let { Settings.KEY.getData(DataManager.getInstance().getDataContext(it)) }
    val target = settings?.find(VibeProvidersConfigurable::class.java)
    if (settings != null && target != null) settings.select(target)
    else ShowSettingsUtil.getInstance().showSettingsDialog(project, VibeProvidersConfigurable::class.java)
  }

  override fun isModified(): Boolean =
    view?.rows?.any { ModelVisibility.isHidden(it.providerId, it.modelId, it.defaultHidden) == it.box.isSelected } == true

  override fun apply() {
    var changed = false
    for (r in view?.rows.orEmpty()) {
      val hide = !r.box.isSelected
      if (ModelVisibility.isHidden(r.providerId, r.modelId, r.defaultHidden) != hide) {
        ModelVisibility.setHidden(r.providerId, r.modelId, hide)
        changed = true
      }
    }
    // The picker filters hidden models when building targets: tell open panels to rebuild
    // selfPublishing: we are subscribed to this same topic, and without the flag Apply would restart the page itself
    // That would close what is open and send every catalog request again
    if (changed) {
      selfPublishing = true
      try {
        project.messageBus.syncPublisher(ProvidersChangeListener.TOPIC).providersChanged()
      }
      finally {
        selfPublishing = false
      }
    }
  }

  override fun disposeUIResources() {
    uiDisposable?.let { Disposer.dispose(it) }
    uiDisposable = null
    view = null
    latestRequest.clear()
  }

  private companion object {
    const val PAGE_PADDING = 8
    const val PAGE_GAP = 6

    /** How much of an error message the header of a block keeps */
    const val REASON_CHARS = 80
  }
}
