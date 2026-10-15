// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.voice

import com.vibe.agent.i18n.VibeI18n.t
import com.vibe.agent.journal.JournalLevel
import com.vibe.agent.journal.SettingsPage

/**
 * What the person is told about a dictation, and how loudly
 *
 * [text] is the journal line, without a source label: the journal has its category
 * [feedText] is the same under the label of the feed, where there is no category to say where it came from
 */
data class VoiceNotice(
  val text: String,
  val level: JournalLevel,
  val page: SettingsPage? = null,
  val actions: List<VoiceAction> = emptyList(),
) {
  val feedText: String get() = t("chat.voice.feedLine", "text" to text)
}

/**
 * What the person can do about a problem, offered as a button under its line in the feed
 * A problem that tells them to do something and gives no way to do it is half an answer
 */
enum class VoiceAction {
  /** The page of the system where the microphone is allowed */
  MICROPHONE_SETTINGS,

  /** The chat settings, where the model of whisper.cpp is set */
  CHOOSE_MODEL,

  /** Another recording: the one that failed is gone */
  RECORD_AGAIN;

  val label: String
    get() = when (this) {
      MICROPHONE_SETTINGS -> t("chat.voice.action.microphone")
      CHOOSE_MODEL -> t("chat.voice.action.model")
      RECORD_AGAIN -> t("chat.voice.action.again")
    }
}

/**
 * Every way a dictation ends other than with text in the draft, each with words of its own
 *
 * Before this, most of them ended in a journal line nobody was looking at, or in no line at all:
 * the person pressed the microphone and the draft stayed empty
 * Pure on purpose: the choice of the message is the part worth testing, and it needs no microphone
 */
sealed interface VoiceProblem {
  data class NoMicrophone(val reason: String) : VoiceProblem
  data object Silence : VoiceProblem
  data object TooShort : VoiceProblem
  data class RecordingBroke(val reason: String) : VoiceProblem
  data class SaveFailed(val reason: String) : VoiceProblem
  data class LimitReached(val minutes: Long) : VoiceProblem
  data object NoModel : VoiceProblem

  /** The path typed in the settings has no file behind it */
  data class ModelMissing(val path: String) : VoiceProblem
  data object NoTranscriber : VoiceProblem

  /**
   * [afterStop] tells the final transcript from the live one
   * The first falls back to another transcriber, the second only loses the preview
   */
  data class ServerFailed(val reason: String, val afterStop: Boolean) : VoiceProblem
  data class TranscriberFailed(val exitCode: Int, val tail: String) : VoiceProblem
  data class TranscriberNotStarted(val reason: String) : VoiceProblem
  data class TimedOut(val seconds: Long) : VoiceProblem

  /** The transcript is blank */
  data object Empty : VoiceProblem

  /** The transcript is not blank but is noise or too short to be a task; [text] is what was heard */
  data class Discarded(val text: String) : VoiceProblem
  data object Cancelled : VoiceProblem

  /** The person dropped the recording itself, before there was anything to transcribe */
  data object RecordingCancelled : VoiceProblem

  fun notice(): VoiceNotice = when (this) {
    is NoMicrophone -> VoiceNotice(t("chat.voice.noMicrophone", "reason" to reason), JournalLevel.WARNING, actions = MICROPHONE_FIXES)
    Silence -> VoiceNotice(t("chat.voice.silent"), JournalLevel.WARNING, actions = MICROPHONE_FIXES)
    TooShort -> VoiceNotice(t("chat.voice.tooShort"), JournalLevel.INFO)
    is RecordingBroke -> VoiceNotice(t("chat.voice.recordingBroke", "reason" to reason), JournalLevel.WARNING, actions = AGAIN)
    is SaveFailed -> VoiceNotice(t("chat.voice.saveFailed", "reason" to reason), JournalLevel.WARNING, actions = AGAIN)
    is LimitReached -> VoiceNotice(t("chat.voice.limitReached", "minutes" to minutes), JournalLevel.INFO)
    NoModel -> VoiceNotice(t("chat.voice.noModel"), JournalLevel.WARNING, SettingsPage.CHAT, MODEL_FIX)
    is ModelMissing -> VoiceNotice(t("chat.voice.modelMissing", "path" to path), JournalLevel.WARNING, SettingsPage.CHAT, MODEL_FIX)
    NoTranscriber -> VoiceNotice(t("chat.voice.noTranscriber"), JournalLevel.WARNING, SettingsPage.CHAT)
    is ServerFailed -> VoiceNotice(
      if (afterStop) t("chat.voice.serverFailedFinal", "reason" to reason) else t("chat.voice.serverFailed", "reason" to reason),
      JournalLevel.WARNING,
    )
    is TranscriberFailed ->
      VoiceNotice(t("chat.voice.transcriberFailed", "code" to exitCode, "tail" to tail), JournalLevel.WARNING, actions = AGAIN)
    is TranscriberNotStarted ->
      VoiceNotice(t("chat.voice.transcriberNotStarted", "reason" to reason), JournalLevel.WARNING, actions = AGAIN)
    is TimedOut -> VoiceNotice(t("chat.voice.timedOut", "seconds" to seconds), JournalLevel.WARNING, SettingsPage.CHAT, MODEL_FIX + AGAIN)
    Empty -> VoiceNotice(t("chat.voice.empty"), JournalLevel.INFO)
    is Discarded -> VoiceNotice(t("chat.voice.discarded", "text" to text, "min" to VoiceTranscription.MIN_TASK_CHARS), JournalLevel.INFO)
    Cancelled -> VoiceNotice(t("chat.voice.cancelled"), JournalLevel.INFO)
    RecordingCancelled -> VoiceNotice(t("chat.voice.recordingCancelled"), JournalLevel.INFO)
  }

  companion object {
    private const val MS_PER_MINUTE = 60_000L
    private val MICROPHONE_FIXES = listOf(VoiceAction.MICROPHONE_SETTINGS, VoiceAction.RECORD_AGAIN)
    private val MODEL_FIX = listOf(VoiceAction.CHOOSE_MODEL)
    private val AGAIN = listOf(VoiceAction.RECORD_AGAIN)

    /** The recording hit its cap, which the person is told in minutes */
    fun limitReached(): LimitReached = LimitReached(VoiceCapture.MAX_MS / MS_PER_MINUTE)

    /** Null when the recording is good audio and goes on to the transcriber */
    fun ofCapture(captured: VoiceCapture.Captured): VoiceProblem? = when (captured) {
      VoiceCapture.Captured.TooShort -> TooShort
      VoiceCapture.Captured.Silence -> Silence
      is VoiceCapture.Captured.Failed -> SaveFailed(captured.reason)
      is VoiceCapture.Captured.Audio -> null
    }

    /** Null when the run gave a transcript, or was cancelled by the person, who has been told already */
    fun ofRun(once: VoiceTranscription.Once): VoiceProblem? = when (once) {
      is VoiceTranscription.Once.Text -> null
      is VoiceTranscription.Once.Failed -> TranscriberFailed(once.exitCode, once.tail)
      is VoiceTranscription.Once.NotStarted -> TranscriberNotStarted(once.reason)
      is VoiceTranscription.Once.TimedOut -> TimedOut(once.seconds)
      VoiceTranscription.Once.Cancelled -> null
    }

    /** Null when [transcript] is a task; otherwise why it was not put into the draft */
    fun ofTranscript(transcript: String): VoiceProblem? =
      if (VoiceTranscription.taskFrom(transcript) != null) null
      else if (transcript.isBlank()) Empty
      else Discarded(transcript.trim())
  }
}

/** The words for an exception: its message, or its class when it has none (a refused connection arrives with a null message) */
internal fun reasonOf(e: Throwable): String = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
