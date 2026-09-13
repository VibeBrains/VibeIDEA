// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.pipelines

import com.vibe.agent.i18n.VibeI18n.t
import com.vibe.agent.providers.ProvidersFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.nio.file.Files
import java.nio.file.Path

data class PipelineStep(
  val role: String,
  val task: String,
  /**
   * Провайдер и модель ИМЕННО ЭТОГО шага, вместо ACP-агента пайплайна.
   *
   * Зачем: дешёвая модель на черновике и сильная на приёмке — это разные модели в одном прогоне,
   * а пока шаг умел ходить только к одному агенту, такой прогон нельзя было описать вовсе.
   * Второе применение — критик: замечания стоит спрашивать у модели ДРУГОГО семейства, потому что
   * своей ошибки модель не видит по той же причине, по которой её сделала.
   *
   * Шаг на своей модели идёт прямым запросом к провайдеру: у него нет инструментов и нет доступа к
   * файлам — ровно текст в ответ на текст. Поэтому такой шаг разрешён только ролям, которые и так
   * ничего не пишут: иначе «реализуй» молча превратилось бы в «расскажи, как реализовать».
   */
  val provider: String? = null,
  val model: String? = null,
  val acceptance: String? = null,
  val maxTokens: Int? = null,
  val maxSteps: Int? = null,
  /**
   * Шаг нужен только тогда, когда предыдущий не приняли.
   *
   * Так описывается каскад: дешёвая модель делает черновик, гейт (`pipelineStepEnd` в
   * `.vibe/hooks.json`) его проверяет, и дорогой шаг выполняется, ТОЛЬКО если гейт отказал.
   * Без гейта поле не значит ничего: непроверенный черновик принять некому, и шаг выполняется.
   */
  val escalation: Boolean = false,
  val continueOnFailure: Boolean = false,
  val ignorePreviousArtifacts: Boolean = false,
  /**
   * Куда шагу разрешено писать и куда запрещено — синтаксис gitignore, разбор в [RolePaths].
   *
   * Права роли ([RoleRights]) отвечают «пишет или нет», и этого не хватает ровно там, где пайплайн
   * из ролей и заводят: «этот шаг пишет тесты», «этот — документацию». Пустые списки означают
   * отсутствие ограничения, а не запрет: иначе появление поля в одном шаге молча урезало бы все
   * остальные.
   */
  val paths: List<String> = emptyList(),
  val denyPaths: List<String> = emptyList(),
  /** Whose context the step sees, see [StepContext]; by default a judge starts clean. */
  val context: StepContext = StepContext.defaultFor(role),
)

/**
 * Where a step runs: in the chat's own session, or in a new session of the same agent.
 *
 * A reviewer that watched the author work reviews the author's reasoning, not the work: review goes
 * better when the reviewer has not seen the author's context (Cognition, «Multi-Agents: What's
 * Actually Working», 22.04.2026). In ACP every session has its own context, so «fresh» is simply a
 * new session on the same connection — not remembered, and the chat's session stays current.
 */
enum class StepContext(val wire: String) {
  SHARED("shared"),
  FRESH("fresh");

  companion object {
    /** The roles that judge rather than build: they start clean unless told otherwise. */
    val JUDGING_ROLES: Set<String> = setOf("code-reviewer", "critic", "qa", "security")

    fun defaultFor(role: String): StepContext = if (role in JUDGING_ROLES) FRESH else SHARED

    fun parse(wire: String?): StepContext? = entries.firstOrNull { it.wire == wire }
  }
}

data class Pipeline(
  val id: String,
  val name: String = id,
  val description: String? = null,
  val steps: List<PipelineStep>,
)

/**
 * `.vibe/pipelines.json` — the VibeIDE contract: version+pipelines[], roles are a
 * closed list, 1..20 steps, a broken pipeline is skipped with a warning shown
 * BEFORE the list (a vanished pipeline must not look merely absent), the rest work.
 * Duplicate id: the second entry is skipped, the first stays.
 */
object PipelinesFile {
  val ROLES: Set<String> = setOf(
    "explore", "implement-step", "recover-or-skip", "orchestrator", "planner", "designer",
    "frontend-dev", "backend-dev", "code-reviewer", "qa", "security", "critic",
  )

  /**
   * Роли, которые ничего не пишут, — и поэтому им можно дать собственную модель.
   *
   * Список выведен из [RoleRights], а не написан заново: две копии одного знания разойдутся ровно
   * тогда, когда появится новая роль.
   */
  private fun readOnly(role: String): Boolean = !RoleRights.mayWrite(role)

  /** Список строк из JSON; не массив или пусто — пустой список, а не ошибка разбора. */
  private fun stringList(element: kotlinx.serialization.json.JsonElement?): List<String> =
    (element as? kotlinx.serialization.json.JsonArray)
      ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.ifEmpty { null } }
      .orEmpty()
  private const val MAX_STEPS = 20
  private val json = Json { ignoreUnknownKeys = true }

  fun path(projectBase: String): Path = Path.of(projectBase, ".vibe", "pipelines.json")

  /**
   * `roles` of a pipeline: the model each role runs on when its step names none (agreed with VibeIDE,
   * 13.09.2026 — names and the one-string model form are theirs).
   *
   * Refused as a whole pipeline, not per entry, for the same reasons a step is: an unknown role is a
   * typo that would otherwise quietly change nothing, and a model for a role that writes is a promise
   * we cannot keep — a direct model request has no tools and no files. VibeIDE lets any role have a
   * model; that difference is stated in the spec rather than hidden.
   */
  private fun roleModelsOf(element: kotlinx.serialization.json.JsonElement?): Map<String, Pair<String?, String?>> {
    val obj = element as? kotlinx.serialization.json.JsonObject ?: return emptyMap()
    return obj.entries.associate { (role, value) ->
      if (role !in ROLES) throw IllegalArgumentException(t("pipeline.warn.unknownRole", "role" to role, "roles" to ROLES.joinToString()))
      val entry = value as? kotlinx.serialization.json.JsonObject
      val resolved = StepModelRef.resolve(
        entry?.get("provider")?.jsonPrimitive?.contentOrNull,
        entry?.get("model")?.jsonPrimitive?.contentOrNull,
      )
      if ((resolved.first == null) != (resolved.second == null)) {
        throw IllegalArgumentException(t("pipeline.warn.halfAddress", "role" to role))
      }
      if (resolved.second != null && !readOnly(role)) {
        throw IllegalArgumentException(t("pipeline.warn.writingRoleOnOwnModel", "role" to role))
      }
      role to resolved
    }
  }

  fun load(projectBase: String?, onWarning: (String) -> Unit): List<Pipeline> {
    if (projectBase == null) return emptyList()
    val file = path(projectBase)
    if (!Files.isRegularFile(file)) return emptyList()
    val result = ArrayList<Pipeline>()
    val seen = HashSet<String>()
    try {
      val root = json.parseToJsonElement(com.vibe.agent.util.VibeJsonc.strip(Files.readString(file))).jsonObject
      for (el in root["pipelines"]?.jsonArray ?: return emptyList()) {
        try {
          val o = el.jsonObject
          val id = o["id"]?.jsonPrimitive?.contentOrNull
          if (id.isNullOrBlank()) { onWarning(t("pipeline.warn.noId")); continue }
          if (!seen.add(id)) { onWarning(t("pipeline.warn.duplicateId", "id" to id)); continue }
          val roleModels = roleModelsOf(o["roles"])
          val steps = o["steps"]?.jsonArray?.map { s ->
            val so = s.jsonObject
            val role = so["role"]?.jsonPrimitive?.contentOrNull
              ?: throw IllegalArgumentException(t("pipeline.warn.stepNoRole"))
            if (role !in ROLES) throw IllegalArgumentException(t("pipeline.warn.unknownRole", "role" to role, "roles" to ROLES.joinToString()))
            // The canonical form is VibeIDE's: `"model": "provider/model"` in one string. The pair
            // `provider` + `model` is our older spelling and stays a synonym. The shared seed used the
            // pair, and VibeIDE never reads `provider` — its step silently ran on the role's default
            // model (found 13.09.2026). Reading both here keeps every existing file working.
            val own = StepModelRef.resolve(
              so["provider"]?.jsonPrimitive?.contentOrNull,
              so["model"]?.jsonPrimitive?.contentOrNull,
            )
            // The step's own model wins; a step without one takes its role's model from `roles`.
            val (provider, model) = if (own.first == null && own.second == null) roleModels[role] ?: own else own
            // Половина адреса — это опечатка, а не выбор: провайдер без модели молча ушёл бы к
            // агенту пайплайна, и человек считал бы, что шаг идёт к его модели.
            if ((provider == null) != (model == null)) {
              throw IllegalArgumentException(t("pipeline.warn.halfAddress", "role" to role))
            }
            // Пишущая роль на своей модели — обещание, которого мы не сдержим: прямой запрос к
            // провайдеру идёт без инструментов и без доступа к файлам.
            if (model != null && !readOnly(role)) {
              throw IllegalArgumentException(t("pipeline.warn.writingRoleOnOwnModel", "role" to role))
            }
            val contextWire = so["context"]?.jsonPrimitive?.contentOrNull?.trim()?.ifEmpty { null }
            // Настройка, мёртвая В КОНТЕКСТЕ: поле разбирается, потребитель у него есть — но не на
            // ЭТОМ шаге. Гейт мёртвых полей такое не видит по построению, он отвечает «есть ли
            // потребитель», а не «работает ли он здесь». Снаружи неотличимо от работающей
            // настройки: человек написал ограничение, IDE промолчала, ограничения нет.
            //
            // Здесь предупреждение, а не отказ: пишущая роль на своей модели — обещание, которого
            // мы не сдержим, и пайплайн ронять правильно; бессмысленное поле — опечатка, и ронять
            // из-за неё рабочий пайплайн значит наказывать за неё.
            if (model != null) {
              if ((so["maxSteps"]?.jsonPrimitive?.intOrNull ?: 0) > 0) {
                onWarning(t("pipeline.warn.maxStepsOnOwnModel", "role" to role))
              }
              if (stringList(so["paths"]).isNotEmpty() || stringList(so["denyPaths"]).isNotEmpty()) {
                onWarning(t("pipeline.warn.pathsOnOwnModel", "role" to role))
              }
              // A direct request has no session to share or to open.
              if (contextWire != null) onWarning(t("pipeline.warn.contextOnOwnModel", "role" to role))
            }
            // An unknown value is a typo, and a typo falls back to the role's default out loud
            // rather than dropping a working pipeline.
            val context = contextWire?.let { wire ->
              StepContext.parse(wire) ?: StepContext.defaultFor(role).also {
                onWarning(t("pipeline.warn.unknownContext", "role" to role, "value" to wire, "default" to it.wire))
              }
            } ?: StepContext.defaultFor(role)
            PipelineStep(
              role = role,
              task = so["task"]?.jsonPrimitive?.contentOrNull?.ifBlank { null }
                ?: throw IllegalArgumentException(t("pipeline.warn.stepNoTask")),
              provider = provider,
              model = model,
              acceptance = so["acceptance"]?.jsonPrimitive?.contentOrNull,
              maxTokens = so["maxTokens"]?.jsonPrimitive?.intOrNull,
              maxSteps = so["maxSteps"]?.jsonPrimitive?.intOrNull,
              escalation = so["escalation"]?.jsonPrimitive?.booleanOrNull ?: false,
              continueOnFailure = so["continueOnFailure"]?.jsonPrimitive?.booleanOrNull ?: false,
              ignorePreviousArtifacts = so["ignorePreviousArtifacts"]?.jsonPrimitive?.booleanOrNull ?: false,
              paths = stringList(so["paths"]),
              denyPaths = stringList(so["denyPaths"]),
              context = context,
            )
          } ?: emptyList()
          if (steps.isEmpty()) { onWarning(t("pipeline.warn.noSteps", "id" to id)); continue }
          if (steps.size > MAX_STEPS) { onWarning(t("pipeline.warn.tooManySteps", "id" to id, "max" to MAX_STEPS)); continue }
          result.add(Pipeline(
            id = id,
            name = o["name"]?.jsonPrimitive?.contentOrNull ?: id,
            description = o["description"]?.jsonPrimitive?.contentOrNull,
            steps = steps,
          ))
        }
        catch (e: Exception) {
          onWarning(t("pipeline.warn.skipped", "reason" to e.message))
        }
      }
    }
    catch (e: Exception) {
      onWarning(t("pipeline.warn.unparsed", "reason" to e.message))
    }
    return result
  }

  /** Role preamble; read-only roles get an explicit no-edit instruction (ACP cannot enforce tools per role — honest deviation from VibeIDE, permissions still gate writes). */
  fun rolePreamble(role: String): String = when (role) {
    "explore" -> "Ты — разведчик. Изучи кодовую базу по задаче. НЕ изменяй файлы — только чтение и анализ."
    "planner" -> "Ты — планировщик. Составь план работ. НЕ изменяй код, кроме файла плана, если он прямо указан в задаче."
    "designer" -> "Ты — дизайнер. Проработай дизайн/интерфейсную часть задачи."
    "frontend-dev" -> "Ты — фронтенд-разработчик. Реализуй интерфейсную часть."
    "backend-dev" -> "Ты — бэкенд-разработчик. Реализуй серверную/логическую часть."
    "implement-step" -> "Ты — исполнитель шага. Сделай ровно то, что сказано в задаче."
    "recover-or-skip" -> "Ты — восстановитель. Оцени состояние после предыдущего шага: почини или явно скажи, что чинить нечего."
    "orchestrator" -> "Ты — оркестратор. Разбей работу и выполни её последовательно."
    "code-reviewer" -> "Ты — ревьюер. Найди дефекты. НЕ изменяй файлы — только отчёт."
    "qa" -> "Ты — QA. Проверь работу по критериям. НЕ изменяй файлы — только отчёт с фактами."
    "security" -> "Ты — безопасник. Проверь изменения на уязвимости. НЕ изменяй файлы — только отчёт."
    "critic" -> "Ты — критик. Перед тобой черновик работы другой модели. Найди в нём то, что не сработает: неверные допущения, пропущенные случаи, места, где написанное расходится с задачей. Не переписывай — назови проблемы и скажи, годится черновик или нет. НЕ изменяй файлы."
    else -> ""
  }
}
