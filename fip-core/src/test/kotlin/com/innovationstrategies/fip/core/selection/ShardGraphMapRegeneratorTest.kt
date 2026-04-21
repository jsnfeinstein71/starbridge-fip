package com.innovationstrategies.fip.core.selection

import com.innovationstrategies.fip.core.domain.IdentityShard
import com.innovationstrategies.fip.core.domain.IdentityShardId
import com.innovationstrategies.fip.core.domain.IdentitySubjectId
import com.innovationstrategies.fip.core.domain.ReconstructionRequest
import com.innovationstrategies.fip.core.domain.ShardType
import com.innovationstrategies.fip.core.reconstruction.FipReconstructionEngine
import com.innovationstrategies.fip.core.writeback.FipWriteBackEngine
import com.innovationstrategies.fip.core.writeback.IdentityUpdateRequest
import com.innovationstrategies.fip.core.writeback.ShardIdGenerator
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShardGraphMapRegeneratorTest {
    private val subjectId = IdentitySubjectId("subject-1")
    private val otherSubjectId = IdentitySubjectId("subject-2")
    private val observedAt = Instant.parse("2026-04-20T00:00:00Z")
    private val regenerator = ShardGraphMapRegenerator()

    @Test
    fun `graph map can be regenerated from subject shards`() {
        val core = shard("core", subjectId, ShardType.IDENTITY_CORE)
        val prefs = shard("prefs", subjectId, ShardType.IDENTITY_PREFS)
        val other = shard("other", otherSubjectId, ShardType.IDENTITY_CORE)

        val graphMap = regenerator.regenerate(subjectId, listOf(core, prefs, other))

        assertEquals(setOf(core.id, prefs.id), graphMap.nodes.keys)
        assertNull(graphMap.nodeFor(other.id))
        assertEquals(400, graphMap.nodeFor(core.id)?.priority)
        assertEquals(300, graphMap.nodeFor(prefs.id)?.priority)
        assertTrue(prefs.id in graphMap.nodeFor(core.id)?.linkedShardIds.orEmpty())
        assertTrue(ShardGraphLink(core.id, prefs.id, weight = 300) in graphMap.links)
    }

    @Test
    fun `regenerated map can be used by graph aware reconstruction`() {
        val core = shard("core", subjectId, ShardType.IDENTITY_CORE)
        val prefs = shard("prefs", subjectId, ShardType.IDENTITY_PREFS)
        val graphMap = regenerator.regenerate(subjectId, listOf(core, prefs))
        val engine = FipReconstructionEngine(
            clock = Clock.fixed(Instant.parse("2026-04-20T12:00:00Z"), ZoneOffset.UTC),
            shardSelector = GraphAwareShardSelector(graphMap)
        )

        val result = engine.reconstruct(
            request = ReconstructionRequest(
                requestId = "request-1",
                subjectId = subjectId,
                taskType = "regenerated-graph-test",
                surface = "core-test",
                maxShardCount = 10,
                explicitShardIds = setOf(core.id)
            ),
            shards = listOf(core, prefs)
        )

        assertEquals(listOf(core, prefs), result.includedShards)
    }

    @Test
    fun `refresh preserves valid priority and removes missing shards after write-back changes`() {
        val prior = shard("prior-core", subjectId, ShardType.IDENTITY_CORE)
        val prefs = shard("prefs", subjectId, ShardType.IDENTITY_PREFS)
        val existing = ShardGraphMap.of(
            nodes = listOf(
                ShardGraphNode(prior.id, subjectId, prior.type, priority = 999, linkedShardIds = setOf(prefs.id)),
                ShardGraphNode(prefs.id, subjectId, prefs.type, priority = 111)
            ),
            links = listOf(ShardGraphLink(prior.id, prefs.id, weight = 777))
        )
        val replacement = writeBackReplacement(prior)

        val refreshed = regenerator.regenerate(subjectId, listOf(replacement, prefs), existing)

        assertNull(refreshed.nodeFor(prior.id))
        assertTrue(replacement.id in refreshed.nodes)
        assertTrue(prefs.id in refreshed.nodes)
        assertTrue(prefs.id in refreshed.nodeFor(replacement.id)?.linkedShardIds.orEmpty())
        assertTrue(refreshed.links.any { it.fromShardId == replacement.id && it.toShardId == prefs.id })
    }

    private fun writeBackReplacement(prior: IdentityShard): IdentityShard {
        val engine = FipWriteBackEngine(
            clock = Clock.fixed(Instant.parse("2026-04-20T12:00:00Z"), ZoneOffset.UTC),
            shardIdGenerator = ShardIdGenerator { IdentityShardId("replacement-core") }
        )
        return engine.writeBack(
            request = IdentityUpdateRequest(
                requestId = "request-1",
                subjectId = subjectId,
                taskType = "refresh-test",
                surface = "core-test",
                shardType = prior.type,
                payload = "updated payload",
                source = "test-source",
                replaceShardIds = setOf(prior.id)
            ),
            existingShards = listOf(prior)
        ).plan.replacementShards.single()
    }

    private fun shard(
        id: String,
        subjectId: IdentitySubjectId,
        type: ShardType
    ): IdentityShard =
        IdentityShard(
            id = IdentityShardId(id),
            subjectId = subjectId,
            type = type,
            version = 1,
            payload = "payload for $id",
            source = "test-source",
            observedAt = observedAt
        )
}
