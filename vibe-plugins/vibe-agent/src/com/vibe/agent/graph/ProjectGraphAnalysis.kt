// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.graph

import com.vibe.agent.graph.CodeGraphIndex.Provenance

/**
 * The project graph as a person reads it: subsystems, the files everything flows through, the links nobody expects
 *
 * The code graph answers an agent's narrow questions; this answers how the project is put together, for a human and an agent alike
 * Everything here is computed from the graph alone, without a model: the labels come from paths and degrees
 * So the same repository gives the same report on every open
 *
 * A port of VibeIDE `codeGraphAnalysis.ts`, with two differences that come from our graph
 * We have one kind of link (an import) and two grades of certainty, so there are no kinds to filter by
 * And a pair of files is ONE link here however many symbols are imported through it, so a file's degree counts its partners
 *
 * Pure: files and links in, an analysis out
 */
object ProjectGraphAnalysis {
  /** How strongly a pair of files known only by a name match pulls them into one subsystem, against 1 for a known link */
  const val GUESS_WEIGHT = 0.25

  const val REPORT_HUBS = 10
  const val REPORT_SURPRISING = 10

  /** A label shows at most this many trailing folders of the folder a subsystem mostly lives in */
  private const val LABEL_FOLDERS = 2

  /** A subsystem is a group of at least this many files; a lone file is not one and goes to `isolated` */
  private const val MIN_SUBSYSTEM_FILES = 2

  /** One link from a file to a file it imports; a pair is one link, and a known one beats a guess */
  data class FileLink(val from: String, val to: String, val provenance: Provenance)

  data class Subsystem(
    val id: Int,
    /** Folder most of its files live in, relative to the project; the hub file's name when they share none */
    val label: String,
    /** Most connected first */
    val files: List<String>,
    val hub: String,
    /** Links between its own files */
    val internalLinks: Int,
  )

  data class HubFile(val file: String, val degree: Int, val subsystem: Int)

  data class SurprisingLink(
    val link: FileLink,
    val fromSubsystem: Int,
    val toSubsystem: Int,
    /** How many links join these two subsystems at all: one means this is the only bridge */
    val bridgeCount: Int,
    /** The two files live under different top-level folders of the project */
    val crossesFolders: Boolean,
  )

  data class Report(
    val fileCount: Int,
    val linkCount: Int,
    val facts: Int,
    val guesses: Int,
    val hubs: List<HubFile>,
    val surprising: List<SurprisingLink>,
    /** Files nothing imports and that import nothing known */
    val isolated: List<String>,
  )

  class Analysis(
    /** Common folder of every file; labels and relative paths are measured from it */
    val root: String,
    val links: List<FileLink>,
    val communityOf: Map<String, Int>,
    /** Distinct files a file is linked with, in either direction */
    val degreeOf: Map<String, Int>,
    /** Groups of two files or more, largest first */
    val subsystems: List<Subsystem>,
    val report: Report,
  ) {
    val subsystemById: Map<Int, Subsystem> = subsystems.associateBy { it.id }

    /** The path as the report shows it: without the folder every file shares */
    fun relative(path: String): String = relativeTo(root, path)
  }

  fun analyze(graph: CodeGraphIndex.Graph): Analysis =
    analyze(graph.nodes.map { it.path }, graph.edges.map { FileLink(it.from, it.to, it.provenance) })

  fun analyze(files: Collection<String>, edges: Collection<FileLink>): Analysis {
    val sortedFiles = files.toSortedSet().toList()
    val root = commonFolder(sortedFiles)
    val links = distinctLinks(sortedFiles, edges)

    val indexOf = sortedFiles.withIndex().associate { (index, file) -> file to index }
    // One pair of files is one relationship, however it is expressed: a file importing three classes of another
    // Would otherwise count three times, and a hub everything imports would swallow its callers into one subsystem
    // A pair known only by a guess pulls weakly: a name match must not glue distant parts of a project together
    val pairWeight = HashMap<Pair<Int, Int>, Double>()
    for (link in links) {
      val a = indexOf.getValue(link.from)
      val b = indexOf.getValue(link.to)
      val key = if (a < b) a to b else b to a
      val weight = if (link.provenance == Provenance.FACT) 1.0 else GUESS_WEIGHT
      pairWeight[key] = maxOf(pairWeight[key] ?: 0.0, weight)
    }
    val degreeOf = HashMap<String, Int>().apply { sortedFiles.forEach { put(it, 0) } }
    for ((a, b) in pairWeight.keys) {
      degreeOf[sortedFiles[a]] = degreeOf.getValue(sortedFiles[a]) + 1
      degreeOf[sortedFiles[b]] = degreeOf.getValue(sortedFiles[b]) + 1
    }
    val weighted = pairWeight.entries
      .sortedWith(compareBy<Map.Entry<Pair<Int, Int>, Double>>({ it.key.first }, { it.key.second }))
      .map { Communities.WeightedLink(it.key.first, it.key.second, it.value) }

    val membership = Communities.detect(sortedFiles.size, weighted)
    val communityOf = sortedFiles.withIndex().associate { (index, file) -> file to membership[index] }

    val members = sortedFiles.groupBy { communityOf.getValue(it) }
    val internal = HashMap<Int, Int>()
    for (link in links) {
      val community = communityOf.getValue(link.from)
      if (community == communityOf.getValue(link.to)) internal[community] = (internal[community] ?: 0) + 1
    }

    val byDegree = compareByDescending<String> { degreeOf.getValue(it) }.thenBy { it }
    val groups = members.entries.filter { it.value.size >= MIN_SUBSYSTEM_FILES }.sortedBy { it.key }
    val folderLabels = groups.map { (_, list) ->
      val folder = relativeTo(root, majorityFolder(list))
      if (folder == root || folder.isEmpty()) "" else folder.split('/').takeLast(LABEL_FOLDERS).joinToString("/")
    }
    val repeated = folderLabels.filter { it.isNotEmpty() }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
    val subsystems = groups.mapIndexed { index, (id, list) ->
      val sorted = list.sortedWith(byDegree)
      val hub = sorted.first()
      val folder = folderLabels[index]
      // One folder split into two subsystems, or files with no folder in common: the hub tells them apart
      val label = when {
        folder.isEmpty() -> basename(hub)
        folder in repeated -> folder + " · " + basename(hub)
        else -> folder
      }
      Subsystem(id, label, sorted, hub, internal[id] ?: 0)
    }

    return Analysis(
      root, links, communityOf, degreeOf, subsystems,
      reportOf(root, sortedFiles, links, communityOf, degreeOf, subsystems.mapTo(HashSet()) { it.id }),
    )
  }

  /** Links between two different known files, one per ordered pair, sorted; a fact outweighs a guess of the same pair */
  private fun distinctLinks(files: List<String>, edges: Collection<FileLink>): List<FileLink> {
    val known = files.toHashSet()
    val merged = HashMap<Pair<String, String>, Provenance>()
    for (edge in edges) {
      if (edge.from == edge.to || edge.from !in known || edge.to !in known) continue
      val key = edge.from to edge.to
      if (merged[key] != Provenance.FACT) merged[key] = edge.provenance
    }
    return merged.entries.map { FileLink(it.key.first, it.key.second, it.value) }
      .sortedWith(compareBy<FileLink>({ it.from }, { it.to }))
  }

  private fun reportOf(
    root: String,
    files: List<String>,
    links: List<FileLink>,
    communityOf: Map<String, Int>,
    degreeOf: Map<String, Int>,
    subsystemIds: Set<Int>,
  ): Report {
    val facts = links.count { it.provenance == Provenance.FACT }

    val hubs = files
      .filter { degreeOf.getValue(it) > 0 }
      .sortedWith(compareByDescending<String> { degreeOf.getValue(it) }.thenBy { it })
      .take(REPORT_HUBS)
      .map { HubFile(it, degreeOf.getValue(it), communityOf.getValue(it)) }

    // One candidate per pair of subsystems: the rarest bridges are the surprise, and a pair joined by forty links is not one
    val pairCount = HashMap<Pair<Int, Int>, Int>()
    val firstOfPair = LinkedHashMap<Pair<Int, Int>, FileLink>()
    for (link in links) {
      val a = communityOf.getValue(link.from)
      val b = communityOf.getValue(link.to)
      if (a == b) continue
      val key = if (a < b) a to b else b to a
      pairCount[key] = (pairCount[key] ?: 0) + 1
      firstOfPair.putIfAbsent(key, link)
    }
    val surprising = firstOfPair.entries
      .map { (key, link) ->
        SurprisingLink(
          link = link,
          fromSubsystem = communityOf.getValue(link.from),
          toSubsystem = communityOf.getValue(link.to),
          bridgeCount = pairCount.getValue(key),
          crossesFolders = topFolder(root, link.from) != topFolder(root, link.to),
        )
      }
      // A lone file is a community of one, and a link to it bridges nothing: only links between real subsystems count
      .filter { it.fromSubsystem in subsystemIds && it.toSubsystem in subsystemIds }
      .sortedWith(
        compareBy<SurprisingLink> { it.bridgeCount }
          .thenByDescending { it.crossesFolders }
          .thenBy { it.link.from }
          .thenBy { it.link.to })
      .take(REPORT_SURPRISING)

    return Report(
      fileCount = files.size,
      linkCount = links.size,
      facts = facts,
      guesses = links.size - facts,
      hubs = hubs,
      surprising = surprising,
      isolated = files.filter { degreeOf.getValue(it) == 0 },
    )
  }

  /** The folder every path lies under; empty when they share none */
  internal fun commonFolder(paths: List<String>): String {
    if (paths.isEmpty()) return ""
    var prefix = paths[0].split('/').dropLast(1)
    for (path in paths) {
      val folders = path.split('/').dropLast(1)
      var i = 0
      while (i < prefix.size && i < folders.size && prefix[i] == folders[i]) i++
      prefix = prefix.take(i)
    }
    return prefix.joinToString("/")
  }

  /**
   * The folder holding at least half of the files, the longest of them: where a subsystem mostly lives
   *
   * Its hub is a poor name: a subsystem of 1700 files is usually held together by a utility everyone imports
   * And «uri.ts» says nothing about which part of the project it is
   * The common folder of all files is just as poor: one stray file pulls it up to the project root
   */
  internal fun majorityFolder(paths: List<String>): String {
    val counts = LinkedHashMap<String, Int>()
    for (path in paths) {
      val segments = path.split('/').dropLast(1)
      for (depth in 1..segments.size) {
        val folder = segments.take(depth).joinToString("/")
        counts[folder] = (counts[folder] ?: 0) + 1
      }
    }
    var best = ""
    for ((folder, count) in counts) {
      if (count * 2 >= paths.size && folder.length > best.length) best = folder
    }
    return best
  }

  internal fun relativeTo(root: String, path: String): String =
    if (root.isNotEmpty() && path.startsWith("$root/")) path.substring(root.length + 1) else path

  internal fun basename(path: String): String = path.substring(path.lastIndexOf('/') + 1)

  private fun topFolder(root: String, path: String): String {
    val rest = relativeTo(root, path)
    val slash = rest.indexOf('/')
    return if (slash == -1) "" else rest.substring(0, slash)
  }
}
