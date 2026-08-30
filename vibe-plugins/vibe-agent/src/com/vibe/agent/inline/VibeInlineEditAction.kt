// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.inline

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.vibe.agent.i18n.VibeI18n.t
import com.vibe.agent.providers.ChatMessage
import com.vibe.agent.providers.LlmClient
import com.vibe.agent.providers.ModelEntry
import com.vibe.agent.providers.ProvidersService
import com.vibe.agent.settings.VibeChatSettings

/**
 * Ctrl+K: ask for a change to the selected code and get the change, not a conversation about it.
 *
 * The applied edit goes through the same diff preview as an agent write: an inline edit is still a
 * model rewriting your file, and the fact that you asked for it in one line does not make the
 * result worth trusting unseen.
 */
class VibeInlineEditAction : AnAction({ t("inline.action") }) {
  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val editor = e.getData(CommonDataKeys.EDITOR) ?: return
    val selection = editor.selectionModel
    val code = selection.selectedText?.takeIf { it.isNotBlank() } ?: run {
      Messages.showInfoMessage(project, t("inline.noSelection"), t("inline.title"))
      return
    }
    val input = Messages.showInputDialog(project, t("inline.prompt"), t("inline.title"), null, "", null) ?: return
    if (input.isBlank()) return
    val request = InlineEditPrompt.parse(input)
    val language = e.getData(CommonDataKeys.PSI_FILE)?.language?.id?.lowercase()
    val start = selection.selectionStart
    val end = selection.selectionEnd

    ApplicationManager.getApplication().executeOnPooledThread {
      val answer = ask(project, request, language, code)
      ApplicationManager.getApplication().invokeLater {
        if (answer == null) {
          Messages.showWarningDialog(project, t("inline.noAnswer"), t("inline.title"))
          return@invokeLater
        }
        if (!request.replacesCode) {
          // Explanations are shown, never applied: an explanation pasted over the code is the
          // failure this whole path exists to avoid.
          Messages.showInfoMessage(project, answer.take(EXPLANATION_LIMIT), t("inline.title"))
          return@invokeLater
        }
        val replacement = InlineEditPrompt.extractCode(answer, code) ?: run {
          Messages.showWarningDialog(project, t("inline.notCode") + "\n\n" + answer.take(EXPLANATION_LIMIT), t("inline.title"))
          return@invokeLater
        }
        applyWithPreview(project, editor, start, end, code, replacement)
      }
    }
  }

  private fun ask(project: Project, request: InlineEditPrompt.Request, language: String?, code: String): String? {
    val providers = ProvidersService.load(project.basePath) { }
    // The model the chat is set to: an inline edit that silently used a different one would
    // produce results the user cannot reconcile with what the chat does.
    val stored = VibeChatSettings.rememberedModel(project)
    val provider = providers.firstOrNull { entry -> entry.models.any { it.id == stored } } ?: providers.firstOrNull() ?: return null
    val modelId = stored?.takeIf { id -> provider.models.any { it.id == id } } ?: provider.models.firstOrNull()?.id ?: return null
    val resolved = ProvidersService.resolve(provider, project.basePath) { } ?: return null
    val rules = com.vibe.agent.context.ProjectContextService.getInstance(project).rules()
      .filter { it.alwaysApply }
      .joinToString("\n") { it.body }
      .takeIf { it.isNotBlank() }
    val prompt = InlineEditPrompt.build(request, language, code, instructions(), rules)
    val answer = StringBuilder()
    return runCatching {
      LlmClient().chat(resolved, ModelEntry(id = modelId), listOf(ChatMessage("user", prompt))) { delta ->
        answer.append(delta)
      }
      answer.toString().ifBlank { null }
    }.getOrNull()
  }

  private fun applyWithPreview(project: Project, editor: Editor, start: Int, end: Int, oldCode: String, newCode: String) {
    val document = editor.document
    val file = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getFile(document)
    val path = file?.path ?: t("inline.title")
    val before = document.text
    val after = before.substring(0, start) + newCode + before.substring(end)
    if (!com.vibe.agent.acp.WritePreview.confirm(project, path, before, after)) return
    WriteCommandAction.runWriteCommandAction(project, t("inline.title"), null, Runnable {
      document.replaceString(start, end, newCode)
    })
  }

  private fun instructions() = InlineEditPrompt.Instructions(
    doc = t("inline.instruction.doc"),
    refactor = t("inline.instruction.refactor"),
    tests = t("inline.instruction.tests"),
    explain = t("inline.instruction.explain"),
    fix = t("inline.instruction.fix"),
    free = t("inline.instruction.free"),
    formatRule = t("inline.instruction.format"),
  )

  private companion object {
    const val EXPLANATION_LIMIT = 4000
  }
}
