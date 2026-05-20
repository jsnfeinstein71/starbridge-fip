package com.innovationstrategies.fip.storage.file

import com.innovationstrategies.fip.core.domain.IdentityShardId
import com.innovationstrategies.fip.core.domain.IdentitySubjectId
import com.innovationstrategies.fip.core.vault.FipAssetType
import com.innovationstrategies.fip.core.vault.FipObjectId
import com.innovationstrategies.fip.core.vault.FipObjectManifest
import com.innovationstrategies.fip.core.vault.FipObjectMetadata
import com.innovationstrategies.fip.core.vault.FipObjectMetadataLevel
import com.innovationstrategies.fip.core.vault.FipObjectPolicy
import com.innovationstrategies.fip.core.vault.FipObjectView
import com.innovationstrategies.fip.core.vault.FipObjectViewPolicy
import com.innovationstrategies.fip.core.vault.ProviderExposurePolicy
import com.innovationstrategies.fip.core.vault.ProviderExposurePosture
import com.innovationstrategies.fip.core.vault.ProviderOutputAuthority
import com.innovationstrategies.fip.core.vault.ReconstructionTrace
import com.innovationstrategies.fip.core.vault.ReconstructionTraceStatus
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileFipObjectManifestRepositoryTest {
    @Test
    fun `manifest save and load round trips`() {
        val repository = repository()
        val manifest = manifest("object-1")

        repository.save(manifest)

        assertEquals(manifest, repository.load(manifest.objectId))
    }

    @Test
    fun `list returns stored manifests sorted by object id`() {
        val repository = repository()
        val second = manifest("object-b")
        val first = manifest("object-a")

        repository.save(second)
        repository.save(first)

        assertEquals(listOf(first, second), repository.list())
    }

    @Test
    fun `delete removes a manifest`() {
        val repository = repository()
        val manifest = manifest("object-1")
        repository.save(manifest)

        assertTrue(repository.delete(manifest.objectId))

        assertNull(repository.load(manifest.objectId))
        assertFalse(repository.delete(manifest.objectId))
    }

    @Test
    fun `metadata levels remain distinct after load`() {
        val repository = repository()
        val manifest = manifest("object-1")

        repository.save(manifest)

        val loaded = assertNotNull(repository.load(manifest.objectId))
        assertEquals("source-name", loaded.metadata.observed["filename"])
        assertEquals("derived-topic", loaded.metadata.inferred["topic"])
        assertEquals("approved", loaded.metadata.ownerApproved["review"])
        assertEquals("bounded", loaded.metadata.policyDerived["exposure"])
        assertEquals("denied", loaded.metadata.systemEnforced["providerDecryption"])
        assertEquals("internal", loaded.metadata.domainSpecific["classification"])
    }

    @Test
    fun `arbitrary generic view names persist correctly`() {
        val repository = repository()
        val manifest = manifest("object-1")

        repository.save(manifest)

        val loaded = assertNotNull(repository.load(manifest.objectId))
        assertEquals(
            setOf("analysis-preview", "full-source-local", "audit-trace"),
            loaded.policy.definedViews.map { it.value }.toSet()
        )
        assertEquals(
            setOf("analysis-preview", "full-source-local"),
            loaded.policy.allowedViews.map { it.value }.toSet()
        )
    }

    @Test
    fun `per-view policy persists correctly`() {
        val repository = repository()
        val manifest = manifest("object-1")

        repository.save(manifest)

        val loaded = assertNotNull(repository.load(manifest.objectId))
        val viewPolicy = assertNotNull(loaded.policy.viewPolicies[ANALYSIS_VIEW])
        assertEquals(setOf(IdentityShardId("shard-a")), viewPolicy.allowedShardIds)
        assertEquals(
            setOf(FipObjectMetadataLevel.OBSERVED, FipObjectMetadataLevel.OWNER_APPROVED),
            viewPolicy.includedMetadataLevels
        )
        assertEquals(ProviderExposurePosture.BOUNDED_VIEW_ONLY, viewPolicy.providerExposurePolicy?.posture)
        assertEquals(true, viewPolicy.auditRequired)
        assertTrue(viewPolicy.reconstructionAllowed)
    }

    @Test
    fun `provider exposure and audit posture persist correctly`() {
        val repository = repository()
        val manifest = manifest("object-1")

        repository.save(manifest)

        val loaded = assertNotNull(repository.load(manifest.objectId))
        assertTrue(loaded.policy.auditRequired)
        assertEquals(ProviderExposurePosture.NO_PROVIDER_DECRYPTION, loaded.policy.providerExposurePolicy.posture)
        assertFalse(loaded.policy.providerExposurePolicy.providerMayDecrypt)
        assertFalse(loaded.policy.providerExposurePolicy.providerMayHoldReconstructionAuthority)
        assertEquals(ProviderOutputAuthority.PROPOSAL_ONLY, loaded.policy.providerExposurePolicy.providerOutputAuthority)
    }

    @Test
    fun `stored manifest file uses explicit fields`() {
        val root = Files.createTempDirectory("fip-object-manifest-test")
        val repository = FileFipObjectManifestRepository(root)
        repository.save(manifest("object-1"))

        val manifestFile = assertNotNull(firstManifestFile(root))
        val contents = manifestFile.readText()
        assertTrue(contents.contains("formatVersion=1"))
        assertTrue(contents.contains("objectId=object-1"))
        assertTrue(contents.contains("assetType=document-bundle"))
        assertTrue(contents.contains("sensitivityLabelCount=2"))
        assertTrue(contents.contains("relatedShardIdCount=2"))
        assertTrue(contents.contains("metadata.observedCount=1"))
        assertTrue(contents.contains("metadata.systemEnforced.0.key=providerDecryption"))
        assertTrue(contents.contains("policy.definedViewCount=3"))
        assertTrue(contents.contains("policy.allowedViewCount=2"))
        assertTrue(contents.contains("policy.viewPolicyCount=2"))
        assertTrue(contents.contains("policy.providerExposure.posture=NO_PROVIDER_DECRYPTION"))
        assertTrue(contents.contains("reconstructionTrace.status=AUTHORIZED_PLACEHOLDER"))
    }

    @Test
    fun `malformed or unsupported stored manifest records fail clearly`() {
        val root = Files.createTempDirectory("fip-object-manifest-test")
        val repository = FileFipObjectManifestRepository(root)
        val manifest = manifest("object-1")
        repository.save(manifest)
        assertNotNull(firstManifestFile(root)).writeText(
            """
            formatVersion=99
            objectId=object-1
            """.trimIndent()
        )

        val error = assertFailsWith<IllegalArgumentException> {
            repository.load(manifest.objectId)
        }

        assertTrue(
            error.message.orEmpty().contains("Unsupported FIP object manifest storage format version: 99.")
        )
    }

    @Test
    fun `missing required manifest fields fail clearly`() {
        val root = Files.createTempDirectory("fip-object-manifest-test")
        val repository = FileFipObjectManifestRepository(root)
        val manifest = manifest("object-1")
        repository.save(manifest)
        assertNotNull(firstManifestFile(root)).writeText("formatVersion=1")

        val error = assertFailsWith<IllegalArgumentException> {
            repository.load(manifest.objectId)
        }

        assertTrue(error.message.orEmpty().contains("missing required field: objectId"))
    }

    private fun repository(): FileFipObjectManifestRepository =
        FileFipObjectManifestRepository(Files.createTempDirectory("fip-object-manifest-test"))

    private fun manifest(objectId: String): FipObjectManifest =
        FipObjectManifest(
            objectId = FipObjectId(objectId),
            name = "Object Evidence Packet $objectId",
            assetType = FipAssetType("document-bundle"),
            owner = IdentitySubjectId("owner-1"),
            sensitivityLabels = setOf("identity-context", "restricted"),
            relatedShardIds = setOf(IdentityShardId("shard-a"), IdentityShardId("shard-b")),
            metadata = FipObjectMetadata(
                observed = mapOf("filename" to "source-name"),
                inferred = mapOf("topic" to "derived-topic"),
                ownerApproved = mapOf("review" to "approved"),
                policyDerived = mapOf("exposure" to "bounded"),
                systemEnforced = mapOf("providerDecryption" to "denied"),
                domainSpecific = mapOf("classification" to "internal")
            ),
            policy = FipObjectPolicy(
                definedViews = setOf(ANALYSIS_VIEW, FULL_SOURCE_VIEW, AUDIT_TRACE_VIEW),
                allowedViews = setOf(ANALYSIS_VIEW, FULL_SOURCE_VIEW),
                viewPolicies = mapOf(
                    ANALYSIS_VIEW to FipObjectViewPolicy(
                        view = ANALYSIS_VIEW,
                        allowedShardIds = setOf(IdentityShardId("shard-a")),
                        includedMetadataLevels = setOf(
                            FipObjectMetadataLevel.OBSERVED,
                            FipObjectMetadataLevel.OWNER_APPROVED
                        ),
                        providerExposurePolicy = ProviderExposurePolicy(
                            posture = ProviderExposurePosture.BOUNDED_VIEW_ONLY
                        ),
                        auditRequired = true
                    ),
                    FULL_SOURCE_VIEW to FipObjectViewPolicy(
                        view = FULL_SOURCE_VIEW,
                        allowedShardIds = setOf(IdentityShardId("shard-a"), IdentityShardId("shard-b")),
                        includedMetadataLevels = FipObjectMetadataLevel.entries.toSet(),
                        providerExposurePolicy = ProviderExposurePolicy(
                            posture = ProviderExposurePosture.NO_PROVIDER_EXPOSURE
                        ),
                        auditRequired = true
                    )
                ),
                providerExposurePolicy = ProviderExposurePolicy(
                    posture = ProviderExposurePosture.NO_PROVIDER_DECRYPTION
                ),
                auditRequired = true
            ),
            reconstructionTrace = ReconstructionTrace(
                traceId = "plan:$objectId:${ANALYSIS_VIEW.value}",
                status = ReconstructionTraceStatus.AUTHORIZED_PLACEHOLDER,
                view = ANALYSIS_VIEW,
                generatedAt = Instant.parse("2026-05-20T12:00:00Z"),
                decisionRefs = listOf("policy-view-allowed")
            )
        )

    private fun firstManifestFile(root: Path): Path? =
        Files.list(root).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".object-manifest.properties") }
                .findFirst()
                .orElse(null)
        }

    private companion object {
        val ANALYSIS_VIEW = FipObjectView("analysis-preview")
        val FULL_SOURCE_VIEW = FipObjectView("full-source-local")
        val AUDIT_TRACE_VIEW = FipObjectView("audit-trace")
    }
}
