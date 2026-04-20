package com.innovationstrategies.fip.core.domain

data class ReconstructionRequest(
    val requestId: String,
    val subjectId: IdentitySubjectId,
    val taskType: String,
    val surface: String,
    val maxShardCount: Int,
    val maxPayloadBytes: Int? = null,
    val allowedShardTypes: Set<ShardType> = DEFAULT_ALLOWED_SHARD_TYPES,
    val explicitShardIds: Set<IdentityShardId> = emptySet(),
    val allowSystemMeta: Boolean = false
) {
    init {
        require(requestId.isNotBlank()) { "requestId must not be blank." }
        require(taskType.isNotBlank()) { "taskType must not be blank." }
        require(surface.isNotBlank()) { "surface must not be blank." }
        require(maxShardCount > 0) { "maxShardCount must be greater than zero." }
        require(maxPayloadBytes == null || maxPayloadBytes > 0) {
            "maxPayloadBytes must be greater than zero when provided."
        }
        require(allowSystemMeta || ShardType.SYSTEM_META !in allowedShardTypes) {
            "SYSTEM_META must be explicitly allowed."
        }
    }

    companion object {
        val DEFAULT_ALLOWED_SHARD_TYPES: Set<ShardType> = setOf(
            ShardType.IDENTITY_CORE,
            ShardType.IDENTITY_PREFS,
            ShardType.BEHAVIOR_RULES,
            ShardType.MEMORY_ANCHORS
        )
    }
}
