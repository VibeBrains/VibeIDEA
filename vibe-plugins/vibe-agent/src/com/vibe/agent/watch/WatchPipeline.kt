// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.watch

import com.vibe.agent.settings.VibeAgentSettings
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * The `/watch` pipeline: probe → subtitles → media → frames.
 *
 * Everything runs as child processes. A utility process would buy nothing here: an external CLI
 * cannot take the IDE down with it, and the only reason VibeIDE isolated its speech engine was a
 * synchronous native addon, which we do not have.
 *
 * The pipeline reports progress and can be cancelled at every stage — downloading a lecture takes
 * minutes, and a cancel that only takes effect at the end is not a cancel.
 */
class WatchPipeline(
  private val tools: WatchTools.Tools,
  private val workDir: Path,
  private val onProgress: (String) -> Unit,
  private val isCancelled: () -> Boolean,
) {
  data class Result(
    val kind: WatchInput.Kind,
    val title: String,
    val transcript: String,
    val frames: List<Path>,
    /** Said out loud when the answer will be weaker than the user expects. */
    val warning: String?,
  )

  fun run(source: String): kotlin.Result<Result> = runCatching {
    val isUrl = WatchInput.isUrl(source)
    val hint = WatchInput.classify(source)
    onProgress("Проверяю источник…")

    val title: String
    val audioOnly: Boolean
    if (isUrl) {
      val dump = exec(listOf(tools.ytDlp, "--dump-single-json", "--no-warnings", source), PROBE_TIMEOUT_SEC)
      check(dump.exitCode == 0) { "yt-dlp не смог прочитать ссылку: ${dump.stderr.trim().takeLast(300)}" }
      title = titleOf(dump.stdout) ?: source
      audioOnly = WatchProbe.remoteIsAudioOnly(dump.stdout, hint)
    }
    else {
      val file = File(source)
      check(file.isFile) { "файл не найден: $source" }
      // A non-zero exit with no output file is normal for `ffmpeg -i`: the banner IS the answer.
      val probe = exec(listOf(tools.ffmpeg, "-hide_banner", "-i", file.absolutePath), PROBE_TIMEOUT_SEC)
      title = file.name
      audioOnly = WatchProbe.localIsAudioOnly(probe.stderr)
        ?: error("ffmpeg не смог прочитать файл: ${probe.stderr.trim().takeLast(300)}")
    }

    if (isCancelled()) error(CANCELLED)

    onProgress("Ищу субтитры…")
    val transcript = subtitles(source, isUrl)

    // Audio with no transcript has nothing to send: no frames, no words. Saying so is the honest
    // end; sending an empty prompt would look like the model ignored the request.
    if (audioOnly && transcript.isBlank()) {
      error("это аудио без субтитров, а распознавания речи в VibeIDEA пока нет — отправлять модели нечего")
    }

    if (audioOnly) {
      return@runCatching Result(WatchInput.Kind.AUDIO, title, transcript, emptyList(), null)
    }

    if (isCancelled()) error(CANCELLED)
    onProgress("Скачиваю видео…")
    val media = media(source, isUrl)

    if (isCancelled()) error(CANCELLED)
    onProgress("Ищу смены сцен…")
    val frames = frames(media)
    check(frames.isNotEmpty()) { "не удалось извлечь ни одного кадра" }

    Result(
      kind = WatchInput.Kind.VIDEO,
      title = title,
      transcript = transcript,
      frames = frames,
      warning = if (transcript.isBlank()) "субтитров нет — разбор пойдёт только по кадрам" else null,
    )
  }

  // --- stages ---

  private fun subtitles(source: String, isUrl: Boolean): String {
    val languages = VibeAgentSettings.watchSubtitleLanguages
    if (!isUrl) return "" // a local file carries no subtitle track we can ask yt-dlp for
    val result = exec(
      listOf(
        tools.ytDlp, "--skip-download", "--write-subs", "--write-auto-subs",
        "--sub-langs", languages, "--convert-subs", "srt",
        "-o", workDir.resolve("subs.%(ext)s").toString(), source,
      ),
      SUBTITLE_TIMEOUT_SEC,
    )
    if (result.exitCode != 0) return ""
    val file = workDir.toFile().listFiles { f: File -> f.name.startsWith("subs.") && f.name.endsWith(".srt") }
      ?.minByOrNull { it.name.length } ?: return ""
    val cues = Subtitles.parse(runCatching { file.readText() }.getOrDefault(""))
    return Subtitles.transcript(cues)
  }

  private fun media(source: String, isUrl: Boolean): Path {
    if (!isUrl) return Path.of(source)
    // `-o video.%(ext)s` and a glob: yt-dlp picks the container itself when merging streams, so a
    // fixed `video.mp4` path goes stale the moment the streams are webm (VibeIDE's dry run).
    val result = exec(
      listOf(
        tools.ytDlp, "-f", "bv*[height<=?${VibeAgentSettings.watchFrameHeight}]+ba/b",
        "-o", workDir.resolve("video.%(ext)s").toString(), "--no-warnings", source,
      ),
      DOWNLOAD_TIMEOUT_SEC,
    )
    check(result.exitCode == 0) { "не удалось скачать видео: ${result.stderr.trim().takeLast(300)}" }
    val file = workDir.toFile().listFiles { f: File -> f.name.startsWith("video.") }?.maxByOrNull { it.length() }
    return file?.toPath() ?: error("файл видео не найден после скачивания")
  }

  private fun frames(media: Path): List<Path> {
    val first = extract(media, VibeAgentSettings.watchSceneThreshold)
    // A static screencast crosses no threshold at all; one retry at a low one, then give up.
    val frames = if (SceneFrames.needsRetry(first.second)) {
      onProgress("Кадров мало — повторяю с низким порогом…")
      clearFrames()
      extract(media, SceneFrames.RETRY_SCENE_THRESHOLD)
    }
    else first

    val kept = SceneFrames.thin(frames.second, VibeAgentSettings.watchMaxFrames)
    val files = workDir.toFile().listFiles { f: File -> f.name.startsWith("frame") && f.name.endsWith(".jpg") }
      ?.sortedBy { it.name }.orEmpty()
    if (kept.isEmpty() || files.isEmpty()) return files.map { it.toPath() }.take(VibeAgentSettings.watchMaxFrames)
    // showinfo numbers frames from the filter's output, which is exactly the file order.
    val byOrder = files.mapIndexed { index, file -> index to file }.toMap()
    return frames.second.mapIndexedNotNull { index, frame -> if (frame in kept) byOrder[index]?.toPath() else null }
  }

  private fun extract(media: Path, threshold: Double): Pair<Int, List<SceneFrames.Frame>> {
    val result = exec(
      listOf(
        tools.ffmpeg, "-hide_banner", "-i", media.toString(),
        "-vf", SceneFrames.filter(threshold, VibeAgentSettings.watchFrameHeight),
        "-fps_mode", "vfr", "-q:v", "3",
        workDir.resolve("frame%04d.jpg").toString(),
      ),
      FRAMES_TIMEOUT_SEC,
    )
    return result.exitCode to SceneFrames.parseShowinfo(result.stderr)
  }

  private fun clearFrames() {
    workDir.toFile().listFiles { f: File -> f.name.startsWith("frame") }?.forEach { it.delete() }
  }

  // --- process plumbing ---

  private data class Exec(val exitCode: Int, val stdout: String, val stderr: String)

  private fun exec(command: List<String>, timeoutSec: Long): Exec {
    val process = ProcessBuilder(command).directory(workDir.toFile()).start()
    val out = StringBuilder()
    val err = StringBuilder()
    val outThread = Thread { process.inputStream.bufferedReader().forEachLine { out.appendLine(it) } }
    val errThread = Thread { process.errorStream.bufferedReader().forEachLine { err.appendLine(it) } }
    outThread.isDaemon = true; errThread.isDaemon = true
    outThread.start(); errThread.start()
    val finished = process.waitFor(timeoutSec, TimeUnit.SECONDS)
    if (!finished) {
      process.destroyForcibly()
      error("инструмент не ответил за $timeoutSec с: ${command.first()}")
    }
    outThread.join(1000); errThread.join(1000)
    return Exec(process.exitValue(), out.toString(), err.toString())
  }

  private fun titleOf(dumpJson: String): String? =
    Regex("\"title\"\\s*:\\s*\"([^\"]{1,200})\"").find(dumpJson)?.groupValues?.get(1)

  companion object {
    const val CANCELLED = "отменено пользователем"

    private const val PROBE_TIMEOUT_SEC = 60L
    private const val SUBTITLE_TIMEOUT_SEC = 180L
    private const val DOWNLOAD_TIMEOUT_SEC = 900L
    private const val FRAMES_TIMEOUT_SEC = 600L

    /** Temporary working directory; the caller removes it once the frames are attached. */
    fun workDir(): Path = Files.createTempDirectory("vibe-watch")
  }
}
