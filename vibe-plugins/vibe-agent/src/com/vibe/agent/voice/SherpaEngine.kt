// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.voice

import com.intellij.openapi.diagnostic.logger
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineToneCtcModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Speech recognition inside the IDE, with no program on the machine: sherpa-onnx and the models of the catalogue
 *
 * A streaming model writes the draft while the person speaks and finds where a phrase ends on its own
 * Each finished phrase is decoded again by the final model, when the profile has one, and its text is the one that stays
 * The models stay loaded between dictations and are let go after [keepAliveMs] of rest: the Russian pair loads in about
 * 1.3 s and holds about a gigabyte of memory (measured 15.10.2026 on arm64), so neither every dictation nor every hour of
 * the day should pay for it
 */
class SherpaEngine(
  private val profile: SttCatalog.Profile,
  private val store: SttModelStore,
  private val keepAliveMs: () -> Long,
) : VoiceEngine {
  override val name = "sherpa-onnx"
  override val model: String get() = profile.archives.joinToString(" + ") { it.id }
  override val live = VoiceEngine.LivePreview.Frames
  override val waitHint: String? = null

  override fun preview(wav: ByteArray): String = error("frames, not snapshots")

  override fun listen(onText: (VoiceEngine.LiveText) -> Unit, onProblem: (VoiceProblem) -> Unit): VoiceEngine.Listening =
    open(onText, onProblem)

  private fun open(onText: (VoiceEngine.LiveText) -> Unit, onProblem: (VoiceProblem) -> Unit) =
    SherpaListening(SherpaResident.Key(profile, store, SherpaResident.threads()), keepAliveMs, onText, onProblem)

  /** A finished recording goes through the same phrases as a live one, only faster than the person spoke it */
  override fun transcribe(audio: File, cancelled: () -> Boolean, note: (VoiceProblem) -> Unit): VoiceEngine.Outcome {
    val samples = VoiceAudio.samplesOfWav(audio.readBytes())
      ?: return VoiceEngine.Outcome.Failed(VoiceProblem.LocalEngineFailed("no audio data in ${audio.name}"))
    val listening = open({}, {})
    var offset = 0
    while (offset < samples.size) {
      if (cancelled()) {
        listening.cancel()
        return VoiceEngine.Outcome.Cancelled
      }
      val end = minOf(samples.size, offset + FILE_CHUNK_SAMPLES)
      listening.feedSamples(samples.copyOfRange(offset, end))
      offset = end
    }
    return listening.finish()
  }

  companion object {
    /** Samples per step when a whole file is heard: a few seconds, so a cancel is seen soon */
    private const val FILE_CHUNK_SAMPLES = 32_000
  }
}

/** What a phrase is made of while it is spoken; the seam between the phrase logic and the native recognizer */
internal interface StreamingDecoder {
  fun accept(samples: FloatArray)

  /** The text of the phrase so far */
  fun text(): String

  /** The model heard the phrase end */
  fun endpoint(): Boolean

  /** Starts the next phrase */
  fun reset()
}

/** Decodes one finished phrase again; the text that stays */
internal fun interface PhraseDecoder {
  fun decode(samples: FloatArray): String
}

/**
 * The phrases of one recording: drafts while they are spoken, the final text when each ends
 *
 * Where a phrase ends is decided here as well as by the model: T-one in sherpa-onnx 1.13.4 never reports an endpoint,
 * not after two and a half seconds of silence and not after five (measured 15.10.2026 through JNI, timestamps intact)
 * So a phrase also ends when the text of the streaming model has not changed for [ENDPOINT_SILENCE_SECONDS] of audio
 * after something was heard, and a stretch of [IDLE_SILENCE_SECONDS] with nothing heard is let go
 * Pure apart from the decoders it is given, so the order of drafts and phrases is tested without a model
 * Not thread-safe: one thread feeds it, the one of [SherpaResident]
 */
internal class PhraseSplitter(
  private val streaming: StreamingDecoder,
  private val final: PhraseDecoder?,
  private val sampleRate: Int,
  private val onText: (VoiceEngine.LiveText) -> Unit,
) {
  private val segment = ArrayList<FloatArray>()
  private var segmentSamples = 0
  private var draft = ""
  private val said = ArrayList<String>()

  /** Samples heard since the phrase began, the silence of the stop included */
  private var heard = 0

  /** [heard] when the text of the streaming model last changed, and that text as it came */
  private var changedAt = 0
  private var raw = ""

  /** Every phrase made final so far */
  val phrases: List<String> get() = said

  fun accept(samples: FloatArray, keep: Boolean = true) {
    if (keep) {
      segment += samples
      segmentSamples += samples.size
    }
    heard += samples.size
    streaming.accept(samples)
    val now = streaming.text()
    if (now != raw) {
      raw = now
      changedAt = heard
    }
    val text = now.trim()
    if (text.isNotEmpty() && text != draft) {
      draft = text
      onText(VoiceEngine.LiveText.Draft(text))
    }
    if (ended()) endPhrase()
  }

  private fun ended(): Boolean {
    val quiet = heard - changedAt
    return streaming.endpoint() ||
           raw.isNotBlank() && quiet >= ENDPOINT_SILENCE_SECONDS * sampleRate ||
           raw.isBlank() && heard >= IDLE_SILENCE_SECONDS * sampleRate ||
           segmentSamples >= MAX_PHRASE_SECONDS * sampleRate
  }

  /** The recording is over: a short silence lets the streaming model settle, and the last phrase is made final */
  fun flush(): List<String> {
    accept(FloatArray((TAIL_SILENCE_SECONDS * sampleRate).toInt()), keep = false)
    endPhrase()
    return phrases
  }

  private fun endPhrase() {
    val decoded = if (final != null && segmentSamples > 0) final.decode(joined()).trim() else ""
    // The final model can come back empty on a phrase the streaming one heard; the draft is then better than nothing
    val text = decoded.ifEmpty { draft }
    if (text.isNotEmpty()) {
      said += text
      onText(VoiceEngine.LiveText.Phrase(text))
    }
    streaming.reset()
    segment.clear()
    segmentSamples = 0
    draft = ""
    heard = 0
    changedAt = 0
    raw = ""
  }

  private fun joined(): FloatArray {
    val out = FloatArray(segmentSamples)
    var at = 0
    for (part in segment) {
      part.copyInto(out, at)
      at += part.size
    }
    return out
  }

  companion object {
    /** A phrase is made final at this length even without a pause, so the buffer of a monologue cannot grow without end */
    const val MAX_PHRASE_SECONDS = 28

    /** Silence after the stop, enough for the streaming model to see the end of the last word */
    const val TAIL_SILENCE_SECONDS = 0.6

    /** The pause after speech at which a phrase counts as said; VibeIDE's default */
    const val ENDPOINT_SILENCE_SECONDS = 0.8

    /** A stretch with nothing heard is let go, so the buffer of a microphone left open holds no minutes of silence */
    const val IDLE_SILENCE_SECONDS = 5.0
  }
}

/**
 * The loaded models and the one thread every native call runs on
 *
 * One thread, because a recognizer is not to be used from two at once, and a dictation and a Telegram note may meet
 * The models are loaded on first use and let go after the keep-alive of the last one to finish, never while one listens
 */
internal object SherpaResident {
  /** What a set of loaded models is for; a change of any part loads them again */
  data class Key(val profile: SttCatalog.Profile, val store: SttModelStore, val threads: Int)

  class Loaded(val key: Key, val online: OnlineRecognizer, val offline: OfflineRecognizer?) {
    fun release() {
      online.release()
      offline?.release()
    }
  }

  private const val MAX_THREADS = 8
  private const val FEATURE_DIM = 80
  private const val PROVIDER = "cpu"
  private val log = logger<SherpaResident>()

  private val executor = ScheduledThreadPoolExecutor(1) { task -> Thread(task, "vibe-stt").apply { isDaemon = true } }

  /** Touched only on [executor] */
  private var loaded: Loaded? = null
  private var listeners = 0
  private var unload: ScheduledFuture<*>? = null

  /** Half the cores and no more than eight, as in VibeIDE: recognition shares the machine with the IDE */
  fun threads(cores: Int = Runtime.getRuntime().availableProcessors()): Int = (cores / 2).coerceIn(1, MAX_THREADS)

  fun <T> submit(task: () -> T): Future<T> = executor.submit(Callable(task))

  /** The models for [key], loaded now when they are not; on [executor] only */
  fun acquire(key: Key): Loaded {
    unload?.cancel(false)
    unload = null
    val current = loaded
    val models = if (current != null && current.key == key) current else {
      current?.release()
      loaded = null
      load(key).also { loaded = it }
    }
    listeners++
    return models
  }

  /** One listener less; the models go after [keepAliveMs] if nobody comes; on [executor] only */
  fun release(keepAliveMs: Long) {
    listeners = (listeners - 1).coerceAtLeast(0)
    if (listeners > 0) return
    unload?.cancel(false)
    unload = executor.schedule({
      if (listeners == 0) {
        loaded?.release()
        loaded = null
        log.info("local speech: models unloaded after rest")
      }
    }, keepAliveMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
  }

  private fun load(key: Key): Loaded {
    val started = System.nanoTime()
    val online = OnlineRecognizer(onlineConfig(key))
    val offline = try {
      key.profile.final?.let { OfflineRecognizer(offlineConfig(it, key)) }
    }
    catch (e: Throwable) {
      online.release()
      throw e
    }
    log.info("local speech: ${key.profile.id} loaded in ${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)} ms")
    return Loaded(key, online, offline)
  }

  private fun onlineConfig(key: Key): OnlineRecognizerConfig {
    val model = key.profile.streaming
    val builder = OnlineModelConfig.builder()
      .setTokens(key.store.pathOf(model, SttCatalog.TOKENS))
      .setNumThreads(key.threads)
      .setProvider(PROVIDER)
      .setDebug(false)
    when (model.kind) {
      SttCatalog.Kind.TONE_CTC ->
        builder.setToneCtc(OnlineToneCtcModelConfig.builder().setModel(key.store.pathOf(model, SttCatalog.MODEL)).build())
      SttCatalog.Kind.TRANSDUCER -> builder.setTransducer(
        OnlineTransducerModelConfig.builder()
          .setEncoder(key.store.pathOf(model, SttCatalog.ENCODER))
          .setDecoder(key.store.pathOf(model, SttCatalog.DECODER))
          .setJoiner(key.store.pathOf(model, SttCatalog.JOINER))
          .build(),
      )
      SttCatalog.Kind.NEMO_CTC -> error("${model.kind.id} is not a streaming model")
    }
    // The model's own endpoints, for the models that report them; the phrase logic has the same rules for those that do not
    val endpoints = EndpointConfig.builder()
      .setRule1(
        EndpointRule.builder().setMustContainNonSilence(false)
          .setMinTrailingSilence(PhraseSplitter.IDLE_SILENCE_SECONDS.toFloat()).setMinUtteranceLength(0f).build(),
      )
      .setRule2(
        EndpointRule.builder().setMustContainNonSilence(true)
          .setMinTrailingSilence(PhraseSplitter.ENDPOINT_SILENCE_SECONDS.toFloat()).setMinUtteranceLength(0f).build(),
      )
      .setRule3(
        EndpointRule.builder().setMustContainNonSilence(false).setMinTrailingSilence(0f)
          .setMinUtteranceLength(PhraseSplitter.MAX_PHRASE_SECONDS.toFloat()).build(),
      )
      .build()
    return OnlineRecognizerConfig.builder()
      .setOnlineModelConfig(builder.build())
      .setEndpointConfig(endpoints)
      .setEnableEndpoint(true)
      .build()
  }

  private fun offlineConfig(model: SttCatalog.Model, key: Key): OfflineRecognizerConfig {
    check(model.kind == SttCatalog.Kind.NEMO_CTC) { "${model.kind.id} cannot decode a phrase" }
    return OfflineRecognizerConfig.builder()
      .setFeatureConfig(FeatureConfig.builder().setSampleRate(SAMPLE_RATE).setFeatureDim(FEATURE_DIM).build())
      .setOfflineModelConfig(
        OfflineModelConfig.builder()
          .setNemo(OfflineNemoEncDecCtcModelConfig.builder().setModel(key.store.pathOf(model, SttCatalog.MODEL)).build())
          .setTokens(key.store.pathOf(model, SttCatalog.TOKENS))
          .setNumThreads(key.threads)
          .setProvider(PROVIDER)
          .setDebug(false)
          .build(),
      )
      .build()
  }
}

/** One recording heard by the resident models; every native call is a task on the thread of [SherpaResident] */
private class SherpaListening(
  key: SherpaResident.Key,
  private val keepAliveMs: () -> Long,
  private val onText: (VoiceEngine.LiveText) -> Unit,
  private val onProblem: (VoiceProblem) -> Unit,
) : VoiceEngine.Listening {
  /** No more frames are taken: the recording finished or was dropped */
  private val closed = AtomicBoolean(false)

  /** A failure was said: nothing more is, so a broken engine does not go on printing drafts */
  private val failed = AtomicBoolean(false)

  /** Touched only on the thread of [SherpaResident] */
  private var stream: OnlineStream? = null
  private var splitter: PhraseSplitter? = null
  private var failure: VoiceProblem? = null

  init {
    SherpaResident.submit {
      try {
        val models = SherpaResident.acquire(key)
        try {
          val opened = models.online.createStream()
          stream = opened
          splitter = PhraseSplitter(OnlineDecoder(models.online, opened), models.offline?.let { OfflineDecoder(it) }, SAMPLE_RATE, ::say)
        }
        catch (e: Throwable) {
          SherpaResident.release(keepAliveMs())
          throw e
        }
      }
      catch (e: Throwable) {
        fail(e)
      }
    }
  }

  override fun feed(pcm: ByteArray, length: Int) = feedSamples(VoiceAudio.floatsOf(pcm, length))

  fun feedSamples(samples: FloatArray) {
    if (closed.get()) return
    SherpaResident.submit {
      val current = splitter ?: return@submit
      if (failure != null) return@submit
      try {
        current.accept(samples)
      }
      catch (e: Throwable) {
        fail(e)
      }
    }
  }

  override fun finish(): VoiceEngine.Outcome {
    closed.set(true)
    val result = SherpaResident.submit {
      try {
        failure?.let { return@submit VoiceEngine.Outcome.Failed(it) }
        val current = splitter ?: return@submit VoiceEngine.Outcome.Failed(VoiceProblem.LocalEngineFailed("no stream"))
        VoiceEngine.Outcome.Text(current.flush().joinToString(" "))
      }
      catch (e: Throwable) {
        VoiceEngine.Outcome.Failed(VoiceProblem.LocalEngineFailed(reasonOf(e)))
      }
      finally {
        close()
      }
    }
    return try {
      result.get(FINISH_TIMEOUT_SEC, TimeUnit.SECONDS)
    }
    catch (_: TimeoutException) {
      VoiceEngine.Outcome.Failed(VoiceProblem.LocalEngineFailed("no answer in $FINISH_TIMEOUT_SEC s"))
    }
    catch (e: ExecutionException) {
      VoiceEngine.Outcome.Failed(VoiceProblem.LocalEngineFailed(reasonOf(e.cause ?: e)))
    }
  }

  override fun cancel() {
    closed.set(true)
    SherpaResident.submit { close() }
  }

  /** On the thread of [SherpaResident]: the stream is freed and the models may rest */
  private fun close() {
    val opened = stream ?: return
    stream = null
    splitter = null
    opened.release()
    SherpaResident.release(keepAliveMs())
  }

  private fun say(text: VoiceEngine.LiveText) {
    if (!failed.get()) onText(text)
  }

  private fun fail(e: Throwable) {
    val problem = VoiceProblem.LocalEngineFailed(reasonOf(e))
    failure = problem
    if (failed.compareAndSet(false, true)) onProblem(problem)
  }

  companion object {
    /** The last phrase of a long recording is decoded in about a second; past this the engine is stuck, not slow */
    private const val FINISH_TIMEOUT_SEC = 60L
  }
}

/** The rate the microphone records at, which is the one the catalogue's models are fed at */
private val SAMPLE_RATE = VoiceCapture.SAMPLE_RATE.toInt()

private class OnlineDecoder(private val recognizer: OnlineRecognizer, private val stream: OnlineStream) : StreamingDecoder {
  override fun accept(samples: FloatArray) {
    stream.acceptWaveform(samples, SAMPLE_RATE)
    while (recognizer.isReady(stream)) recognizer.decode(stream)
  }

  override fun text(): String = recognizer.getResult(stream).text

  override fun endpoint(): Boolean = recognizer.isEndpoint(stream)

  override fun reset() = recognizer.reset(stream)
}

private class OfflineDecoder(private val recognizer: OfflineRecognizer) : PhraseDecoder {
  override fun decode(samples: FloatArray): String {
    val stream = recognizer.createStream()
    try {
      stream.acceptWaveform(samples, SAMPLE_RATE)
      recognizer.decode(stream)
      return recognizer.getResult(stream).text
    }
    finally {
      stream.release()
    }
  }
}
