// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.voice

import java.io.File
import java.net.URI
import java.util.jar.JarFile
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Voice does not depend on the agent
 *
 * The dictation reads no session, no turn and no mode of an agent, and no class of the voice package refers to the code that talks
 * to one, so a microphone works with no agent started and whatever the agent is doing
 * The check reads the compiled classes: a reference hides in a method descriptor as easily as in an import
 */
class VoiceIndependenceTest {
  private val forbidden = listOf("com/vibe/agent/acp/", "com/vibe/agent/ui/")

  /** Every compiled class of the package, with its bytes, from the jar or the folder the package was loaded from */
  private fun classesOfVoice(): Map<String, ByteArray> {
    val prefix = "com/vibe/agent/voice/"
    val here = VoiceDictation::class.java.getResource("VoiceDictation.class")!!
    return if (here.protocol == "jar") {
      // The class loader of the tests answers with a connection of its own, so the jar is named from the address
      val jarFile = File(URI(here.toExternalForm().removePrefix("jar:").substringBefore("!/")))
      JarFile(jarFile).use { jar ->
        jar.entries().asSequence().filter { it.name.startsWith(prefix) && it.name.endsWith(".class") }
          .associate { it.name to jar.getInputStream(it).readBytes() }
      }
    }
    else {
      File(here.toURI()).parentFile.listFiles { f -> f.name.endsWith(".class") }.orEmpty().associate { it.name to it.readBytes() }
    }.filterKeys { !isTestClass(it) }
  }

  /** A test of the package lives beside its classes in a folder, and it names the packages it checks */
  private fun isTestClass(name: String): Boolean = "Test" in name.substringAfterLast('/')

  @Test
  fun `no class of the voice package refers to the agent or to the panel that hosts it`() {
    val classes = classesOfVoice()
    assertTrue(classes.size > 15, "the scan found the package: ${classes.keys}")
    classes.forEach { (name, bytes) ->
      val text = String(bytes, Charsets.ISO_8859_1)
      forbidden.forEach { assertFalse(it in text, "$name refers to $it") }
    }
  }

  @Test
  fun `the scan can see a reference when there is one`() {
    // The panel does refer to the agent; if the scan could not tell, the test above would pass for the wrong reason
    val bytes = com.vibe.agent.ui.AgentPanel::class.java.getResourceAsStream("AgentPanel.class")!!.readBytes()
    assertTrue("com/vibe/agent/acp/" in String(bytes, Charsets.ISO_8859_1))
  }
}
