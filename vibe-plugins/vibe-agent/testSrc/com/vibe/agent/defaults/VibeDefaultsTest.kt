// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.defaults

import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VibeDefaultsTest {
  private fun tempProject() = Files.createTempDirectory("vibe-defaults-test").toString()

  @Test
  fun seedsFullEnvironmentIntoEmptyProject() {
    val base = tempProject()
    val report = VibeDefaults.seed(base)
    assertTrue(report.created >= 62, "created=${report.created}")
    val vibe = java.nio.file.Path.of(base, ".vibe")
    // Spot checks across every section of the shared VibeBrains set — the full
    // resources↔manifest correspondence is guarded by the gate test above.
    for (f in listOf("README.md", ".gitignore", "rules.md", "hooks.json",
                     "pipelines.json", "servers.json",
                     "design/components.md", "design/uiKit.md", "local/.seeded.json",
                     "agents.json",
                     "learning/MISSION.example.md", "prompts/pipeline.md",
                     "rules/verification.mdc", "skills/review-pr/SKILL.md",
                     "providers/README.md", "providers/_template-openai-compatible.jsonc",
                     "providers/opencode-go.jsonc", "providers/opencode-zen.jsonc",
                     "providers/openrouter.jsonc", "providers/minimax.jsonc",
                     "providers/zai.jsonc", "providers/kimi.jsonc", "providers/deepseek.jsonc",
                     "providers/openai.jsonc", "providers/anthropic.jsonc",
                     "providers/alibaba-coding-plan.jsonc", "providers/meta-muse.jsonc",
                     "providers/muse-glimmer-local.jsonc", "providers/ollama.jsonc")) {
      assertTrue(Files.isRegularFile(vibe.resolve(f)), "missing $f")
    }
    // Runtime artifacts are git-ignored from the very first seed.
    val gitignore = Files.readString(vibe.resolve(".gitignore"))
    // Рантайм закрыт одной строкой — папкой, а не перечислением имён, которое приходилось
    // дополнять при каждом новом файле и забывать. Старые имена оставлены на случай, когда
    // перенос не случился (файл занят, нет прав), — иначе журнал попал бы в коммит.
    assertTrue(gitignore.contains("local/"), "рантайм-папка обязана быть закрыта: " + gitignore)
    assertTrue(gitignore.contains("audit.jsonl") && gitignore.contains("checkpoints.jsonl"))
  }

  @Test
  fun seededProviderCatalogIsValidAndActivatesTheBase() {
    val base = tempProject()
    VibeDefaults.seed(base)
    val warnings = mutableListOf<String>()
    val emptyGlobal = Files.createTempDirectory("vibe-empty-global")
    val providers = com.vibe.agent.providers.ProvidersService.loadFrom(
      emptyGlobal, java.nio.file.Path.of(base, ".vibe")) { warnings.add(it) }
    // Everything ships switched on (owner's call): what stays off is only the copy-me template
    // and the tariff that feeds prompts to the vendor's training.
    val ids = providers.map { it.id }.toSet()
    assertTrue(ids.containsAll(setOf("opencode-go", "opencode-zen", "openrouter", "minimax",
                                     "zai", "kimi", "deepseek", "openai", "anthropic", "ollama")),
               "ids=$ids")
    assertTrue("my-provider" !in ids, "шаблон-образец не должен попадать в реестр")
    assertTrue("meta-muse-contributor" !in ids, "contributor-тариф включается только руками")
    // Every seeded file parsed cleanly — a JSONC typo anywhere in the catalog shows up here.
    assertTrue(warnings.isEmpty(), "warnings=$warnings")
    // Keys never live in the files: every provider that needs one names where it comes from.
    // Local endpoints (auth "none") legitimately need no key at all.
    for (p in providers) {
      assertTrue(!p.baseURL.isNullOrBlank(), "provider '${p.id}' has no baseURL")
      if (p.auth.type == "none") continue
      assertTrue(p.apiKeyEnv != null || p.apiKeyRef != null, "provider '${p.id}' has no key source")
    }
  }

  @Test
  fun manifestMatchesEmbeddedResourcesExactly() {
    // The seed set will live in a shared submodule (VibeBrains): a file added there without a
    // manifest entry would silently never be seeded, and a manifest entry without a file would
    // silently seed nothing — both must fail loudly here.
    val resources = listEmbeddedResources()
    val manifest = VibeDefaults.manifestResourceNames().toSet()
    assertEquals(emptySet(), manifest - resources, "манифест ссылается на несуществующие ресурсы")
    // Файлы, адресованные другому продукту, засевать не наше дело — и требовать их в манифесте
    // тоже: иначе первый же файл соседа в общем наборе красит этот гейт и вынуждает нас
    // засеять чужое. Сегодня адресации в наборе нет и множество пусто.
    val foreign = resources.filterNot { VibeDefaults.addressedToUs(it) }.toSet()
    // Set-metadata files (deprecated.json) serve the seeders and are never seeded.
    assertEquals(emptySet(), resources - manifest - VibeDefaults.SET_METADATA - foreign,
                 "ресурс без записи в манифесте — не будет засеян")
    assertEquals(emptySet(), manifest.filterNot { VibeDefaults.addressedToUs(it) }.toSet(),
                 "манифест засевает файл, адресованный другому продукту")
  }

  private fun listEmbeddedResources(): Set<String> {
    val url = VibeDefaults::class.java.getResource("/vibeDefaults") ?: error("нет /vibeDefaults в classpath")
    return when (url.protocol) {
      "file" -> {
        val root = java.nio.file.Path.of(url.toURI())
        java.nio.file.Files.walk(root).use { s ->
          s.filter { Files.isRegularFile(it) }.map { root.relativize(it).toString() }.toList()
        }.toSet()
      }
      "jar" -> {
        // IntelliJ's test classloader returns its own URLConnection — parse the jar path
        // out of the URL (jar:file:/path/to.jar!/vibeDefaults) instead of casting.
        val jarPath = java.net.URLDecoder.decode(
          url.toString().substringAfter("jar:file:").substringBefore("!"), Charsets.UTF_8)
        java.util.zip.ZipFile(jarPath).use { zip ->
          zip.entries().asSequence()
            .filter { !it.isDirectory && it.name.startsWith("vibeDefaults/") }
            .map { it.name.removePrefix("vibeDefaults/") }
            .toSet()
        }
      }
      else -> error("неожиданный протокол ресурсов: ${url.protocol}")
    }
  }

  @Test
  fun secondSeedKeepsEverythingUntouched() {
    val base = tempProject()
    VibeDefaults.seed(base)
    val rules = java.nio.file.Path.of(base, ".vibe", "rules.md")
    Files.writeString(rules, "MY CUSTOM RULES")
    val second = VibeDefaults.seed(base)
    assertEquals(0, second.created)
    // A user's edit is never overwritten.
    assertEquals("MY CUSTOM RULES", Files.readString(rules))
  }

  @Test
  fun seedFillsOnlyMissingFiles() {
    val base = tempProject()
    val vibe = java.nio.file.Path.of(base, ".vibe")
    Files.createDirectories(vibe)
    Files.writeString(vibe.resolve("hooks.json"), "pre-existing")
    val report = VibeDefaults.seed(base)
    assertTrue(report.created > 0)
    assertTrue(report.kept >= 1)
    assertEquals("pre-existing", Files.readString(vibe.resolve("hooks.json")))
  }

  @Test
  fun staleSeedIsRemovedOnlyWhenByteIdenticalToAKnownVersion() {
    val vibe = Files.createTempDirectory("vibe-deprecated-test")
    Files.createDirectories(vibe.resolve("providers"))
    val pristine = "// historical seed content\n"
    Files.writeString(vibe.resolve("providers/old-a.jsonc"), pristine)
    Files.writeString(vibe.resolve("providers/old-b.jsonc"), "// user-edited content\n")
    val journal = mutableMapOf(
      "providers/old-a.jsonc" to VibeDefaults.JournalEntry("x"),
      "providers/old-b.jsonc" to VibeDefaults.JournalEntry("y"),
    )
    val spec = """{"deprecated":[
      {"path":"providers/old-a.jsonc","sha256":["${VibeDefaults.sha256(pristine)}"]},
      {"path":"providers/old-b.jsonc","sha256":["${VibeDefaults.sha256(pristine)}"]},
      {"path":"providers/old-c.jsonc","sha256":["${VibeDefaults.sha256(pristine)}"]}
    ]}"""
    // CRLF checkout of a known version must also count as untouched.
    Files.createDirectories(vibe.resolve("crlf"))
    Files.writeString(vibe.resolve("crlf/old-d.jsonc"), pristine.replace("\n", "\r\n"))
    val specD = """{"deprecated":[{"path":"crlf/old-d.jsonc","sha256":["${VibeDefaults.sha256(pristine)}"]}]}"""
    val (removedD, _) = VibeDefaults.cleanupDeprecated(vibe, mutableMapOf(), specD)
    assertEquals(listOf("crlf/old-d.jsonc"), removedD)

    val (removed, keptModified) = VibeDefaults.cleanupDeprecated(vibe, journal, spec)
    // Byte-identical to a known version — deleted, journal entry gone.
    assertEquals(listOf("providers/old-a.jsonc"), removed)
    assertTrue(!Files.exists(vibe.resolve("providers/old-a.jsonc")))
    assertTrue(!journal.containsKey("providers/old-a.jsonc"))
    // Edited — kept and reported; absent — silently nothing.
    assertEquals(listOf("providers/old-b.jsonc"), keptModified)
    assertTrue(Files.exists(vibe.resolve("providers/old-b.jsonc")))
    assertTrue(journal.containsKey("providers/old-b.jsonc"))
  }

  @Test
  fun realDeprecatedManifestIsWellFormed() {
    // The embedded deprecated.json (VibeBrains set metadata) must parse and carry hashes.
    val vibe = Files.createTempDirectory("vibe-deprecated-real")
    val spec = checkNotNull(
      VibeDefaultsTest::class.java.getResourceAsStream("/vibeDefaults/deprecated.json")) { "нет deprecated.json" }
      .use { it.readBytes().toString(Charsets.UTF_8) }
    // No stale files on disk → nothing removed, nothing reported, no crash.
    val (removed, keptModified) = VibeDefaults.cleanupDeprecated(vibe, mutableMapOf(), spec)
    assertEquals(emptyList(), removed)
    assertEquals(emptyList(), keptModified)
    assertTrue(spec.contains("openai-gpt56") && spec.contains("sha256"))
  }

  @Test
  fun journalRecordsSeededHashes() {
    val base = tempProject()
    VibeDefaults.seed(base)
    val journal = Files.readString(java.nio.file.Path.of(base, ".vibe", "local", ".seeded.json"))
    assertTrue(journal.contains("rules.md"))
    assertTrue(journal.contains("hooks.json"))
  }

  @Test
  fun `нет адресации — весь набор наш, есть запись — чужое не наше`() {
    assertEquals(emptyMap(), VibeDefaults.parseTargeting(null))
    assertTrue(VibeDefaults.addressedToUs("patrols.json"), "без адресации набор целиком наш")

    val map = VibeDefaults.parseTargeting(
      """
      { "version": 1, "files": [
        { "path": "patrols.json", "products": ["vibeidea"] },
        { "path": "somethingElse.json", "products": ["vibeide"] },
        { "path": "forEveryone.json" }
      ] }
      """.trimIndent()
    )
    assertEquals(listOf("vibeidea"), map["patrols.json"])
    assertEquals(listOf("vibeide"), map["somethingElse.json"])
    assertTrue("forEveryone.json" !in map, "запись без products — то же самое, что её отсутствие")
  }

  @Test
  fun `каждый products в отгружаемом наборе состоит из известных id`() {
    // Опечатка `vibeidee` делает запись ничьей: она молча не сработает НИГДЕ — ровно тот класс
    // отказов, ради которого поле и вводилось. Рантайм про незнакомый id молчит намеренно (это
    // совместимость вперёд), поэтому ловить опечатку обязан тест, и по отгружаемым байтам.
    val unknown = LinkedHashMap<String, MutableSet<String>>()
    for (name in listEmbeddedResources()) {
      if (!name.endsWith(".json") && !name.endsWith(".jsonc")) continue
      val text = VibeDefaults::class.java.getResource("/vibeDefaults/$name")?.readText() ?: continue
      val root = runCatching {
        kotlinx.serialization.json.Json.parseToJsonElement(com.vibe.agent.util.VibeJsonc.strip(text))
      }.getOrNull() ?: continue
      collectProducts(root) { id ->
        if (id !in com.vibe.agent.defaults.VibeProducts.KNOWN) unknown.getOrPut(name) { LinkedHashSet() }.add(id)
      }
    }
    assertEquals(emptyMap(), unknown, "неизвестный id продукта в наборе — почти всегда опечатка")
  }

  private fun collectProducts(el: kotlinx.serialization.json.JsonElement, sink: (String) -> Unit) {
    when (el) {
      is kotlinx.serialization.json.JsonObject -> {
        com.vibe.agent.defaults.VibeProducts.declaredProducts(el)?.forEach(sink)
        el.values.forEach { collectProducts(it, sink) }
      }
      is kotlinx.serialization.json.JsonArray -> el.forEach { collectProducts(it, sink) }
      else -> Unit
    }
  }
}
