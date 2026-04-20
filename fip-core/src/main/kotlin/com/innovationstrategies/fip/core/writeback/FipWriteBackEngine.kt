package com.innovationstrategies.fip.core.writeback

import com.innovationstrategies.fip.core.domain.IdentityShard
import java.time.Clock

class FipWriteBackEngine(
    private val clock: Clock = Clock.systemUTC(),
    private val shardIdGenerator: ShardIdGenerator = UuidShardIdGenerator
) {
    fun plan(
        request: IdentityUpdateRequest,
        existingShards: Collection<IdentityShard>
    ): ReShardPlan {
        val selectedShards = selectShardsForReplacement(request, existingShards)
        val replacementVersion = (selectedShards.maxOfOrNull { it.version } ?: 0) + 1
        val replacementShard = IdentityShard(
            id = shardIdGenerator.generateReplacementShardId(request),
            subjectId = request.subjectId,
            type = request.shardType,
            version = replacementVersion,
            payload = request.payload,
            source = request.source,
            observedAt = clock.instant(),
            tags = request.tags
        )
        val selectedShardIds = selectedShards.map { it.id }.toSet()

        return ReShardPlan(
            requestId = request.requestId,
            subjectId = request.subjectId,
            selectedShardIds = selectedShardIds,
            shardIdsToDelete = selectedShardIds,
            replacementShards = listOf(replacementShard),
            wasBounded = selectedShards.size < replacementCandidates(request, existingShards).size
        )
    }

    fun writeBack(
        request: IdentityUpdateRequest,
        existingShards: Collection<IdentityShard>
    ): WriteBackResult {
        val plan = plan(request, existingShards)

        return WriteBackResult(
            requestId = request.requestId,
            subjectId = request.subjectId,
            createdShardIds = plan.replacementShards.map { it.id }.toSet(),
            replacedShardIds = plan.selectedShardIds,
            deletedShardIds = plan.shardIdsToDelete,
            plan = plan,
            wasBounded = plan.wasBounded,
            decidedAt = clock.instant()
        )
    }

    private fun selectShardsForReplacement(
        request: IdentityUpdateRequest,
        existingShards: Collection<IdentityShard>
    ): List<IdentityShard> {
        enforceRequestedShardSubjectConsistency(request, existingShards)

        return replacementCandidates(request, existingShards)
            .sortedBy { it.id.value }
            .take(request.maxReplacementSourceShards)
    }

    private fun replacementCandidates(
        request: IdentityUpdateRequest,
        existingShards: Collection<IdentityShard>
    ): List<IdentityShard> {
        val subjectShards = existingShards.filter { it.subjectId == request.subjectId }

        return if (request.replaceShardIds.isEmpty()) {
            subjectShards.filter { it.type == request.shardType }
        } else {
            subjectShards.filter { it.id in request.replaceShardIds }
        }
    }

    private fun enforceRequestedShardSubjectConsistency(
        request: IdentityUpdateRequest,
        existingShards: Collection<IdentityShard>
    ) {
        if (request.replaceShardIds.isEmpty()) {
            return
        }

        val mismatchedShardIds = existingShards
            .filter { it.id in request.replaceShardIds && it.subjectId != request.subjectId }
            .map { it.id.value }

        require(mismatchedShardIds.isEmpty()) {
            "Requested replacement shard ids do not match subject: ${mismatchedShardIds.joinToString(",")}."
        }
    }
}
