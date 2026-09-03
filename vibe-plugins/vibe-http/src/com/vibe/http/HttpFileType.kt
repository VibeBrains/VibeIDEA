// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.http

import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.util.IconLoader
import com.vibe.agent.i18n.VibeI18n.t
import javax.swing.Icon

/**
 * Тип файла `.http` — чтобы запросы были узнаваемы в дереве проекта и открывались как свои.
 *
 * Со своим языком [HttpLanguage]: он нужен подсветке и значку «выполнить» на полях. Без языка файл
 * открывался бы как безымянный текст, а гаттеру не за что было бы зацепиться.
 */
class HttpFileType : LanguageFileType(HttpLanguage) {
  override fun getName(): String = "VibeHttpRequest"
  override fun getDescription(): String = t("http.filetype.description")
  override fun getDefaultExtension(): String = "http"
  override fun getIcon(): Icon = ICON

  companion object {
    val INSTANCE = HttpFileType()
    private val ICON: Icon = IconLoader.getIcon("/icons/vibeHttp.svg", HttpFileType::class.java)

    /** `.rest` — то же самое под другим именем; так называет их VS Code REST Client. */
    val EXTENSIONS = listOf("http", "rest")
  }
}
