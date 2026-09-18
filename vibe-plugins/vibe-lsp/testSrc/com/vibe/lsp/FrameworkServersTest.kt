// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Vue, Svelte и Astro: регистрация серверов и то, чем чинится их главная особенность.
 *
 * У этих трёх языков дефект не в разборе, а в отсутствии: в открытой платформе `.vue` не
 * существует ни как тип файла, ни как язык. Поэтому здесь заперто не поведение парсера, а связи,
 * потеря которых снаружи выглядит одинаково — «ничего не работает, и никто не сказал почему».
 */
class FrameworkServersTest {
  private val xml: String by lazy {
    val stream = javaClass.classLoader.getResourceAsStream("META-INF/vibe-lsp4ij-integration.xml")
    requireNotNull(stream) { "не найден META-INF/vibe-lsp4ij-integration.xml" }.bufferedReader().readText()
  }

  private fun mappingsOf(serverId: String): List<Pair<String, String>> =
    Regex("<fileNamePatternMapping patterns=\"([^\"]+)\" serverId=\"$serverId\" languageId=\"([^\"]+)\"")
      .findAll(xml)
      .flatMap { match -> match.groupValues[1].split(';').map { it.trim() to match.groupValues[2] } }
      .toList()

  @Test
  fun `каждый сервор объявлен и сопоставлен своему расширению`() {
    val expected = mapOf(
      LspDoctor.VUE.id to ("*.vue" to "vue"),
      LspDoctor.SVELTE.id to ("*.svelte" to "svelte"),
      LspDoctor.ASTRO.id to ("*.astro" to "astro"),
    )
    for ((id, mapping) in expected) {
      assertTrue("<server id=\"$id\"" in xml, "сервер $id не зарегистрирован")
      assertTrue(mapping in mappingsOf(id), "у $id нет сопоставления $mapping")
    }
  }

  @Test
  fun `доктор знает все три — иначе «сервер не найден» не назовёт команду установки`() {
    val ids = LspDoctor.ALL.map { it.id }
    assertTrue(LspDoctor.VUE.id in ids)
    assertTrue(LspDoctor.SVELTE.id in ids)
    assertTrue(LspDoctor.ASTRO.id in ids)
    // Расширение в описании сервера — то, по которому доктор решает, спрашивать ли о нём вообще.
    assertEquals(setOf("vue"), LspDoctor.VUE.extensions)
    assertEquals(setOf("svelte"), LspDoctor.SVELTE.extensions)
    assertEquals(setOf("astro"), LspDoctor.ASTRO.extensions)
  }

  /**
   * Языки фреймворков уходят своим серверам, а не общим.
   *
   * `.vue` отдан Tailwind и ESLint давно — это нормально, они смотрят на файл со своей стороны. А
   * вот vtsls на `.vue` быть НЕ должно: tsserver разбирает однофайловый компонент как TypeScript и
   * даёт ошибку на каждой строке разметки — ровно тот дефект, что был у `.tsx` 05.09.2026.
   */
  @Test
  fun `однофайловые компоненты не отданы tsserver напрямую`() {
    val vtsls = mappingsOf("vibeVtsls").map { it.first }
    assertTrue("*.vue" !in vtsls, "vtsls на .vue разберёт разметку как TypeScript")
    assertTrue("*.svelte" !in vtsls)
    assertTrue("*.astro" !in vtsls)
  }

  /**
   * Плагины tsserver — то, чем разрешается `import X from './X.vue'` в ОБЫЧНОМ `.ts`.
   *
   * Сервер Vue тот файл не открывает вовсе, поэтому починить импорт с его стороны нельзя. Форма
   * записи заперта целиком: `location` без пути означает «ищи там, где лежишь сам» — в чужом
   * проекте это промах, а промах здесь выглядит как красный импорт, а не как ошибка.
   */
  @Test
  fun `плагины tsserver объявлены полностью`() {
    val settings = vtslsSettings(vuePlugin = "/opt/vibe/vue", astroPlugin = "/opt/vibe/astro")
    val plugins = settings.getAsJsonObject("vtsls").getAsJsonObject("tsserver").getAsJsonArray("globalPlugins")
    assertEquals(2, plugins.size())
    val vue = plugins[0].asJsonObject
    assertEquals("@vue/typescript-plugin", vue.get("name").asString)
    assertEquals("/opt/vibe/vue", vue.get("location").asString)
    assertEquals("vue", vue.getAsJsonArray("languages")[0].asString)
    // Без этого плагин молча отключается везде, где взят TypeScript проекта, — то есть почти везде.
    assertTrue(vue.get("enableForWorkspaceTypeScriptVersions").asBoolean)
  }

  /**
   * Плагин, которого нет на диске, не попадает в список.
   *
   * Цена ошибки несимметрична: tsserver на несуществующий `location` отвечает отказом запуска, и
   * ломается ВЕСЬ TypeScript, а не один Vue.
   */
  @Test
  fun `отсутствующий плагин не объявляется`() {
    val settings = vtslsSettings(vuePlugin = null, astroPlugin = null)
    val plugins = settings.getAsJsonObject("vtsls").getAsJsonObject("tsserver").getAsJsonArray("globalPlugins")
    assertEquals(0, plugins.size())
  }

  /**
   * `typescript.tsdk` — обязательное условие Astro, а не пожелание.
   *
   * Сервер отвечает на `initialize` ошибкой `-32603` «The `typescript.tsdk` init option is
   * required» (проверено прямым запросом 18.09.2026). Юнит-тест сервера не поднимает, поэтому
   * запирается форма: поле с пустым значением хуже отсутствующего — сервер примет его за указание.
   */
  @Test
  fun `tsdk передаётся полным путём или не передаётся вовсе`() {
    assertNull(tsdkOptions("/нет/такого/проекта"), "несуществующий путь не должен превращаться в настройку")
    val lib = TsSdk.bundledLib()
    if (lib != null) {
      val options = requireNotNull(tsdkOptions(null))
      assertEquals(lib.toString(), options.getAsJsonObject("typescript").get("tsdk").asString)
    }
  }

  /** Имена бандлов подсветки — те же пять, что собирает скрипт зависимостей. */
  @Test
  fun `бандлы подсветки объявлены для всех пяти языков`() {
    assertEquals(listOf("vue", "svelte", "astro", "sass", "stylus"), VibeTextMateBundles.NAMES)
  }
}
