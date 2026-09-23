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
  const val TOOL_OPEN_FILE = "vibe_open_file"
  const val TOOL_READ_FILE = "vibe_read_file"
  const val TOOL_IDE_INFO = "vibe_ide_info"
  const val TOOL_PROBLEMS = "vibe_problems"
  const val TOOL_TRACE = "vibe_trace_symbol"
  const val TOOL_DOCS_SEARCH = "vibe_docs_search"
  const val TOOL_WRITE_FILE = "vibe_write_file"
  const val TOOL_REPLACE_IN_FILE = "vibe_replace_in_file"
  const val TOOL_RUN_COMMAND = "vibe_run_command"
  const val TOOL_COMMAND_OUTPUT = "vibe_command_output"
  const val TOOL_COMMAND_STOP = "vibe_command_stop"
  const val TOOL_TEXT_SLOP = "vibe_text_slop_check"

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
    TOOL_OPEN_FILE to Risk.READ,
    TOOL_READ_FILE to Risk.READ,
    TOOL_IDE_INFO to Risk.READ,
    TOOL_PROBLEMS to Risk.READ,
    TOOL_TRACE to Risk.READ,
    TOOL_DOCS_SEARCH to Risk.READ,
    TOOL_DECISIONS_SEARCH to Risk.READ,
    TOOL_DECISIONS_RECORD to Risk.WRITE,
    TOOL_WRITE_FILE to Risk.WRITE,
    TOOL_REPLACE_IN_FILE to Risk.WRITE,
    TOOL_RUN_COMMAND to Risk.EXECUTE,
    TOOL_COMMAND_OUTPUT to Risk.READ,
    TOOL_COMMAND_STOP to Risk.EXECUTE,
    TOOL_RUN to Risk.EXECUTE,
    TOOL_TEXT_SLOP to Risk.READ,
  )

  /**
   * Человеческое имя инструмента, объявленное в каталоге, или null для чужого.
   *
   * Лента показывала техническое имя (`vibe_decisions_search`), и разговор выглядел машинным логом.
   * Заголовок уже написан здесь — для внешнего агента; брать его второй раз в UI не нужно.
   */
  fun titleOf(name: String): String? = TOOLS.firstOrNull { it.name == name }?.title

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
      name = TOOL_OPEN_FILE,
      title = "Что открыто в редакторе",
      description = "Файл, открытый в редакторе IDE прямо сейчас: путь, выделенный человеком кусок и текст файла. " +
                    "Это ответ на «посмотри открытый файл» — без него такая просьба неисполнима, и остаётся " +
                    "просить у человека путь, который он уже назвал тем, что файл открыл. Возвращает и список " +
                    "остальных открытых вкладок: над чем человек работает, видно по ним, а не по одному файлу.",
      schema = buildJsonObject { put("type", "object"); putJsonObject("properties") {} },
    ),
    Tool(
      name = TOOL_READ_FILE,
      title = "Прочитать файл",
      description = "Текст файла по пути — то, чего не даёт ни граф импортов, ни индекс слов: они говорят, КАКОЙ " +
                    "файл смотреть, а прочитать его было нечем. Путь относительно корня проекта или полный. " +
                    "Права те же, что у остальных каналов чтения: `.vibe/ignore` и границы проекта соблюдаются, " +
                    "отказ называется словами. Длинный файл обрезается, и об обрезке говорится прямо.",
      schema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
          putJsonObject("path") { put("type", "string"); put("description", "Путь файла, например src/main.ts") }
          putJsonObject("maxChars") { put("type", "integer"); put("description", "Сколько символов вернуть, по умолчанию 60000") }
        }
        putJsonArray("required") { add(kotlinx.serialization.json.JsonPrimitive("path")) }
      },
    ),
    Tool(
      name = TOOL_DOCS_SEARCH,
      title = "Найти в документации VibeIDEA",
      description = "Поиск по СОБСТВЕННОЙ документации этой IDE — мануалы, спеки форматов и каталог возможностей, " +
                    "вшитые в сборку. Лексический и офлайн: описывает установленную версию, а не то, как выглядит " +
                    "репозиторий сегодня.\n" +
                    "Звать ДО того, как гадать о чём-либо про VibeIDEA:\n" +
                    "- формат файла, который просят создать (.vibe/servers.json, .vibe/providers.json, pipelines.json) — " +
                    "спеки с таблицами полей и примерами лежат здесь;\n" +
                    "- как задумана возможность и чего она требует заранее;\n" +
                    "- что продукт вообще умеет, когда просьба сформулирована широко.\n" +
                    "Ответ называет файл, заголовок и строку — человек может проверить. Пустой результат так и " +
                    "говорится, с числом просмотренных файлов: «здесь не написано» и «инструмент сломался» — " +
                    "разные ответы, и ни один не значит «выдумай». База знаний (инженерные грабли) не индексируется.",
      schema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
          putJsonObject("query") { put("type", "string"); put("description", "Что искать, словами человека: «servers.json», «пайплайны», «языковые серверы»") }
          putJsonObject("limit") { put("type", "integer"); put("description", "Сколько разделов вернуть, по умолчанию 5") }
        }
        putJsonArray("required") { add(kotlinx.serialization.json.JsonPrimitive("query")) }
      },
    ),
    Tool(
      name = TOOL_TEXT_SLOP,
      title = "Проверить текст на нейрослоп",
      description = "Детерминированная проверка текста на приметы машинного письма: штампы («бесшовный», «раскрыть " +
                    "потенциал»), пустые обороты, шаблоны вроде «это не X — это Y», голос ассистента, безымянные " +
                    "«исследования показывают», ритм и оформление. Русский и английский; код, цитаты и ссылки прозой " +
                    "не считаются.\n" +
                    "Звать ПОСЛЕ того, как написал текст для людей — документацию, README, заметки к релизу, тексты " +
                    "интерфейса, — и ДО того, как отдать его. Ответ называет строку, найденный кусок и как исправить, " +
                    "плюс счёт по фиксированной арифметике: проход — от 90 и без тяжёлых находок.\n" +
                    "Счёт — пол, а не вердикт: выдуманный факт и сдвинутый смысл он не видит. Порядок правки и проверку " +
                    "свежим рецензентом описывает навык anti-slop; правила проекта — .vibe/slop.json.",
      schema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
          putJsonObject("path") { put("type", "string"); put("description", "Файл относительно корня проекта, например docs/guide.md") }
          putJsonObject("text") { put("type", "string"); put("description", "Сам текст — когда он ещё не записан в файл") }
        }
      },
    ),
    Tool(
      name = TOOL_PROBLEMS,
      title = "Ошибки и предупреждения файла",
      description = "То, что IDE подчеркнула в файле: ошибки разбора, инспекции платформы и диагностика языкового " +
                    "сервера — ровно то, что видит человек на экране, с номером строки, важностью и самой строкой. " +
                    "Спрашивать ПЕРЕД тем, как чинить: «почини ошибки» без этого списка означает чинить по догадке.\n" +
                    "Важно: разметка есть только у ОТКРЫТОГО в редакторе файла. Для закрытого ответ — «откройте его», " +
                    "и это не пустой список: пустой означал бы, что ошибок нет.",
      schema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
          putJsonObject("path") { put("type", "string"); put("description", "Путь файла; без него — открытый в редакторе") }
        }
      },
    ),
    Tool(
      name = TOOL_TRACE,
      title = "Откуда пришло значение",
      description = "Откуда взялось имя. Сперва спрашивает языковой сервер файла: он разрешает имя так же, как " +
                    "компилятор, — через импорты, реэкспорты и псевдонимы — и отдаёт место объявления и сигнатуру " +
                    "(строки [LSP]). Если сервер файл не обслуживает или не ответил, разбор идёт по тексту: объявление " +
                    "в этом файле, импорт с указанием откуда, внедрение в конструктор, параметр — и дальше по файлам, " +
                    "хоп за хопом, а первая строка ответа называет причину. Отвечает на вопрос, который иначе модель " +
                    "ДОДУМЫВАЕТ: файл показывает, ЧТО написано, и молчит о том, откуда это пришло.",
      schema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
          putJsonObject("name") { put("type", "string"); put("description", "Имя, происхождение которого нужно") }
          putJsonObject("path") { put("type", "string"); put("description", "Файл, где оно встретилось; без него — открытый в редакторе") }
          putJsonObject("hops") { put("type", "integer"); put("description", "Сколько файлов пройти по цепочке, по умолчанию 4") }
        }
        putJsonArray("required") { add(kotlinx.serialization.json.JsonPrimitive("name")) }
      },
    ),
    Tool(
      name = TOOL_IDE_INFO,
      title = "Что умеет эта IDE",
      description = "Состояние самой IDE, а не проекта: версия и сборка, платформа и JVM, наши и сторонние плагины, " +
                    "языковые серверы с их состоянием и найденная нода. Спрашивать ОБЯЗАТЕЛЬНО перед любым советом " +
                    "про настройки IDE: совет вслепую («включите плагин X») тратит время человека дважды — сперва на " +
                    "выполнение, потом на выяснение, почему не помогло.",
      schema = buildJsonObject { put("type", "object"); putJsonObject("properties") {} },
    ),
    Tool(
      name = TOOL_WRITE_FILE,
      title = "Записать файл",
      description = "Создать файл или переписать его целиком. Права те же, что у чтения: за пределы проекта и " +
                    "в закрытое `.vibe/ignore` запись не проходит. Правка ОДНОГО куска в большом файле — " +
                    "vibe_replace_in_file: переписывать тысячу строк ради трёх значит терять чужие изменения.",
      schema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
          putJsonObject("path") { put("type", "string"); put("description", "Путь файла относительно корня проекта") }
          putJsonObject("content") { put("type", "string"); put("description", "Новое содержимое целиком") }
        }
        putJsonArray("required") {
          add(kotlinx.serialization.json.JsonPrimitive("path"))
          add(kotlinx.serialization.json.JsonPrimitive("content"))
        }
      },
    ),
    Tool(
      name = TOOL_REPLACE_IN_FILE,
      title = "Поправить кусок файла",
      description = "Заменить кусок текста в файле на другой. Кусок обязан встречаться РОВНО ОДИН раз — иначе отказ " +
                    "с числом совпадений: правка, попавшая в три места вместо одного, ломает файл молча. " +
                    "Прочитайте файл (vibe_read_file или vibe_open_file) до замены: кусок должен совпасть посимвольно.",
      schema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
          putJsonObject("path") { put("type", "string"); put("description", "Путь файла") }
          putJsonObject("old") { put("type", "string"); put("description", "Кусок, который заменяем, посимвольно как в файле") }
          putJsonObject("new") { put("type", "string"); put("description", "На что заменяем") }
        }
        putJsonArray("required") {
          add(kotlinx.serialization.json.JsonPrimitive("path"))
          add(kotlinx.serialization.json.JsonPrimitive("old"))
          add(kotlinx.serialization.json.JsonPrimitive("new"))
        }
      },
    ),
    Tool(
      name = TOOL_RUN_COMMAND,
      title = "Выполнить команду",
      description = "Выполнить команду оболочки в корне проекта. По умолчанию ЖДЁТ и возвращает вывод с кодом " +
                    "выхода — так и надо для тестов, сборки и git. Окружение берётся у логин-оболочки, поэтому " +
                    "работают те же команды, что в терминале человека.\n" +
                    "`background: true` — для того, что не кончается само: дев-сервер, наблюдатель за файлами, " +
                    "долгий прогон. Такая команда возвращает ИМЯ, вывод и код выхода спрашиваются потом через " +
                    "vibe_command_output, а остановить её — vibe_command_stop. Запускать дев-сервер без " +
                    "background бессмысленно: он не завершится, и ход упрётся в потолок времени.",
      schema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
          putJsonObject("command") { put("type", "string"); put("description", "Команда как в терминале, например npm test") }
          putJsonObject("background") { put("type", "boolean"); put("description", "Не ждать завершения: вернуть имя команды") }
          putJsonObject("timeoutSeconds") { put("type", "integer"); put("description", "Сколько ждать, если не в фоне; по умолчанию 300") }
        }
        putJsonArray("required") { add(kotlinx.serialization.json.JsonPrimitive("command")) }
      },
    ),
    Tool(
      name = TOOL_COMMAND_OUTPUT,
      title = "Вывод фоновой команды",
      description = "Что успела написать фоновая команда и кончилась ли она. Без имени — список всего, что сейчас " +
                    "запущено: через час работы иначе не вспомнить, что где крутится.",
      schema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
          putJsonObject("id") { put("type", "string"); put("description", "Имя команды, выданное при запуске") }
        }
      },
    ),
    Tool(
      name = TOOL_COMMAND_STOP,
      title = "Остановить фоновую команду",
      description = "Останавливает фоновую команду вместе с её потомками: оболочка без них оставила бы сервер жить " +
                    "на порту, и следующий запуск упал бы на «адрес занят».",
      schema = stringArg("id", "Имя команды, выданное при запуске"),
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
