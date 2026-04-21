package com.innovationstrategies.fip.core.reconstruction

import com.innovationstrategies.fip.core.domain.IdentityShard
import com.innovationstrategies.fip.core.domain.IdentityShardId
import com.innovationstrategies.fip.core.domain.IdentitySubjectId
import com.innovationstrategies.fip.core.domain.ProvenanceReason
import com.innovationstrategies.fip.core.domain.ReconstructionPolicy
import com.innovationstrategies.fip.core.domain.ReconstructionRequest
import com.innovationstrategies.fip.core.domain.ShardType
import com.innovationstrategies.fip.core.selection.SelectionInfluence
import com.innovationstrategies.fip.core.selection.RepositoryBackedGraphAwareShardSelector
import com.innovationstrategies.fip.core.selection.ShardGraphMap
import com.innovationstrategies.fip.core.selection.ShardGraphNode
import com.innovationstrategies.fip.core.storage.ShardGraphMapRepository
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RepositoryBackedGraphAwareReconstructionTest {
    private val subjectId = IdentitySubjectId("subject-1")
    private val observedAt = Instant.parse("2026-04-20T00:00:00Z")
    private val clock = Clock.fixed(Instant.parse("2026-04-20T12:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `reconstruction can use stored graph map when present`() {
        val seed = shard("seed", ShardType.IDENTITY_CORE)
        val linked = shard("linked", ShardType.IDENTITY_PREFS)
        val unrelated = shard("unrelated", ShardType.BEHAVIOR_RULES)
        val engine = engineWith(graphMap(seed, linked, unrelated))

        val result = engine.reconstruct(
            request(explicitShardIds = setOf(seed.id)),
            listOf(unrelated, linked, seed)
        )

        assertEquals(listOf(seed, linked), result.includedShards)
        assertEquals(setOf(unrelated.id), result.excludedShardIds)
        assertTrue(result.provenance.any { it.shardId == unrelated.id && it.reason == ProvenanceReason.NOT_IN_GRAPH_SELECTION })
        assertEquals(
            setOf(SelectionInfluence.EXPLICIT_SEED, SelectionInfluence.WEIGHTED_PRIORITY),
            result.selectedShardReports.first { it.shard.id == seed.id }.influences
        )
        assertEquals(
            setOf(SelectionInfluence.GRAPH_LINKED, SelectionInfluence.WEIGHTED_PRIORITY),
            result.selectedShardReports.first { it.shard.id == linked.id }.influences
        )
    }

    @Test
    fun `fallback selection still works when no graph map exists`() {
        val explicit = shard("z-explicit", ShardType.IDENTITY_PREFS)
        val defaultOrdered = shard("a-default", ShardType.IDENTITY_CORE)
        val engine = engineWith(null)

        val result = engine.reconstruct(
            request(maxShardCount = 1, explicitShardIds = setOf(explicit.id)),
            listOf(defaultOrdered, explicit)
        )

        assertEquals(listOf(explicit), result.includedShards)
        assertEquals(setOf(defaultOrdered.id), result.excludedShardIds)
        assertTrue(result.wasBounded)
        assertFalse(result.provenance.any { it.reason == ProvenanceReason.TYPE_NOT_ALLOWED })
    }

    @Test
    fun `explicit shard ids still outrank graph expansion`() {
        val explicit = shard("z-explicit", ShardType.IDENTITY_PREFS)
        val linkedHighPriority = shard("a-linked", ShardType.IDENTITY_CORE)
        val engine = engineWith(
            ShardGraphMap.of(
                nodes = listOf(
                    node(explicit, priority = 1, linkedShardIds = setOf(linkedHighPriority.id)),
                    node(linkedHighPriority, priority = 500)
                )
            )
        )

        val result = engine.reconstruct(
            request(maxShardCount = 1, explicitShardIds = setOf(explicit.id)),
            listOf(linkedHighPriority, explicit)
        )

        assertEquals(listOf(explicit), result.includedShards)
        assertEquals(setOf(linkedHighPriority.id), result.excludedShardIds)
        assertEquals(ProvenanceReason.OUTSIDE_BOUND, result.provenance.first().reason)
        assertEquals(
            setOf(SelectionInfluence.EXPLICIT_SEED, SelectionInfluence.WEIGHTED_PRIORITY),
            result.selectedShardReports.single().influences
        )
        assertEquals(
            setOf(SelectionInfluence.GRAPH_LINKED, SelectionInfluence.WEIGHTED_PRIORITY),
            result.skippedAtSelection.single().influences
        )
    }

    @Test
    fun `excluded linked shard types are respected during reconstruction`() {
        val seed = shard("seed", ShardType.IDENTITY_CORE)
        val excluded = shard("excluded", ShardType.MEMORY_EPHEMERAL)
        val engine = engineWith(
            ShardGraphMap.of(
                nodes = listOf(
                    node(seed, linkedShardIds = setOf(excluded.id)),
                    node(excluded)
                )
            )
        )
        val request = request(
            explicitShardIds = setOf(seed.id),
            allowedShardTypes = ReconstructionRequest.DEFAULT_ALLOWED_SHARD_TYPES + ShardType.MEMORY_EPHEMERAL
        )
        val policy = ReconstructionPolicy(
            allowedShardTypes = request.allowedShardTypes,
            excludedShardTypes = setOf(ShardType.MEMORY_EPHEMERAL),
            allowSystemMeta = false
        )

        val result = engine.reconstruct(request, listOf(seed, excluded), policy)

        assertEquals(listOf(seed), result.includedShards)
        assertEquals(setOf(excluded.id), result.excludedShardIds)
        assertTrue(result.provenance.any { it.shardId == excluded.id && it.reason == ProvenanceReason.EXPLICITLY_EXCLUDED })
    }

    @Test
    fun `denied linked system meta shards are respected during reconstruction`() {
        val seed = shard("seed", ShardType.IDENTITY_CORE)
        val systemMeta = shard("system", ShardType.SYSTEM_META)
        val engine = engineWith(
            ShardGraphMap.of(
                nodes = listOf(
                    node(seed, linkedShardIds = setOf(systemMeta.id)),
                    node(systemMeta)
                )
            )
        )

        val result = engine.reconstruct(
            request(explicitShardIds = setOf(seed.id)),
            listOf(seed, systemMeta)
        )

        assertEquals(listOf(seed), result.includedShards)
        assertEquals(setOf(systemMeta.id), result.excludedShardIds)
        assertTrue(result.provenance.any { it.shardId == systemMeta.id && it.reason == ProvenanceReason.SYSTEM_META_DENIED })
    }

    private fun engineWith(graphMap: ShardGraphMap?): FipReconstructionEngine =
        FipReconstructionEngine(
            clock = clock,
            shardSelector = RepositoryBackedGraphAwareShardSelector(
                graphMapRepository = InMemoryShardGraphMapRepository(graphMap)
            )
        )

    private fun request(
        maxShardCount: Int = 10,
        explicitShardIds: Set<IdentityShardId> = emptySet(),
        allowedShardTypes: Set<ShardType> = ReconstructionRequest.DEFAULT_ALLOWED_SHARD_TYPES
    ): ReconstructionRequest =
        ReconstructionRequest(
            requestId = "request-1",
            subjectId = subjectId,
            taskType = "graph-aware-reconstruction-test",
            surface = "core-test",
            maxShardCount = maxShardCount,
            explicitShardIds = explicitShardIds,
            allowedShardTypes = allowedShardTypes
        )

    private fun graphMap(
        seed: IdentityShard,
        linked: IdentityShard,
        unrelated: IdentityShard
    ): ShardGraphMap =
        ShardGraphMap.of(
            nodes = listOf(
                node(seed, linkedShardIds = setOf(linked.id)),
                node(linked, priority = 50),
                node(unrelated, priority = 100)
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

    private inner class InMemoryShardGraphMapRepository(
        private val graphMap: ShardGraphMap?
    ) : ShardGraphMapRepository {
        override fun save(subjectId: IdentitySubjectId, graphMap: ShardGraphMap): ShardGraphMap = graphMap

        override fun load(subjectId: IdentitySubjectId): ShardGraphMap? =
            graphMap
    }
}
