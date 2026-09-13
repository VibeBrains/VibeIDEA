// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp.extjs

/**
 * Ext JS structure read without a JavaScript parser.
 *
 * The open platform has no JS core: `.js` is highlighted by TextMate and its PSI is flat. The first version
 * matched `Ext.define` with regular expressions over the whole text, which was enough for class names and
 * cost two things: a name inside a comment counted, and nothing about the class body was known.
 *
 * Navigating to methods needs the body — where a class starts and ends, its members, its parent. For that a
 * tokenizer that knows strings, comments, template and regex literals, plus bracket depth, is enough: the
 * object passed to `Ext.define` is found by tokens and its top-level keys are read from it. A class name
 * assembled from variables is still invisible; that is the price of not executing the code.
 */

/** A declaration to navigate to: a class name or alias and its place in the file. */
data class ExtSymbol(val name: String, val offset: Int)

/** A top-level key of a class body, or a key of its `config` block. */
data class ExtMember(val name: String, val offset: Int, val kind: Kind, val valueStart: Int, val valueEnd: Int) {
  enum class Kind { METHOD, PROPERTY, CONFIG }
}

/** One `Ext.define`: its name, body range, parent, mixins, aliases and members. */
data class ExtClass(
  val name: String,
  val offset: Int,
  val bodyStart: Int,
  val bodyEnd: Int,
  val extend: String?,
  val mixins: List<String>,
  val aliases: List<ExtSymbol>,
  val members: List<ExtMember>,
)

/** Everything one file declares and every place it fires or listens to an event. */
data class ExtFileScan(val classes: List<ExtClass>, val events: List<ExtSymbol>)

/** Class name — a dotted chain of identifiers: `common.MorbusOnko.Modals.BaseModal`. */
private val CLASS_NAME = Regex("^[A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*)+$")

/** A string that can be a class name, alias or xtype: no spaces, no URL punctuation. */
private val SYMBOL_LIKE = Regex("^[A-Za-z_$][\\w$.-]*$")

/** `getTitle`, `setStore`, `applyValue`, `updateValue`, `resetValue` → the config they belong to. */
private val ACCESSOR = Regex("^(get|set|apply|update|reset)([A-Z][\\w$]*)$")

private const val WIDGET_PREFIX = "widget."

/** Calls whose first string argument is an event name. */
private val EVENT_CALLS = setOf("fireEvent", "fireEventArgs", "on", "un", "addListener", "removeListener")

/** Keys of a `listeners` object that are options, not events. */
private val LISTENER_OPTIONS = setOf(
  "scope", "delay", "buffer", "single", "element", "delegate", "target", "destroyable", "priority", "order", "args", "onFrame",
)

/** Keys whose object holds config properties that get generated accessors. */
private val CONFIG_BLOCKS = setOf("config", "cachedConfig", "eventedConfig")

/** Keywords after which `/` starts a regex literal rather than a division. */
private val REGEX_AFTER_WORDS = setOf("return", "typeof", "case", "in", "of", "new", "delete", "void", "throw", "instanceof")

/** A line this long is minified code: indexing it yields thousands of meaningless targets. */
const val MINIFIED_LINE_CHARS = 5_000

internal enum class TokKind { IDENT, STRING, PUNCT }

/** A token. For a string, [text] is its content and [offset] points at the first character inside the quotes. */
internal data class Tok(val kind: TokKind, val text: String, val offset: Int)

internal fun tokenize(text: CharSequence): List<Tok> {
  val tokens = ArrayList<Tok>()
  var i = 0
  val n = text.length
  while (i < n) {
    val c = text[i]
    when {
      c.isWhitespace() -> i++
      c == '/' && i + 1 < n && text[i + 1] == '/' -> {
        while (i < n && text[i] != '\n') i++
      }
      c == '/' && i + 1 < n && text[i + 1] == '*' -> {
        val end = indexOf(text, "*/", i + 2)
        i = if (end < 0) n else end + 2
      }
      c == '/' && regexMayStart(tokens.lastOrNull()) -> i = skipRegex(text, i)
      c == '\'' || c == '"' -> {
        val start = i + 1
        var j = start
        while (j < n && text[j] != c && text[j] != '\n') j += if (text[j] == '\\') 2 else 1
        tokens.add(Tok(TokKind.STRING, text.subSequence(start, j.coerceAtMost(n)).toString(), start))
        i = j + 1
      }
      c == '`' -> {
        var j = i + 1
        while (j < n && text[j] != '`') j += if (text[j] == '\\') 2 else 1
        tokens.add(Tok(TokKind.STRING, "", i + 1))
        i = j + 1
      }
      Character.isJavaIdentifierStart(c) -> {
        var j = i + 1
        while (j < n && Character.isJavaIdentifierPart(text[j])) j++
        tokens.add(Tok(TokKind.IDENT, text.subSequence(i, j).toString(), i))
        i = j
      }
      c.isDigit() -> {
        var j = i + 1
        while (j < n && (Character.isJavaIdentifierPart(text[j]) || text[j] == '.')) j++
        i = j
      }
      else -> {
        tokens.add(Tok(TokKind.PUNCT, c.toString(), i))
        i++
      }
    }
  }
  return tokens
}

private fun regexMayStart(previous: Tok?): Boolean = when (previous?.kind) {
  null -> true
  TokKind.PUNCT -> previous.text !in setOf(")", "]", "}")
  TokKind.IDENT -> previous.text in REGEX_AFTER_WORDS
  TokKind.STRING -> false
}

private fun skipRegex(text: CharSequence, start: Int): Int {
  var i = start + 1
  var inClass = false
  while (i < text.length && text[i] != '\n') {
    when (text[i]) {
      '\\' -> i++
      '[' -> inClass = true
      ']' -> inClass = false
      '/' -> if (!inClass) return i + 1
    }
    i++
  }
  return i
}

private fun indexOf(text: CharSequence, needle: String, from: Int): Int {
  var i = from
  while (i <= text.length - needle.length) {
    if (text.regionMatches(i, needle, 0, needle.length)) return i
    i++
  }
  return -1
}

private fun Tok?.isPunct(ch: String) = this != null && kind == TokKind.PUNCT && text == ch

private fun Tok?.isIdent(name: String) = this != null && kind == TokKind.IDENT && text == name

/** One `key: value` (or `key() {}`) of an object literal, by token indices; [valueEnd] is exclusive. */
private data class Entry(val key: Tok, val valueStart: Int, val valueEnd: Int, val shorthand: Boolean)

private data class ObjectLiteral(val entries: List<Entry>, val close: Int)

/** Reads the object literal whose `{` is at [open]. Tolerates an unclosed object at the end of the file. */
private fun readObject(tokens: List<Tok>, open: Int): ObjectLiteral {
  val entries = ArrayList<Entry>()
  var j = open + 1
  while (j < tokens.size && !tokens[j].isPunct("}")) {
    val key = tokens[j]
    val keyUsable = key.kind == TokKind.IDENT || key.kind == TokKind.STRING
    val shorthand = keyUsable && key.kind == TokKind.IDENT && tokens.getOrNull(j + 1).isPunct("(")
    val valueStart = when {
      keyUsable && tokens.getOrNull(j + 1).isPunct(":") -> j + 2
      shorthand -> j + 1
      else -> j
    }
    var k = valueStart
    var depth = 0
    while (k < tokens.size) {
      val t = tokens[k]
      if (t.kind == TokKind.PUNCT) {
        if (depth == 0 && (t.text == "," || t.text == "}")) break
        when (t.text) {
          "(", "[", "{" -> depth++
          ")", "]", "}" -> depth--
        }
      }
      k++
    }
    if (keyUsable && valueStart != j) entries.add(Entry(key, valueStart, k, shorthand))
    j = if (tokens.getOrNull(k).isPunct(",")) k + 1 else k
  }
  return ObjectLiteral(entries, j)
}

private fun offsetOf(tokens: List<Tok>, index: Int, text: CharSequence): Int =
  tokens.getOrNull(index)?.offset ?: text.length

private fun stringsIn(tokens: List<Tok>, start: Int, end: Int): List<Tok> =
  (start until end).map { tokens[it] }.filter { it.kind == TokKind.STRING }

/** Every class and event of a file. */
fun scanFile(text: CharSequence): ExtFileScan {
  val tokens = tokenize(text)
  val classes = ArrayList<ExtClass>()
  val events = ArrayList<ExtSymbol>()
  for (i in tokens.indices) {
    val t = tokens[i]
    if (t.isIdent("Ext") && tokens.getOrNull(i + 1).isPunct(".") && tokens.getOrNull(i + 2).isIdent("define") &&
        tokens.getOrNull(i + 3).isPunct("(") && tokens.getOrNull(i + 4)?.kind == TokKind.STRING) {
      classes.add(readClass(tokens, i + 4, text))
    }
    if (t.kind == TokKind.IDENT && t.text in EVENT_CALLS && tokens.getOrNull(i + 1).isPunct("(")) {
      val name = tokens.getOrNull(i + 2)
      if (name?.kind == TokKind.STRING && name.text.isNotBlank() && !tokens.getOrNull(i - 1).isIdent("function")) {
        events.add(ExtSymbol(name.text, name.offset))
      }
    }
    if ((t.isIdent("listeners") || (t.kind == TokKind.STRING && t.text == "listeners")) &&
        tokens.getOrNull(i + 1).isPunct(":") && tokens.getOrNull(i + 2).isPunct("{")) {
      readObject(tokens, i + 2).entries
        .filter { it.key.text !in LISTENER_OPTIONS }
        .forEach { events.add(ExtSymbol(it.key.text, it.key.offset)) }
    }
  }
  return ExtFileScan(classes, events)
}

private fun readClass(tokens: List<Tok>, nameIndex: Int, text: CharSequence): ExtClass {
  val name = tokens[nameIndex]
  val open = nameIndex + 2
  if (!tokens.getOrNull(nameIndex + 1).isPunct(",") || !tokens.getOrNull(open).isPunct("{")) {
    return ExtClass(name.text, name.offset, name.offset, name.offset, null, emptyList(), emptyList(), emptyList())
  }
  val body = readObject(tokens, open)
  var extend: String? = null
  val mixins = ArrayList<String>()
  val aliases = ArrayList<ExtSymbol>()
  val members = ArrayList<ExtMember>()
  for (entry in body.entries) {
    val first = tokens.getOrNull(entry.valueStart)
    when (entry.key.text) {
      "extend" -> if (first?.kind == TokKind.STRING) extend = first.text
      "alias", "xtype" -> stringsIn(tokens, entry.valueStart, entry.valueEnd).forEach { aliases.add(ExtSymbol(it.text, it.offset)) }
      "mixins" -> {
        // Array form lists class names; object form maps a mixin id to a class name — values only.
        val inObject = first.isPunct("{")
        (entry.valueStart until entry.valueEnd).forEach { k ->
          val token = tokens[k]
          if (token.kind == TokKind.STRING && (!inObject || tokens.getOrNull(k - 1).isPunct(":"))) mixins.add(token.text)
        }
      }
    }
    if (entry.key.text in CONFIG_BLOCKS && first.isPunct("{")) {
      readObject(tokens, entry.valueStart).entries.forEach { config ->
        members.add(ExtMember(config.key.text, config.key.offset, ExtMember.Kind.CONFIG,
                              offsetOf(tokens, config.valueStart, text), offsetOf(tokens, config.valueEnd, text)))
      }
    }
    val kind = if (entry.shorthand || first.isIdent("function")) ExtMember.Kind.METHOD else ExtMember.Kind.PROPERTY
    members.add(ExtMember(entry.key.text, entry.key.offset, kind,
                          offsetOf(tokens, entry.valueStart, text), offsetOf(tokens, entry.valueEnd, text)))
  }
  return ExtClass(name.text, name.offset, tokens[open].offset, offsetOf(tokens, body.close, text), extend, mixins, aliases, members)
}

/** Class names and aliases as they are written — the declarations of a file. */
fun scanSymbols(text: CharSequence): List<ExtSymbol> =
  scanFile(text).classes.flatMap { listOf(ExtSymbol(it.name, it.offset)) + it.aliases }

/**
 * The names a declaration is looked up by. `alias: 'widget.grid'` is used in markup as `xtype: 'grid'`,
 * so the short form is a key too.
 */
fun lookupNames(symbol: String): List<String> =
  if (symbol.startsWith(WIDGET_PREFIX) && symbol.length > WIDGET_PREFIX.length) listOf(symbol, symbol.removePrefix(WIDGET_PREFIX))
  else listOf(symbol)

/** Whether text is an Ext JS class name: without a dot it is a variable, not a class. */
fun isExtClassName(text: String): Boolean = CLASS_NAME.matches(text)

/** Whether a string literal is worth looking up as a class, alias or xtype. */
fun isSymbolLike(text: String): Boolean = SYMBOL_LIKE.matches(text)

/** The config property an accessor is generated for: `getStore` → `store`; null when it is not an accessor. */
fun configNameOf(accessor: String): String? =
  ACCESSOR.matchEntire(accessor)?.groupValues?.get(2)?.replaceFirstChar { it.lowercaseChar() }

/** Minified code: one line longer than [MINIFIED_LINE_CHARS]. */
fun isMinified(text: CharSequence): Boolean {
  var lineStart = 0
  for (i in text.indices) {
    if (text[i] == '\n') {
      if (i - lineStart > MINIFIED_LINE_CHARS) return true
      lineStart = i + 1
    }
  }
  return text.length - lineStart > MINIFIED_LINE_CHARS
}

/** The innermost class whose body holds [offset]. */
fun classAt(classes: List<ExtClass>, offset: Int): ExtClass? =
  classes.filter { offset in it.bodyStart..it.bodyEnd }.minByOrNull { it.bodyEnd - it.bodyStart }

/** The method of [cls] whose body holds [offset] — the method `callParent` is called from. */
fun methodAt(cls: ExtClass, offset: Int): ExtMember? =
  cls.members.filter { it.kind == ExtMember.Kind.METHOD && offset in it.valueStart..it.valueEnd }.minByOrNull { it.valueEnd - it.valueStart }

/** The identifier under the caret (the caret right after it counts too). */
fun identifierAt(text: CharSequence, offset: Int): ExtSymbol? {
  if (text.isEmpty()) return null
  var start = offset.coerceIn(0, text.length)
  if ((start == text.length || !Character.isJavaIdentifierPart(text[start])) && start > 0 && Character.isJavaIdentifierPart(text[start - 1])) start--
  if (start >= text.length || !Character.isJavaIdentifierPart(text[start])) return null
  while (start > 0 && Character.isJavaIdentifierPart(text[start - 1])) start--
  var end = start
  while (end < text.length && Character.isJavaIdentifierPart(text[end])) end++
  if (!Character.isJavaIdentifierStart(text[start])) return null
  return ExtSymbol(text.subSequence(start, end).toString(), start)
}

/** What stands before `.name`: `this` in `this.name`, `me` in `me .name`; null when there is no dot. */
fun receiverOf(text: CharSequence, identifierStart: Int): String? {
  var i = identifierStart - 1
  while (i >= 0 && text[i].isWhitespace()) i--
  if (i < 0 || text[i] != '.') return null
  i--
  while (i >= 0 && text[i].isWhitespace()) i--
  val end = i + 1
  while (i >= 0 && Character.isJavaIdentifierPart(text[i])) i--
  return if (end > i + 1) text.subSequence(i + 1, end).toString() else null
}

/** Whether the identifier at [identifierEnd] is called: `name (`. */
fun isCalled(text: CharSequence, identifierEnd: Int): Boolean {
  var i = identifierEnd
  while (i < text.length && text[i].isWhitespace()) i++
  return i < text.length && text[i] == '('
}

/**
 * The content of the string literal under the caret.
 *
 * Quotes are searched within the line: a literal in Ext JS does not wrap, and a search over the whole file
 * on an unbalanced quote would take the caret into the next function.
 */
fun literalAt(text: CharSequence, offset: Int): String? = literalRangeAt(text, offset)?.let { text.subSequence(it.first, it.last + 1).toString() }

/** The range of the content of the literal under the caret, empty literal as an empty range. */
fun literalRangeAt(text: CharSequence, offset: Int): IntRange? {
  if (offset < 0 || offset > text.length) return null
  val lineStart = text.lastIndexOfChar('\n', offset - 1) + 1
  val lineEnd = text.indexOfChar('\n', offset).let { if (it < 0) text.length else it }

  var quoteStart = -1
  var quote = ' '
  var index = lineStart
  while (index < lineEnd) {
    val char = text[index]
    if (char == '\'' || char == '"') {
      if (quoteStart < 0) {
        quoteStart = index
        quote = char
      }
      else if (char == quote) {
        // The caret on the quote itself counts: people click on the edge of a selection.
        if (offset in quoteStart..index) return (quoteStart + 1) until index
        quoteStart = -1
      }
    }
    index++
  }
  return null
}

private fun CharSequence.indexOfChar(char: Char, from: Int): Int {
  var index = from.coerceAtLeast(0)
  while (index < length) {
    if (this[index] == char) return index
    index++
  }
  return -1
}

private fun CharSequence.lastIndexOfChar(char: Char, from: Int): Int {
  var index = from.coerceAtMost(length - 1)
  while (index >= 0) {
    if (this[index] == char) return index
    index--
  }
  return -1
}
