// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.voice

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.extensions.PluginId
import java.io.File
import java.util.Locale

/**
 * The native half of local speech recognition: the sherpa-onnx JNI library and the ONNX Runtime it links against
 *
 * They ship per platform in `<plugin>/native/<os-arch>/` and are loaded from there by absolute path
 * The library's own loader is switched off first: it would unpack the libraries into a temporary folder on every start,
 * or look in `java.library.path`, and neither is where the plugin keeps them
 * `System.load` binds the library to the class loader of the caller, which is the plugin's, the same one that loads the
 * sherpa-onnx classes, so their native methods find it
 * ONNX Runtime goes first: on Windows the JNI library resolves it by name among the libraries already loaded,
 * on macOS it would also be found beside the JNI library (`LC_RPATH @loader_path`)
 */
object SherpaNative {
  /** Whether this machine can recognise speech locally */
  sealed interface Status {
    data object Ready : Status

    /** No libraries for this platform in the build: a fact about the build, said once in the log and not to the person */
    data class Unsupported(val platform: String) : Status

    /** The libraries are there and would not load: a defect worth naming */
    data class Failed(val reason: String) : Status
  }

  /** Points at a folder of the native libraries instead of the plugin's; for tests and runs from the sources */
  const val DIR_ENV = "VIBE_SHERPA_NATIVE"

  private const val PLUGIN_ID = "com.vibe.agent"
  private const val NATIVE_DIR = "native"
  private const val JNI_NAME = "sherpa-onnx-jni"
  private const val RUNTIME_NAME = "onnxruntime"
  private val log = logger<SherpaNative>()

  @Volatile private var loaded: Status? = null

  /**
   * The folder name of a platform, as sherpa-onnx names its native jars: `osx-aarch64`, `win-x64`, `linux-x64`
   * Null for a system it has no build for
   */
  fun platformOf(osName: String, osArch: String): String? {
    val os = osName.lowercase(Locale.ROOT)
    val arch = osArch.lowercase(Locale.ROOT)
    val family = when {
      "mac" in os || "darwin" in os -> "osx"
      "win" in os -> "win"
      "linux" in os -> "linux"
      else -> return null
    }
    val bits = when (arch) {
      "aarch64", "arm64" -> if (family == "win") "arm64" else "aarch64"
      "x86_64", "amd64", "x64" -> "x64"
      else -> return null
    }
    return "$family-$bits"
  }

  /**
   * The two files to load from [names], the listing of a platform folder, in the order they are loaded
   * The runtime's name carries its version (`libonnxruntime.1.27.0.dylib`), so it is found by its prefix and not spelled here
   * Among several the shortest name wins: `onnxruntime.dll` and not a provider library beside it
   * Null when either is missing
   */
  fun libraries(names: List<String>, mapLibraryName: (String) -> String = System::mapLibraryName): List<String>? {
    val jni = mapLibraryName(JNI_NAME).takeIf { it in names } ?: return null
    val runtimePrefix = mapLibraryName(RUNTIME_NAME).substringBefore('.')
    val extension = "." + jni.substringAfterLast('.')
    val runtime = names.filter { it.startsWith(runtimePrefix) && it.endsWith(extension) }.minByOrNull { it.length } ?: return null
    return listOf(runtime, jni)
  }

  /** The folder the libraries of this platform are looked for in, or null when the plugin has none */
  fun directory(platform: String): File? {
    System.getenv(DIR_ENV)?.takeIf { it.isNotBlank() }?.let { return File(it) }
    val plugin = runCatching { PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))?.pluginPath }.getOrNull() ?: return null
    return plugin.resolve(NATIVE_DIR).resolve(platform).toFile()
  }

  /** Loads the libraries once per IDE run; later calls answer what the first one found */
  fun status(): Status = loaded ?: synchronized(this) { loaded ?: load().also { loaded = it } }

  private fun load(): Status {
    val platform = platformOf(System.getProperty("os.name").orEmpty(), System.getProperty("os.arch").orEmpty())
      ?: return Status.Unsupported(System.getProperty("os.name") + " " + System.getProperty("os.arch"))
        .also { log.info("local speech: $it") }
    val dir = directory(platform)
    val files = dir?.list()?.toList()?.let { libraries(it) }
    if (dir == null || files == null) return Status.Unsupported(platform).also { log.info("local speech: no native libraries in $dir") }
    return try {
      com.k2fsa.sherpa.onnx.LibraryLoader.setAutoLoadEnabled(false)
      files.forEach { System.load(File(dir, it).absolutePath) }
      log.info("local speech: sherpa-onnx loaded from $dir")
      Status.Ready
    }
    catch (e: Throwable) {
      log.warn("local speech: sherpa-onnx did not load from $dir", e)
      Status.Failed(reasonOf(e))
    }
  }
}
