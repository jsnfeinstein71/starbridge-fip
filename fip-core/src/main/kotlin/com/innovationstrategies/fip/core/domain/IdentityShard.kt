package com.innovationstrategies.fip.core.domain

import java.time.Instant

@JvmInline
value class IdentityShardId(val value: String) {
    init {
        require(value.isNotBlank()) { "IdentityShardId must not be blank." }
    }
}

@JvmInline
value class IdentitySubjectId(val value: String) {
    init {
        require(value.isNotBlank()) { "IdentitySubjectId must not be blank." }
    }
}

data class IdentityShard(
    val id: IdentityShardId,
    val subjectId: IdentitySubjectId,
    val type: ShardType,
    val version: Int,
    val payload: String,
    val source: String,
    val observedAt: Instant,
    val tags: Set<String> = emptySet()
) {
    init {
        require(version > 0) { "IdentityShard version must be greater than zero." }
        require(payload.isNotBlank()) { "IdentityShard payload must not be blank." }
        require(source.isNotBlank()) { "IdentityShard source must not be blank." }
        require(tags.none { it.isBlank() }) { "IdentityShard tags must not contain blank values." }
    }
}
