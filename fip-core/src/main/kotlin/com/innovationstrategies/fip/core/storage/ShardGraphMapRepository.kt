package com.innovationstrategies.fip.core.storage

import com.innovationstrategies.fip.core.domain.IdentitySubjectId
import com.innovationstrategies.fip.core.selection.ShardGraphMap

interface ShardGraphMapRepository {
    fun save(subjectId: IdentitySubjectId, graphMap: ShardGraphMap): ShardGraphMap

    fun load(subjectId: IdentitySubjectId): ShardGraphMap?

    fun replaceForSubject(subjectId: IdentitySubjectId, graphMap: ShardGraphMap): ShardGraphMap =
        save(subjectId, graphMap)
}
