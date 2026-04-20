package com.innovationstrategies.fip.core.storage

import com.innovationstrategies.fip.core.domain.IdentityShard
import com.innovationstrategies.fip.core.domain.IdentityShardId
import com.innovationstrategies.fip.core.domain.IdentitySubjectId

interface IdentityShardRepository {
    fun save(shard: IdentityShard): IdentityShard

    fun load(id: IdentityShardId): IdentityShard?

    fun listForSubject(subjectId: IdentitySubjectId): List<IdentityShard>

    fun delete(id: IdentityShardId): Boolean
}
