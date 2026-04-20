package com.innovationstrategies.fip.core.selection

import com.innovationstrategies.fip.core.domain.IdentityShard
import com.innovationstrategies.fip.core.domain.ReconstructionRequest
import com.innovationstrategies.fip.core.domain.ShardType

class DefaultShardSelector : ShardSelector {
    override fun select(
        policy: ShardSelectionPolicy,
        shards: Collection<IdentityShard>
    ): ShardSelectionPlan {
        val skippedShards = mutableListOf<SkippedShard>()
        val eligibleShards = mutableListOf<IdentityShard>()

        for (shard in shards) {
            val skipReason = skipReasonFor(policy, shard)
            if (skipReason != null) {
                skippedShards += SkippedShard(shard, skipReason)
            } else {
                eligibleShards += shard
            }
        }

        val orderedShards = eligibleShards
            .distinctBy { it.id }
            .sortedWith(compareBy<IdentityShard> { selectionRank(policy, it) }.thenBy { it.id.value })
        val selectedShards = orderedShards.take(policy.maxShardCount)
        val boundedShards = orderedShards.drop(policy.maxShardCount)
        skippedShards += boundedShards.map { SkippedShard(it, ShardSelectionSkipReason.OUTSIDE_BOUND) }

        return ShardSelectionPlan(
            selectedShards = selectedShards,
            skippedShards = skippedShards,
            wasBounded = boundedShards.isNotEmpty()
        )
    }

    private fun skipReasonFor(
        policy: ShardSelectionPolicy,
        shard: IdentityShard
    ): ShardSelectionSkipReason? =
        when {
            shard.subjectId != policy.subjectId -> ShardSelectionSkipReason.SUBJECT_MISMATCH
            shard.type == ShardType.SYSTEM_META && !policy.allowSystemMeta -> ShardSelectionSkipReason.SYSTEM_META_DENIED
            shard.type in policy.excludedShardTypes -> ShardSelectionSkipReason.EXPLICITLY_EXCLUDED
            shard.type !in policy.allowedShardTypes -> ShardSelectionSkipReason.TYPE_NOT_ALLOWED
            else -> null
        }

    private fun selectionRank(policy: ShardSelectionPolicy, shard: IdentityShard): Int =
        when {
            shard.id in policy.explicitShardIds -> 0
            shard.type in ReconstructionRequest.DEFAULT_ALLOWED_SHARD_TYPES -> 1
            else -> 2
        }
}
