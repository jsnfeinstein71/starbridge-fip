package com.innovationstrategies.fip.core.writeback

import com.innovationstrategies.fip.core.domain.IdentityShardId
import com.innovationstrategies.fip.core.domain.IdentitySubjectId
import com.innovationstrategies.fip.core.domain.ShardType
import java.nio.charset.StandardCharsets

data class IdentityUpdateRequest(
    val requestId: String,
    val subjectId: IdentitySubjectId,
    val taskType: String,
    val surface: String,
    val shardType: ShardType,
    val payload: String,
    val source: String,
    val replaceShardIds: Set<IdentityShardId> = emptySet(),
    val tags: Set<String> = emptySet(),
    val maxReplacementSourceShards: Int = 16,
    val maxPayloadBytes: Int? = null,
    val allowSystemMeta: Boolean = false
) {
    init {
        require(requestId.isNotBlank()) { "requestId must not be blank." }
        require(taskType.isNotBlank()) { "taskType must not be blank." }
        require(surface.isNotBlank()) { "surface must not be blank." }
        require(payload.isNotBlank()) { "payload must not be blank." }
        require(source.isNotBlank()) { "source must not be blank." }
        require(tags.none { it.isBlank() }) { "tags must not contain blank values." }
        require(maxReplacementSourceShards > 0) {
            "maxReplacementSourceShards must be greater than zero."
        }
        require(maxPayloadBytes == null || maxPayloadBytes > 0) {
            "maxPayloadBytes must be greater than zero when provided."
        }
        require(
            maxPayloadBytes == null ||
                payload.toByteArray(StandardCharsets.UTF_8).size <= maxPayloadBytes
        ) {
            "payload must not exceed maxPayloadBytes."
        }
        require(allowSystemMeta || shardType != ShardType.SYSTEM_META) {
            "SYSTEM_META write-back must be explicitly allowed."
        }
    }
}
