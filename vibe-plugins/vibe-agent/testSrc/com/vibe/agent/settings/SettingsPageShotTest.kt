// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.settings

import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.util.ui.FormBuilder
import com.vibe.agent.i18n.VibeI18n.t
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.swing.JComponent
import kotlin.test.Test

/**
 * Снимок страницы настроек в картинку — чтобы на неё можно было ПОСМОТРЕТЬ.
 *
 * Три захода подряд числа говорили «всё хорошо», а владелец присылал обрезанную страницу. Числа
 * мерили компонент, глаз видел страницу. Здесь страница собирается теми же формами и с теми же
 * строками каталога, что настоящая, кладётся в окно заданной ширины и рисуется в PNG.
 *
 * Тест ничего не утверждает — он оставляет снимки. Утверждения делают соседние тесты; этот нужен
 * человеку (и мне), чтобы увидеть результат до того, как его увидит владелец.
 */
class SettingsPageShotTest {
  private val out = File(System.getenv("VIBE_SHOTS") ?: "/tmp/claude-501/shots")

  /** Та же сборка, что на странице языковых серверов: список, подсказки, поля с кнопкой обзора. */
  private fun lspLikePage(): JComponent {
    val builder = FormBuilder.createFormBuilder()
    builder.addLabeledComponent(t("settings.lsp.ts.engine"), SettingsUi.combo(arrayOf(
      t("settings.lsp.ts.auto"), t("settings.lsp.ts.vtsls"))))
    builder.addComponent(SettingsUi.hint(t("settings.lsp.ts.hint")))
    builder.addLabeledComponent(t("settings.lsp.node.path"), TextFieldWithBrowseButton())
    builder.addComponent(SettingsUi.hint(t("settings.lsp.node.hint")))
    for (name in listOf("TypeScript (vtsls)", "Angular (@angular/language-server)",
                        "Vue (@vue/language-server)", "Svelte (svelte-language-server)",
                        "Astro (@astrojs/language-server)", "Tailwind CSS", "SCSS/Sass (Some Sass)",
                        "Stylus", "PHP (Phpactor)", "PHP (Intelephense)",
                        "CSS/LESS (vscode-css-language-server)", "ESLint (vscode-eslint-language-server)")) {
      builder.addLabeledComponent(name, TextFieldWithBrowseButton())
    }
    builder.addComponent(SettingsUi.hint(t("settings.lsp.hint")))
    return SettingsUi.page(builder.panel)
  }

  /**
   * Разложить дерево без настоящего окна.
   *
   * `validate()` на не показанном контейнере не делает ничего: первый снимок вышел пустым серым
   * прямоугольником. Раскладку приходится позвать самому, сверху вниз — только после неё у детей
   * появляются размеры, а значит и то, что рисовать.
   */
  private fun layOut(component: java.awt.Component) {
    if (component is java.awt.Container) {
      component.doLayout()
      component.components.forEach { layOut(it) }
    }
  }

  private fun shoot(name: String, page: JComponent, width: Int, height: Int) {
    page.setBounds(0, 0, width, height)
    // Дважды: первая раскладка даёт вьюпорту размер, вторая — его содержимому.
    layOut(page)
    layOut(page)
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val g = image.createGraphics()
    g.color = Color.WHITE
    g.fillRect(0, 0, width, height)
    page.printAll(g)
    g.dispose()
    out.mkdirs()
    val file = File(out, "$name.png")
    javax.imageio.ImageIO.write(image, "png", file)
    val scroll = page as javax.swing.JScrollPane
    val view = scroll.viewport.view
    println("СНИМОК: ${file.absolutePath} (${width}x$height)")
    println("   вьюпорт ${scroll.viewport.width}, вид ${view.width}, минимум вида ${view.minimumSize.width}, " +
            "полоса видна: ${scroll.horizontalScrollBar.isVisible}, " +
            "следует ширине: ${(view as javax.swing.Scrollable).scrollableTracksViewportWidth}")
  }

  @Test
  fun `снимки страницы на трёх ширинах`() {
    // 660 — столько остаётся странице в обычном окне настроек; 500 — узкое окно; 900 — широкое.
    shoot("lsp-660", lspLikePage(), 660, 900)
    shoot("lsp-500", lspLikePage(), 500, 900)
    shoot("lsp-900", lspLikePage(), 900, 900)
  }
}
