package com.innovationstrategies.fip.core.writeback

import com.innovationstrategies.fip.core.domain.IdentityShard
import com.innovationstrategies.fip.core.domain.IdentityShardId
import com.innovationstrategies.fip.core.domain.IdentitySubjectId
import com.innovationstrategies.fip.core.domain.ShardType
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FipWriteBackEngineTest {
    private val subjectId = IdentitySubjectId("subject-1")
    private val otherSubjectId = IdentitySubjectId("subject-2")
    private val observedAt = Instant.parse("2026-04-20T00:00:00Z")
    private val decidedAt = Instant.parse("2026-04-20T12:00:00Z")
    private val engine = FipWriteBackEngine(
        clock = Clock.fixed(decidedAt, ZoneOffset.UTC),
        shardIdGenerator = ShardIdGenerator { IdentityShardId("generated-replacement-shard") }
    )

    @Test
    fun `write-back produces replacement shards`() {
        val prior = shard("prior-core", subjectId, ShardType.IDENTITY_CORE, version = 2)

        val result = engine.writeBack(
            request = request(payload = "updated core payload"),
            existingShards = listOf(prior)
        )

        val replacement = result.plan.replacementShards.single()
        assertEquals(IdentityShardId("generated-replacement-shard"), replacement.id)
        assertEquals(subjectId, replacement.subjectId)
        assertEquals(ShardType.IDENTITY_CORE, replacement.type)
        assertEquals(3, replacement.version)
        assertEquals("updated core payload", replacement.payload)
        assertEquals(setOf("write-back"), replacement.tags)
        assertTrue(replacement.id != prior.id)
    }

    @Test
    fun `shard id generation is injectable and testable`() {
        val prior = shard("prior-core", subjectId, ShardType.IDENTITY_CORE)
        val generatedShardId = IdentityShardId("deterministic-test-shard")
        val deterministicEngine = FipWriteBackEngine(
            clock = Clock.fixed(decidedAt, ZoneOffset.UTC),
            shardIdGenerator = ShardIdGenerator { generatedShardId }
        )

        val result = deterministicEngine.writeBack(
            request = request(),
            existingShards = listOf(prior)
        )

        assertEquals(generatedShardId, result.plan.replacementShards.single().id)
        assertEquals(setOf(generatedShardId), result.createdShardIds)
    }

    @Test
    fun `prior shard ids can be marked for deletion`() {
        val prior = shard("prior-core", subjectId, ShardType.IDENTITY_CORE)

        val result = engine.writeBack(
            request = request(replaceShardIds = setOf(prior.id)),
            existingShards = listOf(prior)
        )

        assertEquals(setOf(prior.id), result.plan.selectedShardIds)
        assertEquals(setOf(prior.id), result.plan.shardIdsToDelete)
        assertEquals(setOf(prior.id), result.deletedShardIds)
    }

    @Test
    fun `subject consistency is enforced for explicit replacement ids`() {
        val otherSubjectShard = shard("other-core", otherSubjectId, ShardType.IDENTITY_CORE)

        val error = assertFailsWith<IllegalArgumentException> {
            engine.writeBack(
                request = request(replaceShardIds = setOf(otherSubjectShard.id)),
                existingShards = listOf(otherSubjectShard)
            )
        }

        assertTrue(error.message.orEmpty().contains("do not match subject"))
    }

    @Test
    fun `write-back result clearly describes created replaced and deleted shard ids`() {
        val prior = shard("prior-core", subjectId, ShardType.IDENTITY_CORE)

        val result = engine.writeBack(
            request = request(replaceShardIds = setOf(prior.id)),
            existingShards = listOf(prior)
        )

        assertEquals("request-1", result.requestId)
        assertEquals(subjectId, result.subjectId)
        assertEquals(setOf(IdentityShardId("generated-replacement-shard")), result.createdShardIds)
        assertEquals(setOf(prior.id), result.replacedShardIds)
        assertEquals(setOf(prior.id), result.deletedShardIds)
        assertEquals(decidedAt, result.decidedAt)
    }

    @Test
    fun `write-back bounds selected prior shards`() {
        val first = shard("a-prior", subjectId, ShardType.IDENTITY_CORE)
        val second = shard("b-prior", subjectId, ShardType.IDENTITY_CORE)

        val result = engine.writeBack(
            request = request(maxReplacementSourceShards = 1),
            existingShards = listOf(second, first)
        )

        assertEquals(setOf(first.id), result.replacedShardIds)
        assertEquals(setOf(first.id), result.deletedShardIds)
        assertTrue(result.wasBounded)
    }

    private fun request(
        payload: String = "updated payload",
        replaceShardIds: Set<IdentityShardId> = emptySet(),
        maxReplacementSourceShards: Int = 16
    ): IdentityUpdateRequest =
        IdentityUpdateRequest(
            requestId = "request-1",
            subjectId = subjectId,
            taskType = "write-back-test",
            surface = "core-test",
            shardType = ShardType.IDENTITY_CORE,
            payload = payload,
            source = "test-source",
            replaceShardIds = replaceShardIds,
            tags = setOf("write-back"),
            maxReplacementSourceShards = maxReplacementSourceShards,
            maxPayloadBytes = 1024
        )

    private fun shard(
        id: String,
        subjectId: IdentitySubjectId,
        type: ShardType,
        version: Int = 1
    ): IdentityShard =
        IdentityShard(
            id = IdentityShardId(id),
            subjectId = subjectId,
            type = type,
            version = version,
            payload = "payload for $id",
            source = "test-source",
            observedAt = observedAt
        )
}
