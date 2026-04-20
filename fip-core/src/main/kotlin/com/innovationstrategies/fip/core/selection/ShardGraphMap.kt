package com.innovationstrategies.fip.core.selection

import com.innovationstrategies.fip.core.domain.IdentityShardId
import com.innovationstrategies.fip.core.domain.IdentitySubjectId
import com.innovationstrategies.fip.core.domain.ShardType

data class ShardGraphNode(
    val shardId: IdentityShardId,
    val subjectId: IdentitySubjectId,
    val shardType: ShardType,
    val priority: Int = DEFAULT_PRIORITY,
    val linkedShardIds: Set<IdentityShardId> = emptySet()
) {
    init {
        require(priority >= 0) { "Shard graph node priority must not be negative." }
    }

    companion object {
        const val DEFAULT_PRIORITY = 100
    }
}

data class ShardGraphLink(
    val fromShardId: IdentityShardId,
    val toShardId: IdentityShardId,
    val weight: Int = DEFAULT_WEIGHT
) {
    init {
        require(weight >= 0) { "Shard graph link weight must not be negative." }
    }

    companion object {
        const val DEFAULT_WEIGHT = 100
    }
}

data class ShardGraphMap(
    val nodes: Map<IdentityShardId, ShardGraphNode> = emptyMap(),
    val links: List<ShardGraphLink> = emptyList()
) {
    init {
        links.forEach { link ->
            require(link.fromShardId in nodes) { "Shard graph link source must exist in nodes." }
            require(link.toShardId in nodes) { "Shard graph link target must exist in nodes." }
        }
    }

    fun nodeFor(shardId: IdentityShardId): ShardGraphNode? =
        nodes[shardId]

    fun directLinkedShardIds(seedShardIds: Set<IdentityShardId>): Set<IdentityShardId> {
        val nodeLinks = seedShardIds
            .mapNotNull { nodes[it] }
            .flatMap { it.linkedShardIds }
        val weightedLinks = links
            .filter { it.fromShardId in seedShardIds }
            .sortedByDescending { it.weight }
            .map { it.toShardId }

        return (weightedLinks + nodeLinks)
            .filter { it !in seedShardIds }
            .toSet()
    }

    companion object {
        fun of(nodes: Collection<ShardGraphNode>, links: Collection<ShardGraphLink> = emptyList()): ShardGraphMap =
            ShardGraphMap(
                nodes = nodes.associateBy { it.shardId },
                links = links.toList()
            )
    }
}
