package com.innovationstrategies.fip.core.vault

import com.innovationstrategies.fip.core.domain.IdentityShardId
import com.innovationstrategies.fip.core.domain.IdentitySubjectId
import java.time.Instant

@JvmInline
value class FipObjectId(val value: String) {
    init {
        require(value.isNotBlank()) { "FipObjectId must not be blank." }
    }
}

@JvmInline
value class FipAssetType(val value: String) {
    init {
        require(value.isNotBlank()) { "FipAssetType must not be blank." }
    }
}

data class FipObjectManifest(
    val objectId: FipObjectId,
    val name: String,
    val assetType: FipAssetType,
    val owner: IdentitySubjectId,
    val sensitivityLabels: Set<String> = emptySet(),
    val relatedShardIds: Set<IdentityShardId> = emptySet(),
    val metadata: FipObjectMetadata = FipObjectMetadata(),
    val policy: FipObjectPolicy = FipObjectPolicy(),
    val reconstructionTrace: ReconstructionTrace = ReconstructionTrace.notReconstructed()
) {
    init {
        require(name.isNotBlank()) { "FipObjectManifest name must not be blank." }
        require(sensitivityLabels.none { it.isBlank() }) {
            "FipObjectManifest sensitivityLabels must not contain blank values."
        }
    }

    val allowedViews: Set<FipObjectView>
        get() = policy.allowedViews
}

@JvmInline
value class FipObjectView(val value: String) {
    init {
        require(value.isNotBlank()) { "FipObjectView must not be blank." }
    }

    val externalName: String
        get() = value
}

enum class FipObjectMetadataLevel {
    OBSERVED,
    INFERRED,
    OWNER_APPROVED,
    POLICY_DERIVED,
    SYSTEM_ENFORCED,
    DOMAIN_SPECIFIC
}

data class FipObjectMetadata(
    val observed: Map<String, String> = emptyMap(),
    val inferred: Map<String, String> = emptyMap(),
    val ownerApproved: Map<String, String> = emptyMap(),
    val policyDerived: Map<String, String> = emptyMap(),
    val systemEnforced: Map<String, String> = emptyMap(),
    val domainSpecific: Map<String, String> = emptyMap()
) {
    init {
        requireValidMetadataLevel("observed", observed)
        requireValidMetadataLevel("inferred", inferred)
        requireValidMetadataLevel("ownerApproved", ownerApproved)
        requireValidMetadataLevel("policyDerived", policyDerived)
        requireValidMetadataLevel("systemEnforced", systemEnforced)
        requireValidMetadataLevel("domainSpecific", domainSpecific)
    }

    val populatedLevels: Set<FipObjectMetadataLevel>
        get() = buildSet {
            if (observed.isNotEmpty()) add(FipObjectMetadataLevel.OBSERVED)
            if (inferred.isNotEmpty()) add(FipObjectMetadataLevel.INFERRED)
            if (ownerApproved.isNotEmpty()) add(FipObjectMetadataLevel.OWNER_APPROVED)
            if (policyDerived.isNotEmpty()) add(FipObjectMetadataLevel.POLICY_DERIVED)
            if (systemEnforced.isNotEmpty()) add(FipObjectMetadataLevel.SYSTEM_ENFORCED)
            if (domainSpecific.isNotEmpty()) add(FipObjectMetadataLevel.DOMAIN_SPECIFIC)
        }

    private fun requireValidMetadataLevel(levelName: String, values: Map<String, String>) {
        require(values.keys.none { it.isBlank() }) {
            "FipObjectMetadata $levelName keys must not be blank."
        }
    }
}

data class FipObjectPolicy(
    val definedViews: Set<FipObjectView> = emptySet(),
    val allowedViews: Set<FipObjectView> = emptySet(),
    val viewPolicies: Map<FipObjectView, FipObjectViewPolicy> = emptyMap(),
    val providerExposurePolicy: ProviderExposurePolicy = ProviderExposurePolicy(),
    val auditRequired: Boolean = true
) {
    init {
        require(definedViews.containsAll(allowedViews)) {
            "FipObjectPolicy definedViews must include every allowed view."
        }
        require(definedViews.containsAll(viewPolicies.keys)) {
            "FipObjectPolicy definedViews must include every view policy."
        }
    }
}

data class FipObjectViewPolicy(
    val view: FipObjectView,
    val allowedShardIds: Set<IdentityShardId>? = null,
    val includedMetadataLevels: Set<FipObjectMetadataLevel> = emptySet(),
    val providerExposurePolicy: ProviderExposurePolicy? = null,
    val auditRequired: Boolean? = null,
    val reconstructionAllowed: Boolean = true
) {
    init {
        require(includedMetadataLevels.none { it.name.isBlank() }) {
            "FipObjectViewPolicy includedMetadataLevels must not contain blank values."
        }
    }
}

data class ProviderExposurePolicy(
    val posture: ProviderExposurePosture = ProviderExposurePosture.NO_PROVIDER_DECRYPTION,
    val providerMayDecrypt: Boolean = false,
    val providerMayHoldReconstructionAuthority: Boolean = false,
    val providerOutputAuthority: ProviderOutputAuthority = ProviderOutputAuthority.PROPOSAL_ONLY
) {
    init {
        require(!providerMayDecrypt) { "ProviderExposurePolicy must not grant provider shard decryption." }
        require(!providerMayHoldReconstructionAuthority) {
            "ProviderExposurePolicy must not grant provider reconstruction authority."
        }
    }
}

enum class ProviderExposurePosture {
    NO_PROVIDER_EXPOSURE,
    NO_PROVIDER_DECRYPTION,
    BOUNDED_VIEW_ONLY
}

enum class ProviderOutputAuthority {
    PROPOSAL_ONLY
}

data class ReconstructionTrace(
    val traceId: String,
    val status: ReconstructionTraceStatus,
    val view: FipObjectView?,
    val generatedAt: Instant?,
    val decisionRefs: List<String> = emptyList()
) {
    init {
        require(traceId.isNotBlank()) { "ReconstructionTrace traceId must not be blank." }
        require(decisionRefs.none { it.isBlank() }) {
            "ReconstructionTrace decisionRefs must not contain blank values."
        }
        require(status == ReconstructionTraceStatus.NOT_RECONSTRUCTED || view != null) {
            "ReconstructionTrace view is required once reconstruction has been attempted."
        }
    }

    companion object {
        fun notReconstructed(): ReconstructionTrace =
            ReconstructionTrace(
                traceId = "not-reconstructed",
                status = ReconstructionTraceStatus.NOT_RECONSTRUCTED,
                view = null,
                generatedAt = null
            )
    }
}

enum class ReconstructionTraceStatus {
    NOT_RECONSTRUCTED,
    AUTHORIZED_PLACEHOLDER,
    DENIED_PLACEHOLDER
}
