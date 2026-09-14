// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp.extjs

import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.indexing.DataIndexer
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileBasedIndexExtension
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.ID
import com.intellij.util.io.DataExternalizer
import com.intellij.util.io.DataInputOutputUtil
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.IOUtil
import com.intellij.util.io.IntInlineKeyDescriptor
import com.intellij.util.io.KeyDescriptor
import java.io.DataInput
import java.io.DataOutput

/**
 * The Ext JS indexes: declarations, class structure, methods by name, events.
 *
 * Indexes and not a project search on every click: the owner's codebase holds thousands of declarations,
 * and walking files on every Ctrl+Click would be felt. All four read one scan of a file (cached on the
 * indexed content), so four indexes cost one tokenization.
 *
 * **Bump [VERSION] on any change of the scan** — otherwise projects opened before keep maps built by old code.
 */
private const val VERSION = 3

private val SCAN = Key.create<ExtFileScan>("vibe.extjs.scan")

private fun scanOf(content: FileContent): ExtFileScan =
  content.getUserData(SCAN) ?: run {
    val text = content.contentAsText
    // Minified code is one enormous line: indexing it turns every short name into a meaningless target.
    val scan = if (isMinified(text)) ExtFileScan(emptyList(), emptyList()) else scanFile(text)
    content.putUserData(SCAN, scan)
    scan
  }

/**
 * Only JavaScript files, by name rather than file type: in the open platform `.js` is sometimes TextMate and
 * sometimes plain text, and a binding to the type would part with reality on the first user setting.
 * `*.min.js` is skipped without reading; other minified files are recognised by content in [scanOf].
 */
private class ExtInputFilter : FileBasedIndex.InputFilter {
  override fun acceptInput(file: VirtualFile): Boolean = file.extension == "js" && !file.name.endsWith(".min.js")
}

internal abstract class ExtIndex<V> : FileBasedIndexExtension<String, V>() {
  override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE
  override fun getVersion(): Int = VERSION
  override fun dependsOnFileContent(): Boolean = true
  override fun getInputFilter(): FileBasedIndex.InputFilter = ExtInputFilter()
}

/** Offsets within one file; a name may occur there more than once. */
private object IntListExternalizer : DataExternalizer<List<Int>> {
  override fun save(out: DataOutput, value: List<Int>) {
    DataInputOutputUtil.writeINT(out, value.size)
    value.forEach { DataInputOutputUtil.writeINT(out, it) }
  }

  override fun read(input: DataInput): List<Int> = List(DataInputOutputUtil.readINT(input)) { DataInputOutputUtil.readINT(input) }
}

/** Class name, alias or short xtype → where it is declared. */
object ExtDefineIndex : ExtIndexHolder<Int>("com.vibe.lsp.extjs.definitions")

/** Class name → its parent, mixins and members. */
object ExtClassIndex : ExtIndexHolder<ExtClassInfo>("com.vibe.lsp.extjs.classes")

/** Member name (method or property) → where a class declares it: the fallback when the receiver's class is unknown. */
object ExtMemberIndex : ExtIndexHolder<List<Int>>("com.vibe.lsp.extjs.members")

/** Event name → every place that fires or listens to it. */
object ExtEventIndex : ExtIndexHolder<List<Int>>("com.vibe.lsp.extjs.events")

/** Holds an index id so the handler can query without knowing the extension classes. */
open class ExtIndexHolder<V>(id: String) {
  val NAME: ID<String, V> = ID.create(id)
}

/** A member as the class index stores it. */
data class ExtMemberInfo(val name: String, val kind: ExtMember.Kind, val offset: Int)

data class ExtClassInfo(val extend: String?, val mixins: List<String>, val members: List<ExtMemberInfo>)

private object ClassInfoExternalizer : DataExternalizer<ExtClassInfo> {
  override fun save(out: DataOutput, value: ExtClassInfo) {
    IOUtil.writeUTF(out, value.extend.orEmpty())
    DataInputOutputUtil.writeINT(out, value.mixins.size)
    value.mixins.forEach { IOUtil.writeUTF(out, it) }
    DataInputOutputUtil.writeINT(out, value.members.size)
    value.members.forEach {
      IOUtil.writeUTF(out, it.name)
      DataInputOutputUtil.writeINT(out, it.kind.ordinal)
      DataInputOutputUtil.writeINT(out, it.offset)
    }
  }

  override fun read(input: DataInput): ExtClassInfo {
    val extend = IOUtil.readUTF(input).ifEmpty { null }
    val mixins = List(DataInputOutputUtil.readINT(input)) { IOUtil.readUTF(input) }
    val members = List(DataInputOutputUtil.readINT(input)) {
      ExtMemberInfo(IOUtil.readUTF(input), ExtMember.Kind.entries[DataInputOutputUtil.readINT(input)], DataInputOutputUtil.readINT(input))
    }
    return ExtClassInfo(extend, mixins, members)
  }
}

internal class ExtDefineIndexExtension : ExtIndex<Int>() {
  override fun getName(): ID<String, Int> = ExtDefineIndex.NAME
  override fun getIndexer(): DataIndexer<String, Int, FileContent> = DataIndexer { content ->
    val scan = scanOf(content)
    buildMap {
      scan.classes.forEach { cls ->
        putIfAbsent(cls.name, cls.offset)
        cls.aliases.forEach { alias -> lookupNames(alias.name).forEach { putIfAbsent(it, alias.offset) } }
      }
    }
  }
  override fun getValueExternalizer(): DataExternalizer<Int> = IntInlineKeyDescriptor()
}

internal class ExtClassIndexExtension : ExtIndex<ExtClassInfo>() {
  override fun getName(): ID<String, ExtClassInfo> = ExtClassIndex.NAME
  override fun getIndexer(): DataIndexer<String, ExtClassInfo, FileContent> = DataIndexer { content ->
    scanOf(content).classes.associate { cls ->
      cls.name to ExtClassInfo(cls.extend, cls.mixins, cls.members.map { ExtMemberInfo(it.name, it.kind, it.offset) })
    }
  }
  override fun getValueExternalizer(): DataExternalizer<ExtClassInfo> = ClassInfoExternalizer
}

internal class ExtMemberIndexExtension : ExtIndex<List<Int>>() {
  override fun getName(): ID<String, List<Int>> = ExtMemberIndex.NAME
  override fun getIndexer(): DataIndexer<String, List<Int>, FileContent> = DataIndexer { content ->
    // Properties too: `constants.NumberConfigs.spinnerInteger` is a property, and indexing methods only answered «nothing».
    scanOf(content).classes.flatMap { it.members }.filter { it.kind != ExtMember.Kind.CONFIG }
      .groupBy({ it.name }, { it.offset })
  }
  override fun getValueExternalizer(): DataExternalizer<List<Int>> = IntListExternalizer
}

internal class ExtEventIndexExtension : ExtIndex<List<Int>>() {
  override fun getName(): ID<String, List<Int>> = ExtEventIndex.NAME
  override fun getIndexer(): DataIndexer<String, List<Int>, FileContent> = DataIndexer { content ->
    scanOf(content).events.groupBy({ it.name }, { it.offset })
  }
  override fun getValueExternalizer(): DataExternalizer<List<Int>> = IntListExternalizer
}
