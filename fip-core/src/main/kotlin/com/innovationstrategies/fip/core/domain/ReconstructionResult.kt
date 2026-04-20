package com.innovationstrategies.fip.core.domain

data class ReconstructionResult(
    val requestId: String,
    val subjectId: IdentitySubjectId,
    val includedShards: List<IdentityShard>,
    val excludedShardIds: Set<IdentityShardId>,
    val provenance: List<ProvenanceRecord>,
    val maxShardCount: Int,
    val wasBounded: Boolean
) {
    init {
        require(requestId.isNotBlank()) { "requestId must not be blank." }
        require(maxShardCount > 0) { "maxShardCount must be greater than zero." }
        require(includedShards.size <= maxShardCount) {
            "includedShards must not exceed maxShardCount."
        }
        require(includedShards.all { it.subjectId == subjectId }) {
            "All included shards must match the reconstruction subject."
        }
    }
}
