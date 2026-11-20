// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.guard

/**
 * Paths in a command's arguments that point outside the project — the hole a text rule cannot see.
 *
 * The shell classifier judges the command's own words: `npm run compare -- --save /VolumES` reads as an
 * ordinary npm run, and the script that clears the folder is never in the line. On macOS `/VolumES` is
 * `/Volumes`, so the argument named a volume root and the disk was wiped (11.11.2026). A path in an
 * argument is data until the program uses it, so it is judged by where it points, not by its spelling.
 *
 * Pure: strings in, reason codes out. The rules mirror VibeIDE's `commandPathsOutsideScope.ts`: any
 * program, `--opt=/path`, `-o/path`, `~`, `$HOME`, `..`, Windows drives and UNC, no case on macOS and
 * Windows, and the roots of volumes and the home folder are always a question.
 */
object CommandPaths {
  /** A path outside the project that the command may write or delete. */
  const val OUTSIDE_PROJECT = "path-outside-project"

  /** A filesystem, volume or home root — the one argument where «it only writes one file» is never true. */
  const val ROOT = "path-root"

  /** A variable this rule cannot resolve: what it names is unknown, so it is treated as outside. */
  const val UNRESOLVED = "path-unresolved"

  /** Programs that only read what they are given: naming a path to them changes nothing. */
  private val READ_ONLY = ShellSafetyAnalyzer.PATH_READERS + setOf(
    "ls", "find", "stat", "du", "df", "file", "which", "readlink", "realpath", "tree", "diff", "cmp",
  )

  private val DRIVE = Regex("^[A-Za-z]:([\\\\/].*)?$")
  private val UNC = Regex("^[\\\\/]{2}[^\\\\/]+[\\\\/][^\\\\/]+.*$")
  private val VAR_PATH = Regex("^\\$\\{?[A-Za-z_][A-Za-z0-9_]*\\}?(?:[\\\\/].*)?$")
  private val URL = Regex("^[A-Za-z][A-Za-z0-9+.-]*://.*$")
  private val SHORT_ATTACHED = Regex("^-[A-Za-z][\\\\/].*$")

  /**
   * Reason codes for one simple command, or an empty list.
   *
   * [projectRoot] is what «inside» means; null — no project to compare against, so only the roots are
   * still named. [cwd] is where relative targets are read from, and [os] and [home] are passed in so the
   * platform rules are testable without a real machine.
   */
  fun reasons(
    program: String,
    args: List<String>,
    projectRoot: String?,
    cwd: String? = null,
    os: String = System.getProperty("os.name").orEmpty(),
    home: String? = System.getProperty("user.home"),
    tempRoots: List<String> = defaultTempRoots(),
  ): List<String> {
    if (baseName(program) in READ_ONLY) return emptyList()
    val insensitive = os.contains("mac", true) || os.contains("win", true) || os.contains("darwin", true)
    val roots = tempRoots.map { normalize(it) }
    val findings = LinkedHashSet<String>()
    for (raw in args) {
      for (candidate in candidates(raw)) {
        val resolved = resolve(candidate, projectRoot, cwd, home) ?: continue
        if (resolved.unresolved) {
          findings.add(UNRESOLVED)
          continue
        }
        val path = resolved.path ?: continue
        if (isRoot(path, projectRoot, cwd, home, os, insensitive)) {
          findings.add(ROOT)
          continue
        }
        val project = projectRoot?.let { normalize(it) }
        if (project != null && inside(path, project, insensitive)) continue
        if (roots.any { inside(path, it, insensitive) }) continue
        if (isDeviceOrKernel(path)) continue
        // A plain relative path is inside the project by construction; a home shortcut or a variable is not one.
        if (plainRelative(candidate)) continue
        findings.add(OUTSIDE_PROJECT)
      }
    }
    return findings.toList()
  }

  /** Reason codes over a raw shell line, one segment at a time; the same split as the destructive classifier. */
  fun reasonsInLine(
    line: String,
    projectRoot: String?,
    cwd: String? = null,
    os: String = System.getProperty("os.name").orEmpty(),
    home: String? = System.getProperty("user.home"),
  ): List<String> = LinkedHashSet<String>().apply {
    for ((command, args) in ShellSafetyAnalyzer.splitSegments(line)) {
      addAll(reasons(command, args, projectRoot, cwd, os, home))
    }
  }.toList()

  /**
   * The path-like values of one argument: a bare token, an option's value after `=`, or a path glued to
   * a short option (`-o/tmp/x`). Flags, URLs and ordinary words are not paths.
   */
  private fun candidates(raw: String): List<String> {
    val arg = raw.trim().trim('"', '\'')
    if (arg.isEmpty() || URL.matches(arg)) return emptyList()
    if (arg.startsWith("-")) {
      val eq = arg.indexOf('=')
      if (eq > 1) return arg.substring(eq + 1).takeIf(::looksLikePath)?.let(::listOf).orEmpty()
      if (SHORT_ATTACHED.matches(arg)) return arg.substring(2).takeIf(::looksLikePath)?.let(::listOf).orEmpty()
      return emptyList()
    }
    return if (looksLikePath(arg)) listOf(arg) else emptyList()
  }

  private fun looksLikePath(value: String): Boolean {
    if (value.isEmpty() || URL.matches(value)) return false
    if (DRIVE.matches(value) || UNC.matches(value)) return true
    if (value.startsWith("/") || value.startsWith("\\")) return true
    if (value == ".." || value.startsWith("../") || value.startsWith("..\\")) return true
    if (value.startsWith("~")) return true
    return VAR_PATH.matches(value)
  }

  private data class Resolved(val path: String?, val unresolved: Boolean)

  /** Where the argument points, or [Resolved.unresolved] when a variable hides it. */
  private fun resolve(path: String, projectRoot: String?, cwd: String?, home: String?): Resolved? {
    var raw = path.trim('"', '\'')
    if (raw == "~" || raw.startsWith("~/") || raw.startsWith("~\\")) {
      raw = (home ?: return Resolved(null, true)) + raw.removePrefix("~")
    } else if (raw.startsWith("\$HOME")) {
      raw = (home ?: return Resolved(null, true)) + raw.removePrefix("\$HOME")
    } else if (raw.startsWith("\${HOME}")) {
      raw = (home ?: return Resolved(null, true)) + raw.removePrefix("\${HOME}")
    } else if (VAR_PATH.matches(raw)) {
      return Resolved(null, true)
    }
    if (!isAbsolute(raw) && !climbs(raw)) {
      // A plain relative path stays where the command runs, which is inside the project unless cwd says otherwise.
      val base = cwd ?: return null
      return Resolved(normalize(base + "/" + raw), false)
    }
    if (!isAbsolute(raw)) {
      val base = cwd ?: projectRoot ?: return Resolved(null, true)
      return Resolved(normalize(base + "/" + raw), false)
    }
    return Resolved(normalize(raw), false)
  }

  private fun isAbsolute(path: String): Boolean =
    path.startsWith("/") || path.startsWith("\\") || DRIVE.matches(path) || UNC.matches(path)

  private fun climbs(path: String): Boolean =
    path == ".." || path.startsWith("../") || path.startsWith("..\\") || path.contains("/../")

  /**
   * A path written against the working folder: no root, no home shortcut, no variable, no climb.
   *
   * Only such a path is inside the project by construction. `~/Pictures` and `$HOME/Pictures` resolve to the home
   * folder — outside it — and a check that asked only «is this spelled absolute» waved both through (VibeIDE 1.31).
   */
  private fun plainRelative(candidate: String): Boolean =
    !isAbsolute(candidate) && !climbs(candidate) && !candidate.startsWith("~") && !VAR_PATH.matches(candidate)

  /** Normalised absolute form: `/a/./b/../c` → `/a/c`; Windows separators and keys fold to one shape. */
  private fun normalize(path: String): String {
    val unix = path.replace('\\', '/').trim('"', '\'')
    val share = uncShareRoot(unix)
    if (share != null) {
      val rest = unix.removePrefix(share)
      return share + normalizeTail(rest)
    }
    val drive = DRIVE.find(unix)?.value?.removeSuffix("/").orEmpty()
    if (drive.isNotEmpty() && unix.length > drive.length) {
      return drive + "/" + normalizeTail(unix.substring(drive.length + 1)).trimStart('/')
    }
    if (drive.isNotEmpty()) return drive + "/"
    return normalizeTail(unix)
  }

  private fun normalizeTail(path: String): String {
    val absolute = path.startsWith("/")
    val stack = ArrayList<String>()
    for (part in path.split('/')) {
      when (part) {
        "", "." -> Unit
        ".." -> if (stack.isNotEmpty() && stack.last() != "..") stack.removeAt(stack.size - 1)
                else if (!absolute) stack.add("..")
        else -> stack.add(part)
      }
    }
    return (if (absolute) "/" else "") + stack.joinToString("/")
  }

  private fun uncShareRoot(path: String): String? {
    if (!path.startsWith("//")) return null
    val parts = path.removePrefix("//").split('/')
    if (parts.size < 2 || parts[0].isEmpty() || parts[1].isEmpty()) return null
    return "//" + parts[0] + "/" + parts[1]
  }

  /**
   * Whether [path] is a root a command must never name: `/`, `/Volumes` and a volume under it, the home
   * folder, a Windows drive root, a UNC share root, and the usual Linux mount parents.
   */
  private fun isRoot(
    path: String,
    projectRoot: String?,
    cwd: String?,
    home: String?,
    os: String,
    insensitive: Boolean,
  ): Boolean {
    val p = fold(path, insensitive)
    if (p == "/") return true
    val mac = os.contains("mac", true) || os.contains("darwin", true)
    if (mac) {
      if (p == "/volumes") return true
      if (p.startsWith("/volumes/") && !p.removePrefix("/volumes/").contains('/')) return true
    }
    val linux = os.contains("linux", true) || os.isEmpty()
    if (linux) {
      if (oneSegmentUnder(p, "/mnt") || oneSegmentUnder(p, "/media") ||
          twoSegmentsUnder(p, "/media") || threeSegmentsUnder(p, "/run/media")) return true
    }
    if (DRIVE.matches(p) && p.endsWith("/") && p.length == 3) return true
    if (uncShareRoot(p) == p) return true
    val roots = listOfNotNull(home, projectRoot, cwd)
    return roots.any { fold(normalize(it), insensitive) == p }
  }

  private fun oneSegmentUnder(path: String, parent: String): Boolean {
    val rest = path.removePrefix(parent).removePrefix("/")
    return path.startsWith("$parent/") && rest.isNotEmpty() && !rest.contains('/')
  }

  private fun twoSegmentsUnder(path: String, parent: String): Boolean {
    val rest = path.removePrefix(parent).removePrefix("/")
    return path.startsWith("$parent/") && rest.split('/').count { it.isNotEmpty() } == 2
  }

  private fun threeSegmentsUnder(path: String, parent: String): Boolean {
    val rest = path.removePrefix(parent).removePrefix("/")
    return path.startsWith("$parent/") && rest.split('/').count { it.isNotEmpty() } == 3
  }

  private fun isDeviceOrKernel(path: String): Boolean =
    listOf("/dev", "/proc", "/sys").any { it == path || path.startsWith("$it/") }

  private fun inside(path: String, root: String, insensitive: Boolean): Boolean {
    val p = fold(path, insensitive)
    val r = fold(root, insensitive).trimEnd('/')
    return p == r || p.startsWith("$r/")
  }

  private fun fold(path: String, insensitive: Boolean): String = if (insensitive) path.lowercase() else path

  private fun baseName(token: String): String =
    token.substringAfterLast('/').substringAfterLast('\\')
      .replace(Regex("\\.(?:exe|com)$", RegexOption.IGNORE_CASE), "").lowercase()

  /** Scratch folders: the same default as [RemovalScope], plus the platform temp links. */
  fun defaultTempRoots(): List<String> = listOfNotNull(
    System.getProperty("java.io.tmpdir"),
    System.getenv("TMPDIR"),
    "/tmp",
    "/var/tmp",
    "/private/tmp",
    "/private/var/folders",
  )
}
