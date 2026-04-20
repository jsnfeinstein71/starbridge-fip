package com.innovationstrategies.fip.storage.file

import com.innovationstrategies.fip.core.domain.IdentityShard
import com.innovationstrategies.fip.core.domain.IdentityShardId
import com.innovationstrategies.fip.core.domain.IdentitySubjectId
import com.innovationstrategies.fip.core.domain.PlaintextContentProtection
import com.innovationstrategies.fip.core.domain.ProtectedShardContent
import com.innovationstrategies.fip.core.domain.ProtectedShardContentMode
import com.innovationstrategies.fip.core.domain.ShardType
import com.innovationstrategies.fip.core.storage.IdentityShardRepository
import java.io.BufferedReader
import java.io.BufferedWriter
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

class FileIdentityShardRepository(
    private val root: Path
) : IdentityShardRepository {
    init {
        Files.createDirectories(root)
    }

    override fun save(shard: IdentityShard): IdentityShard {
        Files.createDirectories(root)
        val target = pathFor(shard.id)
        val temporary = Files.createTempFile(root, target.fileName.toString(), ".tmp")

        try {
            Files.newBufferedWriter(temporary, StandardCharsets.UTF_8).use { writer ->
                propertiesFor(shard).store(writer, "FIP identity shard")
            }
            moveIntoPlace(temporary, target)
        } finally {
            Files.deleteIfExists(temporary)
        }

        return shard
    }

    override fun load(id: IdentityShardId): IdentityShard? {
        val path = pathFor(id)
        if (!path.exists()) {
            return null
        }
        return readShard(path)
    }

    override fun listForSubject(subjectId: IdentitySubjectId): List<IdentityShard> =
        Files.list(root).use { paths ->
            paths
                .filter { it.isRegularFile() && it.fileName.toString().endsWith(SHARD_FILE_SUFFIX) }
                .collect(Collectors.toList())
                .map { readShard(it) }
                .filter { it.subjectId == subjectId }
                .sortedBy { it.id.value }
        }

    override fun delete(id: IdentityShardId): Boolean =
        Files.deleteIfExists(pathFor(id))

    private fun readShard(path: Path): IdentityShard {
        val properties = Properties()
        Files.newBufferedReader(path, StandardCharsets.UTF_8).use { reader: BufferedReader ->
            properties.load(reader)
        }

        val formatVersion = properties.required("formatVersion").toInt()
        require(formatVersion == FORMAT_VERSION) {
            "Unsupported identity shard storage format version: $formatVersion."
        }

        val tagCount = properties.required("tagCount").toInt()
        val tags = (0 until tagCount)
            .map { index -> properties.required("tag.$index") }
            .toSet()

        val protectedContent = readProtectedContent(properties)

        return IdentityShard(
            id = IdentityShardId(properties.required("id")),
            subjectId = IdentitySubjectId(properties.required("subjectId")),
            type = ShardType.valueOf(properties.required("type")),
            version = properties.required("version").toInt(),
            protectedContent = protectedContent,
            payload = compatibilityPayloadFor(protectedContent),
            source = properties.required("source"),
            observedAt = Instant.parse(properties.required("observedAt")),
            tags = tags
        )
    }

    private fun propertiesFor(shard: IdentityShard): Properties =
        Properties().apply {
            setProperty("formatVersion", FORMAT_VERSION.toString())
            setProperty("id", shard.id.value)
            setProperty("subjectId", shard.subjectId.value)
            setProperty("type", shard.type.name)
            setProperty("version", shard.version.toString())
            setProperty("source", shard.source)
            setProperty("observedAt", shard.observedAt.toString())
            setProperty("contentMode", shard.protectedContent.mode.name)
            setProperty("contentValue", shard.protectedContent.value)
            setProperty("contentAlgorithm", contentAlgorithmFor(shard.protectedContent))

            val sortedTags = shard.tags.sorted()
            setProperty("tagCount", sortedTags.size.toString())
            sortedTags.forEachIndexed { index, tag ->
                setProperty("tag.$index", tag)
            }
        }

    private fun readProtectedContent(properties: Properties): ProtectedShardContent {
        val contentMode = ProtectedShardContentMode.valueOf(properties.required("contentMode"))
        val contentValue = properties.required("contentValue")
        val contentAlgorithm = properties.required("contentAlgorithm")
        return when (contentMode) {
            ProtectedShardContentMode.PLAINTEXT -> ProtectedShardContent.Plaintext(contentValue)
            ProtectedShardContentMode.ENCRYPTED -> ProtectedShardContent.EncryptedPayload(
                value = contentValue,
                algorithm = contentAlgorithm
            )
        }
    }

    private fun compatibilityPayloadFor(content: ProtectedShardContent): String =
        when (content) {
            is ProtectedShardContent.Plaintext -> PlaintextContentProtection.compatibilityPayload(content)
            is ProtectedShardContent.EncryptedPayload -> ProtectedShardContent.PROTECTED_PAYLOAD_PLACEHOLDER
        }

    private fun contentAlgorithmFor(content: ProtectedShardContent): String =
        when (content) {
            is ProtectedShardContent.Plaintext -> PLAINTEXT_CONTENT_ALGORITHM
            is ProtectedShardContent.EncryptedPayload -> content.algorithm
        }

    private fun pathFor(id: IdentityShardId): Path =
        root.resolve("${fileNameTokenFor(id)}$SHARD_FILE_SUFFIX").normalize()

    private fun fileNameTokenFor(id: IdentityShardId): String =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(id.value.toByteArray(StandardCharsets.UTF_8))

    private fun moveIntoPlace(source: Path, target: Path) {
        try {
            Files.move(source, target, REPLACE_EXISTING, ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target, REPLACE_EXISTING)
        }
    }

    private fun Properties.required(key: String): String =
        requireNotNull(getProperty(key)) { "Stored identity shard is missing required field: $key." }

    companion object {
        private const val FORMAT_VERSION = 1
        private const val SHARD_FILE_SUFFIX = ".shard.properties"
        private const val PLAINTEXT_CONTENT_ALGORITHM = "PLAINTEXT-DEVELOPMENT"
    }
}
