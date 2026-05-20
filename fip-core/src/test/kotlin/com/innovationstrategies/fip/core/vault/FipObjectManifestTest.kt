package com.innovationstrategies.fip.core.vault

import com.innovationstrategies.fip.core.domain.IdentityShardId
import com.innovationstrategies.fip.core.domain.IdentitySubjectId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FipObjectManifestTest {
    @Test
    fun `valid object manifest can be created`() {
        val manifest = manifest()

        assertEquals("object-1", manifest.objectId.value)
        assertEquals("Object Evidence Packet", manifest.name)
        assertEquals("document-bundle", manifest.assetType.value)
        assertEquals("owner-1", manifest.owner.value)
        assertTrue(manifest.policy.auditRequired)
        assertEquals(ReconstructionTraceStatus.NOT_RECONSTRUCTED, manifest.reconstructionTrace.status)
    }

    @Test
    fun `blank object identifiers and names are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            FipObjectId(" ")
        }
        assertFailsWith<IllegalArgumentException> {
            manifest(name = " ")
        }
    }

    @Test
    fun `metadata levels are preserved distinctly`() {
        val metadata = FipObjectMetadata(
            observed = mapOf("filename" to "evidence.md"),
            inferred = mapOf("topic" to "privacy-preserving reconstruction"),
            ownerApproved = mapOf("release" to "review-safe"),
            policyDerived = mapOf("systemMeta" to "default-deny"),
            systemEnforced = mapOf("providerDecryption" to "denied"),
            domainSpecific = mapOf("domain" to "object-vault")
        )
        val manifest = manifest(metadata = metadata)

        assertEquals("evidence.md", manifest.metadata.observed["filename"])
        assertEquals("privacy-preserving reconstruction", manifest.metadata.inferred["topic"])
        assertEquals("review-safe", manifest.metadata.ownerApproved["release"])
        assertEquals("default-deny", manifest.metadata.policyDerived["systemMeta"])
        assertEquals("denied", manifest.metadata.systemEnforced["providerDecryption"])
        assertEquals("object-vault", manifest.metadata.domainSpecific["domain"])
    }

    @Test
    fun `allowed views are represented clearly`() {
        val fullView = FipObjectView("full-local")
        val modelSafeView = FipObjectView("model-safe")
        val summaryView = FipObjectView("summary")
        val manifest = manifest(
            policy = FipObjectPolicy(
                definedViews = setOf(fullView, modelSafeView, summaryView),
                allowedViews = setOf(
                    fullView,
                    modelSafeView,
                    summaryView
                )
            )
        )

        assertEquals(
            setOf("full-local", "model-safe", "summary"),
            manifest.allowedViews.map { it.externalName }.toSet()
        )
    }

    @Test
    fun `audit required and provider exposure policy are represented`() {
        val modelSafeView = FipObjectView("model-safe")
        val auditView = FipObjectView("audit-trace")
        val providerExposurePolicy = ProviderExposurePolicy(
            posture = ProviderExposurePosture.BOUNDED_VIEW_ONLY,
            providerMayDecrypt = false,
            providerMayHoldReconstructionAuthority = false,
            providerOutputAuthority = ProviderOutputAuthority.PROPOSAL_ONLY
        )
        val manifest = manifest(
            policy = FipObjectPolicy(
                definedViews = setOf(modelSafeView, auditView),
                allowedViews = setOf(modelSafeView, auditView),
                providerExposurePolicy = providerExposurePolicy,
                auditRequired = true
            )
        )

        assertTrue(manifest.policy.auditRequired)
        assertEquals(ProviderExposurePosture.BOUNDED_VIEW_ONLY, manifest.policy.providerExposurePolicy.posture)
        assertFalse(manifest.policy.providerExposurePolicy.providerMayDecrypt)
        assertFalse(manifest.policy.providerExposurePolicy.providerMayHoldReconstructionAuthority)
        assertEquals(ProviderOutputAuthority.PROPOSAL_ONLY, manifest.policy.providerExposurePolicy.providerOutputAuthority)
    }

    @Test
    fun `provider exposure policy rejects provider decryption authority`() {
        assertFailsWith<IllegalArgumentException> {
            ProviderExposurePolicy(providerMayDecrypt = true)
        }
        assertFailsWith<IllegalArgumentException> {
            ProviderExposurePolicy(providerMayHoldReconstructionAuthority = true)
        }
    }

    @Test
    fun `related shard ids can be attached to object manifest`() {
        val relatedShardIds = setOf(
            IdentityShardId("shard-core-1"),
            IdentityShardId("shard-policy-1")
        )
        val manifest = manifest(relatedShardIds = relatedShardIds)

        assertEquals(relatedShardIds, manifest.relatedShardIds)
    }

    private fun manifest(
        name: String = "Object Evidence Packet",
        metadata: FipObjectMetadata = FipObjectMetadata(),
        policy: FipObjectPolicy = FipObjectPolicy(),
        relatedShardIds: Set<IdentityShardId> = emptySet()
    ): FipObjectManifest =
        FipObjectManifest(
            objectId = FipObjectId("object-1"),
            name = name,
            assetType = FipAssetType("document-bundle"),
            owner = IdentitySubjectId("owner-1"),
            sensitivityLabels = setOf("restricted", "identity-context"),
            relatedShardIds = relatedShardIds,
            metadata = metadata,
            policy = policy
        )
}
