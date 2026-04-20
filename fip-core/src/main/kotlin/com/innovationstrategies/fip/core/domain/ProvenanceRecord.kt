package com.innovationstrategies.fip.core.domain

import java.time.Instant

data class ProvenanceRecord(
    val requestId: String,
    val shardId: IdentityShardId,
    val shardType: ShardType,
    val decision: ProvenanceDecision,
    val reason: ProvenanceReason,
    val decidedAt: Instant
) {
    init {
        require(requestId.isNotBlank()) { "requestId must not be blank." }
        require(decision != ProvenanceDecision.INCLUDED || reason.supportsInclusion) {
            "Inclusion provenance must use an inclusion reason."
        }
        require(decision != ProvenanceDecision.EXCLUDED || reason.supportsExclusion) {
            "Exclusion provenance must use an exclusion reason."
        }
    }
}

enum class ProvenanceDecision {
    INCLUDED,
    EXCLUDED
}

enum class ProvenanceReason(
    val supportsInclusion: Boolean,
    val supportsExclusion: Boolean
) {
    REQUESTED_EXPLICITLY(supportsInclusion = true, supportsExclusion = false),
    WITHIN_BOUND(supportsInclusion = true, supportsExclusion = false),
    TYPE_NOT_ALLOWED(supportsInclusion = false, supportsExclusion = true),
    EXPLICITLY_EXCLUDED(supportsInclusion = false, supportsExclusion = true),
    SYSTEM_META_DENIED(supportsInclusion = false, supportsExclusion = true),
    OUTSIDE_BOUND(supportsInclusion = false, supportsExclusion = true),
    PAYLOAD_LIMIT_EXCEEDED(supportsInclusion = false, supportsExclusion = true),
    SUBJECT_MISMATCH(supportsInclusion = false, supportsExclusion = true)
}
