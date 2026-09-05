// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.ingest

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.vibe.agent.i18n.VibeI18n.t
import com.vibe.agent.ui.composer.PdfExtract
import com.vibe.agent.ui.composer.PdfText
import java.nio.file.Files
import java.nio.file.Path

/**
 * «Положить в корпус» — документ остаётся в проекте, а не живёт одну сессию.
 *
 * Из интернета ничего не тянем: скачивание по ссылке — это сеть без спроса, а тут файл, который уже
 * лежит на диске у человека, и он сам решил его положить.
 */
class IngestAction : AnAction({ t("ingest.action") }) {
  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val descriptor = FileChooserDescriptor(true, false, false, false, false, false)
      .withTitle(t("ingest.choose.title"))
      .withDescription(t("ingest.choose.description"))
    val chosen = FileChooser.chooseFile(descriptor, project, null) ?: return
    val file = Path.of(chosen.path)
    // Чтение и запуск pdftotext — в фоне: сорокастраничный PDF на EDT подвешивает IDE.
    ApplicationManager.getApplication().executeOnPooledThread {
      val source = read(file)
      ApplicationManager.getApplication().invokeLater {
        if (project.isDisposed) return@invokeLater
        finish(project, source)
      }
    }
  }

  private fun read(file: Path): Result<Ingest.Source> {
    val name = file.fileName.toString()
    val date = java.time.LocalDate.now().toString()
    val head = runCatching { Files.newInputStream(file).use { it.readNBytes(8) } }.getOrDefault(ByteArray(0))
    if (PdfText.looksLikePdf(name, head)) {
      val document = PdfExtract.read(file.toFile()).getOrElse { return Result.failure(it) }
      return Result.success(Ingest.Source(
        title = Ingest.titleFromFileName(name),
        origin = file.toString(),
        text = document.text,
        date = date,
        pages = document.pages,
        droppedChars = document.droppedChars,
      ))
    }
    val text = runCatching { Files.readString(file) }.getOrElse { return Result.failure(it) }
    return Result.success(Ingest.Source(Ingest.titleFromFileName(name), file.toString(), text, date))
  }

  private fun finish(project: Project, read: Result<Ingest.Source>) {
    val source = read.getOrElse {
      Messages.showErrorDialog(project, it.message ?: it.javaClass.simpleName, t("ingest.action"))
      return
    }
    val refusal = Ingest.validate(source)
    if (refusal != null) {
      Messages.showWarningDialog(project, when (refusal) {
        Ingest.Refusal.NO_TEXT -> t("ingest.refusal.noText")
        Ingest.Refusal.NO_TITLE -> t("ingest.refusal.noTitle")
      }, t("ingest.action"))
      return
    }
    val path = IngestStore.getInstance(project).write(source, t("ingest.index.header"))
    if (path == null) {
      Messages.showErrorDialog(project, t("ingest.writeFailed", "folder" to Ingest.FOLDER), t("ingest.action"))
      return
    }
    val file = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
      .refreshAndFindFileByPath(project.basePath + "/" + path)
    if (file != null) com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFile(file, true)
  }
}
