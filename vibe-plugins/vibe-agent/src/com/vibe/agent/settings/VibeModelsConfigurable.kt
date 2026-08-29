// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.settings

import com.vibe.agent.i18n.VibeI18n.t
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.vibe.agent.providers.LlmClient
import com.vibe.agent.providers.ModelCatalogCache
import com.vibe.agent.providers.ProviderEntry
import com.vibe.agent.providers.ProvidersChangeListener
import com.vibe.agent.providers.ProvidersService
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * Модели: collapsible provider groups (VibeIDE §4 — all collapsed by default, chevron header with
 * a (N) / (X/Y) counter), AND-tokenized search over name/id/provider/traits, the «активные»
 * display-only filter (on by default) and trait badges on rows.
 *
 * Catalogs are pulled from the providers' APIs, so the page must be able to REBUILD: a key typed
 * on the Провайдеры page turns an empty group into a hundred models, and waiting for an IDE
 * restart to see that is not an answer. Hence three refresh paths — the shared
 * [ProvidersChangeListener] topic (a key was applied next door), «Обновить каталоги» for the whole
 * page, and «обновить» per provider group. Unsaved checkbox states survive a rebuild.
 */
// NoScroll: the page scrolls itself (see VibeProvidersConfigurable — the platform wrapper would
// add a horizontal scrollbar sized by unwrapped label widths).
class VibeModelsConfigurable(private val project: Project) : Configurable, Configurable.NoScroll {
  private class Row(val providerId: String, val modelId: String, val hay: String, val defaultHidden: Boolean, val box: JBCheckBox)

  private class Group(
    val providerId: String,
    val name: String,
    val header: JBLabel,
    val body: JPanel,
    val container: JPanel,
    val filteredOutHint: JBLabel,
    val statusHint: JBLabel,
  ) {
    var expanded = false // manual state; searching temporarily force-expands matching groups
    /** Номер последнего запущенного запроса каталога: ответ старее текущего игнорируется. */
    var fetchSeq = 0
  }

  private val rows = ArrayList<Row>()
  private val groups = LinkedHashMap<String, Group>()
  private val search = SearchTextField()
  private val activesOnly = JBCheckBox(t("settings.models.activesOnly"), true).apply {
    toolTipText = t("settings.models.activesOnlyTooltip")
  }
  private var listPanel: JPanel? = null
  private var uiDisposable: Disposable? = null
  /** Держится на время нашей же публикации топика: Apply не должен перезапускать эту страницу. */
  private var selfPublishing = false

  override fun getDisplayName(): String = t("settings.models.title")

  override fun createComponent(): JComponent {
    val list = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    listPanel = list
    search.textEditor.emptyText.text = t("settings.models.search")
    search.addDocumentListener(object : javax.swing.event.DocumentListener {
      override fun insertUpdate(e: javax.swing.event.DocumentEvent) = update()
      override fun removeUpdate(e: javax.swing.event.DocumentEvent) = update()
      override fun changedUpdate(e: javax.swing.event.DocumentEvent) = update()
    })
    activesOnly.addActionListener { update() }

    val refreshAll = ActionLink(t("settings.models.refreshAll")) { rebuild(refreshCatalogs = true) }
      .apply { toolTipText = t("settings.models.refreshAllTooltip") }

    // A key applied on the Провайдеры page publishes this topic — rebuild instead of showing
    // yesterday's empty groups until the next restart.
    val disposable = Disposer.newDisposable("VibeModelsConfigurable")
    uiDisposable = disposable
    project.messageBus.connect(disposable).subscribe(ProvidersChangeListener.TOPIC, ProvidersChangeListener {
      // syncPublisher доставляет синхронно, поэтому флаг проверяем ЗДЕСЬ, а не в invokeLater:
      // иначе наш собственный Apply перезапускал бы страницу и заново дёргал все каталоги.
      if (selfPublishing) return@ProvidersChangeListener
      SwingUtilities.invokeLater { if (!project.isDisposed) rebuild(refreshCatalogs = true) }
    })

    rebuild(refreshCatalogs = true)
    return JPanel(BorderLayout(0, JBUI.scale(6))).apply {
      border = JBUI.Borders.empty(8)
      add(JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
        add(search, BorderLayout.CENTER)
        add(JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
          add(activesOnly, BorderLayout.CENTER)
          add(refreshAll, BorderLayout.EAST)
        }, BorderLayout.EAST)
      }, BorderLayout.NORTH)
      add(com.vibe.agent.ui.VibeScroll.pane(TracksViewportWidthPanel(list)), BorderLayout.CENTER)
    }
  }

  /** Re-read the registry and repopulate the groups, keeping toggles the user has not applied yet. */
  private fun rebuild(refreshCatalogs: Boolean) {
    val list = listPanel ?: return
    val pending = rows.associate { (it.providerId to it.modelId) to it.box.isSelected }
    // Строки каталога живут только в памяти страницы: если следующий запрос не удастся
    // (сеть/ключ), потерять их нельзя — вместе с ними исчезли бы и непринятые галочки.
    val carriedCatalogRows = rows.filter { it.defaultHidden }.map { it.providerId to it.modelId }
    val expandedIds = groups.values.filter { it.expanded }.mapTo(HashSet()) { it.providerId }
    rows.clear(); groups.clear(); list.removeAll()

    val providers = ProvidersService.load(project.basePath) { }
    if (providers.isEmpty()) {
      list.add(JBLabel("<html>" + t("settings.providers.empty") + "</html>"))
    }
    for (p in providers) {
      val body = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.emptyLeft(20)
        isVisible = false // all groups collapsed by default (VibeIDE §4)
      }
      val header = JBLabel().apply {
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        border = JBUI.Borders.empty(6, 2, 2, 2)
      }
      val refreshOne = ActionLink(t("settings.models.refreshOne")) { refreshCatalog(p.id) }
        .apply {
          toolTipText = t("settings.models.refreshOneTooltip", "provider" to p.name)
          border = JBUI.Borders.empty(6, 8, 2, 2)
        }
      val container = JPanel(BorderLayout()).apply {
        add(JPanel(BorderLayout()).apply {
          add(header, BorderLayout.CENTER)
          add(refreshOne, BorderLayout.EAST)
        }, BorderLayout.NORTH)
        add(body, BorderLayout.CENTER)
      }
      val filteredOutHint = JBLabel(t("settings.models.allHidden")).apply {
        foreground = com.intellij.ui.JBColor.GRAY
        isVisible = false
      }
      val statusHint = JBLabel().apply { foreground = com.intellij.ui.JBColor.GRAY; isVisible = false }
      val group = Group(p.id, p.name, header, body, container, filteredOutHint, statusHint)
      header.addMouseListener(object : MouseAdapter() {
        override fun mouseClicked(e: MouseEvent) {
          group.expanded = !group.expanded
          update()
        }
      })
      for (m in p.models) {
        // Hand-declared models are visible by default; catalog-only ones (added later) are not.
        addRow(group, m.id, ModelRows.label(m.name, m.id, ModelRows.badges(m, custom = true)),
               defaultHidden = false, pending = pending)
      }
      body.add(filteredOutHint)
      // Статус — в контейнере, а не внутри body: свёрнутая группа скрывает body, и ответ на
      // «обновить» уходил бы в невидимую строку.
      container.add(statusHint, BorderLayout.SOUTH)
      group.expanded = p.id in expandedIds
      // Возвращаем каталожные строки, известные до перестройки, — свежий ответ их дополнит.
      for ((providerId, modelId) in carriedCatalogRows) {
        if (providerId == p.id && rows.none { it.providerId == p.id && it.modelId == modelId }) {
          addRow(group, modelId, modelId, defaultHidden = true, pending = pending)
        }
      }
      groups[p.id] = group
      list.add(container)
    }
    if (refreshCatalogs) fetchCatalogs(providers, pending)
    update()
    list.revalidate(); list.repaint()
  }

  private fun addRow(
    group: Group,
    modelId: String,
    label: String,
    defaultHidden: Boolean,
    pending: Map<Pair<String, String>, Boolean> = emptyMap(),
  ) {
    val selected = pending[group.providerId to modelId]
                   ?: !ModelVisibility.isHidden(group.providerId, modelId, defaultHidden)
    val box = JBCheckBox(label, selected)
    box.toolTipText = if (box.isSelected) t("settings.models.show") else t("settings.models.hidden")
    box.addActionListener {
      box.toolTipText = if (box.isSelected) t("settings.models.show") else t("settings.models.hidden")
      if (activesOnly.isSelected) update()
    }
    rows.add(Row(group.providerId, modelId, "${group.name} ${group.providerId} $label", defaultHidden, box))
    group.body.add(box)
  }

  /** Recompute row/group visibility, counters and search-driven expansion. */
  private fun update() {
    val tokens = ModelRows.tokens(search.text)
    val searching = tokens.isNotEmpty()
    for (group in groups.values) {
      val groupRows = rows.filter { it.providerId == group.providerId }
      val afterActives = groupRows.filter { !activesOnly.isSelected || it.box.isSelected }
      val found = afterActives.filter { ModelRows.matches(it.hay, tokens) }
      for (r in groupRows) r.box.isVisible = r in found
      val chevron = if (searching || group.expanded) "▾" else "▸"
      group.header.text = "$chevron  ${group.name}  ${ModelRows.counter(found.size, afterActives.size, searching)}"
      group.header.toolTipText = t("settings.models.totalCount", "count" to groupRows.size)
      // While searching: only groups with matches are visible and they are force-expanded;
      // otherwise the manual collapsed/expanded state rules (collapsed by default).
      group.container.isVisible = !searching || found.isNotEmpty()
      group.body.isVisible = if (searching) true else group.expanded
      group.filteredOutHint.isVisible = !searching && activesOnly.isSelected && groupRows.isNotEmpty() && afterActives.isEmpty()
    }
    listPanel?.revalidate(); listPanel?.repaint()
  }

  /** Re-ask ONE provider for its catalog (the «обновить» link of a group). */
  private fun refreshCatalog(providerId: String) {
    val provider = ProvidersService.load(project.basePath) { }.firstOrNull { it.id == providerId } ?: return
    val group = groups[providerId]
    group?.statusHint?.apply { text = t("settings.models.asking"); isVisible = true }
    fetchCatalogs(listOf(provider), rows.associate { (it.providerId to it.modelId) to it.box.isSelected })
  }

  /**
   * Pull provider model catalogs off the EDT so fetched models can be hidden here, not only static
   * ones. The cached catalog is shown FIRST (the page must not be empty while the network works),
   * and providers are polled CONCURRENTLY — a sequential walk paid every dead provider's timeout.
   */
  private fun fetchCatalogs(providers: List<ProviderEntry>, pending: Map<Pair<String, String>, Boolean>) {
    if (providers.isEmpty()) return
    // Поколение считаем на группу, а не глобально: «обновить» одного провайдера не должно
    // обесценивать живой запрос соседнего.
    val seq = providers.associate { p -> p.id to (groups[p.id]?.let { ++it.fetchSeq } ?: 0) }
    ApplicationManager.getApplication().executeOnPooledThread {
      val cache = ModelCatalogCache.load()
      val now = System.currentTimeMillis()
      for (p in providers) {
        val entry = cache[p.id] ?: continue
        if (entry.fingerprint != ModelCatalogCache.fingerprint(p)) continue
        val mySeq = seq[p.id] ?: 0
        showCatalog(p.id, mySeq, entry.modelIds, pending)
        setStatus(p.id, mySeq, t("settings.models.fromCache", "age" to ModelCatalogCache.ageText(entry.fetchedAtMs, now)))
      }
      val llm = LlmClient.forCatalog()
      val fresh = java.util.Collections.synchronizedMap(LinkedHashMap<String, ModelCatalogCache.Entry>())
      val tasks = providers.mapNotNull { p ->
        val mySeq = seq[p.id] ?: 0
        if (p.modelsFetch?.enabled == false) { setStatus(p.id, mySeq, t("settings.models.fetchDisabled")); return@mapNotNull null }
        val resolved = ProvidersService.resolve(p, project.basePath) { }
        if (resolved == null || (resolved.apiKey == null && !resolved.isLocal)) {
          setStatus(p.id, mySeq, t("settings.models.noKey"))
          return@mapNotNull null
        }
        ApplicationManager.getApplication().executeOnPooledThread {
          val ids = try { llm.listModels(resolved, p.modelsFetch?.url) }
          catch (e: Exception) {
            setStatus(p.id, mySeq, t("settings.models.fetchFailed", "reason" to e.message?.take(80)))
            return@executeOnPooledThread
          }
          // Только успешный ответ пишется в кэш — 401 не должен стирать вчерашний каталог.
          fresh[p.id] = ModelCatalogCache.Entry(ModelCatalogCache.fingerprint(p), ids, System.currentTimeMillis())
          showCatalog(p.id, mySeq, ids, pending) { added ->
            if (ids.isEmpty()) t("settings.models.catalogEmpty")
            else t("settings.models.catalogReturned", "count" to ids.size, "word" to modelsWord(ids.size)) +
                 (if (added == 0) t("settings.models.noNew") else t("settings.models.added", "count" to added))
          }
        }
      }
      tasks.forEach { runCatching { it.get() } }
      ModelCatalogCache.put(fresh)
    }
  }

  /**
   * Adds catalog rows unknown to the group and reports the result through [statusOf].
   * invokeLater, never invokeAndWait: the page lives in a modal dialog, and a pooled thread
   * blocking on the EDT there is a deadlock waiting to happen.
   */
  private fun showCatalog(
    providerId: String,
    seq: Int,
    ids: List<String>,
    pending: Map<Pair<String, String>, Boolean>,
    statusOf: ((added: Int) -> String)? = null,
  ) {
    SwingUtilities.invokeLater {
      val group = groups[providerId] ?: return@invokeLater
      if (group.fetchSeq != seq) return@invokeLater // ответ устарел: страницу успели перестроить
      val known = rows.filter { it.providerId == providerId }.map { it.modelId }.toSet()
      var added = 0
      for (id in ids.filter { it !in known }) { addRow(group, id, id, defaultHidden = true, pending = pending); added++ }
      statusOf?.let { group.statusHint.apply { text = it(added); isVisible = true } }
      update()
    }
  }

  private fun setStatus(providerId: String, seq: Int, text: String) {
    SwingUtilities.invokeLater {
      val group = groups[providerId] ?: return@invokeLater
      if (group.fetchSeq != seq) return@invokeLater // ответ от предыдущего «обновить»
      group.statusHint.apply { this.text = text; isVisible = true }
      listPanel?.revalidate(); listPanel?.repaint()
    }
  }

  private fun modelsWord(n: Int): String = when {
    n % 10 == 1 && n % 100 != 11 -> t("settings.models.word.one")
    n % 10 in 2..4 && n % 100 !in 12..14 -> t("settings.models.word.few")
    else -> t("settings.models.word.many")
  }

  override fun isModified(): Boolean = rows.any { ModelVisibility.isHidden(it.providerId, it.modelId, it.defaultHidden) == it.box.isSelected }

  override fun apply() {
    var changed = false
    for (r in rows) {
      val hide = !r.box.isSelected
      if (ModelVisibility.isHidden(r.providerId, r.modelId, r.defaultHidden) != hide) {
        ModelVisibility.setHidden(r.providerId, r.modelId, hide)
        changed = true
      }
    }
    // The picker filters hidden models when building targets — tell open panels to rebuild.
    // selfPublishing: мы подписаны на этот же топик, и без флага Apply перезапускал бы саму
    // страницу — с потерей раскрытости и повторным походом в сеть за всеми каталогами.
    if (changed) {
      selfPublishing = true
      try { project.messageBus.syncPublisher(ProvidersChangeListener.TOPIC).providersChanged() }
      finally { selfPublishing = false }
    }
  }

  override fun disposeUIResources() {
    uiDisposable?.let { Disposer.dispose(it) }
    uiDisposable = null
    listPanel = null
    rows.clear(); groups.clear()
  }
}
