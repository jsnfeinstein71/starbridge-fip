package com.innovationstrategies.fip.core.selection

import com.innovationstrategies.fip.core.domain.IdentityShard
import com.innovationstrategies.fip.core.storage.ShardGraphMapRepository

class RepositoryBackedGraphAwareShardSelector(
    private val graphMapRepository: ShardGraphMapRepository,
    private val fallbackSelector: ShardSelector = DefaultShardSelector(),
    private val expandDirectLinks: Boolean = true
) : ShardSelector {
    override fun select(
        policy: ShardSelectionPolicy,
        shards: Collection<IdentityShard>
    ): ShardSelectionPlan {
        val graphMap = graphMapRepository.load(policy.subjectId)
            ?: return fallbackSelector.select(policy, shards)

        return GraphAwareShardSelector(
            graphMap = graphMap,
            expandDirectLinks = expandDirectLinks
        ).select(policy, shards)
    }
}
