// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp.dap

import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunConfigurationOptions
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.redhat.devtools.lsp4ij.dap.DebugMode
import com.redhat.devtools.lsp4ij.dap.DebugServerWaitStrategy
import com.redhat.devtools.lsp4ij.dap.LaunchConfiguration
import com.redhat.devtools.lsp4ij.dap.configurations.DAPRunConfiguration
import com.redhat.devtools.lsp4ij.dap.descriptors.DebugAdapterDescriptor
import com.redhat.devtools.lsp4ij.dap.descriptors.DebugAdapterDescriptorFactory
import com.redhat.devtools.lsp4ij.dap.descriptors.DefaultDebugAdapterDescriptor

/**
 * Turns "debug this file" into a working run configuration without a single field to fill in.
 *
 * LSP4IJ can already debug through any DAP adapter — but only after a person writes the launch
 * JSON, the command line and the ready pattern by hand, and none of the three is guessable. This
 * factory fills them from [DebugAdapters], so the path from a breakpoint to a stopped process is
 * one action, the way it is in a paid IDE.
 *
 * Everything decidable is decided in [DebugAdapters]; what is left here is the platform wiring,
 * which cannot be unit-tested without a running IDE and therefore must not hold decisions.
 */
abstract class VibeDebugAdapterFactory(private val spec: DebugAdapters.AdapterSpec) : DebugAdapterDescriptorFactory() {

  override fun isDebuggableFile(file: VirtualFile, project: Project): Boolean =
    DebugAdapters.adapterFor(file.name) === spec

  /** Run without debugging is meaningless for an adapter: it exists to stop on breakpoints. */
  override fun canRun(executorId: String): Boolean = DefaultDebugExecutor.EXECUTOR_ID == executorId

  override fun createDebugAdapterDescriptor(
    options: RunConfigurationOptions,
    environment: ExecutionEnvironment,
  ): DebugAdapterDescriptor = DefaultDebugAdapterDescriptor(options, environment, serverDefinition)

  /**
   * Every shape this adapter knows, plus attach — so the drop-down in the run configuration is a
   * choice between real ways of starting the work, not a single «this file» that half the projects
   * cannot use.
   */
  override fun getLaunchConfigurations(): List<LaunchConfiguration> =
    DebugAdapters.shapes(spec).map { shape ->
      LaunchConfiguration(
        "${spec.id}.${shape.name.lowercase()}",
        shapeName(shape),
        DebugAdapters.configurationFor(spec, shape, FILE_VARIABLE, WORKSPACE_VARIABLE),
        DebugMode.LAUNCH,
      )
    } + LaunchConfiguration(
      "${spec.id}.attach",
      "Attach",
      DebugAdapters.attachConfiguration(spec, WORKSPACE_VARIABLE, DebugAdapters.defaultAttachPort(spec)),
      DebugMode.ATTACH,
    )

  /** English on purpose: these names live in LSP4IJ's own configuration UI, next to its wording. */
  private fun shapeName(shape: DebugAdapters.Shape): String = when (shape) {
    DebugAdapters.Shape.FILE -> "Current file"
    DebugAdapters.Shape.NPM_SCRIPT -> "npm run dev"
    DebugAdapters.Shape.PHP_SERVER -> "PHP built-in server"
    DebugAdapters.Shape.LISTEN -> "Listen for Xdebug"
  }

  /**
   * Fills a fresh configuration for the file the person asked to debug.
   *
   * Returns false when the adapter is not installed, and deliberately does not invent a command
   * from the bare name: a configuration that looks ready and dies with "Cannot run program" is
   * worse than one that never appeared, because it blames the project rather than the missing
   * package. The notifier says what to install.
   */
  override fun prepareConfiguration(configuration: RunConfiguration, file: VirtualFile, project: Project): Boolean {
    val command = DebugAdapters.command(spec) ?: return false
    // The configuration's own setters rather than its options object: the latter is protected,
    // and reaching around a deliberate access modifier is how a fork starts paying merge tax.
    val options = configuration as? DAPRunConfiguration ?: return false
    val workspace = project.basePath ?: file.parent?.path ?: return false

    options.command = command
    options.file = file.path
    options.workingDirectory = workspace
    options.serverId = spec.id
    options.serverName = spec.displayName
    options.debugMode = DebugMode.LAUNCH
    // The default shape, not always «this file»: for PHP that would run the page under the CLI
    // interpreter and break on the first session.
    val shape = DebugAdapters.defaultShape(spec)
    options.launchConfigurationId = "${spec.id}.${shape.name.lowercase()}"
    options.launchConfiguration = DebugAdapters.configurationFor(spec, shape, file.path, workspace)
    options.attachConfigurationId = "${spec.id}.attach"
    options.attachConfiguration =
      DebugAdapters.attachConfiguration(spec, workspace, DebugAdapters.defaultAttachPort(spec))

    // A socket adapter is ready when it says so; waiting a fixed timeout instead would either
    // start the session too early on a slow machine or waste seconds on every run.
    if (spec.transport == DebugAdapters.Transport.SOCKET && spec.readyPattern != null) {
      options.debugServerWaitStrategy = DebugServerWaitStrategy.TRACE
      options.debugServerReadyPattern = spec.readyPattern
    }
    return true
  }

  private companion object {
    /** LSP4IJ substitutes these at start; they must reach the JSON unexpanded. */
    const val FILE_VARIABLE = "\${file}"
    const val WORKSPACE_VARIABLE = "\${workspaceFolder}"
  }
}

/** Named subclasses rather than parameters in plugin.xml: the extension point takes a class. */
class JsDebugAdapterFactory : VibeDebugAdapterFactory(DebugAdapters.JS_DEBUG)

class PhpDebugAdapterFactory : VibeDebugAdapterFactory(DebugAdapters.PHP_DEBUG)
