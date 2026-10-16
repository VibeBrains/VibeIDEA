// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.checkpoints

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The snapshot is git's own object, so the tests run against a real repository: git decides what is true */
class CheckpointServiceTest {
  private fun git(dir: Path, vararg args: String): Pair<Int, String> {
    val process = ProcessBuilder(listOf("git", "-C", dir.toString()) + args).redirectErrorStream(true).start()
    val out = String(process.inputStream.readBytes(), Charsets.UTF_8)
    return process.waitFor() to out.trim()
  }

  private fun ok(dir: Path, vararg args: String): String = git(dir, *args).also { (code, out) -> assertEquals(0, code, "git ${args.toList()}: $out") }.second

  private fun repo(dir: Path, withIdentity: Boolean = true) {
    assumeTrue(runCatching { git(dir, "init", "-q").first == 0 }.getOrDefault(false), "git недоступен")
    if (withIdentity) {
      git(dir, "config", "user.email", "test@example.com")
      git(dir, "config", "user.name", "Repo Owner")
    }
    // The product's own journal lives in the folder and must not count as a change of the work
    Files.writeString(dir.resolve(".gitignore"), ".vibe/\nbuild/\n")
  }

  private fun write(dir: Path, name: String, text: String) {
    val file = dir.resolve(name)
    Files.createDirectories(file.parent)
    Files.writeString(file, text)
  }

  @Test
  fun `a diff against a checkpoint shows changed, new and deleted files — and only the asked ones`(@TempDir dir: Path) {
    repo(dir)
    write(dir, "kept.txt", "было\n")
    write(dir, "gone.txt", "удалят\n")
    ok(dir, "add", "-A")
    ok(dir, "commit", "-q", "-m", "init")

    val service = CheckpointService(dir.toString())
    val checkpoint = assertNotNull(service.create("перед шагом"))
    write(dir, "kept.txt", "стало\n")
    write(dir, "new.txt", "новый\n")
    Files.delete(dir.resolve("gone.txt"))
    write(dir, "other.txt", "не спрашивали\n")

    val diff = assertNotNull(service.diff(checkpoint, listOf("kept.txt", "new.txt", "gone.txt")))
    assertTrue("+стало" in diff, diff)
    assertTrue("+новый" in diff, "файл, созданный после снимка, не отслеживается git — и всё равно виден: $diff")
    assertTrue("-удалят" in diff, diff)
    assertFalse("other.txt" in diff, "дифф — только по путям, которых касались шаги")
    assertEquals("", service.diff(checkpoint, emptyList()))
  }

  @Test
  fun `a snapshot is a commit without a parent, signed by the product, held by a reference that outlives git gc`(@TempDir dir: Path) {
    repo(dir)
    write(dir, "a.txt", "один\n")
    ok(dir, "add", "-A")
    ok(dir, "commit", "-q", "-m", "init")
    val headBefore = ok(dir, "rev-parse", "HEAD")
    write(dir, "untracked.txt", "новый\n")
    val statusBefore = ok(dir, "status", "--porcelain")

    val cp = assertNotNull(CheckpointService(dir.toString()).create("метка\nв две строки", turnIndex = 4, threadId = "thread-7", agent = "acp-gemini"))

    assertEquals("", ok(dir, "rev-list", "--parents", "-n", "1", cp.hash).removePrefix(cp.hash).trim(), "у снимка не должно быть родителя")
    assertEquals(
      listOf("VibeIDEA", "snapshot@vibeidea.local", "VibeIDEA", "snapshot@vibeidea.local"),
      ok(dir, "log", "-1", "--format=%an%n%ae%n%cn%n%ce", cp.hash).lines(),
      "подпись — продукта, а не user.name репозитория",
    )
    assertTrue(ok(dir, "ls-tree", "-r", "--name-only", cp.hash).lines().containsAll(listOf("a.txt", "untracked.txt")), "неотслеживаемое в снимке")
    assertEquals(cp.hash, ok(dir, "for-each-ref", "--format=%(objectname)", "refs/vibe/checkpoints"), "ссылка refs/vibe/checkpoints/<sha>")
    assertEquals(cp.hash, ok(dir, "rev-parse", "refs/vibe/checkpoints/${cp.hash}"))

    ok(dir, "gc", "-q", "--prune=now")
    ok(dir, "cat-file", "-e", "${cp.hash}^{commit}")

    val read = CheckpointContract.parseMessage(ok(dir, "log", "-1", "--format=%B", cp.hash))
    assertEquals(CheckpointFields(ok(dir, "rev-parse", "${cp.hash}^{tree}"), 4, null, "thread-7", "acp-gemini", "метка в две строки"), read)
    assertEquals(headBefore, ok(dir, "rev-parse", "HEAD"))
    assertEquals(statusBefore, ok(dir, "status", "--porcelain"), "индекс и ветки снимок не трогает")
  }

  @Test
  fun `a repository with no commits and an identity git refuses still gets a snapshot`(@TempDir dir: Path) {
    repo(dir, withIdentity = false)
    // An empty identity is what git rejects for a commit: the snapshot must not depend on it
    git(dir, "config", "user.name", "")
    git(dir, "config", "user.email", "")
    write(dir, "first.txt", "первый\n")

    val cp = assertNotNull(CheckpointService(dir.toString()).create("в пустом репозитории"))

    assertEquals("VibeIDEA", ok(dir, "log", "-1", "--format=%an", cp.hash))
    assertTrue("first.txt" in ok(dir, "ls-tree", "-r", "--name-only", cp.hash))
    assertNull(git(dir, "rev-parse", "--verify", "-q", "HEAD").takeIf { it.first == 0 }, "коммитов в ветке по-прежнему нет")
  }

  @Test
  fun `a broken repository gives no snapshot and no error`(@TempDir dir: Path) {
    assumeTrue(runCatching { git(dir, "--version").first == 0 }.getOrDefault(false), "git недоступен")
    write(dir, "a.txt", "x\n")
    // A gitfile that points nowhere stops git's search for a repository in the parents, wherever the temporary folder sits
    write(dir, ".git", "gitdir: ${dir.resolve("nowhere")}\n")
    assertTrue(git(dir, "rev-parse", "--show-toplevel").first != 0)
    val service = CheckpointService(dir.toString())
    assertNull(service.create("нет git"))
    assertEquals(emptyList(), service.list())
  }

  @Test
  fun `a turn that changed nothing reuses the snapshot of its thread, another thread takes its own`(@TempDir dir: Path) {
    repo(dir)
    write(dir, "a.txt", "один\n")
    val service = CheckpointService(dir.toString())

    val first = assertNotNull(service.create("первый", turnIndex = 0, threadId = "t1"))
    val second = assertNotNull(service.create("второй", turnIndex = 1, threadId = "t1"))
    assertEquals(first.hash, second.hash, "дерево не изменилось — тот же снимок")
    assertEquals(1, ok(dir, "for-each-ref", "refs/vibe/checkpoints").lines().size)
    assertEquals(listOf("второй", "первый"), service.list().map { it.label }, "в ленту ход попадает всё равно")

    val other = assertNotNull(service.create("чужой тред", turnIndex = 0, threadId = "t2"))
    assertFalse(other.hash == first.hash, "снимок чужого треда не переиспользуется")

    write(dir, "a.txt", "два\n")
    val changed = assertNotNull(service.create("третий", turnIndex = 2, threadId = "t1"))
    assertFalse(changed.hash == first.hash)
  }

  @Test
  fun `the rollback returns the snapshot, deletes only what the plan said and leaves the real index and the ignored alone`(@TempDir dir: Path) {
    repo(dir)
    write(dir, "kept.txt", "было\n")
    write(dir, "docs/план работ.md", "план\n")
    ok(dir, "add", "-A")
    ok(dir, "commit", "-q", "-m", "init")
    write(dir, "staged.txt", "в индексе\n")
    ok(dir, "add", "staged.txt")
    val service = CheckpointService(dir.toString())
    val cp = assertNotNull(service.create("до правок"))

    write(dir, "kept.txt", "стало\n")
    write(dir, "новый файл.txt", "создан агентом\n")
    write(dir, "build/out.bin", "собрано, игнорируется\n")
    Files.delete(dir.resolve("docs/план работ.md"))
    val indexBefore = Files.readAllBytes(dir.resolve(".git/index"))
    val headBefore = ok(dir, "rev-parse", "HEAD")

    val rollback = assertNotNull(service.plan(cp))
    assertTrue(rollback.wouldChange)
    assertEquals(listOf("новый файл.txt"), rollback.plan.delete, "удаляется созданное после снимка; игнорируемое и удалённое не считаются")
    assertTrue("docs/план работ.md" in rollback.plan.restore && "kept.txt" in rollback.plan.restore)

    assertTrue(service.restore(cp, rollback.plan))

    assertEquals("было\n", Files.readString(dir.resolve("kept.txt")))
    assertEquals("план\n", Files.readString(dir.resolve("docs/план работ.md")), "удалённое возвращено")
    assertFalse(Files.exists(dir.resolve("новый файл.txt")), "созданное после снимка удалено")
    assertTrue(Files.exists(dir.resolve("build/out.bin")), "игнорируемое git не удаляется никогда")
    assertTrue(indexBefore.contentEquals(Files.readAllBytes(dir.resolve(".git/index"))), "настоящий индекс не тронут")
    assertEquals(headBefore, ok(dir, "rev-parse", "HEAD"))
    assertFalse(assertNotNull(service.plan(cp)).wouldChange, "после отката папка равна снимку")
  }

  @Test
  fun `an entry written before the references existed gets one if git still has the commit, and is unavailable if not`(@TempDir dir: Path) {
    repo(dir)
    write(dir, "a.txt", "один\n")
    val tree = run {
      ok(dir, "add", "-A")
      ok(dir, "write-tree")
    }
    val dangling = ok(dir, "-c", "user.name=x", "-c", "user.email=x@x", "commit-tree", tree, "-m", "vibe checkpoint: старый")
    val journal = dir.resolve(".vibe/local/checkpoints.jsonl")
    Files.createDirectories(journal.parent)
    Files.writeString(journal, """{"hash":"$dangling","label":"старый","at":1}""" + "\n" + """{"hash":"${"0".repeat(39)}1","label":"утерян","at":2}""" + "\n" + "не json\n")

    val entries = CheckpointService(dir.toString()).list()

    assertEquals(listOf("утерян", "старый"), entries.map { it.label })
    assertEquals(listOf(false, true), entries.map { it.available })
    assertEquals(dangling, ok(dir, "rev-parse", "refs/vibe/checkpoints/$dangling"), "ссылка поставлена при чтении")
    assertNull(CheckpointService(dir.toString()).plan(entries.first()), "план для недоступного снимка — null, а не падение")
  }

  @Test
  fun `pruning drops the references that nothing lives on, spares the young and the foreign`(@TempDir dir: Path) {
    repo(dir)
    write(dir, "a.txt", "один\n")
    val service = CheckpointService(dir.toString())
    val cp = assertNotNull(service.create("живой"))
    ok(dir, "update-ref", "refs/vibe/pipelines/run1/step1", cp.hash)
    val twoHours = System.currentTimeMillis() + 2 * CheckpointContract.PRUNE_MIN_AGE_MS

    service.prune(twoHours)
    assertEquals(cp.hash, ok(dir, "rev-parse", "refs/vibe/checkpoints/${cp.hash}"), "снимок из журнала живой, как бы он ни был стар")

    Files.writeString(dir.resolve(".vibe/local/checkpoints.jsonl"), "")
    service.prune()
    assertEquals(cp.hash, ok(dir, "rev-parse", "refs/vibe/checkpoints/${cp.hash}"), "свежий щадится, даже если журнал о нём не знает")

    service.prune(twoHours)
    assertTrue(git(dir, "rev-parse", "--verify", "-q", "refs/vibe/checkpoints/${cp.hash}").first != 0, "старый и не живой снят")
    assertEquals(cp.hash, ok(dir, "rev-parse", "refs/vibe/pipelines/run1/step1"), "чужое пространство уборка не трогает")
  }
}
