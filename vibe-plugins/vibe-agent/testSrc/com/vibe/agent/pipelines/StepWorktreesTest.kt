// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.pipelines

import com.vibe.agent.checkpoints.CheckpointService
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Steps of a wave each write in a tree of their own, and their work comes back over the person's — a real git decides */
class StepWorktreesTest {
  private fun git(dir: Path, vararg args: String): String {
    val process = ProcessBuilder(listOf("git", "-C", dir.toString()) + args).redirectErrorStream(true).start()
    val out = process.inputStream.readBytes().decodeToString()
    process.waitFor()
    return out
  }

  private fun repo(dir: Path): Boolean {
    if (runCatching { git(dir, "init", "-q"); Files.isDirectory(dir.resolve(".git")) }.getOrDefault(false).not()) return false
    git(dir, "config", "user.email", "test@example.com")
    git(dir, "config", "user.name", "test")
    Files.writeString(dir.resolve("shared.txt"), "one\ntwo\nthree\n")
    git(dir, "add", "-A")
    git(dir, "commit", "-q", "-m", "init")
    return true
  }

  @Test
  fun `two steps write in their own trees, and both come back over an uncommitted edit of the person`(@TempDir dir: Path) {
    assumeTrue(repo(dir), "git недоступен")
    // An earlier step's work, never committed: the trees must start from it, not from HEAD
    Files.writeString(dir.resolve("earlier.txt"), "from an earlier step\n")
    val base = assertNotNull(CheckpointService(dir.toString()).create("перед волной")).hash
    val trees = StepWorktrees(dir.toString())
    val tests = assertNotNull(trees.create("run1-s2", base) { error(it) })
    val docs = assertNotNull(trees.create("run1-s3", base) { error(it) })
    assertTrue(Files.exists(Path.of(tests.path, "earlier.txt")), "дерево начато не со снимка рабочего каталога")
    Files.writeString(Path.of(tests.path, "test.txt"), "a test\n")
    Files.writeString(Path.of(docs.path, "doc.md"), "a doc\n")
    // The person edits while the wave runs
    Files.writeString(dir.resolve("mine.txt"), "the person's own\n")

    assertEquals(StepWorktrees.Merge.Clean, trees.mergeBack(tests, "step 2"))
    assertEquals(StepWorktrees.Merge.Clean, trees.mergeBack(docs, "step 3"))
    assertEquals("a test\n", Files.readString(dir.resolve("test.txt")))
    assertEquals("a doc\n", Files.readString(dir.resolve("doc.md")))
    assertEquals("the person's own\n", Files.readString(dir.resolve("mine.txt")))
    assertEquals("", git(dir, "diff", "--cached", "--name-only"), "индекс человека тронут")
    assertFalse(".vibe-worktrees" in git(dir, "status", "--porcelain"), "деревья видны как неотслеживаемые файлы")

    trees.remove(tests)
    trees.remove(docs)
    assertFalse(Files.exists(Path.of(tests.path)))
    assertFalse(StepWorktrees.BRANCH_PREFIX in git(dir, "branch"))
  }

  @Test
  fun `a step that clashes with the person's edit leaves markers and its tree`(@TempDir dir: Path) {
    assumeTrue(repo(dir), "git недоступен")
    val base = assertNotNull(CheckpointService(dir.toString()).create("перед волной")).hash
    val trees = StepWorktrees(dir.toString())
    val tree = assertNotNull(trees.create("run2-s1", base) { error(it) })
    Files.writeString(Path.of(tree.path, "shared.txt"), "one\nTWO from the step\nthree\n")
    Files.writeString(dir.resolve("shared.txt"), "one\nTWO from the person\nthree\n")

    val merge = assertIs<StepWorktrees.Merge.Conflict>(trees.mergeBack(tree, "step 1"))
    assertEquals(listOf("shared.txt"), merge.files)
    assertTrue("<<<<<<<" in Files.readString(dir.resolve("shared.txt")))
    assertTrue(Files.exists(Path.of(tree.path)), "дерево с работой шага удалено до ручного слияния")
  }

  @Test
  fun `a step that wrote nothing has nothing to bring back`(@TempDir dir: Path) {
    assumeTrue(repo(dir), "git недоступен")
    val base = assertNotNull(CheckpointService(dir.toString()).create("перед волной")).hash
    val trees = StepWorktrees(dir.toString())
    val tree = assertNotNull(trees.create("run3-s1", base) { error(it) })
    assertEquals(StepWorktrees.Merge.Nothing, trees.mergeBack(tree, "step 1"))
  }

  @Test
  fun `a path written in the tree is named as the project's file`() {
    assertEquals("/p/src/a.kt", StepWorktrees.rebase("/p/.vibe-worktrees/vibe-agent-x/src/a.kt", "/p/.vibe-worktrees/vibe-agent-x", "/p"))
    assertEquals("/elsewhere/b.kt", StepWorktrees.rebase("/elsewhere/b.kt", "/p/.vibe-worktrees/vibe-agent-x", "/p"))
    // A sibling tree whose name starts the same is not this tree
    assertEquals("/p/.vibe-worktrees/vibe-agent-xy/c.kt",
                 StepWorktrees.rebase("/p/.vibe-worktrees/vibe-agent-xy/c.kt", "/p/.vibe-worktrees/vibe-agent-x", "/p"))
  }

  @Test
  fun `branch names are the ones VibeIDE makes`() {
    assertEquals("vibe-agent-run1-s2", StepWorktrees.branchName("Run1 s2"))
    assertEquals("vibe-agent-правка-файл-2", StepWorktrees.branchName("Правка: файл 2"))
    assertEquals("vibe-agent-a.b", StepWorktrees.branchName("..a..b.."))
    assertEquals("vibe-agent-session", StepWorktrees.branchName("***"))
  }
}
