package com.innovationstrategies.fip.storage.file

import com.innovationstrategies.fip.core.domain.IdentityShardId
import com.innovationstrategies.fip.core.domain.IdentitySubjectId
import com.innovationstrategies.fip.core.domain.ShardType
import com.innovationstrategies.fip.core.selection.ShardGraphLink
import com.innovationstrategies.fip.core.selection.ShardGraphMap
import com.innovationstrategies.fip.core.selection.ShardGraphNode
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileShardGraphMapRepositoryTest {
    private val subjectId = IdentitySubjectId("subject-1")

    @Test
    fun `save and load graph map for subject`() {
        val repository = repository()
        val graphMap = graphMap()

        repository.save(subjectId, graphMap)

        assertEquals(graphMap, repository.load(subjectId))
    }

    @Test
    fun `load returns null for missing subject graph map`() {
        val repository = repository()

        assertNull(repository.load(subjectId))
    }

    @Test
    fun `linked shard metadata round trips through storage`() {
        val repository = repository()
        val graphMap = graphMap()

        repository.save(subjectId, graphMap)

        val loaded = assertNotNull(repository.load(subjectId))
        assertEquals(
            setOf(IdentityShardId("linked-shard")),
            loaded.nodeFor(IdentityShardId("seed-shard"))?.linkedShardIds
        )
        assertEquals(
            ShardGraphLink(
                fromShardId = IdentityShardId("seed-shard"),
                toShardId = IdentityShardId("linked-shard"),
                weight = 75
            ),
            loaded.links.single()
        )
    }

    @Test
    fun `replace graph map for subject overwrites previous graph map`() {
        val repository = repository()
        repository.save(subjectId, graphMap(seedPriority = 100))

        val replacement = graphMap(seedPriority = 250)
        repository.replaceForSubject(subjectId, replacement)

        assertEquals(replacement, repository.load(subjectId))
    }

    @Test
    fun `stored graph map file uses explicit fields`() {
        val root = Files.createTempDirectory("fip-graph-map-test")
        val repository = FileShardGraphMapRepository(root)

        repository.save(subjectId, graphMap())

        val graphFile = assertNotNull(
            Files.list(root).use { paths ->
                paths.filter { it.fileName.toString().endsWith(".graph.properties") }.findFirst().orElse(null)
            }
        )
        val contents = graphFile.readText()
        assertTrue(contents.contains("formatVersion=1"))
        assertTrue(contents.contains("subjectId=subject-1"))
        assertTrue(contents.contains("nodeCount=2"))
        assertTrue(contents.contains("node.0.shardId=linked-shard"))
        assertTrue(contents.contains("node.1.shardId=seed-shard"))
        assertTrue(contents.contains("node.1.linkedShardId.0=linked-shard"))
        assertTrue(contents.contains("linkCount=1"))
        assertTrue(contents.contains("link.0.fromShardId=seed-shard"))
        assertTrue(contents.contains("link.0.toShardId=linked-shard"))
        assertTrue(contents.contains("link.0.weight=75"))
    }

    @Test
    fun `malformed graph map record fails with clear validation error`() {
        val root = Files.createTempDirectory("fip-graph-map-test")
        writeGraphFile(
            root.resolve("c3ViamVjdC0x.graph.properties"),
            """
            formatVersion=1
            subjectId=subject-1
            nodeCount=1
            node.0.shardId=seed-shard
            node.0.subjectId=subject-1
            node.0.shardType=IDENTITY_CORE
            node.0.priority=100
            node.0.linkedShardIdCount=0
            linkCount=1
            link.0.fromShardId=seed-shard
            link.0.toShardId=missing-target
            link.0.weight=75
            """.trimIndent()
        )

        val error = assertFailsWith<IllegalArgumentException> {
            FileShardGraphMapRepository(root).load(subjectId)
        }

        assertTrue(error.message.orEmpty().contains("target must exist"))
    }

    private fun repository(): FileShardGraphMapRepository =
        FileShardGraphMapRepository(Files.createTempDirectory("fip-graph-map-test"))

    private fun graphMap(seedPriority: Int = 100): ShardGraphMap =
        ShardGraphMap.of(
            nodes = listOf(
                node(
                    id = "seed-shard",
                    type = ShardType.IDENTITY_CORE,
                    priority = seedPriority,
                    linkedShardIds = setOf(IdentityShardId("linked-shard"))
                ),
                node(
                    id = "linked-shard",
                    type = ShardType.IDENTITY_PREFS,
                    priority = 80
                )
            ),
            links = listOf(
                ShardGraphLink(
                    fromShardId = IdentityShardId("seed-shard"),
                    toShardId = IdentityShardId("linked-shard"),
                    weight = 75
                )
            )
        )

    private fun node(
        id: String,
        type: ShardType,
        priority: Int,
        linkedShardIds: Set<IdentityShardId> = emptySet()
    ): ShardGraphNode =
        ShardGraphNode(
            shardId = IdentityShardId(id),
            subjectId = subjectId,
            shardType = type,
            priority = priority,
            linkedShardIds = linkedShardIds
        )

    private fun writeGraphFile(path: Path, contents: String) {
        path.writeText(contents)
    }
}
