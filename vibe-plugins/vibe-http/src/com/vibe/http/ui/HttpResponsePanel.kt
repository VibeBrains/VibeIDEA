// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.http.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.JBUI
import com.vibe.agent.i18n.VibeI18n.t
import com.vibe.agent.ui.VibeScroll
import com.vibe.http.HttpExchange
import com.vibe.http.HttpRequestFile
import com.vibe.http.HttpVariables
import com.vibe.http.VibeHttpService
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JTextArea

/**
 * Ответ на запрос — в ЦЕНТРАЛЬНОЙ панели.
 *
 * Концепция владельца 08.09.2026: сбоку действия, в центре то, с чем работают. Ответ — это как раз
 * работа: тело листают, ищут в нём, сверяют заголовки, смотрят историю и банку кук, когда «почему-то
 * 401». В колонке шириной с список запросов JSON на двести строк читать нельзя.
 *
 * Панель ничего не отправляет: запрос выполняет список слева и присылает сюда результат. Так у
 * отправки остаётся ровно одно место — иначе «повторить» из истории и «отправить» из списка
 * однажды разойдутся в том, какие переменные подставлены.
 */
class HttpResponsePanel(private val project: Project) : JPanel(BorderLayout()) {
  private val statusLine = JBLabel(" ").apply { border = JBUI.Borders.empty(4, 8) }
  private val bodyView = area()
  private val headersView = area()
  private val historyView = area()
  private val cookiesView = area()

  /**
   * Вкладка «Cookies» — банка кук, а не отдельный ответ.
   *
   * Поэтому она обновляется по показу и по кнопке, а не по отправке: куку кладёт один запрос, а
   * мешает она следующему, и смотрят сюда именно тогда, когда «почему-то 401».
   */
  private val cookiesPanel = JPanel(BorderLayout()).apply {
    add(VibeScroll.pane(cookiesView), BorderLayout.CENTER)
    add(JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 2)).apply {
      add(JButton(t("http.cookies.refresh"), AllIcons.Actions.Refresh).apply { addActionListener { showCookies() } })
      add(JButton(t("http.cookies.clear"), AllIcons.Actions.GC).apply { addActionListener { clearCookies() } })
    }, BorderLayout.SOUTH)
  }

  private val tabs = JBTabbedPane().apply {
    addTab(t("http.tab.body"), VibeScroll.pane(bodyView))
    addTab(t("http.tab.headers"), VibeScroll.pane(headersView))
    addTab(t("http.tab.history"), VibeScroll.pane(historyView))
    addTab(t("http.tab.cookies"), cookiesPanel)
    addChangeListener { if (selectedComponent === cookiesPanel) showCookies() }
  }

  /**
   * Показывает куки, которые сейчас поедут в запросы.
   *
   * Значение сокращается: чтобы понять, что сессия есть, полный токен не нужен, а панель с полным
   * токеном однажды попадёт на скриншот в тикете.
   */
  private fun showCookies() {
    val now = System.currentTimeMillis()
    val cookies = VibeHttpService.getInstance(project).cookies().filter { com.vibe.http.Cookies.isAlive(it, now) }
    cookiesView.text = if (cookies.isEmpty()) t("http.cookies.empty")
    else cookies.sortedWith(compareBy({ it.domain }, { it.name })).joinToString("\n") { cookie ->
      t("http.cookies.line",
        "name" to cookie.name,
        "value" to com.vibe.http.Cookies.shorten(cookie.value),
        "domain" to cookie.domain,
        "path" to cookie.path,
        "expires" to (cookie.expiresAtEpochMs?.let { java.time.Instant.ofEpochMilli(it).toString() } ?: t("http.cookies.session")))
    }
    cookiesView.caretPosition = 0
  }

  private fun clearCookies() {
    VibeHttpService.getInstance(project).clearCookies()
    showCookies()
    statusLine.foreground = JBColor.foreground()
    statusLine.text = t("http.cookies.cleared")
  }

  /**
   * История ответов проекта. В памяти панели, а не на диске: в ответах токены и персональные
   * данные, и писать их в файлы без спроса нельзя.
   */
  private var history: List<com.vibe.http.HttpHistory.Entry> = emptyList()

  /** Последний отправленный запрос — по нему история узнаёт, чья это запись. */
  private var lastRequest: HttpRequestFile.Request? = null


  init {
    add(statusLine, BorderLayout.NORTH)
    add(tabs, BorderLayout.CENTER)
    HttpWorkbench.getInstance(project).response = this
  }

  private fun area() = JTextArea().apply {
    isEditable = false
    lineWrap = false
    font = com.intellij.util.ui.JBFont.create(java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12))
    border = JBUI.Borders.empty(6)
  }

  /** Перечитывает открытый файл: список запросов и окружения берутся из него. */
  /** Слово к числу подбирает интерфейс: единицы — такой же переводимый текст, как всё остальное. */
  private fun size(bytes: Long): String {
    val size = HttpExchange.size(bytes)
    val value = if (size.unit == HttpExchange.SizeUnit.BYTES) size.value.toLong().toString()
                else String.format("%.1f", size.value)
    return when (size.unit) {
      HttpExchange.SizeUnit.BYTES -> t("http.size.bytes", "value" to value)
      HttpExchange.SizeUnit.KIB -> t("http.size.kib", "value" to value)
      HttpExchange.SizeUnit.MIB -> t("http.size.mib", "value" to value)
    }
  }

  private fun duration(ms: Long): String {
    val duration = HttpExchange.duration(ms)
    return if (duration.inSeconds) t("http.time.seconds", "value" to String.format("%.1f", duration.value))
    else t("http.time.ms", "value" to duration.value.toLong().toString())
  }

  /** Показывает произвольный текст в теле — сводку прогона всех запросов, например. */
  fun showText(text: String) {
    bodyView.text = text
    bodyView.caretPosition = 0
  }

  /** Показывает результат отправки; вызывается списком запросов слева. */
  fun show(
    result: VibeHttpService.Result,
    unresolved: List<HttpVariables.Unresolved>,
    request: HttpRequestFile.Request,
  ) {
    // Запрос приходит вместе с ответом, а не хранится где-то рядом: историю ведёт тот, кто её
    // показывает, и «чей это ответ» не должно зависеть от порядка вызовов.
    lastRequest = request
    when (result) {
      is VibeHttpService.Result.Done -> {
        val response = result.response
        statusLine.foreground = when (HttpExchange.outcome(response.status)) {
          HttpExchange.Outcome.SUCCESS -> JBColor.namedColor("Vibe.Http.success", JBColor(0x208A3C, 0x57965C))
          HttpExchange.Outcome.REDIRECT -> JBColor.namedColor("Vibe.Http.redirect", JBColor(0xC27D04, 0xD6AE58))
          else -> JBColor.namedColor("Vibe.Http.error", JBColor(0xDB3B4B, 0xDB5C5C))
        }
        statusLine.text = t(
          "http.status",
          "status" to response.status,
          "time" to duration(response.durationMs),
          "size" to size(response.sizeBytes),
        )
        bodyView.text = if (HttpExchange.looksLikeJson(response)) HttpExchange.prettyJson(response.body) else response.body
        bodyView.caretPosition = 0
        headersView.text = response.headers.joinToString("\n") { "${it.name}: ${it.value}" }
        remember(response)
      }
      is VibeHttpService.Result.Refused -> {
        statusLine.foreground = JBColor.namedColor("Vibe.Http.error", JBColor(0xDB3B4B, 0xDB5C5C))
        statusLine.text = when (result.refusal.reason) {
          com.vibe.http.HttpCall.Reason.UNRESOLVED_VARIABLE -> t("http.refused.variable", "name" to result.refusal.detail)
          com.vibe.http.HttpCall.Reason.NO_SCHEME -> t("http.refused.scheme", "target" to result.refusal.detail)
          com.vibe.http.HttpCall.Reason.BAD_TARGET -> t("http.refused.target", "target" to result.refusal.detail)
          com.vibe.http.HttpCall.Reason.BODY_FILE_MISSING -> t("http.refused.bodyFile", "path" to result.refusal.detail)
        }
      }
      is VibeHttpService.Result.Failed -> {
        statusLine.foreground = JBColor.namedColor("Vibe.Http.error", JBColor(0xDB3B4B, 0xDB5C5C))
        statusLine.text = t("http.failed", "reason" to result.message)
      }
    }
    // Неподставленные переменные называем ОТДЕЛЬНО и после ответа: сервер мог ответить и на кривой
    // адрес, и тогда «200 OK» без этой строки означал бы, что всё в порядке.
    if (unresolved.isNotEmpty()) {
      statusLine.text = statusLine.text + "  ·  " + t("http.unresolved", "names" to unresolved.joinToString { "{{${it.name}}}" })
    }
  }

  /**
   * Кладёт ответ в историю и показывает её.
   *
   * Отдельной строкой сообщается ТОЛЬКО изменение против прошлого раза: «всё как вчера» на каждом
   * прогоне приучает не читать эту строку вовсе, а «вчера работало» — единственный вопрос, ради
   * которого в историю и заглядывают.
   */
  private fun remember(response: HttpExchange.Response) {
    val request = lastRequest ?: return
    history = com.vibe.http.HttpHistory.add(history, com.vibe.http.HttpHistory.Entry(
      requestTitle = request.title, method = request.method, target = request.target,
      status = response.status, durationMs = response.durationMs, sizeBytes = response.sizeBytes,
      body = response.body, atEpochMs = System.currentTimeMillis(),
    ))
    val mine = com.vibe.http.HttpHistory.of(history, request.title, request.method, request.target)
    val stamp = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")
    historyView.text = mine.joinToString("\n") { entry ->
      val time = java.time.Instant.ofEpochMilli(entry.atEpochMs).atZone(java.time.ZoneId.systemDefault()).format(stamp)
      t("http.history.line", "time" to time, "status" to entry.status,
        "ms" to entry.durationMs, "size" to size(entry.sizeBytes))
    }
    historyView.caretPosition = 0
    com.vibe.http.HttpHistory.changeAgainstPrevious(mine, object : com.vibe.http.HttpHistory.Labels {
      override fun statusChanged(before: Int, now: Int) = t("http.history.statusChanged", "before" to before, "now" to now)
      override fun bodyChanged(comparedWith: Int) = t("http.history.bodyChanged")
    })?.let { statusLine.text = statusLine.text + "  ·  " + it }
  }
}
