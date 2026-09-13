// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.audit

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.ContentFactory
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.vibe.agent.i18n.VibeI18n.t
import com.vibe.agent.settings.VibeAgentSettings
import com.vibe.agent.ui.VibeScroll
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.SwingUtilities

/**
 * «Аудит»: the agent's log as it is written, not as a snapshot taken when a dialog opened.
 *
 * Incremental by construction — see [AuditTail]. A viewer that re-read a rotating ten-megabyte file
 * every second would cost more than the work it observes.
 */
class VibeAuditPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {
  private val area = JTextArea().apply {
    isEditable = false
    font = java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, JBUI.scaleFontSize(11f))
  }
  private val follow = JBCheckBox(t("audit.follow"), true)
  private val onlyFailures = JBCheckBox(t("audit.onlyFailures"), false)
  private val status = JBLabel().apply { foreground = JBColor.GRAY; font = JBFont.label().deriveFont(11f) }

  private var position: AuditTail.Position? = null
  private var carry = ""
  private var lines = mutableListOf<String>()

  private val timer = AppExecutorUtil.getAppScheduledExecutorService()
    .scheduleWithFixedDelay({ runCatching { tick() } }, 1, 2, TimeUnit.SECONDS)

  init {
    border = JBUI.Borders.empty(6)
    val bar = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
      isOpaque = false
      add(follow)
      add(onlyFailures.also { it.addActionListener { render() } })
      add(com.vibe.agent.ui.composer.PillButton(t("audit.clearView"), outlined = true) { lines.clear(); render() })
      add(com.vibe.agent.ui.composer.PillButton(t("audit.summary"), outlined = true) { showSummary() })
      add(com.vibe.agent.ui.composer.PillButton(t("audit.revoke"), outlined = true) { revokeApprovals() })
    }
    val header = JPanel().apply {
      layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
      isOpaque = false
      add(status.apply { alignmentX = Component.LEFT_ALIGNMENT })
      add(bar.apply { alignmentX = Component.LEFT_ALIGNMENT })
    }
    add(header, BorderLayout.NORTH)
    add(VibeScroll.pane(area), BorderLayout.CENTER)
    render()
  }

  private fun file(): Path? = project.basePath?.let { com.vibe.agent.defaults.VibeLocal.file(it, "audit.jsonl") }

  private fun tick() {
    val path = file() ?: return
    if (!Files.exists(path)) return
    val size = Files.size(path)
    // The key changes when the file is replaced; size alone would miss a rotation that lands on
    // exactly the same length.
    val key = runCatching { Files.getAttribute(path, "unix:ino").toString() }
      .getOrElse { Files.getLastModifiedTime(path).toMillis().toString() }
    val step = AuditTail.next(position, size, key)
    val from = when (step) {
      is AuditTail.Step.Idle -> return
      is AuditTail.Step.Restart -> 0L
      is AuditTail.Step.Append -> step.from
    }
    if (step is AuditTail.Step.Restart && position != null) {
      lines.add(t("audit.rotated"))
      carry = ""
    }
    val chunk = readFrom(path, from) ?: return
    val (complete, leftover) = AuditTail.completeLines(carry + chunk)
    carry = leftover
    if (complete.isNotEmpty()) {
      lines.addAll(complete)
      lines = AuditTail.trim(lines, AuditTail.MAX_VIEW_LINES).toMutableList()
    }
    position = AuditTail.Position(size, key)
    SwingUtilities.invokeLater { if (!project.isDisposed) render() }
  }

  private fun readFrom(path: Path, from: Long): String? = runCatching {
    Files.newByteChannel(path).use { channel ->
      channel.position(from)
      val buffer = java.nio.ByteBuffer.allocate((channel.size() - from).coerceAtMost(MAX_CHUNK).toInt())
      channel.read(buffer)
      String(buffer.array(), 0, buffer.position(), StandardCharsets.UTF_8)
    }
  }.getOrNull()

  private fun render() {
    status.text = if (!VibeAgentSettings.auditEnabled) t("audit.disabled")
                  else t("audit.note", "max" to AuditTail.MAX_VIEW_LINES)
    val visible = if (onlyFailures.isSelected) lines.filter { it.contains("\"ok\":false") || it.startsWith("—") } else lines
    area.text = if (visible.isEmpty()) t("audit.empty") else visible.joinToString("\n")
    if (follow.isSelected) area.caretPosition = area.document.length
  }

  /**
   * «What did the agent read, write and run» over the lines this panel holds.
   *
   * The journal answers «what happened at 14:02»; this answers «what did this agent touch», which is
   * the question after an agent was working with private files.
   */
  private fun showSummary() {
    val agents = AuditReadSummary.of(lines.toList())
    val text = if (agents.isEmpty()) t("audit.summary.empty") else buildString {
      for (agent in agents) {
        appendLine(t("audit.summary.agent", "agent" to agent.name, "read" to agent.read.size,
                     "written" to agent.written.size, "commands" to agent.commands))
        if (agent.read.isNotEmpty()) appendLine(t("audit.summary.read", "files" to agent.read.take(SUMMARY_FILES).joinToString(", ")))
        if (agent.written.isNotEmpty()) appendLine(t("audit.summary.written", "files" to agent.written.take(SUMMARY_FILES).joinToString(", ")))
      }
      appendLine()
      append(t("audit.summary.note"))
    }
    com.intellij.openapi.ui.Messages.showInfoMessage(project, text, t("audit.summary.title"))
  }

  /**
   * Withdraws every approval of the project in one step: granting was always one click, and
   * withdrawing must not be «edit the skill and wait for the next question».
   *
   * Only approvals that actually exist are counted and cleared, so the confirmation names real numbers.
   */
  private fun revokeApprovals() {
    val props = com.intellij.ide.util.PropertiesComponent.getInstance(project)
    val base = project.basePath
    val skills = com.vibe.agent.skills.SkillsStore.list(base).map { it.pkg.id }
      .filter { props.getValue(com.vibe.agent.safety.ApprovalKeys.SKILL + it) != null }
    val commands = base?.let { dir ->
      val file = Path.of(dir, com.vibe.agent.commands.ProjectCommands.FILE)
      val text = runCatching { Files.readString(file) }.getOrNull() ?: return@let emptyList()
      com.vibe.agent.commands.ProjectCommands.parse(text).commands.map { it.id }
    }.orEmpty().filter { props.getValue(com.vibe.agent.safety.ApprovalKeys.COMMAND + it) != null }
    val title = t("audit.revoke")
    if (skills.isEmpty() && commands.isEmpty()) {
      com.intellij.openapi.ui.Messages.showInfoMessage(project, t("audit.revoke.none"), title)
      return
    }
    val answer = com.intellij.openapi.ui.Messages.showYesNoDialog(
      project, t("audit.revoke.confirm", "skills" to skills.size, "commands" to commands.size), title,
      com.intellij.openapi.ui.Messages.getWarningIcon(),
    )
    if (answer != com.intellij.openapi.ui.Messages.YES) return
    com.vibe.agent.safety.ApprovalKeys.all(skills, commands).forEach { props.unsetValue(it) }
    VibeAuditService.getInstance(project).get()?.append(AuditEvent(
      ts = System.currentTimeMillis(), action = AuditEvent.Action.REVOKE, ok = true, actor = AuditActor.HUMAN,
      meta = mapOf("skills" to skills.size.toString(), "commands" to commands.size.toString()),
    ))
    com.intellij.openapi.ui.Messages.showInfoMessage(project, t("audit.revoke.done", "skills" to skills.size, "commands" to commands.size), title)
  }

  override fun dispose() {
    timer.cancel(false)
  }

  private companion object {
    /** One tick never reads more than this: a burst of writes must not stall the EDT hop. */
    const val MAX_CHUNK = 1L * 1024 * 1024

    /** Files named per agent in the summary; the counts above them stay exact. */
    const val SUMMARY_FILES = 20
  }
}

class VibeAuditToolWindowFactory : ToolWindowFactory {
  /** Подпись — из каталога строк: идентификатор панели ASCII и не переводится, имя переводится. */
  override fun init(toolWindow: com.intellij.openapi.wm.ToolWindow) {
    toolWindow.stripeTitle = com.vibe.agent.i18n.VibeI18n.t("toolWindow.audit")
  }

  override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
    val panel = VibeAuditPanel(project)
    val content = ContentFactory.getInstance().createContent(panel, "", false)
    content.setDisposer(panel)
    toolWindow.contentManager.addContent(content)
  }
}
