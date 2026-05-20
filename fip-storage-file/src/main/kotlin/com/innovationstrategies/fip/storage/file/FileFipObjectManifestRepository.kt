package com.innovationstrategies.fip.storage.file

import com.innovationstrategies.fip.core.domain.IdentityShardId
import com.innovationstrategies.fip.core.domain.IdentitySubjectId
import com.innovationstrategies.fip.core.storage.FipObjectManifestRepository
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
import java.io.BufferedReader
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.time.Instant
import java.util.Base64
import java.util.Properties
import java.util.stream.Collectors
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile

class FileFipObjectManifestRepository(
    private val root: Path
) : FipObjectManifestRepository {
    init {
        Files.createDirectories(root)
    }

    override fun save(manifest: FipObjectManifest): FipObjectManifest {
        Files.createDirectories(root)
        val target = pathFor(manifest.objectId)
        val temporary = Files.createTempFile(root, target.fileName.toString(), ".tmp")

        try {
            Files.newBufferedWriter(temporary, StandardCharsets.UTF_8).use { writer ->
                propertiesFor(manifest).store(writer, "FIP object manifest")
            }
            moveIntoPlace(temporary, target)
        } finally {
            Files.deleteIfExists(temporary)
        }

        return manifest
    }

    override fun load(objectId: FipObjectId): FipObjectManifest? {
        val path = pathFor(objectId)
        if (!path.exists()) {
            return null
        }
        return readManifest(path).also { manifest ->
            require(manifest.objectId == objectId) {
                "Stored FIP object manifest id does not match requested object id."
            }
        }
    }

    override fun list(): List<FipObjectManifest> =
        Files.list(root).use { paths ->
            paths
                .filter { it.isRegularFile() && it.fileName.toString().endsWith(MANIFEST_FILE_SUFFIX) }
                .collect(Collectors.toList())
                .map { readManifest(it) }
                .sortedBy { it.objectId.value }
        }

    override fun delete(objectId: FipObjectId): Boolean =
        Files.deleteIfExists(pathFor(objectId))

    private fun readManifest(path: Path): FipObjectManifest {
        val properties = Properties()
        Files.newBufferedReader(path, StandardCharsets.UTF_8).use { reader: BufferedReader ->
            properties.load(reader)
        }

        val formatVersion = properties.required("formatVersion").toInt()
        require(formatVersion == FORMAT_VERSION) {
            "Unsupported FIP object manifest storage format version: $formatVersion."
        }

        return FipObjectManifest(
            objectId = FipObjectId(properties.required("objectId")),
            name = properties.required("name"),
            assetType = FipAssetType(properties.required("assetType")),
            owner = IdentitySubjectId(properties.required("owner")),
            sensitivityLabels = readStringSet(properties, "sensitivityLabel"),
            relatedShardIds = readStringSet(properties, "relatedShardId").map { IdentityShardId(it) }.toSet(),
            metadata = FipObjectMetadata(
                observed = readStringMap(properties, "metadata.observed"),
                inferred = readStringMap(properties, "metadata.inferred"),
                ownerApproved = readStringMap(properties, "metadata.ownerApproved"),
                policyDerived = readStringMap(properties, "metadata.policyDerived"),
                systemEnforced = readStringMap(properties, "metadata.systemEnforced"),
                domainSpecific = readStringMap(properties, "metadata.domainSpecific")
            ),
            policy = readPolicy(properties),
            reconstructionTrace = readReconstructionTrace(properties)
        )
    }

    private fun readPolicy(properties: Properties): FipObjectPolicy {
        val definedViews = readStringSet(properties, "policy.definedView").map { FipObjectView(it) }.toSet()
        val allowedViews = readStringSet(properties, "policy.allowedView").map { FipObjectView(it) }.toSet()
        val viewPolicyCount = properties.required("policy.viewPolicyCount").toInt()
        require(viewPolicyCount >= 0) { "policy.viewPolicyCount must not be negative." }
        val viewPolicies = (0 until viewPolicyCount)
            .map { index -> readViewPolicy(properties, index) }
            .associateBy { it.view }

        return FipObjectPolicy(
            definedViews = definedViews,
            allowedViews = allowedViews,
            viewPolicies = viewPolicies,
            providerExposurePolicy = readProviderExposurePolicy(properties, "policy.providerExposure"),
            auditRequired = properties.required("policy.auditRequired").toBooleanStrict()
        )
    }

    private fun readViewPolicy(properties: Properties, index: Int): FipObjectViewPolicy {
        val prefix = "policy.viewPolicy.$index"
        val providerExposurePolicy = if (properties.required("$prefix.providerExposure.present").toBooleanStrict()) {
            readProviderExposurePolicy(properties, "$prefix.providerExposure")
        } else {
            null
        }
        val auditRequired = if (properties.required("$prefix.auditRequired.present").toBooleanStrict()) {
            properties.required("$prefix.auditRequired").toBooleanStrict()
        } else {
            null
        }
        val allowedShardIds = when (properties.required("$prefix.allowedShardIdMode")) {
            "ALL_RELATED" -> null
            "EXPLICIT" -> readStringSet(properties, "$prefix.allowedShardId").map { IdentityShardId(it) }.toSet()
            else -> error("Unsupported FIP object view policy allowedShardIdMode.")
        }

        return FipObjectViewPolicy(
            view = FipObjectView(properties.required("$prefix.view")),
            allowedShardIds = allowedShardIds,
            includedMetadataLevels = readStringSet(properties, "$prefix.includedMetadataLevel")
                .map { FipObjectMetadataLevel.valueOf(it) }
                .toSet(),
            providerExposurePolicy = providerExposurePolicy,
            auditRequired = auditRequired,
            reconstructionAllowed = properties.required("$prefix.reconstructionAllowed").toBooleanStrict()
        )
    }

    private fun readProviderExposurePolicy(properties: Properties, prefix: String): ProviderExposurePolicy =
        ProviderExposurePolicy(
            posture = ProviderExposurePosture.valueOf(properties.required("$prefix.posture")),
            providerMayDecrypt = properties.required("$prefix.providerMayDecrypt").toBooleanStrict(),
            providerMayHoldReconstructionAuthority =
                properties.required("$prefix.providerMayHoldReconstructionAuthority").toBooleanStrict(),
            providerOutputAuthority = ProviderOutputAuthority.valueOf(properties.required("$prefix.providerOutputAuthority"))
        )

    private fun readReconstructionTrace(properties: Properties): ReconstructionTrace {
        val view = if (properties.required("reconstructionTrace.view.present").toBooleanStrict()) {
            FipObjectView(properties.required("reconstructionTrace.view"))
        } else {
            null
        }
        val generatedAt = if (properties.required("reconstructionTrace.generatedAt.present").toBooleanStrict()) {
            Instant.parse(properties.required("reconstructionTrace.generatedAt"))
        } else {
            null
        }

        return ReconstructionTrace(
            traceId = properties.required("reconstructionTrace.traceId"),
            status = ReconstructionTraceStatus.valueOf(properties.required("reconstructionTrace.status")),
            view = view,
            generatedAt = generatedAt,
            decisionRefs = readStringList(properties, "reconstructionTrace.decisionRef")
        )
    }

    private fun propertiesFor(manifest: FipObjectManifest): Properties =
        Properties().apply {
            setProperty("formatVersion", FORMAT_VERSION.toString())
            setProperty("objectId", manifest.objectId.value)
            setProperty("name", manifest.name)
            setProperty("assetType", manifest.assetType.value)
            setProperty("owner", manifest.owner.value)
            writeStringSet("sensitivityLabel", manifest.sensitivityLabels)
            writeStringSet("relatedShardId", manifest.relatedShardIds.map { it.value }.toSet())
            writeStringMap("metadata.observed", manifest.metadata.observed)
            writeStringMap("metadata.inferred", manifest.metadata.inferred)
            writeStringMap("metadata.ownerApproved", manifest.metadata.ownerApproved)
            writeStringMap("metadata.policyDerived", manifest.metadata.policyDerived)
            writeStringMap("metadata.systemEnforced", manifest.metadata.systemEnforced)
            writeStringMap("metadata.domainSpecific", manifest.metadata.domainSpecific)
            writePolicy(manifest.policy)
            writeReconstructionTrace(manifest.reconstructionTrace)
        }

    private fun Properties.writePolicy(policy: FipObjectPolicy) {
        writeStringSet("policy.definedView", policy.definedViews.map { it.value }.toSet())
        writeStringSet("policy.allowedView", policy.allowedViews.map { it.value }.toSet())
        writeProviderExposurePolicy("policy.providerExposure", policy.providerExposurePolicy)
        setProperty("policy.auditRequired", policy.auditRequired.toString())

        val viewPolicies = policy.viewPolicies.values.sortedBy { it.view.value }
        setProperty("policy.viewPolicyCount", viewPolicies.size.toString())
        viewPolicies.forEachIndexed { index, viewPolicy ->
            val prefix = "policy.viewPolicy.$index"
            val allowedShardIds = viewPolicy.allowedShardIds
            setProperty("$prefix.view", viewPolicy.view.value)
            if (allowedShardIds == null) {
                setProperty("$prefix.allowedShardIdMode", "ALL_RELATED")
                writeStringSet("$prefix.allowedShardId", emptySet())
            } else {
                setProperty("$prefix.allowedShardIdMode", "EXPLICIT")
                writeStringSet("$prefix.allowedShardId", allowedShardIds.map { it.value }.toSet())
            }
            writeStringSet("$prefix.includedMetadataLevel", viewPolicy.includedMetadataLevels.map { it.name }.toSet())
            setProperty("$prefix.providerExposure.present", (viewPolicy.providerExposurePolicy != null).toString())
            viewPolicy.providerExposurePolicy?.let { writeProviderExposurePolicy("$prefix.providerExposure", it) }
            setProperty("$prefix.auditRequired.present", (viewPolicy.auditRequired != null).toString())
            viewPolicy.auditRequired?.let { setProperty("$prefix.auditRequired", it.toString()) }
            setProperty("$prefix.reconstructionAllowed", viewPolicy.reconstructionAllowed.toString())
        }
    }

    private fun Properties.writeProviderExposurePolicy(prefix: String, policy: ProviderExposurePolicy) {
        setProperty("$prefix.posture", policy.posture.name)
        setProperty("$prefix.providerMayDecrypt", policy.providerMayDecrypt.toString())
        setProperty(
            "$prefix.providerMayHoldReconstructionAuthority",
            policy.providerMayHoldReconstructionAuthority.toString()
        )
        setProperty("$prefix.providerOutputAuthority", policy.providerOutputAuthority.name)
    }

    private fun Properties.writeReconstructionTrace(trace: ReconstructionTrace) {
        setProperty("reconstructionTrace.traceId", trace.traceId)
        setProperty("reconstructionTrace.status", trace.status.name)
        setProperty("reconstructionTrace.view.present", (trace.view != null).toString())
        trace.view?.let { setProperty("reconstructionTrace.view", it.value) }
        setProperty("reconstructionTrace.generatedAt.present", (trace.generatedAt != null).toString())
        trace.generatedAt?.let { setProperty("reconstructionTrace.generatedAt", it.toString()) }
        writeStringList("reconstructionTrace.decisionRef", trace.decisionRefs)
    }

    private fun readStringSet(properties: Properties, prefix: String): Set<String> =
        readStringList(properties, prefix).toSet()

    private fun readStringList(properties: Properties, prefix: String): List<String> {
        val count = properties.required("${prefix}Count").toInt()
        require(count >= 0) { "${prefix}Count must not be negative." }
        return (0 until count).map { index -> properties.required("$prefix.$index") }
    }

    private fun readStringMap(properties: Properties, prefix: String): Map<String, String> {
        val count = properties.required("${prefix}Count").toInt()
        require(count >= 0) { "${prefix}Count must not be negative." }
        return (0 until count).associate { index ->
            properties.required("$prefix.$index.key") to properties.required("$prefix.$index.value")
        }
    }

    private fun Properties.writeStringSet(prefix: String, values: Set<String>) {
        writeStringList(prefix, values.sorted())
    }

    private fun Properties.writeStringList(prefix: String, values: List<String>) {
        setProperty("${prefix}Count", values.size.toString())
        values.forEachIndexed { index, value -> setProperty("$prefix.$index", value) }
    }

    private fun Properties.writeStringMap(prefix: String, values: Map<String, String>) {
        val entries = values.toSortedMap().entries.toList()
        setProperty("${prefix}Count", entries.size.toString())
        entries.forEachIndexed { index, entry ->
            setProperty("$prefix.$index.key", entry.key)
            setProperty("$prefix.$index.value", entry.value)
        }
    }

    private fun pathFor(objectId: FipObjectId): Path =
        root.resolve("${fileNameTokenFor(objectId)}$MANIFEST_FILE_SUFFIX").normalize()

    private fun fileNameTokenFor(objectId: FipObjectId): String =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(objectId.value.toByteArray(StandardCharsets.UTF_8))

    private fun moveIntoPlace(source: Path, target: Path) {
        try {
            Files.move(source, target, REPLACE_EXISTING, ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target, REPLACE_EXISTING)
        }
    }

    private fun Properties.required(key: String): String =
        requireNotNull(getProperty(key)) { "Stored FIP object manifest is missing required field: $key." }

    companion object {
        private const val FORMAT_VERSION = 1
        private const val MANIFEST_FILE_SUFFIX = ".object-manifest.properties"
    }
}
