package com.innovationstrategies.fip.core.vault

import com.innovationstrategies.fip.core.domain.IdentityShardId
import com.innovationstrategies.fip.core.domain.IdentitySubjectId
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FipReconstructionPacketTest {
    private val planner = FipReconstructionPlanner()

    @Test
    fun `allowed object view produces approved packet`() {
        val packet = packetFor(VIEW_ALLOWED)

        assertTrue(packet.isApproved)
        assertEquals(ReconstructionPlanStatus.ALLOWED, packet.status)
        assertTrue(packet.denialReasons.isEmpty())
        assertEquals(FipObjectId("object-1"), packet.objectId)
        assertEquals(VIEW_ALLOWED, packet.requestedView)
        assertEquals(ReconstructionPurpose("operator-preview"), packet.allowedUse)
    }

    @Test
    fun `denied object view produces denied packet with reason`() {
        val packet = packetFor(FipObjectView("unknown-view"))

        assertFalse(packet.isApproved)
        assertEquals(ReconstructionPlanStatus.DENIED, packet.status)
        assertEquals(setOf(ReconstructionDenialReason.UNKNOWN_VIEW), packet.denialReasons)
        assertEquals(packet.denialReasons, packet.blockedReasons)
        assertTrue(packet.selectedShardIds.isEmpty())
    }

    @Test
    fun `provider exposure posture and output authority are preserved`() {
        val packet = packetFor(VIEW_ALLOWED, requiresProviderExposure = true)

        assertEquals(ProviderExposurePosture.BOUNDED_VIEW_ONLY, packet.providerExposurePosture)
        assertEquals(ProviderOutputAuthority.PROPOSAL_ONLY, packet.providerOutputAuthority)
    }

    @Test
    fun `selected shard ids and metadata levels appear in packet`() {
        val packet = packetFor(VIEW_ALLOWED)

        assertEquals(setOf(IdentityShardId("shard-a")), packet.selectedShardIds)
        assertEquals(
            setOf(FipObjectMetadataLevel.OBSERVED, FipObjectMetadataLevel.OWNER_APPROVED),
            packet.selectedMetadataLevels
        )
        assertTrue(packet.excludedMetadataLevels.contains(FipObjectMetadataLevel.INFERRED))
    }

    @Test
    fun `audit requirement appears in packet`() {
        val packet = packetFor(VIEW_ALLOWED)

        assertTrue(packet.auditRequired)
    }

    @Test
    fun `packet is trace ready without reconstructing content`() {
        val packet = packetFor(VIEW_ALLOWED)

        assertEquals("plan:object-1:${VIEW_ALLOWED.value}", packet.traceId)
        assertEquals(ReconstructionTraceStatus.AUTHORIZED_PLACEHOLDER, packet.traceStatus)
        assertEquals(Instant.parse("2026-05-20T12:00:00Z"), packet.traceGeneratedAt)
    }

    private fun packetFor(
        view: FipObjectView,
        requiresProviderExposure: Boolean = false
    ): FipReconstructionPacket {
        val purpose = ReconstructionPurpose("operator-preview")
        val plan = planner.plan(
            manifest = manifest(),
            request = ReconstructionViewRequest(
                requestedView = view,
                purpose = purpose
            ),
            context = ReconstructionContext(
                requester = IdentitySubjectId("requester-1"),
                requestedAt = Instant.parse("2026-05-20T12:00:00Z"),
                requiresProviderExposure = requiresProviderExposure
            )
        )
        return FipReconstructionPacketFactory.fromPlan(plan, purpose)
    }

    private fun manifest(): FipObjectManifest =
        FipObjectManifest(
            objectId = FipObjectId("object-1"),
            name = "Object Packet",
            assetType = FipAssetType("document-bundle"),
            owner = IdentitySubjectId("owner-1"),
            relatedShardIds = setOf(IdentityShardId("shard-a"), IdentityShardId("shard-b")),
            metadata = FipObjectMetadata(
                observed = mapOf("filename" to "source-name"),
                inferred = mapOf("topic" to "bounded reconstruction"),
                ownerApproved = mapOf("review" to "approved")
            ),
            policy = FipObjectPolicy(
                definedViews = setOf(VIEW_ALLOWED, VIEW_DENIED),
                allowedViews = setOf(VIEW_ALLOWED),
                viewPolicies = mapOf(
                    VIEW_ALLOWED to FipObjectViewPolicy(
                        view = VIEW_ALLOWED,
                        allowedShardIds = setOf(IdentityShardId("shard-a")),
                        includedMetadataLevels = setOf(
                            FipObjectMetadataLevel.OBSERVED,
                            FipObjectMetadataLevel.OWNER_APPROVED
                        ),
                        providerExposurePolicy = ProviderExposurePolicy(
                            posture = ProviderExposurePosture.BOUNDED_VIEW_ONLY
                        ),
                        auditRequired = true
                    )
                ),
                auditRequired = true
            )
        )

    private companion object {
        val VIEW_ALLOWED = FipObjectView("analysis-preview")
        val VIEW_DENIED = FipObjectView("audit-trace")
    }
}
