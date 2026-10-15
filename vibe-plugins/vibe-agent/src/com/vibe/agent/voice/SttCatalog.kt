// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.voice

import com.intellij.openapi.diagnostic.logger
import com.vibe.agent.defaults.VibeDefaults
import com.vibe.agent.util.VibeJsonc
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.util.Locale

/**
 * The speech models local recognition can download, and which of them make up a dictation in each language
 *
 * Data, not code: the list lives in the shared `.vibe` set (`voice/sttModels.jsonc`) that VibeIDE reads too, and is read
 * from the build, never seeded into a project; an address, a size or a checksum copied into the code of each product
 * drifts apart without a sound, and a model whose checksum is wrong never installs
 * Pure: text in, a catalogue out; an entry that does not hold together is dropped with a warning and the rest work
 */
data class SttCatalog(val sampleRate: Int, val archives: List<Archive>, val profiles: Map<String, Profile>) {
  /** One zip on the mirror that unpacks into `<models>/<dir>/` */
  data class Archive(
    val id: String,
    val dir: String,
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
    val files: List<String>,
    val license: String?,
  )

  /** What a model is to the recognizer, and the files each kind is made of */
  enum class Kind(val id: String, val streaming: Boolean, val roles: List<String>) {
    TONE_CTC("tone-ctc", true, listOf(MODEL, TOKENS)),
    TRANSDUCER("transducer", true, listOf(ENCODER, DECODER, JOINER, TOKENS)),
    NEMO_CTC("nemo-ctc", false, listOf(MODEL, TOKENS));

    companion object {
      fun of(id: String?): Kind? = entries.firstOrNull { it.id == id }
    }
  }

  /** A model of [kind] made of [files] (role to file name) inside the folder of [archive] */
  data class Model(val kind: Kind, val archive: Archive, val files: Map<String, String>)

  /**
   * A dictation in one language: [streaming] writes the draft while the person speaks and finds where a phrase ends,
   * [final], when there is one, decodes each finished phrase again for the text that stays
   */
  data class Profile(val id: String, val streaming: Model, val final: Model?) {
    val archives: List<Archive> get() = listOfNotNull(streaming.archive, final?.archive).distinctBy { it.id }
    val downloadBytes: Long get() = archives.sumOf { it.sizeBytes }
  }

  /**
   * The profile for the language of the settings
   * Russian is the primary audience, so a blank or automatic language is Russian; any other language without a profile
   * of its own falls back to English, as in VibeIDE
   */
  fun profileFor(language: String): Profile? {
    val code = language.trim().lowercase(Locale.ROOT)
    val id = when {
      code.isEmpty() || code == AUTO -> PRIMARY
      code.startsWith(PRIMARY) -> PRIMARY
      else -> profiles.keys.firstOrNull { code.startsWith(it) } ?: FALLBACK
    }
    return profiles[id]
  }

  companion object {
    /** The file in the shared set */
    const val FILE = "voice/sttModels.jsonc"

    const val MODEL = "model"
    const val TOKENS = "tokens"
    const val ENCODER = "encoder"
    const val DECODER = "decoder"
    const val JOINER = "joiner"

    private const val PRIMARY = "ru"
    private const val FALLBACK = "en"
    private const val AUTO = "auto"
    private const val HTTPS = "https://"
    private val SHA256 = Regex("^[0-9a-f]{64}$")
    private val json = Json { ignoreUnknownKeys = true }

    /** The catalogue of this build, or null when the set has none that reads */
    val bundled: SttCatalog? by lazy {
      VibeDefaults.setFile(FILE)?.let { text -> parse(text) { logger<SttCatalog>().warn(it) } }
    }

    fun parse(text: String, onWarning: (String) -> Unit): SttCatalog? {
      val root = runCatching { json.parseToJsonElement(VibeJsonc.strip(text)) as? JsonObject }
        .getOrElse { onWarning("$FILE: ${it.message}"); null } ?: return null
      val sampleRate = (root["sampleRate"] as? JsonPrimitive)?.intOrNull ?: run { onWarning("$FILE: no sampleRate"); return null }
      val archives = (root["archives"] as? JsonArray).orEmpty().mapNotNull { archiveOf(it as? JsonObject, onWarning) }.distinctBy { it.id }
      val byId = archives.associateBy { it.id }
      val profiles = (root["profiles"] as? JsonObject).orEmpty().mapNotNull { (id, value) ->
        val o = value as? JsonObject ?: return@mapNotNull null
        val streaming = modelOf(o["streaming"] as? JsonObject, byId, "$id.streaming", onWarning)
        if (streaming == null || !streaming.kind.streaming) {
          onWarning("$FILE: profile $id has no streaming model")
          return@mapNotNull null
        }
        val finalObject = o["final"] as? JsonObject
        val final = finalObject?.let { modelOf(it, byId, "$id.final", onWarning) }
        if (finalObject != null && (final == null || final.kind.streaming)) {
          onWarning("$FILE: profile $id has a final model that cannot decode a phrase")
          return@mapNotNull null
        }
        id to Profile(id, streaming, final)
      }.toMap()
      return SttCatalog(sampleRate, archives, profiles)
    }

    private fun archiveOf(o: JsonObject?, onWarning: (String) -> Unit): Archive? {
      if (o == null) return null
      val id = string(o["id"]) ?: run { onWarning("$FILE: an archive without an id"); return null }
      val dir = string(o["dir"])?.takeIf { it.isNotBlank() && '/' !in it && '\\' !in it && it != ".." }
      val url = string(o["url"])?.takeIf { it.startsWith(HTTPS) }
      val sha = string(o["sha256"])?.lowercase(Locale.ROOT)?.takeIf { SHA256.matches(it) }
      val size = (o["sizeBytes"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 }
      val files = (o["files"] as? JsonArray).orEmpty().mapNotNull { string(it) }.filter { it.isNotBlank() && ".." !in it }
      if (dir == null || url == null || sha == null || size == null || files.isEmpty()) {
        onWarning("$FILE: archive $id is incomplete (dir, https url, sha256, sizeBytes and files are required)")
        return null
      }
      return Archive(id, dir, url, sha, size, files, string(o["license"]))
    }

    private fun modelOf(o: JsonObject?, archives: Map<String, Archive>, where: String, onWarning: (String) -> Unit): Model? {
      if (o == null) return null
      val kind = Kind.of(string(o["kind"])) ?: run { onWarning("$FILE: $where has an unknown kind"); return null }
      val archive = archives[string(o["archive"])]
                    ?: run { onWarning("$FILE: $where names an archive that is not in the list"); return null }
      val files = kind.roles.associateWith { string(o[it]).orEmpty() }
      val absent = files.filterValues { it !in archive.files }
      if (absent.isNotEmpty()) {
        val named = absent.entries.joinToString { "${it.key}=${it.value}" }
        onWarning("$FILE: $where names files the archive ${archive.id} does not list: $named")
        return null
      }
      return Model(kind, archive, files)
    }

    private fun string(element: Any?): String? = (element as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
  }
}
