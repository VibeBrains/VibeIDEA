// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.mcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Wire constants of the Model Context Protocol as we speak it, and the catalogue of tools we offer.
 *
 * **Why a real protocol instead of another path on our HTTP API.** The API answers `/run` and
 * `/health` to scripts written for us; a third path in the same shape would answer to scripts
 * written for us as well, and to nothing else. MCP is spoken by every agent worth bridging to, so
 * the same work buys interoperability instead of one more private verb.
 *
 * **Two revisions on purpose.** [VERSION_2026] is stateless — no handshake, `server/discover`
 * instead, `resultType` on every result — while most clients in the wild still open with
 * `initialize` from [VERSION_2025]. Answering only the new one would make us correct and unusable;
 * answering only the old one would make us obsolete on arrival.
 */
object McpProtocol {
  /** Stateless revision: no initialize, `server/discover`, `resultType`, cache hints. */
  const val VERSION_2026 = "2026-07-28"

  /** The handshake revision most clients still speak; kept for exactly that reason. */
  const val VERSION_2025 = "2025-06-18"

  val SUPPORTED: List<String> = listOf(VERSION_2026, VERSION_2025)

  const val SERVER_NAME = "vibeidea"

  /** Said when the IDE side is missing — kept here with the rest of the protocol wording. */
  const val NO_PROJECT = "MCP недоступен: в IDE нет открытого проекта"

  /** JSON-RPC error codes. The MCP range starts at -32020 (error allocation policy, 2026-07-28). */
  object Error {
    const val PARSE = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL = -32603
    const val HEADER_MISMATCH = -32020
    const val UNSUPPORTED_PROTOCOL_VERSION = -32022
  }

  object Meta {
    const val PROTOCOL_VERSION = "io.modelcontextprotocol/protocolVersion"
    const val SERVER_INFO = "io.modelcontextprotocol/serverInfo"
    const val CLIENT_INFO = "io.modelcontextprotocol/clientInfo"
    const val CLIENT_CAPABILITIES = "io.modelcontextprotocol/clientCapabilities"
  }

  /**
   * HTTP headers of the 2026 transport that mirror the body, so gateways can route without reading
   * it. Named once: the listener reads them and the doctor's self-probe sends them, and two
   * spellings would drift apart silently. Case-insensitive on the wire.
   */
  object Header {
    const val PROTOCOL_VERSION = "MCP-Protocol-Version"
    const val METHOD = "Mcp-Method"
    const val NAME = "Mcp-Name"
  }

  /**
   * How long a client may cache a listing. Ten seconds rather than an hour: the tool set does not
   * change on its own, but it does change when the project does, and a stale catalogue sends an
   * agent to call a tool that is no longer there.
   */
  const val LIST_TTL_MS = 10_000L

  /** One tool as the protocol describes it: a name, a sentence, and a schema for its arguments. */
  data class Tool(val name: String, val title: String, val description: String, val schema: JsonObject)

  private fun stringArg(name: String, description: String, required: Boolean = true): JsonObject =
    buildJsonObject {
      put("type", "object")
      putJsonObject("properties") {
        putJsonObject(name) {
          put("type", "string")
          put("description", description)
        }
      }
      if (required) putJsonArray("required") { add(kotlinx.serialization.json.JsonPrimitive(name)) }
    }

  const val TOOL_IMPORTERS = "vibe_code_graph_importers"
  const val TOOL_IMPORTS = "vibe_code_graph_imports"
  const val TOOL_PATH = "vibe_code_graph_path"
  const val TOOL_PROJECT = "vibe_project_info"
  const val TOOL_RUN = "vibe_run_agent"
  const val TOOL_DECISIONS_SEARCH = "vibe_decisions_search"
  const val TOOL_DECISIONS_RECORD = "vibe_decisions_record"
  const val TOOL_CORPUS_SEARCH = "vibe_corpus_search"
  const val TOOL_SYMBOL_USAGES = "vibe_symbol_usages"

  /**
   * Что инструмент делает с машиной человека.
   *
   * Класс опасности объявляется здесь, а не выводится по имени: имя — подпись для агента, и оно
   * меняется свободнее, чем права. Каждый новый инструмент обязан попасть в [RISK], иначе он
   * считается опасным — неизвестное право безопаснее отклонить, чем выдать.
   */
  enum class Risk {
    /** Только читает проект и наши журналы. */
    READ,

    /** Пишет файлы в проект. */
    WRITE,

    /** Запускает работу: агента, команду, процесс. */
    EXECUTE,
  }

  private val RISK: Map<String, Risk> = mapOf(
    TOOL_IMPORTERS to Risk.READ,
    TOOL_IMPORTS to Risk.READ,
    TOOL_PATH to Risk.READ,
    TOOL_PROJECT to Risk.READ,
    TOOL_CORPUS_SEARCH to Risk.READ,
    TOOL_SYMBOL_USAGES to Risk.READ,
    TOOL_DECISIONS_SEARCH to Risk.READ,
    TOOL_DECISIONS_RECORD to Risk.WRITE,
    TOOL_RUN to Risk.EXECUTE,
  )

  /** Класс опасности инструмента; неизвестное имя — [Risk.EXECUTE], то есть самое строгое. */
  fun riskOf(name: String): Risk = RISK[name] ?: Risk.EXECUTE

  /**
   * The tools, in a fixed order — the 2026 revision asks for a deterministic listing so clients can
   * cache it and so a prompt cache is not broken by a reshuffle that changes nothing.
   *
   * The graph tools are here because they answer what a file cannot answer about itself, and
   * because an outside agent reading our repository has no other way to ask. `vibe_run_agent`
   * carries no new authority: it is the `POST /run` that already exists, behind the same token.
   */
  val TOOLS: List<Tool> = listOf(
    Tool(
      name = TOOL_IMPORTERS,
      title = "Кто импортирует файл",
      description = "Файлы проекта, которые импортируют указанный. Каждое ребро помечено происхождением: " +
                    "«факт» — импорт совпал с объявленным полным именем, «догадка» — совпал только последний сегмент.",
      schema = stringArg("path", "Путь файла относительно корня проекта, например src/main.ts"),
    ),
    Tool(
      name = TOOL_IMPORTS,
      title = "Что импортирует файл",
      description = "Файлы проекта, которые импортирует указанный, с тем же признаком происхождения.",
      schema = stringArg("path", "Путь файла относительно корня проекта"),
    ),
    Tool(
      name = TOOL_PATH,
      title = "Как связаны два файла",
      description = "Кратчайшая цепочка импортов между двумя файлами без учёта направления. " +
                    "Пустой ответ означает, что связи нет — это тоже ответ.",
      schema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
          putJsonObject("from") { put("type", "string"); put("description", "Путь первого файла") }
          putJsonObject("to") { put("type", "string"); put("description", "Путь второго файла") }
        }
        putJsonArray("required") {
          add(kotlinx.serialization.json.JsonPrimitive("from"))
          add(kotlinx.serialization.json.JsonPrimitive("to"))
        }
      },
    ),
    Tool(
      name = TOOL_SYMBOL_USAGES,
      title = "Где используется имя",
      description = "Места, где встречается идентификатор, — по индексу слов IDE, а не перебором файлов. " +
                    "Возвращает путь, номер строки и саму строку: этого хватает, чтобы решить, какой файл " +
                    "читать целиком, и не читать остальные. Совпадения в комментариях и строках тоже " +
                    "возвращаются — индекс слов не разбирает синтаксис, и обратное было бы обещанием, " +
                    "которого он не даёт.",
      schema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
          putJsonObject("name") { put("type", "string"); put("description", "Идентификатор целиком, например resolveExtends") }
          putJsonObject("limit") { put("type", "integer"); put("description", "Сколько мест вернуть, по умолчанию 50") }
        }
        putJsonArray("required") { add(kotlinx.serialization.json.JsonPrimitive("name")) }
      },
    ),
    Tool(
      name = TOOL_PROJECT,
      title = "Что за проект открыт",
      description = "Имя и корень открытого проекта, размер графа импортов.",
      schema = buildJsonObject { put("type", "object"); putJsonObject("properties") {} },
    ),
    Tool(
      name = TOOL_RUN,
      title = "Поставить задачу агенту IDE",
      description = "Отдаёт задачу агенту VibeIDEA в открытом проекте и возвращает идентификатор сессии. " +
                    "Тот же ход, что POST /run, и та же авторизация.",
      schema = stringArg("task", "Что сделать. Формулировка уходит агенту как сообщение пользователя"),
    ),
    Tool(
      name = TOOL_CORPUS_SEARCH,
      title = "Что проект уже записал по теме",
      description = "Один поиск по всему корпусу проекта: база знаний, принятые решения и внешние документы. " +
                    "Возвращает пути и описания — читать файлы решает сам агент, целиком они дороже задачи.",
      schema = stringArg("query", "Тема или вопрос"),
    ),
    Tool(
      name = TOOL_DECISIONS_SEARCH,
      title = "Что уже решено по теме",
      description = "Принятые решения проекта по теме запроса: номер, вопрос и путь файла. " +
                    "Читать ДО того, как предлагать вариант: отвергнутое однажды не становится лучше со временем.",
      schema = stringArg("query", "Тема или вопрос, например «подсветка PHP» или «хранение паролей»"),
    ),
    Tool(
      name = TOOL_DECISIONS_RECORD,
      title = "Зафиксировать решение",
      description = "Записывает решение файлом в журнал проекта и строкой в его индекс. " +
                    "Причина обязательна: запись без «почему» не отвечает на вопрос, ради которого её открывают.",
      schema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
          putJsonObject("question") { put("type", "string"); put("description", "Вопрос, на который отвечали") }
          putJsonObject("chosen") { put("type", "string"); put("description", "Что выбрали") }
          putJsonObject("rejected") { put("type", "string"); put("description", "Что рассмотрели и не взяли") }
          putJsonObject("why") { put("type", "string"); put("description", "Почему именно так") }
          putJsonObject("supersedes") {
            put("type", "string")
            put("description", "Номер решения, которое это отменяет. Журнал не переписывают: старое решение остаётся, но помечается заменённым")
          }
        }
        putJsonArray("required") {
          add(kotlinx.serialization.json.JsonPrimitive("question"))
          add(kotlinx.serialization.json.JsonPrimitive("chosen"))
          add(kotlinx.serialization.json.JsonPrimitive("why"))
        }
      },
    ),
  )
}
