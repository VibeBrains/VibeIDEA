// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.pipelines

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `roles`: the model a role runs on when its step names none — through the real loader. */
class PipelineRolesTest {
  private fun load(json: String): Pair<List<Pipeline>, List<String>> {
    val base = Files.createTempDirectory("roles")
    try {
      val file = PipelinesFile.path(base.toString())
      Files.createDirectories(file.parent)
      Files.writeString(file, json)
      val warnings = ArrayList<String>()
      return PipelinesFile.load(base.toString()) { warnings.add(it) } to warnings
    }
    finally {
      base.toFile().deleteRecursively()
    }
  }

  @Test
  fun `a step without a model takes its role's model`() {
    val (pipelines, warnings) = load("""{"pipelines":[{"id":"p","roles":{"code-reviewer":{"model":"anthropic/claude-opus-5"}},
      "steps":[{"role":"code-reviewer","task":"найди дефекты"}]}]}""")
    assertTrue(warnings.isEmpty(), warnings.toString())
    val step = pipelines.single().steps.single()
    assertEquals("anthropic", step.provider)
    assertEquals("claude-opus-5", step.model)
  }

  @Test
  fun `the step's own model wins over the role's`() {
    val (pipelines, _) = load("""{"pipelines":[{"id":"p","roles":{"critic":{"model":"anthropic/claude-opus-5"}},
      "steps":[{"role":"critic","task":"оцени","model":"zai/glm-5.3-flash"}]}]}""")
    assertEquals("glm-5.3-flash", pipelines.single().steps.single().model)
  }

  @Test
  fun `roles not used by a step change nothing for other roles`() {
    val (pipelines, _) = load("""{"pipelines":[{"id":"p","roles":{"explore":{"model":"zai/glm-5.3-flash"}},
      "steps":[{"role":"backend-dev","task":"сделай"}]}]}""")
    assertNull(pipelines.single().steps.single().model)
  }

  @Test
  fun `a model for a writing role refuses the pipeline`() {
    val (pipelines, warnings) = load("""{"pipelines":[{"id":"p","roles":{"backend-dev":{"model":"zai/glm-5.3-flash"}},
      "steps":[{"role":"backend-dev","task":"сделай"}]}]}""")
    assertTrue(pipelines.isEmpty())
    assertEquals(1, warnings.size)
  }

  @Test
  fun `an unknown role in roles refuses the pipeline`() {
    val (pipelines, warnings) = load("""{"pipelines":[{"id":"p","roles":{"reviewer":{"model":"zai/glm-5.3-flash"}},
      "steps":[{"role":"explore","task":"изучи"}]}]}""")
    assertTrue(pipelines.isEmpty())
    assertEquals(1, warnings.size)
  }
}
