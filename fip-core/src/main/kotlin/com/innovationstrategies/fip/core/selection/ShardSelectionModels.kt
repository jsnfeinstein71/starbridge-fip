package com.innovationstrategies.fip.core.selection

import com.innovationstrategies.fip.core.domain.IdentityShard
import com.innovationstrategies.fip.core.domain.IdentityShardId
import com.innovationstrategies.fip.core.domain.IdentitySubjectId
import com.innovationstrategies.fip.core.domain.ShardType

data class ShardSelectionPolicy(
    val subjectId: IdentitySubjectId,
    val allowedShardTypes: Set<ShardType>,
    val excludedShardTypes: Set<ShardType>,
    val maxShardCount: Int,
    val explicitShardIds: Set<IdentityShardId> = emptySet(),
    val allowSystemMeta: Boolean = false
) {
    init {
        require(maxShardCount > 0) { "maxShardCount must be greater than zero." }
        require(allowSystemMeta || ShardType.SYSTEM_META !in allowedShardTypes) {
            "SYSTEM_META must be explicitly allowed."
        }
    }
}

data class ShardSelectionPlan(
    val selectedShards: List<IdentityShard>,
    val skippedShards: List<SkippedShard>,
    val wasBounded: Boolean
) {
    init {
        require(selectedShards.distinctBy { it.id }.size == selectedShards.size) {
            "selectedShards must not contain duplicate shard ids."
        }
    }
}

data class SkippedShard(
    val shard: IdentityShard,
    val reason: ShardSelectionSkipReason
)

enum class ShardSelectionSkipReason {
    SUBJECT_MISMATCH,
    SYSTEM_META_DENIED,
    EXPLICITLY_EXCLUDED,
    TYPE_NOT_ALLOWED,
    OUTSIDE_BOUND
}

interface ShardSelector {
    fun select(
        policy: ShardSelectionPolicy,
        shards: Collection<IdentityShard>
    ): ShardSelectionPlan
}
