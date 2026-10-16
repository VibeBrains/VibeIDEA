// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.checkpoints

/**
 * The turn checkpoint contract shared with VibeIDE: the pure half
 *
 * A snapshot taken by one product must read the same in the other, because both work in the same repository
 * The format, the pruning rule, the rollback plan and the warning rule are the contract (`checkpointSpec.md` of the
 * defaults set); the vectors (`testVectors/checkpoint.json`) pin every function here
 * No git and no files in this file: it is what the vectors test, and git is only the way the product applies it
 */
object CheckpointContract {
  const val PRODUCT = "VibeIDEA"

  /** Author and committer of every snapshot: the product's, never the repository's `user.name` */
  const val SIGNER_EMAIL = "snapshot@vibeidea.local"

  /** The namespace of the references that keep snapshots alive; the last element of a name is the snapshot id */
  const val REF_PREFIX = "refs/vibe/checkpoints/"

  /** A snapshot younger than this is never pruned: another window may not have written its thread yet */
  const val PRUNE_MIN_AGE_MS = 3_600_000L

  /** The tools whose effects lie outside what the chat tracks; compared exactly, no case folding and no prefixes */
  val SHELL_TOOL_NAMES: Set<String> = setOf("run_command", "run_persistent_command", "run_nl_command")

  /** Word «turn» of the subject line: the wire format is Russian in both products, kept as escapes for the language gate */
  private const val TURN_WORD = "\u0445\u043e\u0434"

  private const val NO_TOOL = "agent"

  private val WHITESPACE = Regex("(?U)[\\s\\uFEFF]+")

  private val HASH = Regex("[0-9a-f]{40}|[0-9a-f]{64}")

  private val KEY_START = Regex("^[A-Za-z][A-Za-z0-9]*")

  /** The fields a reader knows; an unknown key is skipped silently, the contract may grow */
  private val KNOWN_KEYS = setOf("tree", "turnIndex", "toolName", "threadId", "agent", "label")

  /** Whether [text] is a full object name: 40 or 64 lowercase hexadecimal digits */
  fun isObjectName(text: String): Boolean = HASH.matches(text)

  /** One line: any run of whitespace, line breaks included, becomes one space, the edges are cut */
  fun oneLine(text: String): String = WHITESPACE.replace(text, " ").trim()

  /** The message of a snapshot commit: the subject, an empty line, then `key: value` lines */
  fun commitMessage(product: String, tree: String, meta: CheckpointMeta): String {
    val subject = if (meta.turnIndex != null) {
      "$product checkpoint: $TURN_WORD ${meta.turnIndex} (${meta.toolName?.let(::oneLine)?.ifEmpty { null } ?: NO_TOOL})"
    }
    else "$product checkpoint snapshot"
    val fields = listOf(
      "tree" to tree,
      "turnIndex" to meta.turnIndex?.toString(),
      "toolName" to meta.toolName,
      "threadId" to meta.threadId,
      "agent" to meta.agent,
      "label" to meta.label,
    ).mapNotNull { (key, value) -> value?.let(::oneLine)?.ifEmpty { null }?.let { "$key: $it" } }
    return subject + "\n\n" + fields.joinToString("\n")
  }

  /**
   * The fields of a snapshot message: only the ones found
   *
   * The first line is the subject and is not read. A line is `key: value` (the first colon followed by a space is the
   * separator), or the older `key value` for the known keys only. A repeated key keeps its first value, an empty
   * value is no field, and a `turnIndex` that is not a whole non-negative number is no field either
   */
  fun parseMessage(message: String): CheckpointFields {
    val found = LinkedHashMap<String, String>()
    for (raw in message.split('\n').drop(1)) {
      val line = raw.trim()
      if (line.isEmpty()) continue
      val key = KEY_START.find(line)?.value ?: continue
      val rest = line.substring(key.length)
      val value = when {
        rest.startsWith(":") && (rest.length == 1 || rest[1].isWhitespace()) -> rest.substring(1).trim()
        rest.isNotEmpty() && rest[0].isWhitespace() && key in KNOWN_KEYS -> rest.trim()
        else -> continue
      }
      if (key !in KNOWN_KEYS || value.isEmpty() || key in found) continue
      found[key] = value
    }
    return CheckpointFields(
      tree = found["tree"],
      turnIndex = found["turnIndex"]?.takeIf { it.all(Char::isDigit) }?.toIntOrNull(),
      toolName = found["toolName"],
      threadId = found["threadId"],
      agent = found["agent"],
      label = found["label"],
    )
  }

  /** The output of `for-each-ref --format='%(refname) %(committerdate:unix)'` over the checkpoint namespace */
  fun parsePinned(stdout: String): List<PinnedRef> = stdout.lineSequence().mapNotNull { raw ->
    val parts = raw.trim().split(Regex("\\s+"))
    if (parts.size != 2 || !parts[0].startsWith(REF_PREFIX)) return@mapNotNull null
    val id = parts[0].removePrefix(REF_PREFIX).takeIf { it.isNotEmpty() && '/' !in it } ?: return@mapNotNull null
    val seconds = parts[1].takeIf { it.all(Char::isDigit) }?.toLongOrNull() ?: return@mapNotNull null
    PinnedRef(id, seconds * 1000)
  }.toList()

  /** The references to drop: not live, and at least [PRUNE_MIN_AGE_MS] old (a time in the future is a negative age) */
  fun staleRefs(nowMs: Long, pinned: List<PinnedRef>, live: Set<String>): List<String> =
    pinned.filter { it.id !in live && nowMs - it.committedAtMs >= PRUNE_MIN_AGE_MS }.map { it.id }

  /** Whether the previous snapshot of the thread already holds this tree: nothing changed, no new commit */
  fun reuse(newTree: String, previousTree: String?): Boolean {
    val fresh = newTree.trim()
    return fresh.isNotEmpty() && previousTree?.trim() == fresh
  }

  /**
   * What a rollback does: every file of the snapshot comes back, every file of the folder that the snapshot lacks and
   * git does not ignore goes away
   * [workingPaths] is what `ls-files --cached --others --exclude-standard` lists, so ignored files are not in it;
   * [ignoredPaths] is for the callers that hold a list with them
   * Both lists are sorted by UTF-16 code units, without repeats
   */
  fun restorePlan(snapshotPaths: Collection<String>, workingPaths: Collection<String>, ignoredPaths: Collection<String> = emptySet()): RestorePlan {
    val inSnapshot = snapshotPaths.toSet()
    val ignored = ignoredPaths.toSet()
    return RestorePlan(
      restore = inSnapshot.sorted(),
      delete = workingPaths.filter { it !in inSnapshot && it !in ignored }.toSortedSet().toList(),
    )
  }

  /** What to say after a rollback; see [RewindCoverage] */
  fun coverage(hasSnapshot: Boolean, wouldChangeAnything: Boolean?, toolNames: Collection<String>): RewindCoverage {
    if (hasSnapshot) return if (wouldChangeAnything == true) RewindCoverage.Restorable else RewindCoverage.Complete
    val shellCalls = toolNames.count { it in SHELL_TOOL_NAMES }
    return if (shellCalls == 0) RewindCoverage.Complete else RewindCoverage.Uncovered(shellCalls)
  }
}

/** The fields a snapshot message can carry; none is required of the caller */
data class CheckpointMeta(
  val turnIndex: Int? = null,
  val toolName: String? = null,
  val threadId: String? = null,
  val agent: String? = null,
  val label: String? = null,
)

/** What [CheckpointContract.parseMessage] found: a field that is not there is null */
data class CheckpointFields(
  val tree: String? = null,
  val turnIndex: Int? = null,
  val toolName: String? = null,
  val threadId: String? = null,
  val agent: String? = null,
  val label: String? = null,
)

/** A checkpoint reference with the commit time of its snapshot */
data class PinnedRef(val id: String, val committedAtMs: Long)

/** The paths a rollback writes and the paths it deletes */
data class RestorePlan(val restore: List<String>, val delete: List<String>)

/**
 * The words after a rollback
 *
 * A silence after the rollback reads as «everything is back», and that is exactly the failure the contract forbids:
 * a command's effects are outside what the chat tracks, and only a snapshot covers them
 */
sealed interface RewindCoverage {
  /** A snapshot exists and the rollback changes something: offer to bring the folder back */
  data object Restorable : RewindCoverage

  /** Nothing to say: the rollback is whole */
  data object Complete : RewindCoverage

  /** There is no snapshot and commands ran in the rolled-back stretch: warn, with their number */
  data class Uncovered(val shellCallCount: Int) : RewindCoverage
}
