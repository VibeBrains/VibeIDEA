// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import com.vibe.agent.i18n.VibeI18n.t
import javax.swing.JComponent
import com.vibe.agent.settings.SettingsUi
import com.vibe.agent.ui.VibeScroll

/**
 * Settings page: where each language server actually is.
 *
 * Nothing here has to be filled in — an empty field means the usual resolution, which starts with
 * the person's own PATH and ends with the copy we ship. The page exists for the case the ordinary
 * rule cannot express: a server that lives in `vendor/bin`, in a shared container folder, or in a
 * checkout built from source.
 */
class VibeLspConfigurable : Configurable {
  private val fields = LinkedHashMap<String, TextFieldWithBrowseButton>()
  private val phpEngine = com.intellij.openapi.ui.ComboBox(PhpEngine.entries.toTypedArray())
  private val tsEngine = com.intellij.openapi.ui.ComboBox(TsEngine.entries.toTypedArray())
  /** Пусто — «автоматически»: путь, названный руками, сильнее любого поиска. */
  private val nodePath = TextFieldWithBrowseButton()
  private val nodeStatus = JBLabel()

  override fun getDisplayName(): String = t("settings.lsp.title")

  override fun createComponent(): JComponent {
    val builder = FormBuilder.createFormBuilder()
    // The PHP engine comes first: it decides which of the two paths below is even in play.
    // Keys spelled out rather than assembled from the id: the i18n gate looks for literal calls,
    // and a computed key reads to it as dead — it would offer to delete a string that is in use.
    phpEngine.renderer = com.intellij.ui.SimpleListCellRenderer.create("") {
      when (it) {
        PhpEngine.AUTO -> t("settings.lsp.php.auto")
        PhpEngine.PHPACTOR -> t("settings.lsp.php.phpactor")
        PhpEngine.INTELEPHENSE -> t("settings.lsp.php.intelephense")
      }
    }
    phpEngine.selectedItem = PhpServerChoice.stored()
    builder.addLabeledComponent(t("settings.lsp.php.engine"), phpEngine)
    builder.addComponent(SettingsUi.hint(t("settings.lsp.php.hint")))
    tsEngine.renderer = com.intellij.ui.SimpleListCellRenderer.create("") {
      when (it) {
        TsEngine.AUTO -> t("settings.lsp.ts.auto")
        TsEngine.VTSLS -> t("settings.lsp.ts.vtsls")
        TsEngine.TSC -> t("settings.lsp.ts.tsc")
      }
    }
    tsEngine.selectedItem = TsServerChoice.stored()
    builder.addLabeledComponent(t("settings.lsp.ts.engine"), tsEngine)
    builder.addComponent(SettingsUi.hint(t("settings.lsp.ts.hint")))
    // Интерпретатор Node — выше списка серверов: три из четырёх серверов и отладчик JS суть
    // программы на ноде, и без неё ни одна строка ниже не имеет значения.
    nodePath.text = NodeInterpreter.stored()
    nodePath.addBrowseFolderListener(
      null,
      FileChooserDescriptorFactory.createSingleFileDescriptor().withTitle(t("settings.lsp.node.choose")),
    )
    val nodeRow = javax.swing.JPanel(java.awt.BorderLayout(com.intellij.util.ui.JBUI.scale(6), 0)).apply {
      add(nodePath, java.awt.BorderLayout.CENTER)
      add(javax.swing.JButton(t("settings.lsp.node.check")).apply {
        addActionListener { nodeStatus.text = "<html>" + checkNode() + "</html>" }
      }, java.awt.BorderLayout.EAST)
    }
    builder.addLabeledComponent(t("settings.lsp.node.path"), nodeRow)
    nodeStatus.text = "<html>" + nodeSummary() + "</html>"
    nodeStatus.foreground = com.intellij.ui.JBColor.GRAY
    builder.addComponent(nodeStatus)
    builder.addComponent(SettingsUi.hint(t("settings.lsp.node.hint")))
    for (spec in LspDoctor.ALL) {
      if (spec.id !in ServerPaths.OVERRIDABLE) continue
      val field = TextFieldWithBrowseButton().apply {
        text = ServerPaths.get(spec.id)
        addBrowseFolderListener(
          null,
          FileChooserDescriptorFactory.createSingleFileDescriptor()
            .withTitle(t("settings.lsp.choose", "server" to spec.displayName)),
        )
      }
      fields[spec.id] = field
      builder.addLabeledComponent(spec.displayName, field)
      // Ровно та ошибка, которую человек делает первой: в поле сервера кладут путь к ноде.
      ServerPaths.interpreterInstead(spec.id)?.let {
        builder.addComponent(SettingsUi.hint(t("settings.lsp.interpreterInField", "path" to it)))
      }
    }
    builder.addComponent(SettingsUi.hint(t("settings.lsp.hint")))
    return SettingsUi.page(builder.panel)
  }

  /** Что за интерпретатор сейчас в деле и откуда он взят — строка, а не молчание. */
  private fun nodeSummary(): String = when (val outcome = NodeRuntime.outcome(null)) {
    is NodeInterpreter.Outcome.Found -> t("settings.lsp.node.found", "path" to outcome.path,
                                          "source" to sourceName(outcome.source))
    is NodeInterpreter.Outcome.BadSetting -> t("settings.lsp.node.bad", "path" to outcome.path)
    NodeInterpreter.Outcome.Missing -> t("settings.lsp.node.missing")
  }

  /**
   * «Проверить»: запускает интерпретатор и печатает, что он ответил.
   *
   * Спрашиваем сам интерпретатор, а не файловую систему: исполняемый файл не той архитектуры и
   * оборванный симлинк выглядят на диске совершенно здоровыми.
   */
  private fun checkNode(): String {
    val typed = nodePath.text.trim()
    val path = typed.ifEmpty { NodeRuntime.path(null) } ?: return t("settings.lsp.node.missing")
    return runCatching {
      val process = ProcessBuilder(path, "--version").redirectErrorStream(true).start()
      val finished = process.waitFor(CHECK_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
      if (!finished) { process.destroyForcibly(); return t("settings.lsp.node.noAnswer", "path" to path) }
      val answer = process.inputStream.readBytes().decodeToString().trim().lineSequence().firstOrNull().orEmpty()
      if (process.exitValue() == 0) t("settings.lsp.node.works", "path" to path, "version" to answer)
      else t("settings.lsp.node.failed", "path" to path, "reason" to answer)
    }.getOrElse { t("settings.lsp.node.failed", "path" to path, "reason" to (it.message ?: "")) }
  }

  private fun sourceName(source: NodeInterpreter.Source): String = when (source) {
    NodeInterpreter.Source.SETTING -> t("settings.lsp.node.source.setting")
    NodeInterpreter.Source.NVMRC -> t("settings.lsp.node.source.nvmrc")
    NodeInterpreter.Source.NVM -> t("settings.lsp.node.source.nvm")
    NodeInterpreter.Source.FNM -> t("settings.lsp.node.source.fnm")
    NodeInterpreter.Source.VOLTA -> t("settings.lsp.node.source.volta")
    NodeInterpreter.Source.ASDF -> t("settings.lsp.node.source.asdf")
    NodeInterpreter.Source.SHELL_PATH -> t("settings.lsp.node.source.shell")
    NodeInterpreter.Source.WELL_KNOWN -> t("settings.lsp.node.source.wellKnown")
  }

  override fun isModified(): Boolean =
    nodePath.text.trim() != NodeInterpreter.stored() ||
    fields.any { (id, field) -> field.text.trim() != ServerPaths.get(id) } ||
    phpEngine.selectedItem != PhpServerChoice.stored() ||
    tsEngine.selectedItem != TsServerChoice.stored()

  override fun apply() {
    NodeInterpreter.store(nodePath.text)
    nodeStatus.text = "<html>" + nodeSummary() + "</html>"
    fields.forEach { (id, field) -> ServerPaths.set(id, field.text) }
    (phpEngine.selectedItem as? PhpEngine)?.let { PhpServerChoice.store(it) }
    (tsEngine.selectedItem as? TsEngine)?.let { TsServerChoice.store(it) }
  }

  override fun reset() {
    nodePath.text = NodeInterpreter.stored()
    nodeStatus.text = "<html>" + nodeSummary() + "</html>"
    fields.forEach { (id, field) -> field.text = ServerPaths.get(id) }
    phpEngine.selectedItem = PhpServerChoice.stored()
    tsEngine.selectedItem = TsServerChoice.stored()
  }
}

private const val CHECK_SECONDS = 5L
