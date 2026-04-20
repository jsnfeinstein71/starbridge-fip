package com.innovationstrategies.fip.core.writeback

import com.innovationstrategies.fip.core.domain.IdentityShard
import com.innovationstrategies.fip.core.domain.IdentityShardId
import com.innovationstrategies.fip.core.domain.IdentitySubjectId

data class ReShardPlan(
    val requestId: String,
    val subjectId: IdentitySubjectId,
    val selectedShardIds: Set<IdentityShardId>,
    val shardIdsToDelete: Set<IdentityShardId>,
    val replacementShards: List<IdentityShard>,
    val wasBounded: Boolean
) {
    init {
        require(requestId.isNotBlank()) { "requestId must not be blank." }
        require(replacementShards.isNotEmpty()) {
            "ReShardPlan must include at least one replacement shard."
        }
        require(replacementShards.all { it.subjectId == subjectId }) {
            "All replacement shards must match the write-back subject."
        }
        require(shardIdsToDelete.containsAll(selectedShardIds)) {
            "shardIdsToDelete must include all selected shard ids."
        }
    }
}
