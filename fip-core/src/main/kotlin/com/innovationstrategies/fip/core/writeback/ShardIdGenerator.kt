package com.innovationstrategies.fip.core.writeback

import com.innovationstrategies.fip.core.domain.IdentityShardId
import java.util.UUID

fun interface ShardIdGenerator {
    fun generateReplacementShardId(request: IdentityUpdateRequest): IdentityShardId
}

object UuidShardIdGenerator : ShardIdGenerator {
    override fun generateReplacementShardId(request: IdentityUpdateRequest): IdentityShardId =
        IdentityShardId("shard-${UUID.randomUUID()}")
}
