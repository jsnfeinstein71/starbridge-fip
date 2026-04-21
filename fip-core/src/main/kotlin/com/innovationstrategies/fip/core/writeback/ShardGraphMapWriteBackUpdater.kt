package com.innovationstrategies.fip.core.writeback

import com.innovationstrategies.fip.core.domain.IdentityShardId
import com.innovationstrategies.fip.core.selection.ShardGraphLink
import com.innovationstrategies.fip.core.selection.ShardGraphMap
import com.innovationstrategies.fip.core.selection.ShardGraphNode

class ShardGraphMapWriteBackUpdater {
    fun update(graphMap: ShardGraphMap, result: WriteBackResult): ShardGraphMap {
        val deletedShardIds = result.deletedShardIds
        val replacementShards = result.plan.replacementShards
        if (deletedShardIds.isEmpty() || replacementShards.isEmpty()) {
            return graphMap
        }

        require(replacementShards.all { it.subjectId == result.subjectId }) {
            "Replacement shard subjects must match write-back subject."
        }
        val mismatchedNode = graphMap.nodes.values.firstOrNull { it.subjectId != result.subjectId }
        require(mismatchedNode == null) {
            "Shard graph map node subject does not match write-back subject: ${mismatchedNode?.shardId?.value}."
        }

        val replacementIds = replacementShards.map { it.id }
        val primaryReplacementId = replacementIds.first()
        val keptNodes = graphMap.nodes.values
            .filter { it.shardId !in deletedShardIds }
            .map { node -> node.remapLinkedShardIds(deletedShardIds, primaryReplacementId) }

        val replacementNodes = replacementShards.map { shard ->
            ShardGraphNode(
                shardId = shard.id,
                subjectId = shard.subjectId,
                shardType = shard.type,
                priority = replacementPriority(graphMap, deletedShardIds),
                linkedShardIds = replacementLinkedShardIds(graphMap, deletedShardIds, shard.id)
            )
        }
        val nodes = keptNodes + replacementNodes
        val nodeIds = nodes.map { it.shardId }.toSet()
        val preservedLinks = graphMap.links
            .mapNotNull { it.remapDeletedEndpoint(deletedShardIds, primaryReplacementId) }
            .filter { it.fromShardId in nodeIds && it.toShardId in nodeIds && it.fromShardId != it.toShardId }
            .distinctBy { "${it.fromShardId.value}\u0000${it.toShardId.value}\u0000${it.weight}" }

        return ShardGraphMap.of(nodes, preservedLinks)
    }

    private fun ShardGraphNode.remapLinkedShardIds(
        deletedShardIds: Set<IdentityShardId>,
        primaryReplacementId: IdentityShardId
    ): ShardGraphNode {
        val remappedLinks = linkedShardIds
            .map { linkedShardId ->
                if (linkedShardId in deletedShardIds) primaryReplacementId else linkedShardId
            }
            .filter { it != shardId }
            .toSet()

        return copy(linkedShardIds = remappedLinks)
    }

    private fun ShardGraphLink.remapDeletedEndpoint(
        deletedShardIds: Set<IdentityShardId>,
        primaryReplacementId: IdentityShardId
    ): ShardGraphLink? {
        val remappedFrom = if (fromShardId in deletedShardIds) primaryReplacementId else fromShardId
        val remappedTo = if (toShardId in deletedShardIds) primaryReplacementId else toShardId
        if (fromShardId in deletedShardIds && toShardId in deletedShardIds) {
            return null
        }
        return copy(fromShardId = remappedFrom, toShardId = remappedTo)
    }

    private fun replacementPriority(
        graphMap: ShardGraphMap,
        deletedShardIds: Set<IdentityShardId>
    ): Int =
        graphMap.nodes.values
            .filter { it.shardId in deletedShardIds }
            .maxOfOrNull { it.priority }
            ?: ShardGraphNode.DEFAULT_PRIORITY

    private fun replacementLinkedShardIds(
        graphMap: ShardGraphMap,
        deletedShardIds: Set<IdentityShardId>,
        replacementId: IdentityShardId
    ): Set<IdentityShardId> {
        val nodeLinks = graphMap.nodes.values
            .filter { it.shardId in deletedShardIds }
            .flatMap { it.linkedShardIds }
        val outgoingLinkTargets = graphMap.links
            .filter { it.fromShardId in deletedShardIds }
            .map { it.toShardId }

        return (nodeLinks + outgoingLinkTargets)
            .filter { it !in deletedShardIds && it != replacementId }
            .toSet()
    }
}
