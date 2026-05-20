package com.innovationstrategies.fip.core.vault

import com.innovationstrategies.fip.core.domain.IdentityShardId
import com.innovationstrategies.fip.core.domain.IdentitySubjectId
import java.time.Instant

data class ReconstructionViewRequest(
    val requestedView: FipObjectView,
    val purpose: ReconstructionPurpose
)

@JvmInline
value class ReconstructionPurpose(val value: String) {
    init {
        require(value.isNotBlank()) { "ReconstructionPurpose must not be blank." }
    }
}

data class ReconstructionContext(
    val requester: IdentitySubjectId? = null,
    val requestedAt: Instant = Instant.now(),
    val requiresProviderExposure: Boolean = false,
    val attributes: Map<String, String> = emptyMap()
) {
    init {
        require(attributes.keys.none { it.isBlank() }) {
            "ReconstructionContext attributes must not contain blank keys."
        }
    }
}

data class ReconstructionPlan(
    val objectId: FipObjectId,
    val requestedView: FipObjectView,
    val status: ReconstructionPlanStatus,
    val denialReasons: Set<ReconstructionDenialReason>,
    val selectedShardIds: Set<IdentityShardId>,
    val includedMetadataLevels: Set<FipObjectMetadataLevel>,
    val excludedMetadataLevels: Set<FipObjectMetadataLevel>,
    val providerExposurePosture: ProviderExposurePosture,
    val providerOutputAuthority: ProviderOutputAuthority,
    val auditRequired: Boolean,
    val reconstructionTrace: ReconstructionTrace,
    val shardSelection: ReconstructionShardSelection,
    val metadataSelection: ReconstructionMetadataSelection
) {
    init {
        require(status == ReconstructionPlanStatus.DENIED || denialReasons.isEmpty()) {
            "Allowed reconstruction plans must not include denial reasons."
        }
        require(status == ReconstructionPlanStatus.ALLOWED || selectedShardIds.isEmpty()) {
            "Denied reconstruction plans must not select shards."
        }
        require(selectedShardIds == shardSelection.selectedShardIds) {
            "selectedShardIds must match shardSelection."
        }
        require(includedMetadataLevels == metadataSelection.includedLevels) {
            "includedMetadataLevels must match metadataSelection."
        }
        require(excludedMetadataLevels == metadataSelection.excludedLevels) {
            "excludedMetadataLevels must match metadataSelection."
        }
    }
}

enum class ReconstructionPlanStatus {
    ALLOWED,
    DENIED
}

enum class ReconstructionDenialReason {
    UNKNOWN_VIEW,
    REQUESTED_VIEW_NOT_ALLOWED,
    POLICY_DENIES_RECONSTRUCTION,
    PROVIDER_EXPOSURE_BLOCKED
}

data class ReconstructionShardSelection(
    val selectedShardIds: Set<IdentityShardId>,
    val excludedShardIds: Set<IdentityShardId>
)

data class ReconstructionMetadataSelection(
    val includedLevels: Set<FipObjectMetadataLevel>,
    val excludedLevels: Set<FipObjectMetadataLevel>
)

class FipReconstructionPlanner {
    fun plan(
        manifest: FipObjectManifest,
        request: ReconstructionViewRequest,
        context: ReconstructionContext = ReconstructionContext()
    ): ReconstructionPlan {
        val requestedView = request.requestedView
        val knownViews = manifest.policy.definedViews
        val isKnownView = requestedView in knownViews
        val isAllowedView = requestedView in manifest.policy.allowedViews
        val viewPolicy = manifest.policy.viewPolicies[requestedView]
        val providerExposurePolicy =
            viewPolicy?.providerExposurePolicy ?: manifest.policy.providerExposurePolicy
        val auditRequired = viewPolicy?.auditRequired ?: manifest.policy.auditRequired
        val denialReasons = buildSet {
            if (!isKnownView) add(ReconstructionDenialReason.UNKNOWN_VIEW)
            if (isKnownView && !isAllowedView) {
                add(ReconstructionDenialReason.REQUESTED_VIEW_NOT_ALLOWED)
            }
            if (viewPolicy?.reconstructionAllowed == false) {
                add(ReconstructionDenialReason.POLICY_DENIES_RECONSTRUCTION)
            }
            if (
                context.requiresProviderExposure &&
                providerExposurePolicy.posture == ProviderExposurePosture.NO_PROVIDER_EXPOSURE
            ) {
                add(ReconstructionDenialReason.PROVIDER_EXPOSURE_BLOCKED)
            }
        }
        val status = if (denialReasons.isEmpty()) {
            ReconstructionPlanStatus.ALLOWED
        } else {
            ReconstructionPlanStatus.DENIED
        }
        val shardSelection = selectShards(manifest, viewPolicy, status)
        val metadataSelection = selectMetadata(manifest, viewPolicy, status)

        return ReconstructionPlan(
            objectId = manifest.objectId,
            requestedView = requestedView,
            status = status,
            denialReasons = denialReasons,
            selectedShardIds = shardSelection.selectedShardIds,
            includedMetadataLevels = metadataSelection.includedLevels,
            excludedMetadataLevels = metadataSelection.excludedLevels,
            providerExposurePosture = providerExposurePolicy.posture,
            providerOutputAuthority = providerExposurePolicy.providerOutputAuthority,
            auditRequired = auditRequired,
            reconstructionTrace = ReconstructionTrace(
                traceId = "plan:${manifest.objectId.value}:${requestedView.value}",
                status = if (status == ReconstructionPlanStatus.ALLOWED) {
                    ReconstructionTraceStatus.AUTHORIZED_PLACEHOLDER
                } else {
                    ReconstructionTraceStatus.DENIED_PLACEHOLDER
                },
                view = requestedView,
                generatedAt = context.requestedAt,
                decisionRefs = denialReasons.map { it.name }
            ),
            shardSelection = shardSelection,
            metadataSelection = metadataSelection
        )
    }

    private fun selectShards(
        manifest: FipObjectManifest,
        viewPolicy: FipObjectViewPolicy?,
        status: ReconstructionPlanStatus
    ): ReconstructionShardSelection {
        if (status == ReconstructionPlanStatus.DENIED) {
            return ReconstructionShardSelection(
                selectedShardIds = emptySet(),
                excludedShardIds = manifest.relatedShardIds
            )
        }

        val requestedShardIds = viewPolicy?.allowedShardIds ?: manifest.relatedShardIds
        val selectedShardIds = requestedShardIds.intersect(manifest.relatedShardIds)

        return ReconstructionShardSelection(
            selectedShardIds = selectedShardIds,
            excludedShardIds = manifest.relatedShardIds - selectedShardIds
        )
    }

    private fun selectMetadata(
        manifest: FipObjectManifest,
        viewPolicy: FipObjectViewPolicy?,
        status: ReconstructionPlanStatus
    ): ReconstructionMetadataSelection {
        if (status == ReconstructionPlanStatus.DENIED) {
            return ReconstructionMetadataSelection(
                includedLevels = emptySet(),
                excludedLevels = FipObjectMetadataLevel.entries.toSet()
            )
        }

        val requestedLevels = viewPolicy?.includedMetadataLevels.orEmpty()
        val includedLevels = requestedLevels.intersect(FipObjectMetadataLevel.entries.toSet())
        val excludedLevels = FipObjectMetadataLevel.entries.toSet() - includedLevels

        return ReconstructionMetadataSelection(
            includedLevels = includedLevels,
            excludedLevels = excludedLevels + (manifest.metadata.populatedLevels - includedLevels)
        )
    }
}
