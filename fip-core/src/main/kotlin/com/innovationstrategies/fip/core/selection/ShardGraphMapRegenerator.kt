package com.innovationstrategies.fip.core.selection

import com.innovationstrategies.fip.core.domain.IdentityShard
import com.innovationstrategies.fip.core.domain.IdentityShardId
import com.innovationstrategies.fip.core.domain.IdentitySubjectId
import com.innovationstrategies.fip.core.domain.ShardType

class ShardGraphMapRegenerator {
    fun regenerate(
        subjectId: IdentitySubjectId,
        shards: Collection<IdentityShard>,
        existingGraphMap: ShardGraphMap? = null
    ): ShardGraphMap {
        val subjectShards = shards
            .filter { it.subjectId == subjectId }
            .distinctBy { it.id }
            .sortedBy { it.id.value }
        val subjectShardIds = subjectShards.map { it.id }.toSet()
        val preservedLinks = existingGraphMap
            ?.links
            .orEmpty()
            .filter { it.fromShardId in subjectShardIds && it.toShardId in subjectShardIds }

        val generatedLinks = generatedLinksFor(subjectShards, preservedLinks)
        val links = (preservedLinks + generatedLinks)
            .distinctBy { "${it.fromShardId.value}\u0000${it.toShardId.value}\u0000${it.weight}" }
        val linkTargetsBySource = links
            .groupBy({ it.fromShardId }, { it.toShardId })

        val nodes = subjectShards.map { shard ->
            val existingNode = existingGraphMap?.nodeFor(shard.id)
            ShardGraphNode(
                shardId = shard.id,
                subjectId = subjectId,
                shardType = shard.type,
                priority = existingNode?.priority ?: priorityFor(shard.type),
                linkedShardIds = (
                    existingNode
                        ?.linkedShardIds
                        .orEmpty()
                        .filter { it in subjectShardIds } +
                        linkTargetsBySource[shard.id].orEmpty()
                    )
                    .filter { it != shard.id }
                    .toSet()
            )
        }

        return ShardGraphMap.of(nodes, links)
    }

    private fun generatedLinksFor(
        subjectShards: List<IdentityShard>,
        preservedLinks: List<ShardGraphLink>
    ): List<ShardGraphLink> {
        val existingPairs = preservedLinks.map { it.fromShardId to it.toShardId }.toSet()
        val anchorShards = subjectShards
            .filter { it.type in ANCHOR_TYPES }
            .ifEmpty { subjectShards.take(1) }

        return anchorShards.flatMap { anchor ->
            subjectShards
                .filter { it.id != anchor.id }
                .filter { (anchor.id to it.id) !in existingPairs }
                .map { linked ->
                    ShardGraphLink(
                        fromShardId = anchor.id,
                        toShardId = linked.id,
                        weight = linkWeightFor(anchor.type, linked.type)
                    )
                }
        }
    }

    private fun priorityFor(shardType: ShardType): Int =
        when (shardType) {
            ShardType.IDENTITY_CORE -> 400
            ShardType.IDENTITY_PREFS -> 300
            ShardType.BEHAVIOR_RULES -> 275
            ShardType.MEMORY_ANCHORS -> 250
            ShardType.ACL_BASELINE -> 175
            ShardType.MEMORY_EPHEMERAL -> 100
            ShardType.ACL_STATE -> 75
            ShardType.BCAL_REFINEMENTS -> 75
            ShardType.SYSTEM_META -> 25
        }

    private fun linkWeightFor(anchorType: ShardType, linkedType: ShardType): Int =
        when {
            anchorType == ShardType.IDENTITY_CORE && linkedType == ShardType.IDENTITY_PREFS -> 300
            anchorType == ShardType.IDENTITY_CORE && linkedType == ShardType.BEHAVIOR_RULES -> 275
            anchorType == ShardType.IDENTITY_CORE && linkedType == ShardType.MEMORY_ANCHORS -> 250
            linkedType in ANCHOR_TYPES -> 200
            linkedType == ShardType.SYSTEM_META -> 25
            else -> 100
        }

    private companion object {
        val ANCHOR_TYPES = setOf(
            ShardType.IDENTITY_CORE,
            ShardType.IDENTITY_PREFS,
            ShardType.BEHAVIOR_RULES,
            ShardType.MEMORY_ANCHORS
        )
    }
}
