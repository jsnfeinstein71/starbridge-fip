package com.innovationstrategies.fip.core.selection

import com.innovationstrategies.fip.core.domain.IdentityShard
import com.innovationstrategies.fip.core.domain.IdentityShardId
import com.innovationstrategies.fip.core.domain.ReconstructionRequest
import com.innovationstrategies.fip.core.domain.ShardType

class GraphAwareShardSelector(
    private val graphMap: ShardGraphMap,
    private val expandDirectLinks: Boolean = true
) : ShardSelector {
    override fun select(
        policy: ShardSelectionPolicy,
        shards: Collection<IdentityShard>
    ): ShardSelectionPlan {
        val shardsById = shards.distinctBy { it.id }.associateBy { it.id }
        val candidateIds = candidateShardIds(policy, shardsById.keys)
        val skippedShards = mutableListOf<SkippedShard>()
        val eligibleShards = mutableListOf<IdentityShard>()

        for (shard in shardsById.values) {
            val skipReason = skipReasonFor(policy, shard, candidateIds)
            if (skipReason != null) {
                skippedShards += SkippedShard(shard, skipReason, influencesFor(policy, shard.id))
            } else {
                eligibleShards += shard
            }
        }

        val orderedShards = eligibleShards.sortedWith(
            compareBy<IdentityShard> { graphRank(policy, it) }
                .thenByDescending { strongestSeedLinkWeight(policy, it.id) }
                .thenByDescending { graphMap.nodeFor(it.id)?.priority ?: 0 }
                .thenBy { it.id.value }
        )
        val selectedShards = orderedShards.take(policy.maxShardCount)
        val boundedShards = orderedShards.drop(policy.maxShardCount)
        skippedShards += boundedShards.map {
            SkippedShard(it, ShardSelectionSkipReason.OUTSIDE_BOUND, influencesFor(policy, it.id))
        }

        return ShardSelectionPlan(
            selectedShards = selectedShards,
            skippedShards = skippedShards,
            wasBounded = boundedShards.isNotEmpty(),
            selectedShardDetails = selectedShards.map { shard ->
                SelectedShardDetail(shard = shard, influences = influencesFor(policy, shard.id))
            }
        )
    }

    private fun candidateShardIds(
        policy: ShardSelectionPolicy,
        availableShardIds: Set<IdentityShardId>
    ): Set<IdentityShardId> {
        val explicitAvailableIds = policy.explicitShardIds.filter { it in availableShardIds }.toSet()
        if (explicitAvailableIds.isEmpty()) {
            return availableShardIds
        }

        val linkedIds = if (expandDirectLinks) {
            graphMap.directLinkedShardIds(explicitAvailableIds).filter { it in availableShardIds }
        } else {
            emptyList()
        }
        return explicitAvailableIds + linkedIds
    }

    private fun skipReasonFor(
        policy: ShardSelectionPolicy,
        shard: IdentityShard,
        candidateIds: Set<IdentityShardId>
    ): ShardSelectionSkipReason? =
        when {
            shard.id !in candidateIds -> ShardSelectionSkipReason.NOT_IN_GRAPH_SELECTION
            shard.subjectId != policy.subjectId -> ShardSelectionSkipReason.SUBJECT_MISMATCH
            graphMap.nodeFor(shard.id)?.subjectId?.let { it != policy.subjectId } == true ->
                ShardSelectionSkipReason.SUBJECT_MISMATCH
            shard.type == ShardType.SYSTEM_META && !policy.allowSystemMeta -> ShardSelectionSkipReason.SYSTEM_META_DENIED
            shard.type in policy.excludedShardTypes -> ShardSelectionSkipReason.EXPLICITLY_EXCLUDED
            shard.type !in policy.allowedShardTypes -> ShardSelectionSkipReason.TYPE_NOT_ALLOWED
            else -> null
        }

    private fun graphRank(policy: ShardSelectionPolicy, shard: IdentityShard): Int =
        when {
            shard.id in policy.explicitShardIds -> 0
            shard.id in graphMap.directLinkedShardIds(policy.explicitShardIds) -> 1
            shard.type in ReconstructionRequest.DEFAULT_ALLOWED_SHARD_TYPES -> 2
            else -> 3
        }

    private fun strongestSeedLinkWeight(policy: ShardSelectionPolicy, shardId: IdentityShardId): Int =
        if (shardId in policy.explicitShardIds) {
            Int.MAX_VALUE
        } else {
            graphMap.links
                .filter { it.fromShardId in policy.explicitShardIds && it.toShardId == shardId }
                .maxOfOrNull { it.weight }
                ?: if (shardId in graphMap.directLinkedShardIds(policy.explicitShardIds)) {
                    0
                } else {
                    -1
                }
        }

    private fun influencesFor(
        policy: ShardSelectionPolicy,
        shardId: IdentityShardId
    ): Set<SelectionInfluence> =
        buildSet {
            if (shardId in policy.explicitShardIds) {
                add(SelectionInfluence.EXPLICIT_SEED)
            }
            if (shardId in graphMap.directLinkedShardIds(policy.explicitShardIds)) {
                add(SelectionInfluence.GRAPH_LINKED)
            }
            if (strongestSeedLinkWeight(policy, shardId) > 0 || (graphMap.nodeFor(shardId)?.priority ?: 0) > 0) {
                add(SelectionInfluence.WEIGHTED_PRIORITY)
            }
        }
}
