package com.innovationstrategies.fip.core.artifact

import com.innovationstrategies.fip.core.domain.ReconstructionResult
import com.innovationstrategies.fip.core.integrity.IntegrityCheckResult
import com.innovationstrategies.fip.core.writeback.WriteBackResult
import java.time.Instant

data class OperationArtifact(
    val formatVersion: Int = CURRENT_FORMAT_VERSION,
    val operationType: OperationArtifactType,
    val generatedAt: Instant,
    val fields: Map<String, String>,
    val entries: List<OperationArtifactEntry> = emptyList()
) {
    init {
        require(formatVersion == CURRENT_FORMAT_VERSION) {
            "Unsupported operation artifact formatVersion: $formatVersion."
        }
        require(fields.keys.none { it.isBlank() }) { "Operation artifact field keys must not be blank." }
        require(entries.all { it.kind.isNotBlank() }) { "Operation artifact entry kinds must not be blank." }
    }

    companion object {
        const val CURRENT_FORMAT_VERSION: Int = 1
    }
}

data class OperationArtifactEntry(
    val kind: String,
    val fields: Map<String, String>
) {
    init {
        require(kind.isNotBlank()) { "Operation artifact entry kind must not be blank." }
        require(fields.keys.none { it.isBlank() }) { "Operation artifact entry field keys must not be blank." }
    }
}

enum class OperationArtifactType {
    RECONSTRUCTION,
    WRITE_BACK,
    INTEGRITY_CHECK
}

object OperationArtifactFactory {
    fun reconstruction(
        result: ReconstructionResult,
        generatedAt: Instant = Instant.now()
    ): OperationArtifact =
        OperationArtifact(
            operationType = OperationArtifactType.RECONSTRUCTION,
            generatedAt = generatedAt,
            fields = linkedMapOf(
                "requestId" to result.requestId,
                "subjectId" to result.subjectId.value,
                "requestedExplicitCount" to result.requestedExplicitShardIds.size.toString(),
                "unresolvedExplicitCount" to result.unresolvedExplicitShardIds.size.toString(),
                "selectedCount" to result.selectedShards.size.toString(),
                "includedCount" to result.includedShards.size.toString(),
                "skippedAtSelectionCount" to result.skippedAtSelection.size.toString(),
                "excludedAtReconstructionCount" to result.excludedAtReconstruction.size.toString(),
                "nonExposableCount" to result.nonExposableProtectedContentShards.size.toString(),
                "excludedCount" to result.excludedShardIds.size.toString(),
                "maxShardCount" to result.maxShardCount.toString(),
                "wasBounded" to result.wasBounded.toString()
            ),
            entries = buildList {
                result.selectedShardReports.forEach { report ->
                    add(
                        OperationArtifactEntry(
                            kind = "selected",
                            fields = linkedMapOf(
                                "shardId" to report.shard.id.value,
                                "shardType" to report.shard.type.name,
                                "version" to report.shard.version.toString(),
                                "contentMode" to report.shard.protectedContent.mode.name,
                                "influences" to report.influences.sortedBy { it.name }.joinToString(",") { it.name }
                            )
                        )
                    )
                }
                result.includedShards.forEach { shard ->
                    add(
                        OperationArtifactEntry(
                            kind = "included",
                            fields = linkedMapOf(
                                "shardId" to shard.id.value,
                                "shardType" to shard.type.name,
                                "version" to shard.version.toString(),
                                "contentMode" to shard.protectedContent.mode.name
                            )
                        )
                    )
                }
                result.skippedAtSelection.forEach { skip ->
                    add(
                        OperationArtifactEntry(
                            kind = "selection-skip",
                            fields = linkedMapOf(
                                "shardId" to skip.shard.id.value,
                                "shardType" to skip.shard.type.name,
                                "reason" to skip.reason.name,
                                "influences" to skip.influences.sortedBy { it.name }.joinToString(",") { it.name }
                            )
                        )
                    )
                }
                result.excludedAtReconstruction.forEach { exclusion ->
                    add(
                        OperationArtifactEntry(
                            kind = "reconstruction-exclusion",
                            fields = linkedMapOf(
                                "shardId" to exclusion.shard.id.value,
                                "shardType" to exclusion.shard.type.name,
                                "reason" to exclusion.reason.name,
                                "nonExposable" to (exclusion.reason.name == "CONTENT_NOT_EXPOSABLE").toString(),
                                "influences" to exclusion.influences.sortedBy { it.name }.joinToString(",") { it.name }
                            )
                        )
                    )
                }
                result.unresolvedExplicitShardIds.sortedBy { it.value }.forEach { shardId ->
                    add(
                        OperationArtifactEntry(
                            kind = "explicit-unresolved",
                            fields = linkedMapOf(
                                "shardId" to shardId.value,
                                "reason" to "SHARD_NOT_AVAILABLE"
                            )
                        )
                    )
                }
                result.provenance.forEach { record ->
                    add(
                        OperationArtifactEntry(
                            kind = "provenance",
                            fields = linkedMapOf(
                                "requestId" to record.requestId,
                                "shardId" to record.shardId.value,
                                "shardType" to record.shardType.name,
                                "decision" to record.decision.name,
                                "reason" to record.reason.name,
                                "decidedAt" to record.decidedAt.toString()
                            )
                        )
                    )
                }
            }
        )

    fun writeBack(
        result: WriteBackResult,
        generatedAt: Instant = Instant.now()
    ): OperationArtifact =
        OperationArtifact(
            operationType = OperationArtifactType.WRITE_BACK,
            generatedAt = generatedAt,
            fields = linkedMapOf(
                "requestId" to result.requestId,
                "subjectId" to result.subjectId.value,
                "createdCount" to result.createdShardIds.size.toString(),
                "replacedCount" to result.replacedShardIds.size.toString(),
                "deletedCount" to result.deletedShardIds.size.toString(),
                "wasBounded" to result.wasBounded.toString(),
                "decidedAt" to result.decidedAt.toString()
            ),
            entries = buildList {
                result.createdShardIds.sortedBy { it.value }.forEach { shardId ->
                    add(OperationArtifactEntry("created", linkedMapOf("shardId" to shardId.value)))
                }
                result.replacedShardIds.sortedBy { it.value }.forEach { shardId ->
                    add(OperationArtifactEntry("replaced", linkedMapOf("shardId" to shardId.value)))
                }
                result.deletedShardIds.sortedBy { it.value }.forEach { shardId ->
                    add(OperationArtifactEntry("deleted", linkedMapOf("shardId" to shardId.value)))
                }
                result.plan.replacementShards.forEach { shard ->
                    add(
                        OperationArtifactEntry(
                            kind = "replacement-shard",
                            fields = linkedMapOf(
                                "shardId" to shard.id.value,
                                "shardType" to shard.type.name,
                                "version" to shard.version.toString(),
                                "contentMode" to shard.protectedContent.mode.name
                            )
                        )
                    )
                }
            }
        )

    fun integrity(
        result: IntegrityCheckResult,
        generatedAt: Instant = Instant.now()
    ): OperationArtifact =
        OperationArtifact(
            operationType = OperationArtifactType.INTEGRITY_CHECK,
            generatedAt = generatedAt,
            fields = linkedMapOf(
                "checkedCount" to result.checkedRecordCount.toString(),
                "validCount" to result.validRecordCount.toString(),
                "invalidCount" to result.invalidRecordCount.toString(),
                "isValid" to result.isValid.toString()
            ),
            entries = buildList {
                result.records.forEachIndexed { index, record ->
                    add(
                        OperationArtifactEntry(
                            kind = "record",
                            fields = linkedMapOf(
                                "recordIndex" to index.toString(),
                                "location" to record.storageLocation,
                                "isValid" to record.isValid.toString(),
                                "shardId" to record.shardId.orEmpty(),
                                "subjectId" to record.subjectId.orEmpty(),
                                "issueCount" to record.issues.size.toString()
                            )
                        )
                    )
                    record.issues.forEach { issue ->
                        add(
                            OperationArtifactEntry(
                                kind = "issue",
                                fields = linkedMapOf(
                                    "recordIndex" to index.toString(),
                                    "location" to record.storageLocation,
                                    "severity" to issue.severity.name,
                                    "code" to issue.code.name,
                                    "message" to issue.message,
                                    "shardId" to record.shardId.orEmpty(),
                                    "subjectId" to record.subjectId.orEmpty()
                                )
                            )
                        )
                    }
                }
            }
        )
}

object OperationArtifactFormatter {
    fun format(artifact: OperationArtifact): String =
        buildString {
            appendLine("formatVersion=${artifact.formatVersion}")
            appendLine("operationType=${artifact.operationType.name}")
            appendLine("generatedAt=${artifact.generatedAt}")
            artifact.fields.toSortedMap().forEach { (key, value) ->
                appendLine("field.$key=${value.escapeArtifactValue()}")
            }
            appendLine("entryCount=${artifact.entries.size}")
            artifact.entries.forEachIndexed { index, entry ->
                appendLine("entry.$index.kind=${entry.kind.escapeArtifactValue()}")
                entry.fields.toSortedMap().forEach { (key, value) ->
                    appendLine("entry.$index.$key=${value.escapeArtifactValue()}")
                }
            }
        }

    private fun String.escapeArtifactValue(): String =
        replace("\\", "\\\\")
            .replace("\r", "\\r")
            .replace("\n", "\\n")
}
