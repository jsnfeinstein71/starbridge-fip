package com.innovationstrategies.fip.core.writeback

import com.innovationstrategies.fip.core.domain.IdentityShardId
import com.innovationstrategies.fip.core.domain.IdentitySubjectId
import java.time.Instant

data class WriteBackResult(
    val requestId: String,
    val subjectId: IdentitySubjectId,
    val createdShardIds: Set<IdentityShardId>,
    val replacedShardIds: Set<IdentityShardId>,
    val deletedShardIds: Set<IdentityShardId>,
    val plan: ReShardPlan,
    val wasBounded: Boolean,
    val decidedAt: Instant
) {
    init {
        require(requestId.isNotBlank()) { "requestId must not be blank." }
        require(plan.requestId == requestId) { "plan requestId must match result requestId." }
        require(plan.subjectId == subjectId) { "plan subjectId must match result subjectId." }
        require(createdShardIds == plan.replacementShards.map { it.id }.toSet()) {
            "createdShardIds must match replacement shard ids."
        }
        require(replacedShardIds == plan.selectedShardIds) {
            "replacedShardIds must match selected shard ids."
        }
        require(deletedShardIds == plan.shardIdsToDelete) {
            "deletedShardIds must match planned shard ids to delete."
        }
    }
}
