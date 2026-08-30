// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.schemas

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.jetbrains.jsonSchema.extension.JsonSchemaFileProvider
import com.jetbrains.jsonSchema.extension.JsonSchemaProviderFactory
import com.jetbrains.jsonSchema.extension.SchemaType

/**
 * Schemas for the project's own JSON configs under `.vibe`, so they are edited with completion
 * instead of by memory.
 *
 * Every one of these files is a contract the product enforces at run time: an unknown role, a typo
 * in an event name or a misspelled `readyCheck` produces a warning in the chat hours later, if at
 * all. In the editor the same mistake is a red squiggle before the file is saved, and the list of
 * valid values is one Ctrl+Space away.
 *
 * Matching is by PATH rather than by file name: someone's unrelated `hooks.json` elsewhere in the
 * repository is not ours, and imposing our schema on it would be exactly the kind of helpfulness
 * that makes people disable a plugin.
 */
class VibeSchemaProviderFactory : JsonSchemaProviderFactory {
  override fun getProviders(project: Project): List<JsonSchemaFileProvider> = FILES.map { (relative, schema) ->
    object : JsonSchemaFileProvider {
      override fun isAvailable(file: VirtualFile): Boolean {
        val base = project.basePath ?: return false
        val path = file.path.replace('\\', '/')
        return path == "$base/$relative" || path.endsWith("/$relative")
      }

      override fun getName(): String = relative

      override fun getSchemaFile(): VirtualFile? =
        JsonSchemaProviderFactory.getResourceFile(VibeSchemaProviderFactory::class.java, "/schemas/$schema.json")

      override fun getSchemaType(): SchemaType = SchemaType.embeddedSchema
    }
  }

  private companion object {
    /** `.vibe/<file>` → schema name. Kept here so adding a config is one line in one place. */
    val FILES = listOf(
      ".vibe/commands.json" to "commands",
      ".vibe/pipelines.json" to "pipelines",
      ".vibe/hooks.json" to "hooks",
      ".vibe/servers.json" to "servers",
      ".vibe/providers.json" to "providers",
    )
  }
}
