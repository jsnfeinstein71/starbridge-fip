package com.innovationstrategies.fip.storage.file

import com.innovationstrategies.fip.core.domain.IdentityShard
import com.innovationstrategies.fip.core.domain.IdentityShardId
import com.innovationstrategies.fip.core.domain.IdentitySubjectId
import com.innovationstrategies.fip.core.domain.ShardType
import com.innovationstrategies.fip.core.integrity.IntegrityCheckResult
import com.innovationstrategies.fip.core.integrity.ShardValidationIssue
import com.innovationstrategies.fip.core.integrity.ShardValidationIssueCode
import com.innovationstrategies.fip.core.integrity.ShardValidationResult
import com.innovationstrategies.fip.core.integrity.ShardValidationSeverity
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Properties
import java.util.stream.Collectors
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

class FileIdentityShardIntegrityChecker(
    private val root: Path
) {
    fun check(): IntegrityCheckResult {
        if (!root.exists() || !root.isDirectory()) {
            return IntegrityCheckResult(checkedRecordCount = 0, records = emptyList())
        }

        val records = Files.list(root).use { paths ->
            paths
                .filter { it.isRegularFile() && it.fileName.toString().endsWith(SHARD_FILE_SUFFIX) }
                .collect(Collectors.toList())
                .sortedBy { it.fileName.toString() }
                .map { validateFile(it) }
        }

        return IntegrityCheckResult(
            checkedRecordCount = records.size,
            records = markDuplicateShardIds(records)
        )
    }

    private fun validateFile(path: Path): ShardValidationResult {
        val issues = mutableListOf<ShardValidationIssue>()
        val properties = Properties()
        try {
            Files.newBufferedReader(path, StandardCharsets.UTF_8).use { properties.load(it) }
        } catch (error: Exception) {
            issues += issue(
                ShardValidationIssueCode.PARSE_FAILURE,
                "Unable to read stored shard record: ${error.message.orEmpty()}"
            )
            return ShardValidationResult(
                storageLocation = path.toString(),
                shardId = null,
                subjectId = null,
                issues = issues
            )
        }

        val shardId = properties.getProperty("id")
        val subjectId = properties.getProperty("subjectId")
        validateRequiredFields(properties, issues)
        validateFormatVersion(properties, issues)
        val tagValues = validateTags(properties, issues)
        val shardType = validateShardType(properties, issues)
        val version = validateVersion(properties, issues)
        val observedAt = validateObservedAt(properties, issues)

        val payload = properties.getProperty("payload")
        if (payload != null && payload.isBlank()) {
            issues += issue(ShardValidationIssueCode.INVALID_PAYLOAD, "payload must not be blank.")
        }

        val source = properties.getProperty("source")
        if (source != null && source.isBlank()) {
            issues += issue(ShardValidationIssueCode.INVALID_SOURCE, "source must not be blank.")
        }

        if (shardId != null && subjectId != null && shardType != null && version != null &&
            payload != null && source != null && observedAt != null
        ) {
            validateDomainModel(
                shardId = shardId,
                subjectId = subjectId,
                shardType = shardType,
                version = version,
                payload = payload,
                source = source,
                observedAt = observedAt,
                tags = tagValues,
                issues = issues
            )
        }

        return ShardValidationResult(
            storageLocation = path.toString(),
            shardId = shardId,
            subjectId = subjectId,
            issues = issues
        )
    }

    private fun validateRequiredFields(
        properties: Properties,
        issues: MutableList<ShardValidationIssue>
    ) {
        REQUIRED_FIELDS.forEach { field ->
            if (properties.getProperty(field) == null) {
                issues += issue(
                    ShardValidationIssueCode.REQUIRED_FIELD_MISSING,
                    "Stored shard record is missing required field: $field."
                )
            }
        }
    }

    private fun validateFormatVersion(
        properties: Properties,
        issues: MutableList<ShardValidationIssue>
    ) {
        val rawFormatVersion = properties.getProperty("formatVersion") ?: return
        val formatVersion = rawFormatVersion.toIntOrNull()
        when {
            formatVersion == null -> issues += issue(
                ShardValidationIssueCode.INVALID_FORMAT_VERSION,
                "formatVersion must be an integer."
            )
            formatVersion != FORMAT_VERSION -> issues += issue(
                ShardValidationIssueCode.UNSUPPORTED_FORMAT_VERSION,
                "Unsupported identity shard storage format version: $formatVersion."
            )
        }
    }

    private fun validateTags(
        properties: Properties,
        issues: MutableList<ShardValidationIssue>
    ): Set<String> {
        val rawTagCount = properties.getProperty("tagCount") ?: return emptySet()
        val tagCount = rawTagCount.toIntOrNull()
        if (tagCount == null || tagCount < 0) {
            issues += issue(ShardValidationIssueCode.INVALID_TAGS, "tagCount must be a non-negative integer.")
            return emptySet()
        }

        return (0 until tagCount)
            .mapNotNull { index ->
                val tag = properties.getProperty("tag.$index")
                when {
                    tag == null -> {
                        issues += issue(
                            ShardValidationIssueCode.REQUIRED_FIELD_MISSING,
                            "Stored shard record is missing required field: tag.$index."
                        )
                        null
                    }
                    tag.isBlank() -> {
                        issues += issue(ShardValidationIssueCode.INVALID_TAGS, "tag.$index must not be blank.")
                        tag
                    }
                    else -> tag
                }
            }
            .toSet()
    }

    private fun validateShardType(
        properties: Properties,
        issues: MutableList<ShardValidationIssue>
    ): ShardType? {
        val rawType = properties.getProperty("type") ?: return null
        return runCatching { ShardType.valueOf(rawType) }
            .getOrElse {
                issues += issue(ShardValidationIssueCode.INVALID_SHARD_TYPE, "Invalid shard type: $rawType.")
                null
            }
    }

    private fun validateVersion(
        properties: Properties,
        issues: MutableList<ShardValidationIssue>
    ): Int? {
        val rawVersion = properties.getProperty("version") ?: return null
        val version = rawVersion.toIntOrNull()
        if (version == null || version <= 0) {
            issues += issue(ShardValidationIssueCode.INVALID_VERSION, "version must be a positive integer.")
            return null
        }
        return version
    }

    private fun validateObservedAt(
        properties: Properties,
        issues: MutableList<ShardValidationIssue>
    ): Instant? {
        val rawObservedAt = properties.getProperty("observedAt") ?: return null
        return runCatching { Instant.parse(rawObservedAt) }
            .getOrElse {
                issues += issue(ShardValidationIssueCode.INVALID_OBSERVED_AT, "observedAt must be an ISO-8601 instant.")
                null
            }
    }

    private fun validateDomainModel(
        shardId: String,
        subjectId: String,
        shardType: ShardType,
        version: Int,
        payload: String,
        source: String,
        observedAt: Instant,
        tags: Set<String>,
        issues: MutableList<ShardValidationIssue>
    ) {
        runCatching {
            IdentityShard(
                id = IdentityShardId(shardId),
                subjectId = IdentitySubjectId(subjectId),
                type = shardType,
                version = version,
                payload = payload,
                source = source,
                observedAt = observedAt,
                tags = tags
            )
        }.onFailure { error ->
            val message = error.message.orEmpty()
            issues += issue(
                code = when {
                    message.contains("IdentityShardId") -> ShardValidationIssueCode.INVALID_SHARD_ID
                    message.contains("IdentitySubjectId") -> ShardValidationIssueCode.INVALID_SUBJECT_ID
                    message.contains("version") -> ShardValidationIssueCode.INVALID_VERSION
                    message.contains("payload") -> ShardValidationIssueCode.INVALID_PAYLOAD
                    message.contains("source") -> ShardValidationIssueCode.INVALID_SOURCE
                    message.contains("tags") -> ShardValidationIssueCode.INVALID_TAGS
                    else -> ShardValidationIssueCode.PARSE_FAILURE
                },
                message = message.ifBlank { "Stored shard record failed domain validation." }
            )
        }
    }

    private fun markDuplicateShardIds(records: List<ShardValidationResult>): List<ShardValidationResult> {
        val duplicateIds = records
            .mapNotNull { it.shardId }
            .groupingBy { it }
            .eachCount()
            .filterValues { it > 1 }
            .keys

        if (duplicateIds.isEmpty()) {
            return records
        }

        return records.map { record ->
            if (record.shardId !in duplicateIds) {
                record
            } else {
                record.copy(
                    issues = record.issues + issue(
                        ShardValidationIssueCode.DUPLICATE_SHARD_ID,
                        "Duplicate shard id detected: ${record.shardId}."
                    )
                )
            }
        }
    }

    private fun issue(
        code: ShardValidationIssueCode,
        message: String
    ): ShardValidationIssue =
        ShardValidationIssue(
            code = code,
            severity = ShardValidationSeverity.ERROR,
            message = message
        )

    companion object {
        private const val FORMAT_VERSION = 1
        private const val SHARD_FILE_SUFFIX = ".shard.properties"

        private val REQUIRED_FIELDS = setOf(
            "formatVersion",
            "id",
            "subjectId",
            "type",
            "version",
            "payload",
            "source",
            "observedAt",
            "tagCount"
        )
    }
}
