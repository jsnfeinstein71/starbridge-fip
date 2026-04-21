package com.innovationstrategies.fip.storage.file

import com.innovationstrategies.fip.core.domain.IdentityShard
import com.innovationstrategies.fip.core.domain.IdentityShardId
import com.innovationstrategies.fip.core.domain.IdentitySubjectId
import com.innovationstrategies.fip.core.domain.ReconstructionRequest
import com.innovationstrategies.fip.core.domain.ShardType
import com.innovationstrategies.fip.core.reconstruction.FipReconstructionEngine
import com.innovationstrategies.fip.core.selection.RepositoryBackedGraphAwareShardSelector
import com.innovationstrategies.fip.core.selection.ShardGraphMap
import com.innovationstrategies.fip.core.selection.ShardGraphNode
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class FileBackedGraphAwareReconstructionTest {
    private val subjectId = IdentitySubjectId("subject-1")
    private val observedAt = Instant.parse("2026-04-20T00:00:00Z")

    @Test
    fun `reconstruction uses file backed subject graph map when present`() {
        val root = Files.createTempDirectory("fip-file-backed-graph-reconstruction-test")
        val graphRepository = FileShardGraphMapRepository(root)
        val seed = shard("seed", ShardType.IDENTITY_CORE)
        val linked = shard("linked", ShardType.IDENTITY_PREFS)
        val unrelated = shard("unrelated", ShardType.BEHAVIOR_RULES)
        graphRepository.save(
            subjectId,
            ShardGraphMap.of(
                nodes = listOf(
                    node(seed, linkedShardIds = setOf(linked.id)),
                    node(linked),
                    node(unrelated)
                )
            )
        )
        val engine = FipReconstructionEngine(
            clock = Clock.fixed(Instant.parse("2026-04-20T12:00:00Z"), ZoneOffset.UTC),
            shardSelector = RepositoryBackedGraphAwareShardSelector(graphRepository)
        )

        val result = engine.reconstruct(
            request(explicitShardIds = setOf(seed.id)),
            listOf(unrelated, linked, seed)
        )

        assertEquals(listOf(seed, linked), result.includedShards)
        assertEquals(setOf(unrelated.id), result.excludedShardIds)
    }

    private fun request(explicitShardIds: Set<IdentityShardId>): ReconstructionRequest =
        ReconstructionRequest(
            requestId = "request-1",
            subjectId = subjectId,
            taskType = "file-backed-graph-reconstruction-test",
            surface = "storage-file-test",
            maxShardCount = 10,
            explicitShardIds = explicitShardIds
        )

    private fun node(
        shard: IdentityShard,
        linkedShardIds: Set<IdentityShardId> = emptySet()
    ): ShardGraphNode =
        ShardGraphNode(
            shardId = shard.id,
            subjectId = shard.subjectId,
            shardType = shard.type,
            linkedShardIds = linkedShardIds
        )

    private fun shard(id: String, type: ShardType): IdentityShard =
        IdentityShard(
            id = IdentityShardId(id),
            subjectId = subjectId,
            type = type,
            version = 1,
            payload = "payload for $id",
            source = "test-source",
            observedAt = observedAt
        )
}
