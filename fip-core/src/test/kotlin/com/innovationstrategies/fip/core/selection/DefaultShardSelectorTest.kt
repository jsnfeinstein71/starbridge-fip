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

class DefaultShardSelectorTest {
    private val subjectId = IdentitySubjectId("subject-1")
    private val otherSubjectId = IdentitySubjectId("subject-2")
    private val observedAt = Instant.parse("2026-04-20T00:00:00Z")
    private val selector = DefaultShardSelector()

    @Test
    fun `explicit shard ids are prioritized`() {
        val ordinary = shard("a-ordinary", ShardType.IDENTITY_CORE)
        val explicit = shard("z-explicit", ShardType.IDENTITY_PREFS)

        val plan = selector.select(
            policy = policy(explicitShardIds = setOf(explicit.id)),
            shards = listOf(ordinary, explicit)
        )

        assertEquals(listOf(explicit, ordinary), plan.selectedShards)
    }

    @Test
    fun `bounded selection is enforced`() {
        val first = shard("a-first", ShardType.IDENTITY_CORE)
        val second = shard("b-second", ShardType.IDENTITY_PREFS)

        val plan = selector.select(
            policy = policy(maxShardCount = 1),
            shards = listOf(second, first)
        )

        assertEquals(listOf(first), plan.selectedShards)
        assertTrue(plan.wasBounded)
        assertEquals(
            listOf(SkippedShard(second, ShardSelectionSkipReason.OUTSIDE_BOUND)),
            plan.skippedShards
        )
    }

    @Test
    fun `excluded types are skipped at selection time`() {
        val allowed = shard("allowed", ShardType.IDENTITY_CORE)
        val excluded = shard("excluded", ShardType.MEMORY_EPHEMERAL)

        val plan = selector.select(
            policy = policy(
                allowedShardTypes = ReconstructionRequest.DEFAULT_ALLOWED_SHARD_TYPES + ShardType.MEMORY_EPHEMERAL,
                excludedShardTypes = setOf(ShardType.MEMORY_EPHEMERAL)
            ),
            shards = listOf(allowed, excluded)
        )

        assertEquals(listOf(allowed), plan.selectedShards)
        assertEquals(
            listOf(SkippedShard(excluded, ShardSelectionSkipReason.EXPLICITLY_EXCLUDED)),
            plan.skippedShards
        )
    }

    @Test
    fun `subject mismatches are skipped at selection time`() {
        val matching = shard("matching", ShardType.IDENTITY_CORE)
        val mismatched = shard("mismatched", ShardType.IDENTITY_CORE, otherSubjectId)

        val plan = selector.select(policy(), listOf(matching, mismatched))

        assertEquals(listOf(matching), plan.selectedShards)
        assertEquals(
            listOf(SkippedShard(mismatched, ShardSelectionSkipReason.SUBJECT_MISMATCH)),
            plan.skippedShards
        )
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

    private fun shard(
        id: String,
        type: ShardType,
        subjectId: IdentitySubjectId = this.subjectId
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
