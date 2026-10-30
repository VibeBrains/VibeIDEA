// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.defaults

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Seeds the workspace `.vibe/` environment from defaults embedded in plugin
 * resources — the VibeIDE approach, owner's decision 2026-08-28: VibeIDEA is an
 * agent-first IDE, every project gets its `.vibe/` unconditionally.
 *
 * Rules: create-if-missing ONLY (a user's file is never overwritten); every file
 * we seed is recorded in `.vibe/.seeded.json` as `path → sha256` so a future
 * release-update can tell an untouched seed from a user-customized file (the
 * VibeIDE three-way model; the journal is the groundwork, the update flow is a
 * separate roadmap item). Seeding is best-effort: an IO failure skips the file,
 * never breaks project open.
 */
object VibeDefaults {
  /**
   * Embedded default files: resource name under /vibeDefaults/ → target path inside `.vibe/`.
   * The set is the VibeBrains submodule (shared with VibeIDE) and is seeded IN FULL — the
   * environment must come out identical whichever product seeded it first. Kept in sync with
   * the submodule by the resources↔manifest gate test. The only special mapping is
   * `gitignore.seed` → `.gitignore` (a dotfile can't live in resources as-is).
   */
  private val MANIFEST = listOf(
    "README.md" to "README.md",
    // Шаблон правил проекта в формате agents.md. Засевается В `.vibe/`, а не в корень: `AGENTS.md`
    // уезжает в коммит человека, и класть его в чужой репозиторий без спроса мы не вправе — в
    // корень его переносит действие IDE.
    "AGENTS.template.md" to "AGENTS.template.md",
    "agents.json" to "agents.json",
    "design/components.md" to "design/components.md",
    "dataSources.json" to "dataSources.json",
    "design/uiKit.md" to "design/uiKit.md",
    "gitignore.seed" to ".gitignore",
    "hooks.json" to "hooks.json",
    "http-client.env.json" to "http-client.env.json",
    "learning/MISSION.example.md" to "learning/MISSION.example.md",
    "modelQuirks.json" to "modelQuirks.json",
    "pipelines.json" to "pipelines.json",
    "prompts/CLAUDE-FABLE-5.md" to "prompts/CLAUDE-FABLE-5.md",
    "prompts/deep-review.md" to "prompts/deep-review.md",
    "prompts/example.md" to "prompts/example.md",
    "prompts/pipeline.md" to "prompts/pipeline.md",
    "requests.http" to "requests.http",
    "roles.json" to "roles.json",
    // Provider catalog: auto-loaded by ProvidersService (unlike the *.example.jsonc seeds),
    // `active` is the toggle — owner's decision №24. One file per provider.
    "patrols.json" to "patrols.json",
    "providers/README.md" to "providers/README.md",
    "providers/_template-openai-compatible.jsonc" to "providers/_template-openai-compatible.jsonc",
    "providers/alibaba-coding-plan.jsonc" to "providers/alibaba-coding-plan.jsonc",
    "providers/anthropic.jsonc" to "providers/anthropic.jsonc",
    "providers/arbr.jsonc" to "providers/arbr.jsonc",
    "providers/atria.jsonc" to "providers/atria.jsonc",
    "providers/aws-bedrock.jsonc" to "providers/aws-bedrock.jsonc",
    "providers/azure-openai.jsonc" to "providers/azure-openai.jsonc",
    "providers/chatgpt.jsonc" to "providers/chatgpt.jsonc",
    "providers/cloudflare-ai-gateway.jsonc" to "providers/cloudflare-ai-gateway.jsonc",
    "providers/deepseek.jsonc" to "providers/deepseek.jsonc",
    "providers/stepfun.jsonc" to "providers/stepfun.jsonc",
    "providers/xai.jsonc" to "providers/xai.jsonc",
    "providers/xiaomi-mimo.jsonc" to "providers/xiaomi-mimo.jsonc",
    "providers/edge0.jsonc" to "providers/edge0.jsonc",
    "providers/google-gemini.jsonc" to "providers/google-gemini.jsonc",
    "providers/google-vertex.jsonc" to "providers/google-vertex.jsonc",
    "providers/groq.jsonc" to "providers/groq.jsonc",
    "providers/inference-net.jsonc" to "providers/inference-net.jsonc",
    "providers/kimi.jsonc" to "providers/kimi.jsonc",
    "providers/meta-muse.jsonc" to "providers/meta-muse.jsonc",
    "providers/minimax.jsonc" to "providers/minimax.jsonc",
    "providers/mistral.jsonc" to "providers/mistral.jsonc",
    "providers/muse-glimmer-local.jsonc" to "providers/muse-glimmer-local.jsonc",
    "providers/ollama.jsonc" to "providers/ollama.jsonc",
    "providers/openai.jsonc" to "providers/openai.jsonc",
    "providers/opencode-go.jsonc" to "providers/opencode-go.jsonc",
    "providers/opencode-zen.jsonc" to "providers/opencode-zen.jsonc",
    "providers/openrouter.jsonc" to "providers/openrouter.jsonc",
    "providers/pollinations.jsonc" to "providers/pollinations.jsonc",
    "providers/qwen.jsonc" to "providers/qwen.jsonc",
    "providers/sakana.jsonc" to "providers/sakana.jsonc",
    "providers/zai.jsonc" to "providers/zai.jsonc",
    "rules.md" to "rules.md",
    "rules/brigadier.mdc" to "rules/brigadier.mdc",
    "rules/clean-rules.mdc" to "rules/clean-rules.mdc",
    "rules/dev-engine.mdc" to "rules/dev-engine.mdc",
    "rules/knowledge.mdc" to "rules/knowledge.mdc",
    "rules/nginx.mdc" to "rules/nginx.mdc",
    "rules/pipeline.mdc" to "rules/pipeline.mdc",
    "rules/providers-json.mdc" to "rules/providers-json.mdc",
    "rules/release.mdc" to "rules/release.mdc",
    "rules/review-php.mdc" to "rules/review-php.mdc",
    "rules/review-typescript.mdc" to "rules/review-typescript.mdc",
    "rules/roadmap-autopilot.mdc" to "rules/roadmap-autopilot.mdc",
    "rules/roadmap.mdc" to "rules/roadmap.mdc",
    "rules/script-save.mdc" to "rules/script-save.mdc",
    "rules/security.mdc" to "rules/security.mdc",
    "rules/spec-first.mdc" to "rules/spec-first.mdc",
    "rules/ui-kit.mdc" to "rules/ui-kit.mdc",
    "rules/verification.mdc" to "rules/verification.mdc",
    "rules/versioning.mdc" to "rules/versioning.mdc",
    "rules/watch-media.mdc" to "rules/watch-media.mdc",
    "servers.json" to "servers.json",
    "slop.json" to "slop.json",
    "skills/anti-slop/SKILL.md" to "skills/anti-slop/SKILL.md",
    "skills/anti-slop/references/reviewer.md" to "skills/anti-slop/references/reviewer.md",
    "skills/anti-slop/references/voice.md" to "skills/anti-slop/references/voice.md",
    // The skill's cases: a rewrite without invented facts, an audit without a verdict on authorship, a light hand on a
    // person's own draft.
    "skills/anti-slop/evals/evals.json" to "skills/anti-slop/evals/evals.json",
    "skills/anti-slop/evals/draft.md" to "skills/anti-slop/evals/draft.md",
    "skills/anti-slop/evals/human-post.md" to "skills/anti-slop/evals/human-post.md",
    // Brigadier: the on/off switch and the one-off token saving; worker/ connects the external executor.
    "skills/brigadier/SKILL.md" to "skills/brigadier/SKILL.md",
    "skills/brigadier/evals/evals.json" to "skills/brigadier/evals/evals.json",
    "skills/brigadier/worker/README.md" to "skills/brigadier/worker/README.md",
    "skills/brigadier/worker/providers/deepseek.json" to "skills/brigadier/worker/providers/deepseek.json",
    "skills/brigadier/worker/worker.ps1" to "skills/brigadier/worker/worker.ps1",
    "skills/brigadier/worker/worker.sh" to "skills/brigadier/worker/worker.sh",
    "skills/demo/SKILL.md" to "skills/demo/SKILL.md",
    "skills/demo/evals/evals.json" to "skills/demo/evals/evals.json",
    "skills/security-audit/SKILL.md" to "skills/security-audit/SKILL.md",
    "skills/security-audit/evals/evals.json" to "skills/security-audit/evals/evals.json",
    "skills/design-vocabulary/SKILL.md" to "skills/design-vocabulary/SKILL.md",
    "skills/example/SKILL.md" to "skills/example/SKILL.md",
    // Образец эвалов: случаи проверяют ровно то, что обещает SKILL.md примера.
    "skills/example/evals/evals.json" to "skills/example/evals/evals.json",
    "skills/example/evals/snippet.ts" to "skills/example/evals/snippet.ts",
    "skills/grill/SKILL.md" to "skills/grill/SKILL.md",
    "skills/implement-specs/SKILL.md" to "skills/implement-specs/SKILL.md",
    "skills/incident-review/SKILL.md" to "skills/incident-review/SKILL.md",
    "skills/optimize-by-metric/SKILL.md" to "skills/optimize-by-metric/SKILL.md",
    "skills/party/SKILL.md" to "skills/party/SKILL.md",
    "skills/resolve-merge-conflicts/SKILL.md" to "skills/resolve-merge-conflicts/SKILL.md",
    "skills/resolve-merge-conflicts/scripts/extract_conflict_context.py" to "skills/resolve-merge-conflicts/scripts/extract_conflict_context.py",
    "skills/review-pr/SKILL.md" to "skills/review-pr/SKILL.md",
    "skills/roadmap-autopilot/SKILL.md" to "skills/roadmap-autopilot/SKILL.md",
    "skills/screenshot-review/SKILL.md" to "skills/screenshot-review/SKILL.md",
    "skills/self-excuses/SKILL.md" to "skills/self-excuses/SKILL.md",
    "skills/self-excuses/evals/evals.json" to "skills/self-excuses/evals/evals.json",
    "skills/spec-driven-implementation/SKILL.md" to "skills/spec-driven-implementation/SKILL.md",
    "skills/teach/SKILL.md" to "skills/teach/SKILL.md",
    "skills/triage-failing-test/SKILL.md" to "skills/triage-failing-test/SKILL.md",
    "skills/update-skill/SKILL.md" to "skills/update-skill/SKILL.md",
    "skills/update-skill/references/best-practices.md" to "skills/update-skill/references/best-practices.md",
    "skills/write-product-spec/SKILL.md" to "skills/write-product-spec/SKILL.md",
    "skills/write-product-spec/references/PRODUCT.skeleton.md" to "skills/write-product-spec/references/PRODUCT.skeleton.md",
    "skills/write-tech-spec/SKILL.md" to "skills/write-tech-spec/SKILL.md",
    "skills/write-tech-spec/references/TECH.skeleton.md" to "skills/write-tech-spec/references/TECH.skeleton.md",
    // Skills of the set added 14.10.2026: each folder carries its own LICENSE, seeded like the rest of the folder
    "skills/codebase-design/DEEPENING.md" to "skills/codebase-design/DEEPENING.md",
    "skills/codebase-design/DESIGN-IT-TWICE.md" to "skills/codebase-design/DESIGN-IT-TWICE.md",
    "skills/codebase-design/LICENSE" to "skills/codebase-design/LICENSE",
    "skills/codebase-design/SKILL.md" to "skills/codebase-design/SKILL.md",
    "skills/codebase-design/evals/evals.json" to "skills/codebase-design/evals/evals.json",
    "skills/concept-brief/SKILL.md" to "skills/concept-brief/SKILL.md",
    "skills/concept-brief/assets/build.py" to "skills/concept-brief/assets/build.py",
    "skills/concept-brief/assets/exampleBody.part" to "skills/concept-brief/assets/exampleBody.part",
    "skills/concept-brief/assets/head.part" to "skills/concept-brief/assets/head.part",
    "skills/concept-brief/assets/print.sh" to "skills/concept-brief/assets/print.sh",
    "skills/concept-brief/evals/evals.json" to "skills/concept-brief/evals/evals.json",
    "skills/diagnosing-bugs/LICENSE" to "skills/diagnosing-bugs/LICENSE",
    "skills/diagnosing-bugs/SKILL.md" to "skills/diagnosing-bugs/SKILL.md",
    "skills/diagnosing-bugs/evals/evals.json" to "skills/diagnosing-bugs/evals/evals.json",
    "skills/diagnosing-bugs/scripts/hitl-loop.template.sh" to "skills/diagnosing-bugs/scripts/hitl-loop.template.sh",
    "skills/grilling/LICENSE" to "skills/grilling/LICENSE",
    "skills/grilling/SKILL.md" to "skills/grilling/SKILL.md",
    "skills/grilling/evals/evals.json" to "skills/grilling/evals/evals.json",
    "skills/na-paltsah/SKILL.md" to "skills/na-paltsah/SKILL.md",
    "skills/na-paltsah/evals/evals.json" to "skills/na-paltsah/evals/evals.json",
    "skills/na-paltsah/examples/nginx.md" to "skills/na-paltsah/examples/nginx.md",
    "skills/night-work/SKILL.md" to "skills/night-work/SKILL.md",
    "skills/night-work/evals/evals.json" to "skills/night-work/evals/evals.json",
    "skills/rescue-session/SKILL.md" to "skills/rescue-session/SKILL.md",
    "skills/rescue-session/evals/evals.json" to "skills/rescue-session/evals/evals.json",
    "skills/retro/LICENSE" to "skills/retro/LICENSE",
    "skills/retro/SKILL.md" to "skills/retro/SKILL.md",
    "skills/retro/evals/evals.json" to "skills/retro/evals/evals.json",
    "skills/tdd/LICENSE" to "skills/tdd/LICENSE",
    "skills/tdd/SKILL.md" to "skills/tdd/SKILL.md",
    "skills/tdd/evals/evals.json" to "skills/tdd/evals/evals.json",
    "skills/tdd/mocking.md" to "skills/tdd/mocking.md",
    "skills/tdd/tests.md" to "skills/tdd/tests.md",
    "skills/to-questionnaire/LICENSE" to "skills/to-questionnaire/LICENSE",
    "skills/to-questionnaire/SKILL.md" to "skills/to-questionnaire/SKILL.md",
    "skills/to-questionnaire/evals/evals.json" to "skills/to-questionnaire/evals/evals.json",
    "skills/wait-what/LICENSE" to "skills/wait-what/LICENSE",
    "skills/wait-what/SKILL.md" to "skills/wait-what/SKILL.md",
    "skills/wait-what/evals/evals.json" to "skills/wait-what/evals/evals.json",
    "skills/wizard/LICENSE" to "skills/wizard/LICENSE",
    "skills/wizard/SKILL.md" to "skills/wizard/SKILL.md",
    "skills/wizard/evals/evals.json" to "skills/wizard/evals/evals.json",
    "skills/wizard/template.sh" to "skills/wizard/template.sh",
    "skills/writing-for-agents/LICENSE" to "skills/writing-for-agents/LICENSE",
    "skills/writing-for-agents/SKILL-MECHANICS.md" to "skills/writing-for-agents/SKILL-MECHANICS.md",
    "skills/writing-for-agents/SKILL.md" to "skills/writing-for-agents/SKILL.md",
    "skills/writing-for-agents/evals/evals.json" to "skills/writing-for-agents/evals/evals.json",
  )

  private const val RESOURCE_ROOT = "/vibeDefaults/"
  private const val JOURNAL = ".seeded.json"
  private const val BASE_DIR = "seedBase"
  private const val DEPRECATED = "deprecated.json"
  private const val VERSIONS = "versions.json"

  /**
   * Адресация ФАЙЛОВ набора: `{ "files": [ { "path": "patrols.json", "products": ["vibeidea"] } ] }`.
   *
   * Второй уровень таргетинга, без которого первого не хватает. Поле `products` внутри файла
   * отвечает «кому эта запись», но `patrols.json` и `dataSources.json` в редакторе без наших
   * панелей — не «выключенные», а бессмысленные целиком: их незачем и засевать. Продукт не должен
   * разбирать файл, чтобы узнать, что файл не для него, поэтому адрес объявляется СНАРУЖИ.
   *
   * Файл рукописный и отдельный от `versions.json`: тот генерируется `bump.mjs`, и ручную запись
   * в нём стёрли бы первым же бампом. Пути в файле нет — файл для всех.
   *
   * Имя подтверждено VibeIDE 09.09.2026 — оно совпадает с именем поля в записях, и одно слово на
   * одно понятие лучше двух. Пока файла в наборе нет, весь набор считается адресованным нам, то
   * есть сегодня читатель ничего не меняет — он приезжает РАНЬШЕ первой адресной записи, и в этом
   * весь смысл.
   */
  private const val TARGETING = "products.json"

  /** Resource names of the manifest — the resources↔manifest gate test compares them with the embedded set. */
  internal fun manifestResourceNames(): List<String> = MANIFEST.map { it.first }

  /** The set's revision registry, for anyone who needs to know which release a file came from. */
  fun versionsContent(): String? = readResource(VERSIONS)

  /**
   * A file of the set as the build carries it — for the files read from the build and never seeded, which
   * `products.json` addresses to no product (the text-slop catalogue, the shared test vectors).
   */
  fun setFile(name: String): String? = readResource(name)

  /** Set files that serve the set itself (registries, its bump script) and are never seeded. */
  internal val SET_METADATA = setOf(DEPRECATED, VERSIONS, TARGETING, "bump.mjs")

  /**
   * Объявление набора: словарь известных продуктов и адресация файлов.
   *
   * Словарь ОБЩИЙ и живёт в наборе, а не константой у каждого продукта. Довод VibeIDE
   * 10.09.2026, и он решающий: строгую проверку делает тест набора, а тест сверяется со списком —
   * если список каждый держит у себя, списки разойдутся молча. Та же механика, что сломала цену
   * под `cost*`, только вместо имени поля имя продукта.
   */
  internal data class SetTargeting(val products: List<String>, val files: Map<String, List<String>>)

  /**
   * Разбор объявления. Чистая функция, чтобы её можно было проверить, не собирая ресурсы.
   * Файл без `files`-записи о пути — путь адресован всем; это то же самое, что отсутствие файла.
   */
  internal fun parseTargeting(text: String?): SetTargeting {
    val empty = SetTargeting(emptyList(), emptyMap())
    val root = text?.let {
      runCatching { Json.parseToJsonElement(com.vibe.agent.util.VibeJsonc.strip(it)).jsonObject }.getOrNull()
    } ?: return empty
    val products = VibeProducts.declaredProducts(root) ?: emptyList()
    val files = LinkedHashMap<String, List<String>>()
    for (el in runCatching { root["files"]?.jsonArray }.getOrNull() ?: emptyList()) {
      val o = el as? JsonObject ?: continue
      val path = o["path"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: continue
      files[path] = VibeProducts.declaredProducts(o) ?: continue
    }
    return SetTargeting(products, files)
  }

  private val targeting: SetTargeting by lazy { parseTargeting(readResource(TARGETING)) }

  /** Нужен ли этот файл набора нашему продукту. Адреса нет — нужен: умолчание совпадает с частым случаем. */
  internal fun addressedToUs(resource: String): Boolean =
    targeting.files[resource]?.any { it.equals(VibeProducts.THIS, ignoreCase = true) } ?: true

  /**
   * Продукты, которых набор знает. Пусто — набор словаря не объявил.
   *
   * Нужны НЕ рантайму: он про незнакомый id молчит намеренно, в этом совместимость вперёд.
   * Нужны тесту набора — `vibeidee` это опечатка, из-за которой запись становится ничьей и молча
   * не срабатывает нигде.
   */
  internal fun knownProducts(): List<String> = targeting.products

  /** What we recorded about a file we seeded: content, the revision it came from, and the
   *  revision the user last said «keep mine» about (so a conflict is not raised twice). */
  data class JournalEntry(val sha: String, val version: Int? = null, val reconciled: Int? = null)

  /**
   * One file an untouched older revision can move to the current release.
   *
   * [from]/[to] are the semver of the file header and of the registry — «1.0.0 → 1.2.0» is what a
   * person can judge; a revision number is our bookkeeping. Null becomes «—» on screen
   */
  data class SeedUpdate(val path: String, val from: String?, val to: String?) {
    /** «path: 1.0.0 → 1.2.0»; a missing header version is shown as «—», never as an empty string */
    fun describe(): String = "$path: ${from ?: "—"} → ${to ?: "—"}"
  }

  /** One file the release moved on while the user's copy differs — needs a human decision. */
  data class SeedConflict(
    val path: String,
    val fromVersion: Int?,
    val toVersion: Int,
    val from: String? = null,
    val to: String? = null,
  )

  data class SeedReport(
    val created: Int,
    val kept: Int,
    /** Stale seeds deleted because their content matched a known historical version. */
    val removed: List<String> = emptyList(),
    /** Stale seeds LEFT in place: the user edited them, deleting would lose work. */
    val keptModified: List<String> = emptyList(),
    /** Untouched copies of older revisions, refreshed to the current one by this run */
    val updated: List<SeedUpdate> = emptyList(),
    /** Untouched copies left for the person because automatic updates are off */
    val pending: List<SeedUpdate> = emptyList(),
    /** Edited copies whose revision moved in the set — reported, never overwritten. */
    val conflicts: List<SeedConflict> = emptyList(),
    /** Set-discipline errors: content moved without a revision bump (bump.mjs not run). */
    val setDrift: List<String> = emptyList(),
  )

  /** The set compared with the project without touching a single file — what the check action shows */
  data class SeedCheck(
    val updates: List<SeedUpdate> = emptyList(),
    val conflicts: List<SeedConflict> = emptyList(),
    val missing: List<String> = emptyList(),
    val setDrift: List<String> = emptyList(),
  ) {
    /** Edits are the person's business; only an untouched copy or a conflict means the set is behind */
    val upToDate: Boolean get() = updates.isEmpty() && conflicts.isEmpty() && missing.isEmpty() && setDrift.isEmpty()
  }

  /**
   * Seed `.vibe/` under [projectBase]. Idempotent; call OFF the EDT.
   *
   * Per file the set's revision registry decides (see [SeedRevisions]): missing → create,
   * untouched copy of an older revision → refresh, the user's own edit → never touch
   * (reported as a conflict when the set has moved on, so the caller can offer a comparison).
   *
   * [autoUpdate] is the product setting: when off, an untouched older revision is only REPORTED in
   * [SeedReport.pending] and the person decides. The default keeps the old behaviour for callers
   * that are not a user gesture (tests, tools)
   */
  fun seed(projectBase: String, autoUpdate: Boolean = true): SeedReport {
    val vibeDir = Path.of(projectBase, ".vibe")
    var created = 0
    var kept = 0
    val updated = ArrayList<SeedUpdate>()
    val pending = ArrayList<SeedUpdate>()
    val conflicts = ArrayList<SeedConflict>()
    val drift = ArrayList<String>()
    val journal = loadJournal(vibeDir).toMutableMap()
    val revisions = SeedRevisions.parse(readResource(VERSIONS))
    runCatching { Files.createDirectories(vibeDir) }.onFailure { return SeedReport(0, 0) }
    for ((resource, relative) in MANIFEST) {
      val target = vibeDir.resolve(relative)
      val content = readResource(resource) ?: continue
      val revision = revisions[resource]
      val recorded = journal[relative]
      val onDisk = if (Files.isRegularFile(target)) runCatching { Files.readAllBytes(target) }.getOrNull() else null
      val verdict = SeedRevisions.verdict(
        revision = revision,
        localSha = onDisk?.let { sha256(it) },
        localShaLf = onDisk?.let { sha256(String(it, Charsets.UTF_8).replace("\r\n", "\n")) },
        journalSha = recorded?.sha,
        journalVersion = recorded?.version,
        reconciledVersion = recorded?.reconciled,
      )
      val shown = {
        SeedUpdate(relative, SeedHeader.version(relative, onDisk?.toString(Charsets.UTF_8)), revision?.vibeVersion)
      }
      when (verdict) {
        SeedVerdict.CREATE -> if (write(target, content)) {
          created++
          journal[relative] = JournalEntry(sha256(content), revision?.version)
          storeBase(vibeDir, relative, content)
        }
        SeedVerdict.UPDATE -> {
          if (autoUpdate) {
            if (write(target, content)) {
              updated.add(shown())
              journal[relative] = JournalEntry(sha256(content), revision?.version)
              storeBase(vibeDir, relative, content)
            }
          } else {
            pending.add(shown())
          }
        }
        SeedVerdict.SAME -> {
          kept++
          // The copy IS the release: the content a later conflict will use as the merge base.
          storeBase(vibeDir, relative, content)
          // Keep the recorded revision current so a later bump is measured from here.
          if (revision != null && recorded?.version != revision.version) {
            journal[relative] = JournalEntry(sha256(content), revision.version, recorded?.reconciled)
          }
        }
        SeedVerdict.CONFLICT -> {
          kept++
          conflicts.add(SeedConflict(
            relative, recorded?.version, revision?.version ?: 0,
            from = SeedHeader.version(relative, onDisk?.toString(Charsets.UTF_8)),
            to = revision?.vibeVersion,
          ))
        }
        SeedVerdict.SET_DRIFT -> { kept++; drift.add(relative) }
        SeedVerdict.USER_EDIT -> kept++
      }
    }
    val (removed, keptModified) = cleanupDeprecated(vibeDir, journal, readResource(DEPRECATED))
    if (created > 0 || updated.isNotEmpty() || removed.isNotEmpty() || journal != loadJournal(vibeDir)) {
      saveJournal(vibeDir, journal)
    }
    return SeedReport(
      created = created, kept = kept, removed = removed, keptModified = keptModified,
      updated = updated, pending = pending, conflicts = conflicts, setDrift = drift,
    )
  }

  /**
   * Compare the project with the shipped set without writing anything — the check-updates action.
   * Same verdict table as [seed], so the two can never disagree about a file
   */
  fun check(projectBase: String): SeedCheck {
    val vibeDir = Path.of(projectBase, ".vibe")
    val journal = loadJournal(vibeDir)
    val revisions = SeedRevisions.parse(readResource(VERSIONS))
    val updates = ArrayList<SeedUpdate>()
    val conflicts = ArrayList<SeedConflict>()
    val missing = ArrayList<String>()
    val drift = ArrayList<String>()
    for ((resource, relative) in MANIFEST) {
      if (readResource(resource) == null) continue
      val revision = revisions[resource]
      val target = vibeDir.resolve(relative)
      val onDisk = if (Files.isRegularFile(target)) runCatching { Files.readAllBytes(target) }.getOrNull() else null
      val recorded = journal[relative]
      val verdict = SeedRevisions.verdict(
        revision = revision,
        localSha = onDisk?.let { sha256(it) },
        localShaLf = onDisk?.let { sha256(String(it, Charsets.UTF_8).replace("\r\n", "\n")) },
        journalSha = recorded?.sha,
        journalVersion = recorded?.version,
        reconciledVersion = recorded?.reconciled,
      )
      when (verdict) {
        SeedVerdict.CREATE -> missing.add(relative)
        SeedVerdict.UPDATE -> updates.add(
          SeedUpdate(relative, SeedHeader.version(relative, onDisk?.toString(Charsets.UTF_8)), revision?.vibeVersion))
        SeedVerdict.CONFLICT -> conflicts.add(SeedConflict(
          relative, recorded?.version, revision?.version ?: 0,
          from = SeedHeader.version(relative, onDisk?.toString(Charsets.UTF_8)),
          to = revision?.vibeVersion,
        ))
        SeedVerdict.SET_DRIFT -> drift.add(relative)
        SeedVerdict.SAME, SeedVerdict.USER_EDIT -> Unit
      }
    }
    return SeedCheck(updates, conflicts, missing, drift)
  }

  private fun write(target: Path, content: String): Boolean = runCatching {
    target.parent?.let { Files.createDirectories(it) }
    Files.writeString(target, content)
  }.isSuccess

  /** Records «keep mine» for [paths] at the set's current revision — the conflict stays quiet
   *  until the set moves again. Called from the notification action. */
  fun markReconciled(projectBase: String, paths: List<String>) = record(projectBase, paths, SeedMerge.Choice.MINE)

  /** Records that the release version was taken for [paths]: journalled as seeded at the current revision. */
  fun markTaken(projectBase: String, paths: List<String>) = record(projectBase, paths, SeedMerge.Choice.THEIRS)

  private fun record(projectBase: String, paths: List<String>, choice: SeedMerge.Choice) {
    val vibeDir = Path.of(projectBase, ".vibe")
    val revisions = SeedRevisions.parse(readResource(VERSIONS))
    val byTarget = MANIFEST.associate { (resource, relative) -> relative to resource }
    val journal = loadJournal(vibeDir).toMutableMap()
    for (path in paths) {
      val resource = byTarget[path] ?: continue
      val version = revisions[resource]?.version ?: continue
      val release = readResource(resource) ?: continue
      journal[path] = SeedMerge.journalAfter(choice, journal[path], sha256(release), version)
      // Either way the person has now seen this revision: it is the base of the next merge.
      storeBase(vibeDir, path, release)
    }
    saveJournal(vibeDir, journal)
  }

  /** Where the content of the release a copy was seeded from is kept — local, outside git (`.vibe/local/`). */
  internal fun baseFile(vibeDir: Path, relative: String): Path = VibeLocal.dirIn(vibeDir).resolve(BASE_DIR).resolve(relative)

  /** Content of the release revision the person's copy of [relative] was seeded from; null for older projects. */
  fun seedBase(projectBase: String, relative: String): String? =
    runCatching { Files.readString(baseFile(Path.of(projectBase, ".vibe"), relative)) }.getOrNull()

  internal fun storeBase(vibeDir: Path, relative: String, content: String) {
    val file = baseFile(vibeDir, relative)
    runCatching {
      if (Files.isRegularFile(file) && Files.readString(file) == content) return
      file.parent?.let { Files.createDirectories(it) }
      Files.writeString(file, content)
    }
  }

  /** The release's content for a seeded path (left side of the comparison). */
  /** Content shipped for a target path inside `.vibe/`; also accepts the resource name itself. */
  fun releaseContent(relativePath: String): String? =
    MANIFEST.firstOrNull { it.second == relativePath }?.let { readResource(it.first) }

  /**
   * Delete stale seeds (files dropped/renamed in the shared set) — but ONLY when the
   * on-disk copy byte-matches a known historical version from `deprecated.json`:
   * an edited file is the user's work and stays untouched (reported instead).
   * [spec] is a parameter (not read inline) so tests can drive both branches.
   */
  internal fun cleanupDeprecated(vibeDir: Path, journal: MutableMap<String, JournalEntry>, spec: String?): Pair<List<String>, List<String>> {
    val removed = ArrayList<String>()
    val keptModified = ArrayList<String>()
    if (spec == null) return removed to keptModified
    runCatching {
      val entries = Json.parseToJsonElement(spec).jsonObject["deprecated"]?.jsonArray ?: return removed to keptModified
      for (el in entries) {
        val o = el.jsonObject
        val rel = o["path"]?.jsonPrimitive?.contentOrNull ?: continue
        val target = vibeDir.resolve(rel)
        if (!Files.isRegularFile(target)) continue
        val known = o["sha256"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
        // Reference hashes are taken from LF files; also try the LF-normalized digest so a
        // CRLF checkout (Windows, text=auto) does not masquerade as a user edit.
        val bytes = Files.readAllBytes(target)
        val actual = sha256(bytes)
        val actualLf = sha256(String(bytes, Charsets.UTF_8).replace("\r\n", "\n"))
        if (actual in known || actualLf in known) {
          if (runCatching { Files.delete(target) }.isSuccess) {
            journal.remove(rel)
            removed.add(rel)
          }
        }
        else {
          keptModified.add(rel)
        }
      }
    }
    return removed to keptModified
  }

  private fun readResource(name: String): String? =
    VibeDefaults::class.java.getResourceAsStream(RESOURCE_ROOT + name)?.use { it.readBytes().toString(Charsets.UTF_8) }

  /**
   * Reads `.seeded.json` in either shape: the original flat `path → sha` (projects seeded before
   * revisions existed — their entries still work, just without a revision) and the current
   * `{version, files: {path: {sha, rev, reconciled}}}`.
   */
  internal fun loadJournal(vibeDir: Path): Map<String, JournalEntry> {
    val file = VibeLocal.fileIn(vibeDir, JOURNAL)
    if (!Files.isRegularFile(file)) return emptyMap()
    return runCatching {
      val root = Json.parseToJsonElement(Files.readString(file)).jsonObject
      val files = root["files"]?.jsonObject ?: root // legacy: the root IS the map
      files.mapNotNull { (path, el) ->
        when (el) {
          is kotlinx.serialization.json.JsonPrimitive -> el.contentOrNull?.let { path to JournalEntry(it) }
          is JsonObject -> el["sha"]?.jsonPrimitive?.contentOrNull?.let {
            path to JournalEntry(it, el["rev"]?.jsonPrimitive?.intOrNull, el["reconciled"]?.jsonPrimitive?.intOrNull)
          }
          else -> null
        }
      }.toMap()
    }.getOrDefault(emptyMap())
  }

  private fun saveJournal(vibeDir: Path, journal: Map<String, JournalEntry>) {
    runCatching {
      Files.writeString(VibeLocal.fileIn(vibeDir, JOURNAL), buildJsonObject {
        put("version", 2)
        put("files", buildJsonObject {
          journal.toSortedMap().forEach { (path, e) ->
            put(path, buildJsonObject {
              put("sha", e.sha)
              e.version?.let { put("rev", it) }
              e.reconciled?.let { put("reconciled", it) }
            })
          }
        })
      }.toString() + "\n")
    }
  }

  fun sha256(s: String): String = sha256(s.toByteArray(Charsets.UTF_8))

  fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
