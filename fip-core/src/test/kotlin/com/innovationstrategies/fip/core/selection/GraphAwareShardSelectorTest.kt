package com.innovationstrategies.fip.core.selection

import com.innovationstrategies.fip.core.domain.IdentityShard
import com.innovationstrategies.fip.core.domain.IdentityShardId
import com.innovationstrategies.fip.core.domain.IdentitySubjectId
import com.innovationstrategies.fip.core.domain.ReconstructionPolicy
import com.innovationstrategies.fip.core.domain.ReconstructionRequest
import com.innovationstrategies.fip.core.domain.ShardType
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GraphAwareShardSelectorTest {
    private val subjectId = IdentitySubjectId("subject-1")
    private val observedAt = Instant.parse("2026-04-20T00:00:00Z")

    @Test
    fun `linked shards can influence selection`() {
        val seed = shard("seed", ShardType.IDENTITY_CORE)
        val linked = shard("linked", ShardType.IDENTITY_PREFS)
        val unrelated = shard("unrelated", ShardType.BEHAVIOR_RULES)
        val selector = GraphAwareShardSelector(
            ShardGraphMap.of(
                nodes = listOf(
                    node(seed, priority = 10, linkedShardIds = setOf(linked.id)),
                    node(linked, priority = 50),
                    node(unrelated, priority = 100)
                )
            )
        )

        val plan = selector.select(
            policy = policy(explicitShardIds = setOf(seed.id)),
            shards = listOf(unrelated, linked, seed)
        )

        assertEquals(listOf(seed, linked), plan.selectedShards)
        assertEquals(unrelated.id, plan.skippedShards.single().shard.id)
        assertEquals(ShardSelectionSkipReason.NOT_IN_GRAPH_SELECTION, plan.skippedShards.single().reason)
        assertEquals(
            setOf(SelectionInfluence.EXPLICIT_SEED, SelectionInfluence.WEIGHTED_PRIORITY),
            plan.selectedShardDetails.first { it.shard.id == seed.id }.influences
        )
        assertEquals(
            setOf(SelectionInfluence.GRAPH_LINKED, SelectionInfluence.WEIGHTED_PRIORITY),
            plan.selectedShardDetails.first { it.shard.id == linked.id }.influences
        )
    }

    @Test
    fun `bounded graph expansion is enforced`() {
        val seed = shard("seed", ShardType.IDENTITY_CORE)
        val linkedHigh = shard("linked-high", ShardType.IDENTITY_PREFS)
        val linkedLow = shard("linked-low", ShardType.BEHAVIOR_RULES)
        val selector = GraphAwareShardSelector(
            ShardGraphMap.of(
                nodes = listOf(
                    node(seed, linkedShardIds = setOf(linkedHigh.id, linkedLow.id)),
                    node(linkedHigh, priority = 100),
                    node(linkedLow, priority = 10)
                )
            )
        )

        val plan = selector.select(
            policy = policy(maxShardCount = 2, explicitShardIds = setOf(seed.id)),
            shards = listOf(linkedLow, linkedHigh, seed)
        )

        assertEquals(listOf(seed, linkedHigh), plan.selectedShards)
        assertTrue(plan.wasBounded)
        assertEquals(linkedLow.id, plan.skippedShards.single().shard.id)
        assertEquals(ShardSelectionSkipReason.OUTSIDE_BOUND, plan.skippedShards.single().reason)
    }

    @Test
    fun `higher priority nodes are selected before lower priority nodes`() {
        val highPriority = shard("high-priority", ShardType.IDENTITY_PREFS)
        val lowPriority = shard("low-priority", ShardType.IDENTITY_CORE)
        val selector = GraphAwareShardSelector(
            ShardGraphMap.of(
                nodes = listOf(
                    node(highPriority, priority = 300),
                    node(lowPriority, priority = 10)
                )
            )
        )

        val plan = selector.select(
            policy = policy(maxShardCount = 1),
            shards = listOf(lowPriority, highPriority)
        )

        assertEquals(listOf(highPriority), plan.selectedShards)
        assertEquals(lowPriority.id, plan.skippedShards.single().shard.id)
        assertEquals(ShardSelectionSkipReason.OUTSIDE_BOUND, plan.skippedShards.single().reason)
    }

    @Test
    fun `stronger weighted links are preferred over weaker links`() {
        val seed = shard("seed", ShardType.IDENTITY_CORE)
        val strongLinkLowPriority = shard("strong-link-low-priority", ShardType.IDENTITY_PREFS)
        val weakLinkHighPriority = shard("weak-link-high-priority", ShardType.BEHAVIOR_RULES)
        val selector = GraphAwareShardSelector(
            ShardGraphMap.of(
                nodes = listOf(
                    node(seed, linkedShardIds = setOf(strongLinkLowPriority.id, weakLinkHighPriority.id)),
                    node(strongLinkLowPriority, priority = 10),
                    node(weakLinkHighPriority, priority = 500)
                ),
                links = listOf(
                    ShardGraphLink(fromShardId = seed.id, toShardId = strongLinkLowPriority.id, weight = 900),
                    ShardGraphLink(fromShardId = seed.id, toShardId = weakLinkHighPriority.id, weight = 25)
                )
            )
        )

        val plan = selector.select(
            policy = policy(maxShardCount = 2, explicitShardIds = setOf(seed.id)),
            shards = listOf(weakLinkHighPriority, strongLinkLowPriority, seed)
        )

        assertEquals(listOf(seed, strongLinkLowPriority), plan.selectedShards)
        assertEquals(weakLinkHighPriority.id, plan.skippedShards.single().shard.id)
        assertEquals(ShardSelectionSkipReason.OUTSIDE_BOUND, plan.skippedShards.single().reason)
    }

    @Test
    fun `excluded linked shard types are still respected`() {
        val seed = shard("seed", ShardType.IDENTITY_CORE)
        val excluded = shard("excluded", ShardType.MEMORY_EPHEMERAL)
        val selector = GraphAwareShardSelector(
            ShardGraphMap.of(
                nodes = listOf(
                    node(seed, linkedShardIds = setOf(excluded.id)),
                    node(excluded)
                )
            )
        )

        val plan = selector.select(
            policy = policy(
                explicitShardIds = setOf(seed.id),
                allowedShardTypes = ReconstructionRequest.DEFAULT_ALLOWED_SHARD_TYPES + ShardType.MEMORY_EPHEMERAL,
                excludedShardTypes = setOf(ShardType.MEMORY_EPHEMERAL)
            ),
            shards = listOf(seed, excluded)
        )

        assertEquals(listOf(seed), plan.selectedShards)
        assertEquals(excluded.id, plan.skippedShards.single().shard.id)
        assertEquals(ShardSelectionSkipReason.EXPLICITLY_EXCLUDED, plan.skippedShards.single().reason)
    }

    @Test
    fun `denied system meta linked shards are still respected`() {
        val seed = shard("seed", ShardType.IDENTITY_CORE)
        val systemMeta = shard("system", ShardType.SYSTEM_META)
        val selector = GraphAwareShardSelector(
            ShardGraphMap.of(
                nodes = listOf(
                    node(seed, linkedShardIds = setOf(systemMeta.id)),
                    node(systemMeta)
                )
            )
        )

        val plan = selector.select(
            policy = policy(
                explicitShardIds = setOf(seed.id)
            ),
            shards = listOf(seed, systemMeta)
        )

        assertEquals(listOf(seed), plan.selectedShards)
        assertEquals(systemMeta.id, plan.skippedShards.single().shard.id)
        assertEquals(ShardSelectionSkipReason.SYSTEM_META_DENIED, plan.skippedShards.single().reason)
    }

    @Test
    fun `explicit shard ids keep highest priority over linked shards`() {
        val explicit = shard("z-explicit", ShardType.IDENTITY_PREFS)
        val linkedHighPriority = shard("a-linked", ShardType.IDENTITY_CORE)
        val selector = GraphAwareShardSelector(
            ShardGraphMap.of(
                nodes = listOf(
                    node(explicit, priority = 1, linkedShardIds = setOf(linkedHighPriority.id)),
                    node(linkedHighPriority, priority = 100)
                )
            )
        )

        val plan = selector.select(
            policy = policy(explicitShardIds = setOf(explicit.id)),
            shards = listOf(linkedHighPriority, explicit)
        )

        assertEquals(listOf(explicit, linkedHighPriority), plan.selectedShards)
    }

    private fun policy(
        allowedShardTypes: Set<ShardType> = ReconstructionRequest.DEFAULT_ALLOWED_SHARD_TYPES,
        excludedShardTypes: Set<ShardType> = ReconstructionPolicy.DEFAULT_EXCLUDED_SHARD_TYPES,
        maxShardCount: Int = 10,
        explicitShardIds: Set<IdentityShardId> = emptySet()
    ): ShardSelectionPolicy =
        ShardSelectionPolicy(
            subjectId = subjectId,
            allowedShardTypes = allowedShardTypes,
            excludedShardTypes = excludedShardTypes,
            maxShardCount = maxShardCount,
            explicitShardIds = explicitShardIds
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
