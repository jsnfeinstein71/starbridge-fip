package com.innovationstrategies.fip.storage.file

import com.innovationstrategies.fip.core.domain.IdentityShard
import com.innovationstrategies.fip.core.domain.IdentityShardId
import com.innovationstrategies.fip.core.domain.IdentitySubjectId
import com.innovationstrategies.fip.core.domain.ShardType
import java.nio.file.Files
import java.time.Instant
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileIdentityShardRepositoryTest {
    private val subjectId = IdentitySubjectId("subject-1")
    private val otherSubjectId = IdentitySubjectId("subject-2")
    private val observedAt = Instant.parse("2026-04-20T00:00:00Z")

    @Test
    fun `save and load shard by id`() {
        val repository = repository()
        val shard = shard("shard-1", subjectId, tags = setOf("stable", "core"))

        repository.save(shard)

        assertEquals(shard, repository.load(shard.id))
    }

    @Test
    fun `load returns null for missing shard`() {
        val repository = repository()

        assertNull(repository.load(IdentityShardId("missing")))
    }

    @Test
    fun `list shards for subject returns only matching subject`() {
        val repository = repository()
        val first = shard("a-shard", subjectId, ShardType.IDENTITY_CORE)
        val second = shard("b-shard", subjectId, ShardType.IDENTITY_PREFS)
        val other = shard("other-shard", otherSubjectId, ShardType.IDENTITY_CORE)

        repository.save(second)
        repository.save(other)
        repository.save(first)

        assertEquals(listOf(first, second), repository.listForSubject(subjectId))
    }

    @Test
    fun `delete shard by id removes stored record`() {
        val repository = repository()
        val shard = shard("shard-1", subjectId)
        repository.save(shard)

        assertTrue(repository.delete(shard.id))

        assertNull(repository.load(shard.id))
        assertFalse(repository.delete(shard.id))
    }

    @Test
    fun `stored shard file uses explicit fields`() {
        val root = Files.createTempDirectory("fip-shards-test")
        val repository = FileIdentityShardRepository(root)
        val shard = shard("shard-1", subjectId, tags = setOf("core"))

        repository.save(shard)

        val shardFile = assertNotNull(
            Files.list(root).use { paths ->
                paths.filter { it.fileName.toString().endsWith(".shard.properties") }.findFirst().orElse(null)
            }
        )
        val contents = shardFile.readText()
        assertTrue(contents.contains("formatVersion=1"))
        assertTrue(contents.contains("id=shard-1"))
        assertTrue(contents.contains("subjectId=subject-1"))
        assertTrue(contents.contains("type=IDENTITY_CORE"))
        assertTrue(contents.contains("tagCount=1"))
        assertTrue(contents.contains("tag.0=core"))
    }

    private fun repository(): FileIdentityShardRepository =
        FileIdentityShardRepository(Files.createTempDirectory("fip-shards-test"))

    private fun shard(
        id: String,
        subjectId: IdentitySubjectId,
        type: ShardType = ShardType.IDENTITY_CORE,
        tags: Set<String> = emptySet()
    ): IdentityShard =
        IdentityShard(
            id = IdentityShardId(id),
            subjectId = subjectId,
            type = type,
            version = 1,
            payload = "payload for $id",
            source = "test-source",
            observedAt = observedAt,
            tags = tags
        )
}
