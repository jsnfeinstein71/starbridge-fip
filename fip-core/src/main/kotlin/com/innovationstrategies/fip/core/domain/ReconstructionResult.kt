package com.innovationstrategies.fip.core.domain

import com.innovationstrategies.fip.core.selection.SelectionInfluence
import com.innovationstrategies.fip.core.selection.ShardSelectionSkipReason

data class ReconstructionResult(
    val requestId: String,
    val subjectId: IdentitySubjectId,
    val selectedShards: List<IdentityShard>,
    val includedShards: List<IdentityShard>,
    val excludedShardIds: Set<IdentityShardId>,
    val requestedExplicitShardIds: Set<IdentityShardId>,
    val unresolvedExplicitShardIds: Set<IdentityShardId>,
    val provenance: List<ProvenanceRecord>,
    val maxShardCount: Int,
    val wasBounded: Boolean,
    val skippedAtSelection: List<SelectionStageSkip>,
    val excludedAtReconstruction: List<ReconstructionStageExclusion>,
    val selectedShardReports: List<SelectedShardReport>
) {
    init {
        require(requestId.isNotBlank()) { "requestId must not be blank." }
        require(maxShardCount > 0) { "maxShardCount must be greater than zero." }
        require(selectedShards.distinctBy { it.id }.size == selectedShards.size) {
            "selectedShards must not contain duplicate shard ids."
        }
        require(includedShards.size <= maxShardCount) {
            "includedShards must not exceed maxShardCount."
        }
        require(selectedShards.all { it.subjectId == subjectId }) {
            "All selected shards must match the reconstruction subject."
        }
        require(includedShards.all { it.subjectId == subjectId }) {
            "All included shards must match the reconstruction subject."
        }
    }

    val nonExposableProtectedContentShards: List<ReconstructionStageExclusion>
        get() = excludedAtReconstruction.filter { it.reason == ProvenanceReason.CONTENT_NOT_EXPOSABLE }
}

data class SelectedShardReport(
    val shard: IdentityShard,
    val influences: Set<SelectionInfluence> = emptySet()
)

data class SelectionStageSkip(
    val shard: IdentityShard,
    val reason: ShardSelectionSkipReason,
    val influences: Set<SelectionInfluence> = emptySet()
)

data class ReconstructionStageExclusion(
    val shard: IdentityShard,
    val reason: ProvenanceReason,
    val influences: Set<SelectionInfluence> = emptySet()
)
