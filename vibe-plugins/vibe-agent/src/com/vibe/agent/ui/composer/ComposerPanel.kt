// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.ui.composer

import com.intellij.icons.AllIcons
import com.intellij.ide.dnd.FileCopyPasteUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.TextComponentEmptyText
import com.intellij.util.ui.AsyncProcessIcon
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.WrapLayout
import com.vibe.agent.settings.VibeChatSettings
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.event.ActionEvent
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.AbstractAction
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.ScrollPaneConstants
import javax.swing.TransferHandler
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.text.DefaultEditorKit

/**
 * VibeIDE composer: rounded container with (top→bottom) the injection-queue banner,
 * image attachments, staged context chips, the auto-growing input with the icon row
 * (attach · ▷ · send/stop) bottom-right, and the pill row. The panel owns the staging
 * state; the sender only ever sees a [ComposedMessage].
 */
class ComposerPanel(
  private val project: Project,
  parentDisposable: Disposable,
  private val listener: Listener,
) : JPanel(BorderLayout()) {
  interface Listener {
    /** Validate and start sending; return false to keep the draft in the field (blocking error). */
    fun onSend(message: ComposedMessage): Boolean
    fun onStop()
    /** Short status line for the feed (e.g. "картинки не поддерживаются агентом"). */
    fun onNotice(text: String)
  }

  val queue = InjectionQueue()
  private val context = LinkedHashMap<String, ContextRef>()
  private val images = ArrayList<ImageAttachment>()

  private val input = JBTextArea().apply {
    lineWrap = true
    wrapStyleWord = true
    font = JBFont.label().deriveFont(INPUT_FONT_SIZE)
    border = JBUI.Borders.empty(INPUT_PAD_V, INPUT_PAD_H)
    emptyText.text = PLACEHOLDER
    emptyText.setFont(JBFont.label().deriveFont(INPUT_FONT_SIZE))
    TextComponentEmptyText.setupPlaceholderVisibility(this)
    background = BG
  }
  private val scroll = object : JBScrollPane(input) {
    override fun getPreferredSize(): Dimension {
      val d = super.getPreferredSize()
      d.height = d.height.coerceIn(JBUI.scale(INPUT_MIN_HEIGHT), JBUI.scale(INPUT_MAX_HEIGHT))
      return d
    }
  }.apply {
    border = JBUI.Borders.empty()
    viewportBorder = JBUI.Borders.empty()
    horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
    verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
    isOpaque = false
    viewport.isOpaque = false
  }

  private val contextStrip = ChipStrip()
  private val attachmentsStrip = ChipStrip()
  private val queueBanner = QueueBanner(queue)

  private val attachButton = PillButton(icon = AllIcons.Actions.Attach) { chooseImages() }.apply {
    toolTipText = ATTACH_TOOLTIP
  }
  private val continueButton = PillButton(icon = AllIcons.Actions.Play_forward) { sendContinue() }
  private val sendButton = PillButton(icon = AllIcons.Actions.Upload, accent = true) { submit() }.apply {
    toolTipText = "Отправить сообщение (Enter; Shift+Enter — перенос строки)"
  }
  private val stopButton = PillButton(icon = AllIcons.Run.Stop) { listener.onStop() }.apply {
    toolTipText = "Остановить генерацию (Esc)"
    isVisible = false
  }
  private val spinner = AsyncProcessIcon("vibe-composer-busy").apply { isVisible = false }
  private val pillsLeft = JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(PILL_GAP), JBUI.scale(PILL_GAP))).apply { isOpaque = false }
  private val pillsRight = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(PILL_GAP), 0)).apply { isOpaque = false }

  private val mention: MentionPopup
  private val slash: SlashPopup
  private var focused = false
  private var imagesAllowed = true
  private var imagesBlockedReason: String? = null

  /** True while a turn is running: Enter queues, send↔stop swap, ▷ hidden. */
  var busy: Boolean = false
    set(value) {
      field = value
      sendButton.isVisible = !value
      stopButton.isVisible = value
      continueButton.isVisible = !value
      spinner.isVisible = value
      updateSendEnabled()
      revalidate()
      repaint()
    }

  /** False when there is nothing to send to (no agent/provider): the send button stays disabled. */
  var targetAvailable: Boolean = true
    set(value) { field = value; updateSendEnabled() }

  init {
    isOpaque = false
    border = JBUI.Borders.empty(OUTER_PAD)

    val top = JPanel().apply {
      layout = BoxLayout(this, BoxLayout.Y_AXIS)
      isOpaque = false
      add(queueBanner.apply { alignmentX = LEFT_ALIGNMENT })
      add(attachmentsStrip.apply { alignmentX = LEFT_ALIGNMENT })
      add(contextStrip.apply { alignmentX = LEFT_ALIGNMENT })
    }
    val icons = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(ICON_GAP), 0)).apply {
      isOpaque = false
      add(attachButton)
      add(continueButton)
      add(sendButton)
      add(stopButton)
    }
    val iconColumn = JPanel(BorderLayout()).apply {
      isOpaque = false
      add(icons, BorderLayout.SOUTH)
    }
    val inputRow = JPanel(BorderLayout(JBUI.scale(ICON_GAP), 0)).apply {
      isOpaque = false
      add(scroll, BorderLayout.CENTER)
      add(iconColumn, BorderLayout.EAST)
    }
    pillsRight.add(spinner)
    val pills = JPanel(BorderLayout()).apply {
      isOpaque = false
      border = JBUI.Borders.compound(JBUI.Borders.customLineTop(SEPARATOR), JBUI.Borders.emptyTop(PILL_GAP))
      add(pillsLeft, BorderLayout.CENTER)
      // EAST stretches to the row height; GridBag keeps the history pill vertically centred in it.
      add(JPanel(java.awt.GridBagLayout()).apply {
        isOpaque = false
        add(pillsRight)
      }, BorderLayout.EAST)
    }
    val box = RoundedBox().apply {
      add(top, BorderLayout.NORTH)
      add(inputRow, BorderLayout.CENTER)
      add(pills, BorderLayout.SOUTH)
    }
    add(box, BorderLayout.CENTER)

    mention = MentionPopup(project, input, parentDisposable, { EditorContext.currentSelection(project) }) { addContext(it) }
    slash = SlashPopup(project, input, parentDisposable)
    installKeys(parentDisposable)
    installTransfer()
    input.document.addDocumentListener(object : DocumentListener {
      // The scroll pane is a validate root: auto-grow needs a revalidate ABOVE it.
      private fun changed() { updateSendEnabled(); this@ComposerPanel.revalidate(); this@ComposerPanel.repaint() }
      override fun insertUpdate(e: DocumentEvent) = changed()
      override fun removeUpdate(e: DocumentEvent) = changed()
      override fun changedUpdate(e: DocumentEvent) = changed()
    })
    input.addFocusListener(object : FocusAdapter() {
      override fun focusGained(e: FocusEvent) { focused = true; box.repaint() }
      override fun focusLost(e: FocusEvent) { focused = false; box.repaint() }
    })
    // Clicking any non-interactive spot of the composer returns focus to the field.
    val focusOnClick = object : MouseAdapter() {
      override fun mouseClicked(e: MouseEvent) { focusInput() }
    }
    box.addMouseListener(focusOnClick)
    top.addMouseListener(focusOnClick)
    pills.addMouseListener(focusOnClick)
    busy = false
  }

  // --- public API for the panel ---

  fun addPill(component: JComponent) { pillsLeft.add(component); pillsLeft.revalidate() }
  fun addRightPill(component: JComponent) { pillsRight.add(component, 0); pillsRight.revalidate() }

  fun focusInput() { input.requestFocusInWindow() }

  /** The text field — the tool window points its preferred focus here. */
  val inputComponent: JComponent get() = input

  /** Puts a message back as the editable draft (a queued note the sender refused must not be lost). */
  fun restoreDraft(message: ComposedMessage) {
    input.text = message.text
    message.context.forEach { context[it.key] = it }
    // Images are kept even when the current target refuses them: the send-time guards decide,
    // and the user may be about to switch the model back.
    images.addAll(message.images)
    if (!imagesAllowed && message.images.isNotEmpty()) imagesBlockedReason?.let { listener.onNotice(it) }
    renderContext()
    renderAttachments()
    focusInput()
  }

  fun addContext(ref: ContextRef) {
    context[ref.key] = ref
    renderContext()
    focusInput()
  }

  /** Attach capability of the current target; images are refused with [reason] when false. */
  fun setImagesAllowed(allowed: Boolean, reason: String?) {
    imagesAllowed = allowed
    imagesBlockedReason = reason
    attachButton.isEnabled = allowed
    attachButton.toolTipText = if (allowed) ATTACH_TOOLTIP else reason
    if (!allowed && images.isNotEmpty()) {
      images.clear()
      renderAttachments()
    }
  }

  /** Current draft (text + staged context + images) without clearing it. */
  fun composed(): ComposedMessage = ComposedMessage(input.text.trim(), context.values.toList(), images.toList())

  fun clearDraft() {
    input.text = ""
    context.clear()
    images.clear()
    renderContext()
    renderAttachments()
  }

  // --- sending ---

  private fun submit() {
    if (mention.isShowing || slash.isShowing) return
    val message = composed()
    if (message.isEmpty) return
    if (busy) {
      queue.add(message)
      clearDraft()
      return
    }
    if (!targetAvailable) return
    if (listener.onSend(message)) clearDraft()
    focusInput()
  }

  private fun sendContinue() {
    if (busy || !targetAvailable) return
    listener.onSend(ComposedMessage(VibeChatSettings.continueText))
    focusInput()
  }

  private fun updateSendEnabled() {
    sendButton.isEnabled = targetAvailable && !composed().isEmpty
    continueButton.isEnabled = targetAvailable
    continueButton.toolTipText = VibeChatSettings.continueText
  }

  // --- keys ---

  private fun installKeys(parentDisposable: Disposable) {
    val im = input.getInputMap(JComponent.WHEN_FOCUSED)
    val am = input.actionMap
    im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), ACTION_SUBMIT)
    im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.SHIFT_DOWN_MASK), DefaultEditorKit.insertBreakAction)
    am.put(ACTION_SUBMIT, object : AbstractAction() {
      override fun actionPerformed(e: ActionEvent) = submit()
    })
    // Backspace/Esc are claimed by IDE editor actions before Swing sees them (IdeKeyEventDispatcher
    // routes every non-letter key on a text component through the keymap), so the chip and stop
    // shortcuts are component-local actions — enabled only when they apply, otherwise the event
    // falls through to normal editing / the IDE's own Escape (focus back to the editor).
    val menuMask = Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx
    localShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), parentDisposable,
      enabled = { !mention.isShowing && context.isNotEmpty() && input.selectionStart == input.selectionEnd && (input.text.isEmpty() || input.caretPosition == 0) },
      perform = ::popContext)
    localShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, menuMask), parentDisposable,
      enabled = { !mention.isShowing && context.isNotEmpty() },
      perform = ::clearContext)
    localShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), parentDisposable,
      enabled = { mention.isShowing || slash.isShowing || busy },
      perform = {
        when {
          mention.isShowing -> mention.close()
          slash.isShowing -> slash.close()
          busy -> listener.onStop()
        }
      })
  }

  private fun localShortcut(key: KeyStroke, parentDisposable: Disposable, enabled: () -> Boolean, perform: () -> Unit) {
    object : DumbAwareAction() {
      override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
      override fun update(e: AnActionEvent) { e.presentation.isEnabled = enabled() }
      override fun actionPerformed(e: AnActionEvent) = perform()
    }.registerCustomShortcutSet(CustomShortcutSet(key), input, parentDisposable)
  }

  // --- context chips ---

  private fun popContext() {
    val last = context.keys.lastOrNull() ?: return
    context.remove(last)
    renderContext()
  }

  private fun clearContext() {
    context.clear()
    renderContext()
  }

  private fun renderContext() {
    contextStrip.setChips(context.values.map { ref ->
      val icon = when (ref) {
        is ContextRef.File -> ref.file.fileType.icon
        is ContextRef.Folder -> AllIcons.Nodes.Folder
        is ContextRef.Selection -> AllIcons.Actions.InSelection
      }
      val tooltip = when (ref) {
        is ContextRef.Selection -> "${ref.file.path}:${ref.fromLine}-${ref.toLine} — открыть с выделением"
        else -> "${ref.file.path} — открыть"
      }
      Chip(icon, ref.label, tooltip, { EditorContext.open(project, ref) }) {
        context.remove(ref.key)
        renderContext()
      }
    })
    updateSendEnabled()
  }

  // --- attachments ---

  private fun chooseImages() {
    if (!imagesAllowed) return
    Attachments.choose(project, this) { picked -> addImages(picked) }
  }

  private fun addImages(picked: List<ImageAttachment>) {
    if (picked.isEmpty()) return
    if (!imagesAllowed) {
      imagesBlockedReason?.let { listener.onNotice(it) }
      return
    }
    val (ok, tooLarge) = picked.partition { it.bytes.size <= Attachments.MAX_IMAGE_BYTES }
    tooLarge.forEach { listener.onNotice("изображение ${it.name} (${it.sizeKb} КБ) больше лимита ${Attachments.MAX_IMAGE_MB} МБ — не прикреплено") }
    if (ok.isEmpty()) return
    images.addAll(ok)
    renderAttachments()
    focusInput()
  }

  private fun renderAttachments() {
    attachmentsStrip.setChips(images.map { image ->
      Chip(AllIcons.FileTypes.Image, "${image.name} · ${image.sizeKb} КБ", image.mimeType, null) {
        images.remove(image)
        renderAttachments()
      }
    })
    updateSendEnabled()
  }

  /**
   * Paste/drop: images become attachments, project files become context chips,
   * anything else (plain text) goes to the default text handler.
   */
  private fun installTransfer() {
    val delegate = input.transferHandler
    val handler = object : TransferHandler() {
      // The text delegate casts the target to JTextComponent: consult it only for the field itself.
      override fun canImport(support: TransferSupport): Boolean =
        support.isDataFlavorSupported(DataFlavor.imageFlavor) ||
        FileCopyPasteUtil.isFileListFlavorAvailable(support.dataFlavors) ||
        (support.component === input && delegate.canImport(support))

      override fun importData(support: TransferSupport): Boolean {
        val t = support.transferable
        if (FileCopyPasteUtil.isFileListFlavorAvailable(support.dataFlavors)) {
          val files = FileCopyPasteUtil.getFileList(t).orEmpty()
          Attachments.loadAsync(files.filter { Attachments.isImageFile(it.name) }) { addImages(it) }
          val fs = LocalFileSystem.getInstance()
          files.filter { !Attachments.isImageFile(it.name) }.mapNotNull { fs.findFileByIoFile(it) }.forEach { vf ->
            addContext(if (vf.isDirectory) ContextRef.Folder(vf) else ContextRef.File(vf))
          }
          return true
        }
        if (support.isDataFlavorSupported(DataFlavor.imageFlavor) && !support.isDataFlavorSupported(DataFlavor.stringFlavor)) {
          Attachments.fromTransferable(t)?.let { addImages(listOf(it)); return true }
        }
        return support.component === input && delegate.importData(support)
      }
    }
    input.transferHandler = handler
    transferHandler = handler
  }

  /** Rounded background + border; border brightens while the input has focus. */
  private inner class RoundedBox : JPanel(BorderLayout(0, JBUI.scale(INNER_GAP))) {
    init {
      isOpaque = false
      border = JBUI.Borders.empty(INNER_PAD)
    }

    override fun paintComponent(g: Graphics) {
      val g2 = g.create() as Graphics2D
      try {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val arc = JBUI.scale(ARC)
        g2.color = BG
        g2.fillRoundRect(0, 0, width, height, arc, arc)
        g2.color = if (focused) FOCUS_BORDER else BORDER
        g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
      }
      finally {
        g2.dispose()
      }
      super.paintComponent(g)
    }
  }

  companion object {
    const val PLACEHOLDER = "План, @ для контекста"
    private const val ATTACH_TOOLTIP = "Прикрепить изображение (или вставить / перетащить)"
    private const val ACTION_SUBMIT = "vibe.composer.submit"
    /** VibeIDE: textarea min 60px, max 500px, then inner scroll. */
    private const val INPUT_MIN_HEIGHT = 60
    private const val INPUT_MAX_HEIGHT = 500
    private const val INPUT_FONT_SIZE = 13f
    private const val INPUT_PAD_V = 4
    private const val INPUT_PAD_H = 6
    private const val OUTER_PAD = 6
    private const val INNER_PAD = 8
    private const val INNER_GAP = 4
    private const val ICON_GAP = 2
    private const val PILL_GAP = 4
    private const val ARC = 16

    val BG: Color = JBColor.namedColor("Vibe.Composer.background", JBColor.namedColor("TextArea.background", JBColor.PanelBackground))
    val BORDER: Color = JBColor.namedColor("Vibe.Composer.border", JBColor.namedColor("Component.borderColor", JBColor.border()))
    val FOCUS_BORDER: Color = JBColor.namedColor("Vibe.Composer.focusBorder", JBColor.namedColor("Component.focusedBorderColor", JBColor.BLUE))
    val SEPARATOR: Color = JBColor.namedColor("Vibe.Composer.separator", JBColor.namedColor("Separator.separatorColor", JBColor.border()))
  }
}
