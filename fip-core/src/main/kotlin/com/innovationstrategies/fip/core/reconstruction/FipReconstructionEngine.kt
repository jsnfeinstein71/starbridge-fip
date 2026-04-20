package com.innovationstrategies.fip.core.reconstruction

import com.innovationstrategies.fip.core.domain.IdentityShard
import com.innovationstrategies.fip.core.domain.IdentityShardId
import com.innovationstrategies.fip.core.domain.ProvenanceDecision
import com.innovationstrategies.fip.core.domain.ProvenanceReason
import com.innovationstrategies.fip.core.domain.ProvenanceRecord
import com.innovationstrategies.fip.core.domain.ReconstructionPolicy
import com.innovationstrategies.fip.core.domain.ReconstructionRequest
import com.innovationstrategies.fip.core.domain.ReconstructionResult
import com.innovationstrategies.fip.core.domain.ShardType
import java.nio.charset.StandardCharsets
import java.time.Clock

class FipReconstructionEngine(
    private val clock: Clock = Clock.systemUTC()
) {
    fun reconstruct(
        request: ReconstructionRequest,
        shards: Collection<IdentityShard>,
        policy: ReconstructionPolicy = ReconstructionPolicy.from(request)
    ): ReconstructionResult {
        val includedShards = mutableListOf<IdentityShard>()
        val excludedShardIds = mutableSetOf<IdentityShardId>()
        val provenance = mutableListOf<ProvenanceRecord>()
        var payloadBytes = 0
        var wasBounded = false

        for (shard in shards) {
            val exclusionReason = exclusionReasonFor(request, policy, shard)
            if (exclusionReason != null) {
                excludedShardIds += shard.id
                provenance += provenanceFor(request, shard, ProvenanceDecision.EXCLUDED, exclusionReason)
                continue
            }

            if (includedShards.size >= request.maxShardCount) {
                wasBounded = true
                excludedShardIds += shard.id
                provenance += provenanceFor(request, shard, ProvenanceDecision.EXCLUDED, ProvenanceReason.OUTSIDE_BOUND)
                continue
            }

            val shardPayloadBytes = shard.payload.toByteArray(StandardCharsets.UTF_8).size
            val maxPayloadBytes = request.maxPayloadBytes
            if (maxPayloadBytes != null && payloadBytes + shardPayloadBytes > maxPayloadBytes) {
                wasBounded = true
                excludedShardIds += shard.id
                provenance += provenanceFor(
                    request,
                    shard,
                    ProvenanceDecision.EXCLUDED,
                    ProvenanceReason.PAYLOAD_LIMIT_EXCEEDED
                )
                continue
            }

            includedShards += shard
            payloadBytes += shardPayloadBytes
            provenance += provenanceFor(
                request,
                shard,
                ProvenanceDecision.INCLUDED,
                if (shard.id in request.explicitShardIds) {
                    ProvenanceReason.REQUESTED_EXPLICITLY
                } else {
                    ProvenanceReason.WITHIN_BOUND
                }
            )
        }

        return ReconstructionResult(
            requestId = request.requestId,
            subjectId = request.subjectId,
            includedShards = includedShards,
            excludedShardIds = excludedShardIds,
            provenance = provenance,
            maxShardCount = request.maxShardCount,
            wasBounded = wasBounded
        )
    }

    private fun exclusionReasonFor(
        request: ReconstructionRequest,
        policy: ReconstructionPolicy,
        shard: IdentityShard
    ): ProvenanceReason? =
        when {
            shard.subjectId != request.subjectId -> ProvenanceReason.SUBJECT_MISMATCH
            shard.type == ShardType.SYSTEM_META && !policy.allowSystemMeta -> ProvenanceReason.SYSTEM_META_DENIED
            shard.type in policy.excludedShardTypes -> ProvenanceReason.EXPLICITLY_EXCLUDED
            shard.type !in policy.allowedShardTypes -> ProvenanceReason.TYPE_NOT_ALLOWED
            else -> null
        }

    private fun provenanceFor(
        request: ReconstructionRequest,
        shard: IdentityShard,
        decision: ProvenanceDecision,
        reason: ProvenanceReason
    ): ProvenanceRecord =
        ProvenanceRecord(
            requestId = request.requestId,
            shardId = shard.id,
            shardType = shard.type,
            decision = decision,
            reason = reason,
            decidedAt = clock.instant()
        )
}
