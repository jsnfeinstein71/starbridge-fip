package com.innovationstrategies.fip.core.writeback

import com.innovationstrategies.fip.core.domain.IdentityShard
import com.innovationstrategies.fip.core.domain.IdentityShardId
import com.innovationstrategies.fip.core.domain.IdentitySubjectId
import com.innovationstrategies.fip.core.domain.ReconstructionRequest
import com.innovationstrategies.fip.core.domain.ShardType
import com.innovationstrategies.fip.core.reconstruction.FipReconstructionEngine
import com.innovationstrategies.fip.core.selection.GraphAwareShardSelector
import com.innovationstrategies.fip.core.selection.ShardGraphLink
import com.innovationstrategies.fip.core.selection.ShardGraphMap
import com.innovationstrategies.fip.core.selection.ShardGraphNode
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShardGraphMapWriteBackUpdaterTest {
    private val subjectId = IdentitySubjectId("subject-1")
    private val observedAt = Instant.parse("2026-04-20T00:00:00Z")
    private val decidedAt = Instant.parse("2026-04-20T12:00:00Z")
    private val updater = ShardGraphMapWriteBackUpdater()

    @Test
    fun `write-back updates graph map when one exists`() {
        val prior = shard("prior", ShardType.IDENTITY_CORE)
        val linked = shard("linked", ShardType.IDENTITY_PREFS)
        val graphMap = graphMap(prior, linked)
        val result = writeBackResult(prior)

        val updated = updater.update(graphMap, result)

        assertNull(updated.nodeFor(prior.id))
        assertTrue(result.createdShardIds.single() in updated.nodes)
        assertEquals(setOf(linked.id), updated.nodeFor(result.createdShardIds.single())?.linkedShardIds)
    }

    @Test
    fun `deleted shard ids are removed and inbound links remap to replacement`() {
        val prior = shard("prior", ShardType.IDENTITY_CORE)
        val linked = shard("linked", ShardType.IDENTITY_PREFS)
        val graphMap = graphMap(prior, linked)
        val result = writeBackResult(prior)
        val replacementId = result.createdShardIds.single()

        val updated = updater.update(graphMap, result)

        assertNull(updated.nodeFor(prior.id))
        assertEquals(setOf(replacementId), updated.nodeFor(linked.id)?.linkedShardIds)
        assertTrue(
            ShardGraphLink(fromShardId = linked.id, toShardId = replacementId, weight = 50) in updated.links
        )
        assertTrue(
            ShardGraphLink(fromShardId = replacementId, toShardId = linked.id, weight = 75) in updated.links
        )
    }

    @Test
    fun `fallback write-back graph update is no-op when no shard ids are replaced`() {
        val prior = shard("prior", ShardType.IDENTITY_CORE)
        val linked = shard("linked", ShardType.IDENTITY_PREFS)
        val graphMap = graphMap(prior, linked)
        val replacement = shard("replacement", ShardType.IDENTITY_CORE)
        val result = WriteBackResult(
            requestId = "request-1",
            subjectId = subjectId,
            createdShardIds = setOf(replacement.id),
            replacedShardIds = emptySet(),
            deletedShardIds = emptySet(),
            plan = ReShardPlan(
                requestId = "request-1",
                subjectId = subjectId,
                selectedShardIds = emptySet(),
                shardIdsToDelete = emptySet(),
                replacementShards = listOf(replacement),
                wasBounded = false
            ),
            wasBounded = false,
            decidedAt = decidedAt
        )

        assertEquals(graphMap, updater.update(graphMap, result))
    }

    @Test
    fun `graph-aware reconstruction can operate after write-back graph update`() {
        val prior = shard("prior", ShardType.IDENTITY_CORE)
        val linked = shard("linked", ShardType.IDENTITY_PREFS)
        val graphMap = graphMap(prior, linked)
        val result = writeBackResult(prior)
        val replacement = result.plan.replacementShards.single()
        val updated = updater.update(graphMap, result)
        val engine = FipReconstructionEngine(
            clock = Clock.fixed(decidedAt, ZoneOffset.UTC),
            shardSelector = GraphAwareShardSelector(updated)
        )

        val reconstruction = engine.reconstruct(
            request = ReconstructionRequest(
                requestId = "recon-1",
                subjectId = subjectId,
                taskType = "write-back-graph-test",
                surface = "core-test",
                maxShardCount = 10,
                explicitShardIds = setOf(replacement.id)
            ),
            shards = listOf(replacement, linked)
        )

        assertEquals(listOf(replacement, linked), reconstruction.includedShards)
    }

    private fun writeBackResult(prior: IdentityShard): WriteBackResult {
        val engine = FipWriteBackEngine(
            clock = Clock.fixed(decidedAt, ZoneOffset.UTC),
            shardIdGenerator = ShardIdGenerator { IdentityShardId("replacement") }
        )
        return engine.writeBack(
            request = IdentityUpdateRequest(
                requestId = "request-1",
                subjectId = subjectId,
                taskType = "write-back-test",
                surface = "core-test",
                shardType = prior.type,
                payload = "updated payload",
                source = "test-source",
                replaceShardIds = setOf(prior.id),
                maxPayloadBytes = 1024
            ),
            existingShards = listOf(prior)
        )
    }

    private fun graphMap(prior: IdentityShard, linked: IdentityShard): ShardGraphMap =
        ShardGraphMap.of(
            nodes = listOf(
                node(prior, priority = 200, linkedShardIds = setOf(linked.id)),
                node(linked, linkedShardIds = setOf(prior.id))
            ),
            links = listOf(
                ShardGraphLink(fromShardId = prior.id, toShardId = linked.id, weight = 75),
                ShardGraphLink(fromShardId = linked.id, toShardId = prior.id, weight = 50)
            )
        )

    private fun node(
        shard: IdentityShard,
        priority: Int = ShardGraphNode.DEFAULT_PRIORITY,
        linkedShardIds: Set<IdentityShardId> = emptySet()
    ): ShardGraphNode =
        ShardGraphNode(
            shardId = shard.id,
            subjectId = shard.subjectId,
            shardType = shard.type,
            priority = priority,
            linkedShardIds = linkedShardIds
        )

    private fun shard(id: String, type: ShardType): IdentityShard =
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
