// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.deploy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeployPlanTest {
  @Test
  fun `the project kind is read from the files that identify it`() {
    assertEquals(DeployPlan.Kind.NODE, DeployPlan.detect(setOf("package.json")))
    assertEquals(DeployPlan.Kind.PYTHON, DeployPlan.detect(setOf("pyproject.toml")))
    assertEquals(DeployPlan.Kind.GO, DeployPlan.detect(setOf("go.mod")))
    assertEquals(DeployPlan.Kind.JVM, DeployPlan.detect(setOf("build.gradle.kts")))
    assertEquals(DeployPlan.Kind.STATIC, DeployPlan.detect(setOf("index.html")))
    assertEquals(DeployPlan.Kind.UNKNOWN, DeployPlan.detect(setOf("readme.md")))
  }

  @Test
  fun `an explicit Dockerfile wins over guessing`() {
    // Dockerfile — это уже ответ автора на вопрос «как это собирать».
    assertEquals(DeployPlan.Kind.DOCKER, DeployPlan.detect(setOf("package.json", "Dockerfile")))
  }

  @Test
  fun `a project that already has a Dockerfile is not offered another one`() {
    val plan = DeployPlan.plan(DeployPlan.Kind.DOCKER, setOf("Dockerfile", ".dockerignore"))
    assertFalse(plan.steps.any { it.id == DeployPlan.STEP_DOCKERFILE })
  }

  @Test
  fun `local and reversible steps come before anything external`() {
    // Внешняя часть обязана начинаться из состояния, которое уже собирается.
    val plan = DeployPlan.plan(DeployPlan.Kind.NODE, setOf("package.json"))
    val firstExternal = plan.steps.indexOfFirst { it.external }
    val lastLocalBefore = plan.steps.take(firstExternal).all { !it.external }
    assertTrue(lastLocalBefore)
    assertEquals(DeployPlan.STEP_REGISTRY, plan.steps[firstExternal].id)
  }

  @Test
  fun `every step that reaches outside is marked as such`() {
    val external = DeployPlan.externalSteps(DeployPlan.plan(DeployPlan.Kind.NODE, setOf("package.json")))
    assertEquals(listOf(DeployPlan.STEP_REGISTRY, DeployPlan.STEP_HOST, DeployPlan.STEP_DOMAIN, DeployPlan.STEP_TLS),
                 external.map { it.id })
  }

  @Test
  fun `a secret file in the repository is a warning, because an image travels further`() {
    val plan = DeployPlan.plan(DeployPlan.Kind.NODE, setOf("package.json", ".env"))
    assertTrue(plan.warnings.contains(DeployPlan.WARN_ENV_IN_REPO))
  }

  @Test
  fun `a missing dockerignore is a warning for anything that gets an image`() {
    assertTrue(DeployPlan.plan(DeployPlan.Kind.NODE, setOf("package.json")).warnings.contains(DeployPlan.WARN_NO_DOCKERIGNORE))
    assertFalse(DeployPlan.plan(DeployPlan.Kind.STATIC, setOf("index.html")).warnings.contains(DeployPlan.WARN_NO_DOCKERIGNORE))
  }

  @Test
  fun `an unknown project says so instead of pretending it has a plan`() {
    assertTrue(DeployPlan.plan(DeployPlan.Kind.UNKNOWN, setOf("readme.md")).warnings.contains(DeployPlan.WARN_UNKNOWN_KIND))
  }
}
