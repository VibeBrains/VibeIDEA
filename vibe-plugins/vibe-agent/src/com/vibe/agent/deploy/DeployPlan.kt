// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.deploy

/**
 * Getting a project out of the laptop: what kind of thing it is, and what has to happen to it.
 *
 * The plan is generated rather than asked for, because the answer is boring and the same every
 * time: a Node app needs a build, an image, a registry, a host, a domain and a certificate. What is
 * NOT boring is the order, and which steps reach outside — those cost money, create resources with
 * someone's name on them, and cannot be undone by Ctrl+Z.
 *
 * So every step carries [Step.external], and nothing external happens without a person saying yes
 * to that specific step. A deploy that "just did it" is the story people tell about the tool they
 * stopped using.
 */
object DeployPlan {
  enum class Kind { NODE, PYTHON, GO, JVM, STATIC, DOCKER, UNKNOWN }

  data class Step(val id: String, val external: Boolean)

  data class Plan(val kind: Kind, val steps: List<Step>, val warnings: List<String>)

  /** Files that identify the project. Order matters: a Dockerfile wins — it is an explicit answer. */
  fun detect(files: Set<String>): Kind = when {
    files.any { it == "Dockerfile" || it == "docker-compose.yml" || it == "compose.yaml" } -> Kind.DOCKER
    files.any { it == "package.json" } -> Kind.NODE
    files.any { it == "requirements.txt" || it == "pyproject.toml" } -> Kind.PYTHON
    files.any { it == "go.mod" } -> Kind.GO
    files.any { it == "build.gradle" || it == "build.gradle.kts" || it == "pom.xml" } -> Kind.JVM
    files.any { it == "index.html" } -> Kind.STATIC
    else -> Kind.UNKNOWN
  }

  /**
   * The steps, in the only order that works: everything local and reversible first, so the external
   * part starts from a state that is already known to build.
   */
  fun plan(kind: Kind, files: Set<String>): Plan {
    val steps = ArrayList<Step>()
    val warnings = ArrayList<String>()

    steps.add(Step(STEP_CHECK, external = false))
    if (kind != Kind.DOCKER) steps.add(Step(STEP_DOCKERFILE, external = false))
    if (kind == Kind.STATIC) steps.add(Step(STEP_STATIC_SERVER, external = false))
    steps.add(Step(STEP_BUILD_IMAGE, external = false))
    steps.add(Step(STEP_LOCAL_RUN, external = false))
    steps.add(Step(STEP_REGISTRY, external = true))
    steps.add(Step(STEP_HOST, external = true))
    steps.add(Step(STEP_DOMAIN, external = true))
    steps.add(Step(STEP_TLS, external = true))
    steps.add(Step(STEP_CI, external = false))

    if (kind == Kind.UNKNOWN) warnings.add(WARN_UNKNOWN_KIND)
    if (files.none { it == ".dockerignore" } && kind != Kind.STATIC) warnings.add(WARN_NO_DOCKERIGNORE)
    // A secret file in the repository becomes a secret in the image, and an image is copied around
    // far more casually than a repository.
    if (files.any { it == ".env" }) warnings.add(WARN_ENV_IN_REPO)
    return Plan(kind, steps, warnings)
  }

  /** Steps that touch the outside world — the ones that need a yes each. */
  fun externalSteps(plan: Plan): List<Step> = plan.steps.filter { it.external }

  const val STEP_CHECK = "check"
  const val STEP_DOCKERFILE = "dockerfile"
  const val STEP_STATIC_SERVER = "static-server"
  const val STEP_BUILD_IMAGE = "build-image"
  const val STEP_LOCAL_RUN = "local-run"
  const val STEP_REGISTRY = "registry"
  const val STEP_HOST = "host"
  const val STEP_DOMAIN = "domain"
  const val STEP_TLS = "tls"
  const val STEP_CI = "ci"

  const val WARN_UNKNOWN_KIND = "unknown-kind"
  const val WARN_NO_DOCKERIGNORE = "no-dockerignore"
  const val WARN_ENV_IN_REPO = "env-in-repo"
}
