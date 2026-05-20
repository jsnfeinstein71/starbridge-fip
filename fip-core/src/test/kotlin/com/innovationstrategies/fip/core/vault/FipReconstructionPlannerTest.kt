package com.innovationstrategies.fip.core.vault

import com.innovationstrategies.fip.core.domain.IdentityShardId
import com.innovationstrategies.fip.core.domain.IdentitySubjectId
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FipReconstructionPlannerTest {
    private val planner = FipReconstructionPlanner()

    @Test
    fun `valid requested view produces allowed plan`() {
        val manifest = manifest()

        val plan = planner.plan(
            manifest = manifest,
            request = request(VIEW_MODEL_BOUNDED),
            context = context()
        )

        assertEquals(ReconstructionPlanStatus.ALLOWED, plan.status)
        assertTrue(plan.denialReasons.isEmpty())
        assertEquals(manifest.objectId, plan.objectId)
        assertEquals(VIEW_MODEL_BOUNDED, plan.requestedView)
    }

    @Test
    fun `unknown view produces denied plan`() {
        val plan = planner.plan(
            manifest = manifest(),
            request = request(FipObjectView("not-defined")),
            context = context()
        )

        assertEquals(ReconstructionPlanStatus.DENIED, plan.status)
        assertTrue(plan.denialReasons.contains(ReconstructionDenialReason.UNKNOWN_VIEW))
        assertTrue(plan.selectedShardIds.isEmpty())
    }

    @Test
    fun `defined but disallowed view produces denied plan`() {
        val plan = planner.plan(
            manifest = manifest(),
            request = request(VIEW_DEFINED_DENIED),
            context = context()
        )

        assertEquals(ReconstructionPlanStatus.DENIED, plan.status)
        assertTrue(plan.denialReasons.contains(ReconstructionDenialReason.REQUESTED_VIEW_NOT_ALLOWED))
        assertTrue(plan.selectedShardIds.isEmpty())
    }

    @Test
    fun `view policy can deny reconstruction`() {
        val policyDeniedView = FipObjectView("policy-denied")
        val manifest = manifest(
            policy = FipObjectPolicy(
                definedViews = setOf(policyDeniedView),
                allowedViews = setOf(policyDeniedView),
                viewPolicies = mapOf(
                    policyDeniedView to FipObjectViewPolicy(
                        view = policyDeniedView,
                        reconstructionAllowed = false
                    )
                )
            )
        )

        val plan = planner.plan(
            manifest = manifest,
            request = request(policyDeniedView),
            context = context()
        )

        assertEquals(ReconstructionPlanStatus.DENIED, plan.status)
        assertTrue(plan.denialReasons.contains(ReconstructionDenialReason.POLICY_DENIES_RECONSTRUCTION))
        assertTrue(plan.selectedShardIds.isEmpty())
    }

    @Test
    fun `provider exposure posture is carried into the plan`() {
        val plan = planner.plan(
            manifest = manifest(),
            request = request(VIEW_MODEL_BOUNDED),
            context = context(requiresProviderExposure = true)
        )

        assertEquals(ProviderExposurePosture.BOUNDED_VIEW_ONLY, plan.providerExposurePosture)
        assertEquals(ProviderOutputAuthority.PROPOSAL_ONLY, plan.providerOutputAuthority)
    }

    @Test
    fun `audit requirement is carried into the plan`() {
        val plan = planner.plan(
            manifest = manifest(),
            request = request(VIEW_MODEL_BOUNDED),
            context = context()
        )

        assertTrue(plan.auditRequired)
    }

    @Test
    fun `selected shard ids are limited to requested view and object relationship`() {
        val plan = planner.plan(
            manifest = manifest(),
            request = request(VIEW_LIMITED),
            context = context()
        )

        assertEquals(setOf(SHARD_A), plan.selectedShardIds)
        assertTrue(plan.shardSelection.excludedShardIds.contains(SHARD_B))
        assertTrue(plan.shardSelection.excludedShardIds.contains(SHARD_C))
        assertFalse(plan.selectedShardIds.contains(IdentityShardId("external-shard")))
    }

    @Test
    fun `metadata levels remain distinct`() {
        val plan = planner.plan(
            manifest = manifest(),
            request = request(VIEW_MODEL_BOUNDED),
            context = context()
        )

        assertEquals(
            setOf(
                FipObjectMetadataLevel.OBSERVED,
                FipObjectMetadataLevel.OWNER_APPROVED,
                FipObjectMetadataLevel.POLICY_DERIVED
            ),
            plan.includedMetadataLevels
        )
        assertTrue(plan.excludedMetadataLevels.contains(FipObjectMetadataLevel.INFERRED))
        assertTrue(plan.excludedMetadataLevels.contains(FipObjectMetadataLevel.SYSTEM_ENFORCED))
        assertTrue(plan.excludedMetadataLevels.contains(FipObjectMetadataLevel.DOMAIN_SPECIFIC))
    }

    @Test
    fun `blocked provider posture denies provider exposure request`() {
        val plan = planner.plan(
            manifest = manifest(),
            request = request(VIEW_LOCAL_ONLY),
            context = context(requiresProviderExposure = true)
        )

        assertEquals(ReconstructionPlanStatus.DENIED, plan.status)
        assertEquals(ProviderExposurePosture.NO_PROVIDER_EXPOSURE, plan.providerExposurePosture)
        assertTrue(plan.denialReasons.contains(ReconstructionDenialReason.PROVIDER_EXPOSURE_BLOCKED))
    }

    @Test
    fun `full source can exist as high authority named view and is not default`() {
        val manifest = manifest()
        val defaultPlan = planner.plan(
            manifest = manifest,
            request = request(FipObjectView("default")),
            context = context()
        )
        val fullPlan = planner.plan(
            manifest = manifest,
            request = request(VIEW_FULL_SOURCE_LOCAL),
            context = context()
        )

        assertEquals(ReconstructionPlanStatus.DENIED, defaultPlan.status)
        assertTrue(defaultPlan.denialReasons.contains(ReconstructionDenialReason.UNKNOWN_VIEW))
        assertEquals(ReconstructionPlanStatus.ALLOWED, fullPlan.status)
        assertEquals(setOf(SHARD_A, SHARD_B, SHARD_C), fullPlan.selectedShardIds)
    }

    @Test
    fun `planner produces trace ready result without reconstructing content`() {
        val requestedAt = Instant.parse("2026-05-20T12:00:00Z")
        val plan = planner.plan(
            manifest = manifest(),
            request = request(VIEW_MODEL_BOUNDED),
            context = context(requestedAt = requestedAt)
        )

        assertEquals(ReconstructionTraceStatus.AUTHORIZED_PLACEHOLDER, plan.reconstructionTrace.status)
        assertEquals("plan:object-1:${VIEW_MODEL_BOUNDED.value}", plan.reconstructionTrace.traceId)
        assertEquals(VIEW_MODEL_BOUNDED, plan.reconstructionTrace.view)
        assertEquals(requestedAt, plan.reconstructionTrace.generatedAt)
    }

    @Test
    fun `core planner does not require deployment specific view names`() {
        val arbitraryView = FipObjectView("analysis-preview")
        val manifest = manifest(
            policy = FipObjectPolicy(
                definedViews = setOf(arbitraryView),
                allowedViews = setOf(arbitraryView),
                viewPolicies = mapOf(
                    arbitraryView to FipObjectViewPolicy(
                        view = arbitraryView,
                        allowedShardIds = setOf(SHARD_A),
                        includedMetadataLevels = setOf(FipObjectMetadataLevel.OBSERVED)
                    )
                )
            )
        )

        val plan = planner.plan(
            manifest = manifest,
            request = request(arbitraryView),
            context = context()
        )

        assertEquals(ReconstructionPlanStatus.ALLOWED, plan.status)
        assertEquals(arbitraryView, plan.requestedView)
        assertEquals(setOf(SHARD_A), plan.selectedShardIds)
    }

    private fun manifest(
        policy: FipObjectPolicy = defaultPolicy()
    ): FipObjectManifest =
        FipObjectManifest(
            objectId = FipObjectId("object-1"),
            name = "Object Evidence Packet",
            assetType = FipAssetType("document-bundle"),
            owner = IdentitySubjectId("owner-1"),
            sensitivityLabels = setOf("restricted"),
            relatedShardIds = setOf(SHARD_A, SHARD_B, SHARD_C),
            metadata = FipObjectMetadata(
                observed = mapOf("filename" to "evidence.md"),
                inferred = mapOf("topic" to "bounded reconstruction"),
                ownerApproved = mapOf("review" to "approved"),
                policyDerived = mapOf("defaultExposure" to "bounded"),
                systemEnforced = mapOf("providerDecryption" to "denied"),
                domainSpecific = mapOf("classification" to "internal")
            ),
            policy = policy
        )

    private fun defaultPolicy(): FipObjectPolicy =
        FipObjectPolicy(
            definedViews = setOf(
                VIEW_MODEL_BOUNDED,
                VIEW_LIMITED,
                VIEW_LOCAL_ONLY,
                VIEW_FULL_SOURCE_LOCAL,
                VIEW_DEFINED_DENIED
            ),
            allowedViews = setOf(
                VIEW_MODEL_BOUNDED,
                VIEW_LIMITED,
                VIEW_LOCAL_ONLY,
                VIEW_FULL_SOURCE_LOCAL
            ),
            viewPolicies = mapOf(
                VIEW_MODEL_BOUNDED to FipObjectViewPolicy(
                    view = VIEW_MODEL_BOUNDED,
                    allowedShardIds = setOf(SHARD_A, SHARD_B),
                    includedMetadataLevels = setOf(
                        FipObjectMetadataLevel.OBSERVED,
                        FipObjectMetadataLevel.OWNER_APPROVED,
                        FipObjectMetadataLevel.POLICY_DERIVED
                    ),
                    providerExposurePolicy = ProviderExposurePolicy(
                        posture = ProviderExposurePosture.BOUNDED_VIEW_ONLY
                    ),
                    auditRequired = true
                ),
                VIEW_LIMITED to FipObjectViewPolicy(
                    view = VIEW_LIMITED,
                    allowedShardIds = setOf(SHARD_A, IdentityShardId("external-shard")),
                    includedMetadataLevels = setOf(FipObjectMetadataLevel.OBSERVED)
                ),
                VIEW_LOCAL_ONLY to FipObjectViewPolicy(
                    view = VIEW_LOCAL_ONLY,
                    allowedShardIds = setOf(SHARD_A),
                    providerExposurePolicy = ProviderExposurePolicy(
                        posture = ProviderExposurePosture.NO_PROVIDER_EXPOSURE
                    )
                ),
                VIEW_FULL_SOURCE_LOCAL to FipObjectViewPolicy(
                    view = VIEW_FULL_SOURCE_LOCAL,
                    allowedShardIds = setOf(SHARD_A, SHARD_B, SHARD_C),
                    includedMetadataLevels = FipObjectMetadataLevel.entries.toSet(),
                    providerExposurePolicy = ProviderExposurePolicy(
                        posture = ProviderExposurePosture.NO_PROVIDER_EXPOSURE
                    ),
                    auditRequired = true
                )
            )
        )

    private fun request(view: FipObjectView): ReconstructionViewRequest =
        ReconstructionViewRequest(
            requestedView = view,
            purpose = ReconstructionPurpose("test-purpose")
        )

    private fun context(
        requestedAt: Instant = Instant.parse("2026-05-20T00:00:00Z"),
        requiresProviderExposure: Boolean = false
    ): ReconstructionContext =
        ReconstructionContext(
            requester = IdentitySubjectId("requester-1"),
            requestedAt = requestedAt,
            requiresProviderExposure = requiresProviderExposure
        )

    private companion object {
        val VIEW_MODEL_BOUNDED = FipObjectView("model-bounded")
        val VIEW_LIMITED = FipObjectView("limited-preview")
        val VIEW_LOCAL_ONLY = FipObjectView("local-only")
        val VIEW_FULL_SOURCE_LOCAL = FipObjectView("full-source-local")
        val VIEW_DEFINED_DENIED = FipObjectView("defined-denied")
        val SHARD_A = IdentityShardId("shard-a")
        val SHARD_B = IdentityShardId("shard-b")
        val SHARD_C = IdentityShardId("shard-c")
    }
}
