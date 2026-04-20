package com.innovationstrategies.fip.core.domain

data class ReconstructionPolicy(
    val allowedShardTypes: Set<ShardType> = ReconstructionRequest.DEFAULT_ALLOWED_SHARD_TYPES,
    val excludedShardTypes: Set<ShardType> = DEFAULT_EXCLUDED_SHARD_TYPES,
    val allowSystemMeta: Boolean = false
) {
    init {
        require(allowSystemMeta || ShardType.SYSTEM_META !in allowedShardTypes) {
            "SYSTEM_META must be explicitly allowed."
        }
    }

    companion object {
        val DEFAULT_EXCLUDED_SHARD_TYPES: Set<ShardType> = setOf(
            ShardType.MEMORY_EPHEMERAL,
            ShardType.ACL_STATE,
            ShardType.BCAL_REFINEMENTS,
            ShardType.SYSTEM_META
        )

        fun from(request: ReconstructionRequest): ReconstructionPolicy =
            ReconstructionPolicy(
                allowedShardTypes = request.allowedShardTypes,
                excludedShardTypes = if (request.allowSystemMeta) {
                    DEFAULT_EXCLUDED_SHARD_TYPES - ShardType.SYSTEM_META
                } else {
                    DEFAULT_EXCLUDED_SHARD_TYPES
                },
                allowSystemMeta = request.allowSystemMeta
            )
    }
}
