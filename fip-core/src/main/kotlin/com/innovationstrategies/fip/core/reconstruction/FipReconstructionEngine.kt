package com.innovationstrategies.fip.core.reconstruction

import com.innovationstrategies.fip.core.domain.IdentityShard
import com.innovationstrategies.fip.core.domain.IdentityShardId
import com.innovationstrategies.fip.core.domain.PlaintextContentProtection
import com.innovationstrategies.fip.core.domain.ProvenanceDecision
import com.innovationstrategies.fip.core.domain.ProvenanceReason
import com.innovationstrategies.fip.core.domain.ProvenanceRecord
import com.innovationstrategies.fip.core.domain.ReconstructionPolicy
import com.innovationstrategies.fip.core.domain.ReconstructionRequest
import com.innovationstrategies.fip.core.domain.ReconstructionResult
import com.innovationstrategies.fip.core.domain.ShardContentExposure
import com.innovationstrategies.fip.core.selection.DefaultShardSelector
import com.innovationstrategies.fip.core.selection.ShardSelectionPolicy
import com.innovationstrategies.fip.core.selection.ShardSelectionSkipReason
import com.innovationstrategies.fip.core.selection.ShardSelector
import java.nio.charset.StandardCharsets
import java.time.Clock

class FipReconstructionEngine(
    private val clock: Clock = Clock.systemUTC(),
    private val contentExposure: ShardContentExposure = PlaintextContentProtection,
    private val shardSelector: ShardSelector = DefaultShardSelector()
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
        val selectionPlan = shardSelector.select(
            policy = ShardSelectionPolicy(
                subjectId = request.subjectId,
                allowedShardTypes = policy.allowedShardTypes,
                excludedShardTypes = policy.excludedShardTypes,
                maxShardCount = request.maxShardCount,
                explicitShardIds = request.explicitShardIds,
                allowSystemMeta = policy.allowSystemMeta
            ),
            shards = shards
        )
        var wasBounded = selectionPlan.wasBounded

        selectionPlan.skippedShards.forEach { skipped ->
            excludedShardIds += skipped.shard.id
            provenance += provenanceFor(
                request,
                skipped.shard,
                ProvenanceDecision.EXCLUDED,
                skipped.reason.toProvenanceReason()
            )
        }

        for (shard in selectionPlan.selectedShards) {
            val exposedContentResult = runCatching { contentExposure.expose(shard.protectedContent) }
            if (exposedContentResult.isFailure) {
                excludedShardIds += shard.id
                provenance += provenanceFor(
                    request,
                    shard,
                    ProvenanceDecision.EXCLUDED,
                    ProvenanceReason.CONTENT_NOT_EXPOSABLE
                )
                continue
            }
            val exposedContent = exposedContentResult.getOrThrow()
            val shardPayloadBytes = exposedContent.toByteArray(StandardCharsets.UTF_8).size
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

    private fun ShardSelectionSkipReason.toProvenanceReason(): ProvenanceReason =
        when (this) {
            ShardSelectionSkipReason.SUBJECT_MISMATCH -> ProvenanceReason.SUBJECT_MISMATCH
            ShardSelectionSkipReason.SYSTEM_META_DENIED -> ProvenanceReason.SYSTEM_META_DENIED
            ShardSelectionSkipReason.EXPLICITLY_EXCLUDED -> ProvenanceReason.EXPLICITLY_EXCLUDED
            ShardSelectionSkipReason.TYPE_NOT_ALLOWED -> ProvenanceReason.TYPE_NOT_ALLOWED
            ShardSelectionSkipReason.OUTSIDE_BOUND -> ProvenanceReason.OUTSIDE_BOUND
        }
}
