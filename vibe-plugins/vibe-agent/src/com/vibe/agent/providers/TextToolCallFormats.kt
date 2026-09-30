// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import com.vibe.agent.providers.TextToolCalls.Call
import com.vibe.agent.providers.TextToolCalls.Format
import com.vibe.agent.providers.TextToolCalls.Found
import com.vibe.agent.providers.TextToolCalls.Schemas
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The markups open model families write tool calls in, as their chat templates and vLLM's parsers define them
 *
 * Each form was checked against the vendor's template or encoder and vLLM's grammar (commit ed3f6d1a of
 * vllm-project/vllm, `vllm/parser/` and `rust/src/parser/src/tool/`): the list, the sources and the forms a server
 * leaves once it strips special tokens are in `docs/vibe/knowledge/agents/toolCallsInText.md`
 *
 * Values in a markup that carries no type (Qwen XML, GLM, MiniMax, Gemma) are typed by the tool's schema,
 * the way vLLM does it: a model writes `40` for a `limit` and means a number
 */
internal object TextToolCallFormats {
  /** The schemas of a tool's parameters, for the values a markup leaves untyped */
  private fun Schemas.properties(tool: String): JsonObject? = of(tool)?.get("properties") as? JsonObject

  /** A format recognised by a regular expression at any place outside code */
  private abstract class Opened(override val id: String, private val opener: Regex, override val openers: List<String>) : Format {
    override fun open(text: CharSequence, from: Int, atAnswerStart: Boolean): Int = opener.find(text, from)?.range?.first ?: -1
  }

  /** A format that is the whole answer or nothing: bare JSON or a Python list, allowed only where the answer begins */
  private abstract class WholeAnswer(override val id: String, private val opener: Regex) : Format {
    override val openers: List<String> = emptyList()

    override fun open(text: CharSequence, from: Int, atAnswerStart: Boolean): Int {
      if (!atAnswerStart || from > 0) return -1
      // The markup starts at its first character, not at the blanks the pattern allows before it
      val match = opener.find(text) ?: return -1
      return match.range.first + match.value.indexOfFirst { !it.isWhitespace() }
    }
  }

  // DeepSeek V3.2 / V4 / V4.1 (DSML), `<invoke>` without a family's marker, MiniMax M2: one invoke grammar

  /** Vendor markers around a tag name: `｜DSML｜`, fullwidth pipes only; an ASCII pair would match inside a value */
  private val DSML_MARKER = Regex("｜{1,4}\\p{L}[\\p{L}\\p{N}_-]*｜{1,4}")

  /** Tag names of the invoke grammar: the call, its parameters and the wrappers vendors put around calls */
  private const val INVOKE_TAGS = "function_calls|tool_calls|toolcalls|calls|tool|invoke|parameter"

  /** A tag name after `<` or `</` with the space V4.1 writes after its marker or a lost marker leaves */
  private val SPACED = Regex("<\\s*(/?)\\s*($INVOKE_TAGS)\\b")

  private val INVOKE_WRAPPER = Regex("</?(?:function_calls|tool_calls|toolcalls|calls|tool|minimax:tool_call)\\s*>")
  private val INVOKE = Regex("<invoke\\s+name=(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))\\s*>(.*?)</invoke\\s*>", RegexOption.DOT_MATCHES_ALL)
  private val PARAMETER = Regex(
    "<parameter\\s+name=(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))(?:\\s+string=\"(true|false)\")?\\s*>(.*?)" +
    "(?:</parameter\\s*>|(?=<parameter\\s))", RegexOption.DOT_MATCHES_ALL)

  private fun MatchResult.nameOf(first: Int): String =
    (groupValues[first].ifEmpty { groupValues[first + 1] }.ifEmpty { groupValues[first + 2] }).trim()

  /**
   * Calls of the invoke grammar in markup already free of markers
   * `string="true"` is a string as written, `string="false"` is JSON, a parameter without the attribute is typed by schema
   */
  private fun readInvokes(markup: String, schemas: Schemas): Found? {
    val calls = ArrayList<Call>()
    for (invoke in INVOKE.findAll(markup)) {
      val name = invoke.nameOf(1)
      val properties = schemas.properties(name)
      val arguments = LinkedHashMap<String, JsonElement>()
      for (parameter in PARAMETER.findAll(invoke.groupValues[4])) {
        val key = parameter.nameOf(1)
        val value = parameter.groupValues[5]
        arguments[key] = when (parameter.groupValues[4]) {
          "true" -> JsonPrimitive(value)
          "false" -> TextToolCalls.jsonValue(value) ?: return null
          else -> SchemaValues.coerce(value, properties?.get(key) as? JsonObject)
        }
      }
      calls += Call(name, JsonObject(arguments))
    }
    if (calls.isEmpty()) return null
    return Found(calls, INVOKE_WRAPPER.replace(INVOKE.replace(markup, ""), ""))
  }

  /** DeepSeek's DSML with its marker, and the same with the marker lost on the way (`< invoke name=…>`) */
  private val DSML = object : Opened(
    "dsml",
    Regex("<\\s*｜{1,4}DSML｜{1,4}\\s*(?:function_calls|tool_calls|toolcalls|tool|calls|invoke)\\b" +
          "|<\\s+(?:function_calls|tool_calls|calls|invoke)\\b"),
    listOf("<｜DSML｜"),
  ) {
    override fun read(markup: String, schemas: Schemas): Found? =
      readInvokes(SPACED.replace(DSML_MARKER.replace(markup, "")) { "<" + it.groupValues[1] + it.groupValues[2] }, schemas)
  }

  /** `<invoke name=…>` with no family's marker: DeepSeek V3.2 once a server strips `｜DSML｜`, Anthropic-style XML */
  private val INVOKE_XML = object : Opened(
    "invoke",
    Regex("<(?:function_calls|tool_calls)\\s*>|<invoke\\s+name="),
    listOf("<function_calls>", "<tool_calls>", "<invoke name="),
  ) {
    override fun read(markup: String, schemas: Schemas): Found? = readInvokes(markup, schemas)
  }

  private val MINIMAX_M2 = object : Opened("minimax-m2", Regex("<minimax:tool_call>"), listOf("<minimax:tool_call>")) {
    override fun read(markup: String, schemas: Schemas): Found? = readInvokes(markup, schemas)
  }

  // MiniMax M3: every structural tag behind a namespace prefix, nested arguments as elements

  private const val M3_PREFIX = "]<]minimax[>["
  private val M3_TAG = Regex("\\G<(/?)([^\\s>/]+)(?:\\s+name=(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]*)))?\\s*>")

  private class Element(val name: String, val attribute: String?) {
    val children = ArrayList<Element>()
    val text = StringBuilder()
  }

  private val MINIMAX_M3 = object : Opened("minimax-m3", Regex(Regex.escape("$M3_PREFIX<tool_call>")), listOf("$M3_PREFIX<tool_call>")) {
    override fun read(markup: String, schemas: Schemas): Found? {
      val root = Element("", null)
      val stack = ArrayDeque(listOf(root))
      var at = 0
      var end = markup.length
      while (at < markup.length) {
        val next = markup.indexOf(M3_PREFIX, at)
        if (next < 0) {
          if (stack.size > 1) return null
          end = at
          break
        }
        stack.last().text.append(markup, at, next)
        val tag = M3_TAG.find(markup, next + M3_PREFIX.length) ?: return null
        val name = tag.groupValues[2]
        if (tag.groupValues[1].isEmpty()) {
          val element = Element(name, tag.nameOf(3).ifEmpty { null })
          stack.last().children += element
          stack.addLast(element)
        }
        else {
          if (stack.size < 2 || stack.last().name != name) return null
          stack.removeLast()
          if (stack.size == 1 && name == "tool_call") {
            at = tag.range.last + 1
            end = at
            break
          }
        }
        at = tag.range.last + 1
      }
      val block = root.children.singleOrNull { it.name == "tool_call" } ?: return null
      val calls = block.children.filter { it.name == "invoke" }.map { invoke ->
        val name = invoke.attribute ?: return null
        val properties = schemas.properties(name)
        Call(name, JsonObject(members(invoke) { key -> properties?.get(key) as? JsonObject }))
      }
      if (calls.isEmpty()) return null
      return Found(calls, root.text.toString() + markup.substring(end))
    }

    /** The children of [element] as object members; a key written twice becomes an array */
    private fun members(element: Element, schemaOf: (String) -> JsonObject?): Map<String, JsonElement> {
      val grouped = LinkedHashMap<String, MutableList<JsonElement>>()
      for (child in element.children) grouped.getOrPut(child.name) { ArrayList() } += value(child, schemaOf(child.name))
      return grouped.mapValues { (_, values) -> values.singleOrNull() ?: JsonArray(values) }
    }

    private fun value(element: Element, schema: JsonObject?): JsonElement {
      if (element.children.isEmpty()) return SchemaValues.coerce(element.text.toString(), schema)
      if (SchemaValues.types(schema).firstOrNull() == "array") {
        val items = schema?.get("items") as? JsonObject
        return JsonArray(element.children.map { value(it, items) })
      }
      val properties = schema?.get("properties") as? JsonObject
      return JsonObject(members(element) { key -> properties?.get(key) as? JsonObject })
    }
  }

  // DeepSeek V3 / R1 (JSON in a fence after the type) and V3.1 (JSON right after the name)

  private val DEEPSEEK_V3_CALL = Regex("<｜tool▁call▁begin｜>(.*?)<｜tool▁sep｜>(.*?)<｜tool▁call▁end｜>", RegexOption.DOT_MATCHES_ALL)
  private val DEEPSEEK_V3_SECTION = Regex("<｜tool▁calls▁(?:begin|end)｜>")
  private val JSON_FENCE = Regex("\\A\\s*```(?:json)?\\s*(.*?)\\s*```\\s*\\z", RegexOption.DOT_MATCHES_ALL)

  private val DEEPSEEK_V3 = object : Opened(
    "deepseek-v3", Regex("<｜tool▁calls?▁begin｜>"), listOf("<｜tool▁calls▁begin｜>", "<｜tool▁call▁begin｜>"),
  ) {
    override fun read(markup: String, schemas: Schemas): Found? {
      val calls = DEEPSEEK_V3_CALL.findAll(markup).toList().map { call ->
        val head = call.groupValues[1].trim()
        val body = call.groupValues[2]
        if (body.trimStart().startsWith("{")) Call(head, TextToolCalls.jsonObject(body) ?: return null)
        else {
          val name = body.substringBefore('\n').trim()
          val json = JSON_FENCE.find(body.substringAfter('\n', ""))?.groupValues?.get(1) ?: return null
          Call(name, TextToolCalls.jsonObject(json) ?: return null)
        }
      }
      if (calls.isEmpty()) return null
      return Found(calls, DEEPSEEK_V3_SECTION.replace(DEEPSEEK_V3_CALL.replace(markup, ""), ""))
    }
  }

  // `<tool_call>` opens four families; what follows the tag tells them apart

  private const val TOOL_CALL = "<tool_call>"
  private val TOOL_CALL_OPEN = Regex(Regex.escape(TOOL_CALL))
  private val TOOL_CALL_BLOCK = Regex("<tool_call>(.*?)(?:</tool_call>|(?=<tool_call>)|\\z)", RegexOption.DOT_MATCHES_ALL)

  /** A call object of the JSON families: `name` and `arguments` (or Llama's `parameters`), arguments maybe a string */
  private fun jsonCall(element: JsonElement): Call? {
    val call = element as? JsonObject ?: return null
    val name = (call["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    val arguments = when (val raw = call["arguments"] ?: call["parameters"]) {
      null, JsonNull -> JsonObject(emptyMap())
      is JsonObject -> raw
      is JsonPrimitive -> raw.contentOrNull?.let { TextToolCalls.jsonObject(it) } ?: return null
      else -> return null
    }
    return Call(name, arguments)
  }

  /** Calls in a JSON value: one call object or an array of them */
  private fun jsonCalls(element: JsonElement): List<Call>? = when (element) {
    is JsonArray -> element.map { jsonCall(it) ?: return null }.takeIf { it.isNotEmpty() }
    else -> jsonCall(element)?.let(::listOf)
  }

  /** Hermes 2 Pro, Qwen 2.5 and Qwen 3, Granite 3.1 and 4: a JSON object (or a list of them) inside `<tool_call>` */
  private val HERMES = object : Opened("hermes", TOOL_CALL_OPEN, listOf(TOOL_CALL)) {
    override fun read(markup: String, schemas: Schemas): Found? {
      val calls = ArrayList<Call>()
      for (block in TOOL_CALL_BLOCK.findAll(markup)) {
        val body = block.groupValues[1].trim()
        if (!body.startsWith("{") && !body.startsWith("[")) return null
        calls += jsonCalls(runCatching { Json.parseToJsonElement(body) }.getOrNull() ?: return null) ?: return null
      }
      if (calls.isEmpty()) return null
      return Found(calls, TOOL_CALL_BLOCK.replace(markup, ""))
    }
  }

  private val QWEN_WRAPPER = Regex("</?(?:seed:)?tool_call>")
  private val QWEN_FUNCTION = Regex("<function=([^>\\n]+)>(.*?)(?:</function>|(?=<function=)|\\z)", RegexOption.DOT_MATCHES_ALL)
  private val QWEN_PARAMETER = Regex(
    "<\\s*parameter\\s*=\\s*([^>]*)>(.*?)(?:<\\s*/\\s*parameter\\s*>|(?=<\\s*parameter\\s*=)|\\z)", RegexOption.DOT_MATCHES_ALL)

  /** Qwen3-Coder and Qwen 3.5+ (`<tool_call><function=…><parameter=…>`), Seed-OSS in `<seed:tool_call>` */
  private val QWEN_XML = object : Opened(
    "qwen-xml", Regex("<(?:seed:)?tool_call>\\s*<function=|<function=[^>\\n]+>"), listOf("<seed:tool_call>", "<function="),
  ) {
    override fun open(text: CharSequence, from: Int, atAnswerStart: Boolean): Int {
      // A bare `<tool_call>` still waiting for its body is claimed here too, so the stream holds it
      val tag = TOOL_CALL_OPEN.find(text, from)?.range?.first ?: -1
      val own = super.open(text, from, atAnswerStart)
      return listOf(tag, own).filter { it >= 0 }.minOrNull() ?: -1
    }

    override fun read(markup: String, schemas: Schemas): Found? {
      val calls = QWEN_FUNCTION.findAll(markup).toList().map { function ->
        val name = function.groupValues[1].trim()
        val properties = schemas.properties(name)
        val arguments = QWEN_PARAMETER.findAll(function.groupValues[2]).associate { parameter ->
          val key = parameter.groupValues[1].trim()
          key to SchemaValues.coerce(trimWrappingNewlines(parameter.groupValues[2]), properties?.get(key) as? JsonObject)
        }
        Call(name, JsonObject(arguments))
      }
      if (calls.isEmpty()) return null
      return Found(calls, QWEN_WRAPPER.replace(QWEN_FUNCTION.replace(markup, ""), ""))
    }

    /** The template wraps a value in one line break each side; the value's own breaks stay */
    private fun trimWrappingNewlines(value: String): String = value.removePrefix("\n").removeSuffix("\n")
  }

  private val GLM_BLOCK = Regex("<tool_call>(.*?)(?:</tool_call>|\\z)", RegexOption.DOT_MATCHES_ALL)
  private val GLM_ARGUMENT = Regex("<arg_key>(.*?)</arg_key>\\s*<arg_value>(.*?)</arg_value>", RegexOption.DOT_MATCHES_ALL)
  private val GLM_NAME = Regex("[\\p{L}\\p{N}_.:-]+")

  /** GLM 4.5 and newer: the name right after `<tool_call>`, then `<arg_key>`/`<arg_value>` pairs */
  private val GLM = object : Opened("glm", TOOL_CALL_OPEN, listOf(TOOL_CALL)) {
    override fun read(markup: String, schemas: Schemas): Found? {
      val calls = ArrayList<Call>()
      for (block in GLM_BLOCK.findAll(markup)) {
        val body = block.groupValues[1]
        val name = body.substringBefore("<arg_key>").trim()
        if (!GLM_NAME.matches(name)) return null
        val properties = schemas.properties(name)
        val arguments = GLM_ARGUMENT.findAll(body).associate { pair ->
          val key = pair.groupValues[1].trim()
          key to SchemaValues.coerce(pair.groupValues[2], properties?.get(key) as? JsonObject)
        }
        calls += Call(name, JsonObject(arguments))
      }
      if (calls.isEmpty()) return null
      return Found(calls, GLM_BLOCK.replace(markup, ""))
    }
  }

  // Kimi K2 (sections of calls named `functions.name:index`) and Kimi K3 (XTML channels)

  private val KIMI_K2_CALL = Regex(
    "<\\|tool_call_begin\\|>\\s*([^\\s<]+?)\\s*<\\|tool_call_argument_begin\\|>\\s*(.*?)\\s*<\\|tool_call_end\\|>",
    RegexOption.DOT_MATCHES_ALL,
  )
  private val KIMI_K2_SECTION = Regex("<\\|tool_calls_section_(?:begin|end)\\|>")
  private val KIMI_K2_INDEX = Regex(":\\d+\\z")

  private val KIMI_K2 = object : Opened(
    "kimi-k2", Regex("<\\|tool_calls_section_begin\\|>|<\\|tool_call_begin\\|>"),
    listOf("<|tool_calls_section_begin|>", "<|tool_call_begin|>"),
  ) {
    override fun read(markup: String, schemas: Schemas): Found? {
      val calls = KIMI_K2_CALL.findAll(markup).toList().map { call ->
        val name = KIMI_K2_INDEX.replace(call.groupValues[1], "").removePrefix("functions.")
        val body = call.groupValues[2].ifBlank { "{}" }
        Call(name, TextToolCalls.jsonObject(body) ?: return null)
      }
      if (calls.isEmpty()) return null
      return Found(calls, KIMI_K2_SECTION.replace(KIMI_K2_CALL.replace(markup, ""), ""))
    }
  }

  private const val K3_OPEN = "<\\|open\\|>\\s*"
  private const val K3_SEP = "\\s*<\\|sep\\|>"
  private const val K3_CLOSE = "<\\|close\\|>\\s*"
  private val K3_TOOLS = Regex("${K3_OPEN}tools$K3_SEP(.*?)(?:${K3_CLOSE}tools$K3_SEP|\\z)", RegexOption.DOT_MATCHES_ALL)
  private val K3_CALL = Regex("${K3_OPEN}call\\s+([^<]*?)$K3_SEP(.*?)${K3_CLOSE}call$K3_SEP", RegexOption.DOT_MATCHES_ALL)
  private val K3_ARGUMENT = Regex("${K3_OPEN}argument\\s+([^<]*?)$K3_SEP(.*?)${K3_CLOSE}argument$K3_SEP", RegexOption.DOT_MATCHES_ALL)
  private val K3_JSON = Regex("${K3_OPEN}json\\b[^<]*?$K3_SEP(.*?)${K3_CLOSE}json$K3_SEP", RegexOption.DOT_MATCHES_ALL)
  private val K3_CHANNEL = Regex("<\\|(?:open|close)\\|>\\s*(?:response|message)$K3_SEP")
  private val K3_ATTRIBUTE = Regex("(\\w+)=\"([^\"]*)\"")

  private fun k3Attributes(text: String): Map<String, String> =
    K3_ATTRIBUTE.findAll(text).associate { it.groupValues[1] to it.groupValues[2].replace("&quot;", "\"").replace("&amp;", "&") }

  private val KIMI_K3 = object : Opened(
    "kimi-k3",
    Regex("(?:<\\|close\\|>\\s*response\\s*<\\|sep\\|>\\s*)?<\\|open\\|>\\s*(?:tools\\s*<\\|sep\\|>|call\\s)"),
    listOf("<|close|>response<|sep|><|open|>tools<|sep|>", "<|open|>tools<|sep|>", "<|open|>call "),
  ) {
    override fun read(markup: String, schemas: Schemas): Found? {
      val calls = K3_CALL.findAll(markup).toList().map { call ->
        val name = k3Attributes(call.groupValues[1])["tool"] ?: return null
        val body = call.groupValues[2]
        val raw = K3_JSON.find(body)?.groupValues?.get(1)
        val arguments = if (raw != null) TextToolCalls.jsonObject(raw) ?: return null
        else JsonObject(K3_ARGUMENT.findAll(body).associate { argument ->
          val attributes = k3Attributes(argument.groupValues[1])
          val value = argument.groupValues[2]
          val typed = if (attributes["type"] == "string") JsonPrimitive(value)
          else runCatching { Json.parseToJsonElement(value) }.getOrElse { JsonPrimitive(value) }
          (attributes["key"] ?: return null) to typed
        })
        Call(name, arguments)
      }
      if (calls.isEmpty()) return null
      return Found(calls, K3_CHANNEL.replace(K3_TOOLS.replace(K3_CALL.replace(markup, ""), ""), ""))
    }
  }

  // Mistral: `[TOOL_CALLS]` with a JSON list (tokenizers v2–v7) or `name[ARGS]{…}` per call (v11 and newer)

  private const val MISTRAL_CALLS = "[TOOL_CALLS]"

  private val MISTRAL = object : Opened("mistral", Regex(Regex.escape(MISTRAL_CALLS)), listOf(MISTRAL_CALLS)) {
    override fun read(markup: String, schemas: Schemas): Found? {
      val calls = ArrayList<Call>()
      var at = markup.indexOf(MISTRAL_CALLS)
      var end = at
      while (at >= 0) {
        var from = at + MISTRAL_CALLS.length
        while (from < markup.length && markup[from].isWhitespace()) from++
        if (markup.startsWith("[", from)) {
          val value = JsonValues.at(markup, from) ?: return null
          calls += jsonCalls(value.first) ?: return null
          end = value.second
        }
        else {
          val argsAt = markup.indexOf('{', from).takeIf { it >= 0 } ?: return null
          val name = markup.substring(from, argsAt).substringBefore("[CALL_ID]").substringBefore("[ARGS]").trim()
          val value = JsonValues.at(markup, argsAt) ?: return null
          calls += Call(name, value.first as? JsonObject ?: return null)
          end = value.second
        }
        // The next call opens with its own marker; words in between end the calls
        val next = markup.indexOf(MISTRAL_CALLS, end)
        at = if (next >= 0 && markup.substring(end, next).isBlank()) next else -1
      }
      if (calls.isEmpty()) return null
      return Found(calls, markup.substring(end))
    }
  }

  // Llama and the bare JSON forms: a server that strips `<|python_tag|>`, `[TOOL_CALLS]` or `<|tool_call|>` leaves JSON

  /** JSON calls from [from] on, one after another, separated by blanks, `;` or `,`; with the end of the last */
  private fun jsonCallsFrom(markup: String, from: Int): Found? {
    val calls = ArrayList<Call>()
    var at = from
    var end = from
    while (true) {
      while (at < markup.length && (markup[at].isWhitespace() || markup[at] == ';' || markup[at] == ',')) at++
      if (at >= markup.length || (markup[at] != '{' && markup[at] != '[')) break
      val value = JsonValues.at(markup, at) ?: break
      calls += jsonCalls(value.first) ?: return null
      at = value.second
      end = at
    }
    if (calls.isEmpty()) return null
    return Found(calls, markup.substring(end))
  }

  private val PYTHON_TAG = Regex("<\\|python_tag\\|>")

  /** Llama 3.x behind `<|python_tag|>`: JSON with `parameters` */
  private val LLAMA = object : Opened("llama", PYTHON_TAG, listOf("<|python_tag|>")) {
    override fun read(markup: String, schemas: Schemas): Found? =
      jsonCallsFrom(markup, PYTHON_TAG.find(markup)?.range?.last?.plus(1) ?: return null)
  }

  /** The answer is JSON calls and nothing before them: Llama 3.x without its tag, Mistral or Granite 3 stripped */
  private val JSON_ANSWER = object : WholeAnswer("json", Regex("\\A\\s*\\[?\\s*\\{\\s*\"name\"\\s*:")) {
    override fun read(markup: String, schemas: Schemas): Found? = jsonCallsFrom(markup, 0)
  }

  /** Granite 3.x: `<|tool_call|>` and a JSON list; Granite 20B: `<function_call>` and one object per call */
  private val GRANITE_TAG = Regex("\\A\\s*(?:<\\|tool_call\\|>|<function_call>)")

  private val GRANITE = object : Opened("granite", Regex("<\\|tool_call\\|>|<function_call>"), listOf("<|tool_call|>", "<function_call>")) {
    override fun read(markup: String, schemas: Schemas): Found? {
      val calls = ArrayList<Call>()
      var rest = markup
      while (true) {
        val tag = GRANITE_TAG.find(rest) ?: break
        val found = jsonCallsFrom(rest, tag.range.last + 1) ?: return null
        calls += found.calls
        rest = found.rest
      }
      if (calls.isEmpty()) return null
      return Found(calls, rest)
    }
  }

  // gpt-oss (harmony): a message to `functions.name` whose body is JSON

  private val HARMONY_CALL = Regex("to=functions\\.([\\w.\\-]+)")
  private val HARMONY_HEADER = Regex("[\\s\\w]*")
  private val HARMONY_TOKEN = Regex("<\\|(?:start|end|message|channel|constrain|call|return)\\|>")

  private val HARMONY = object : Opened(
    "harmony",
    Regex("(?:<\\|start\\|>\\s*assistant\\s*)?(?:(?:<\\|channel\\|>\\s*)?(?:commentary|analysis|final)\\s+)?to=functions\\."),
    listOf(
      "<|start|>assistant to=functions.", "<|start|>assistant<|channel|>commentary to=functions.",
      "<|channel|>commentary to=functions.", "to=functions.",
    ),
  ) {
    override fun read(markup: String, schemas: Schemas): Found? {
      val calls = ArrayList<Call>()
      var end = 0
      for (call in HARMONY_CALL.findAll(markup)) {
        if (call.range.first < end) continue
        val body = markup.indexOf('{', call.range.last + 1).takeIf { it >= 0 } ?: return null
        // Between the recipient and the body only the header may stand: the channel, `json`, the constrain and message tokens
        val header = HARMONY_TOKEN.replace(markup.substring(call.range.last + 1, body), " ")
        if (!HARMONY_HEADER.matches(header)) return null
        val value = JsonValues.at(markup, body) ?: return null
        calls += Call(call.groupValues[1], value.first as? JsonObject ?: return null)
        end = value.second
      }
      if (calls.isEmpty()) return null
      return Found(calls, HARMONY_TOKEN.replace(markup.substring(end), ""))
    }
  }

  // Gemma 4: `<|tool_call>call:name{key:value,…}<tool_call|>` with strings between `<|"|>`

  private val GEMMA_BLOCK = Regex("<\\|tool_call>(.*?)(?:<tool_call\\|>|\\z)", RegexOption.DOT_MATCHES_ALL)
  private val GEMMA_HEAD = Regex("\\A\\s*(?:call)?:([\\w.\\-]+)\\s*(?=\\{)")

  private val GEMMA = object : Opened("gemma", Regex("<\\|tool_call>"), listOf("<|tool_call>")) {
    override fun read(markup: String, schemas: Schemas): Found? {
      val calls = GEMMA_BLOCK.findAll(markup).toList().map { block ->
        val body = block.groupValues[1]
        val head = GEMMA_HEAD.find(body) ?: return null
        val name = head.groupValues[1]
        val parsed = GemmaValues(body, head.range.last + 1).value(schemas.of(name)) as? JsonObject ?: return null
        Call(name, parsed)
      }
      if (calls.isEmpty()) return null
      return Found(calls, GEMMA_BLOCK.replace(markup, ""))
    }
  }

  /** Gemma's argument syntax: unquoted keys, strings between `<|"|>`, bare values typed by schema */
  private class GemmaValues(private val text: String, private var at: Int) {
    fun value(schema: JsonObject?): JsonElement? {
      skip()
      return when {
        text.startsWith(QUOTE, at) -> {
          val close = text.indexOf(QUOTE, at + QUOTE.length).takeIf { it >= 0 } ?: text.length
          val string = text.substring(at + QUOTE.length, close)
          at = minOf(text.length, close + QUOTE.length)
          JsonPrimitive(string)
        }
        text.startsWith("{", at) -> {
          at++
          val properties = schema?.get("properties") as? JsonObject
          val members = LinkedHashMap<String, JsonElement>()
          while (true) {
            skip()
            if (at >= text.length) return null
            if (text[at] == '}') {
              at++
              break
            }
            val colon = text.indexOf(':', at).takeIf { it >= 0 } ?: return null
            val key = text.substring(at, colon).trim().removeSurrounding("\"").removeSurrounding(QUOTE)
            at = colon + 1
            members[key] = value(properties?.get(key) as? JsonObject) ?: return null
            skip()
            if (at < text.length && text[at] == ',') at++
          }
          JsonObject(members)
        }
        text.startsWith("[", at) -> {
          at++
          val items = schema?.get("items") as? JsonObject
          val values = ArrayList<JsonElement>()
          while (true) {
            skip()
            if (at >= text.length) return null
            if (text[at] == ']') {
              at++
              break
            }
            values += value(items) ?: return null
            skip()
            if (at < text.length && text[at] == ',') at++
          }
          JsonArray(values)
        }
        else -> {
          val start = at
          while (at < text.length && text[at] !in ",}]") at++
          SchemaValues.coerce(text.substring(start, at).trim(), schema)
        }
      }
    }

    private fun skip() {
      while (at < text.length && text[at].isWhitespace()) at++
    }

    private companion object {
      const val QUOTE = "<|\"|>"
    }
  }

  // Llama 4 and the pythonic templates: the answer is a Python list of calls with keyword arguments

  private val PYTHONIC = object : WholeAnswer("pythonic", Regex("\\A\\s*(?:<\\|python_start\\|>\\s*)?\\[\\s*[A-Za-z_][\\w.]*\\s*\\(")) {
    override fun read(markup: String, schemas: Schemas): Found? {
      val reader = PythonCalls(markup, markup.indexOf('['))
      val calls = reader.calls() ?: return null
      return Found(calls, markup.substring(reader.end).replace("<|python_end|>", ""))
    }
  }

  /** `[name(key=literal, …), …]`: strings, numbers, True/False/None (and their JSON spellings), lists, dicts, tuples */
  private class PythonCalls(private val text: String, private var at: Int) {
    val end: Int get() = at

    fun calls(): List<Call>? {
      if (!eat('[')) return null
      val calls = ArrayList<Call>()
      while (true) {
        skip()
        if (eat(']')) return calls.takeIf { it.isNotEmpty() }
        val name = identifier(dotted = true) ?: return null
        if (!eat('(')) return null
        val arguments = LinkedHashMap<String, JsonElement>()
        while (true) {
          skip()
          if (eat(')')) break
          val key = identifier(dotted = false) ?: return null
          if (!eat('=')) return null
          arguments[key] = literal() ?: return null
          skip()
          eat(',')
        }
        calls += Call(name, JsonObject(arguments))
        skip()
        eat(',')
      }
    }

    private fun literal(): JsonElement? {
      skip()
      if (at >= text.length) return null
      val c = text[at]
      return when {
        c == '"' || c == '\'' -> string(c)
        c == '[' || c == '(' -> sequence(if (c == '[') ']' else ')')
        c == '{' -> dict()
        else -> {
          val start = at
          while (at < text.length && (text[at].isLetterOrDigit() || text[at] in "+-._")) at++
          when (val word = text.substring(start, at)) {
            "True", "true" -> JsonPrimitive(true)
            "False", "false" -> JsonPrimitive(false)
            "None", "null" -> JsonNull
            else -> word.toLongOrNull()?.let(::JsonPrimitive) ?: word.toDoubleOrNull()?.takeIf { it.isFinite() }?.let(::JsonPrimitive)
          }
        }
      }
    }

    private fun string(quote: Char): JsonElement? {
      at++
      val out = StringBuilder()
      while (at < text.length && text[at] != quote) {
        if (text[at] == '\\' && at + 1 < text.length) {
          at++
          out.append(when (text[at]) { 'n' -> '\n'; 't' -> '\t'; 'r' -> '\r'; else -> text[at] })
        }
        else out.append(text[at])
        at++
      }
      if (!eat(quote)) return null
      return JsonPrimitive(out.toString())
    }

    private fun sequence(close: Char): JsonElement? {
      at++
      val values = ArrayList<JsonElement>()
      while (true) {
        skip()
        if (eat(close)) return JsonArray(values)
        values += literal() ?: return null
        skip()
        eat(',')
      }
    }

    private fun dict(): JsonElement? {
      at++
      val members = LinkedHashMap<String, JsonElement>()
      while (true) {
        skip()
        if (eat('}')) return JsonObject(members)
        val key = (literal() as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        skip()
        if (!eat(':')) return null
        members[key] = literal() ?: return null
        skip()
        eat(',')
      }
    }

    private fun identifier(dotted: Boolean): String? {
      skip()
      val start = at
      while (at < text.length && (text[at].isLetterOrDigit() || text[at] == '_' || (dotted && text[at] == '.'))) at++
      return text.substring(start, at).takeIf { it.isNotEmpty() && !it[0].isDigit() }
    }

    private fun skip() {
      while (at < text.length && text[at].isWhitespace()) at++
    }

    private fun eat(c: Char): Boolean {
      skip()
      if (at < text.length && text[at] == c) {
        at++
        return true
      }
      return false
    }
  }

  /**
   * In the order a shared opener is tried: MiniMax M3 before `<tool_call>` (its opener contains it), the JSON body of
   * `<tool_call>` before the XML one and GLM, the marked invoke forms before the bare one
   */
  val ALL: List<Format> = listOf(
    MINIMAX_M3, DSML, MINIMAX_M2, INVOKE_XML, DEEPSEEK_V3, HERMES, QWEN_XML, GLM, KIMI_K2, KIMI_K3, MISTRAL, LLAMA,
    GRANITE, HARMONY, GEMMA, JSON_ANSWER, PYTHONIC,
  )
}

/** The first JSON value in a text, as `raw_decode` finds it: the value and where it ends */
internal object JsonValues {
  fun at(text: String, from: Int): Pair<JsonElement, Int>? {
    var at = from
    while (at < text.length && text[at].isWhitespace()) at++
    if (at >= text.length) return null
    val end = when (text[at]) {
      '{', '[' -> balanced(text, at)
      '"' -> stringEnd(text, at)
      else -> {
        var i = at
        while (i < text.length && text[i] !in ",;}] \n\t\r") i++
        i
      }
    }
    if (end <= at) return null
    val element = runCatching { Json.parseToJsonElement(text.substring(at, end)) }.getOrNull() ?: return null
    return element to end
  }

  private fun balanced(text: String, from: Int): Int {
    var depth = 0
    var i = from
    while (i < text.length) {
      when (text[i]) {
        '"' -> i = stringEnd(text, i) - 1
        '{', '[' -> depth++
        '}', ']' -> if (--depth == 0) return i + 1
      }
      i++
    }
    return -1
  }

  private fun stringEnd(text: String, from: Int): Int {
    var i = from + 1
    while (i < text.length) {
      when (text[i]) {
        '\\' -> i++
        '"' -> return i + 1
      }
      i++
    }
    return text.length + 1
  }
}

/**
 * A value written as text, typed by its JSON schema, by vLLM's rule (`coerce_to_schema_type`)
 * The schema's types are tried as null, integer, number, boolean, object, array, string; the first that fits wins
 * No schema — a string; a schema none of whose types fits and that has no string — JSON if it is JSON, else the text
 */
internal object SchemaValues {
  private val ORDER = listOf("null", "integer", "number", "boolean", "object", "array", "string")

  /** The types a schema allows, in [ORDER]: `type` as a word or a list, `nullable`, and the types of `anyOf`/`oneOf` */
  fun types(schema: JsonObject?): List<String> {
    if (schema == null) return emptyList()
    val found = HashSet<String>()
    fun collect(node: JsonObject) {
      when (val type = node["type"]) {
        is JsonPrimitive -> type.contentOrNull?.let(found::add)
        is JsonArray -> type.forEach { (it as? JsonPrimitive)?.contentOrNull?.let(found::add) }
        else -> Unit
      }
      if ((node["nullable"] as? JsonPrimitive)?.contentOrNull == "true") found += "null"
      for (key in listOf("anyOf", "oneOf")) (node[key] as? JsonArray)?.forEach { (it as? JsonObject)?.let(::collect) }
    }
    collect(schema)
    return ORDER.filter { it in found }
  }

  fun coerce(raw: String, schema: JsonObject?): JsonElement {
    val types = types(schema)
    if (types.isEmpty()) return JsonPrimitive(raw)
    val trimmed = raw.trim()
    for (type in types) {
      when (type) {
        "null" -> if (trimmed.equals("null", ignoreCase = true)) return JsonNull
        "integer" -> trimmed.toLongOrNull()?.let { return JsonPrimitive(it) }
        "number" -> trimmed.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { number ->
          // A whole number goes out as an integer, as vLLM does: `4.0` for a count is 4
          return if (number == Math.rint(number) && Math.abs(number) < Long.MAX_VALUE) JsonPrimitive(number.toLong())
          else JsonPrimitive(number)
        }
        "boolean" -> when (trimmed.lowercase()) {
          "true", "1" -> return JsonPrimitive(true)
          "false", "0" -> return JsonPrimitive(false)
        }
        "object" -> TextToolCalls.jsonObject(trimmed)?.let { return it }
        "array" -> (runCatching { Json.parseToJsonElement(trimmed) }.getOrNull() as? JsonArray)?.let { return it }
        "string" -> return JsonPrimitive(raw)
      }
    }
    return runCatching { Json.parseToJsonElement(trimmed) }.getOrNull() ?: JsonPrimitive(raw)
  }
}
