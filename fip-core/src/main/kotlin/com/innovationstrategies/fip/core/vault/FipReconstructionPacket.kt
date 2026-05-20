package com.innovationstrategies.fip.core.vault

import com.innovationstrategies.fip.core.domain.IdentityShardId
import java.time.Instant

data class FipReconstructionPacket(
    val objectId: FipObjectId,
    val requestedView: FipObjectView,
    val allowedUse: ReconstructionPurpose,
    val status: ReconstructionPlanStatus,
    val denialReasons: Set<ReconstructionDenialReason>,
    val selectedShardIds: Set<IdentityShardId>,
    val selectedMetadataLevels: Set<FipObjectMetadataLevel>,
    val excludedMetadataLevels: Set<FipObjectMetadataLevel>,
    val providerExposurePosture: ProviderExposurePosture,
    val providerOutputAuthority: ProviderOutputAuthority,
    val auditRequired: Boolean,
    val traceId: String,
    val traceStatus: ReconstructionTraceStatus,
    val traceGeneratedAt: Instant?,
    val blockedReasons: Set<ReconstructionDenialReason>
) {
    init {
        require(status == ReconstructionPlanStatus.DENIED || denialReasons.isEmpty()) {
            "Approved reconstruction packets must not include denial reasons."
        }
        require(status == ReconstructionPlanStatus.ALLOWED || selectedShardIds.isEmpty()) {
            "Denied reconstruction packets must not select shards."
        }
        require(blockedReasons.containsAll(denialReasons)) {
            "blockedReasons must include denialReasons."
        }
    }

    val isApproved: Boolean
        get() = status == ReconstructionPlanStatus.ALLOWED
}

object FipReconstructionPacketFactory {
    fun fromPlan(
        plan: ReconstructionPlan,
        allowedUse: ReconstructionPurpose
    ): FipReconstructionPacket =
        FipReconstructionPacket(
            objectId = plan.objectId,
            requestedView = plan.requestedView,
            allowedUse = allowedUse,
            status = plan.status,
            denialReasons = plan.denialReasons,
            selectedShardIds = plan.selectedShardIds,
            selectedMetadataLevels = plan.includedMetadataLevels,
            excludedMetadataLevels = plan.excludedMetadataLevels,
            providerExposurePosture = plan.providerExposurePosture,
            providerOutputAuthority = plan.providerOutputAuthority,
            auditRequired = plan.auditRequired,
            traceId = plan.reconstructionTrace.traceId,
            traceStatus = plan.reconstructionTrace.status,
            traceGeneratedAt = plan.reconstructionTrace.generatedAt,
            blockedReasons = plan.denialReasons
        )
}
