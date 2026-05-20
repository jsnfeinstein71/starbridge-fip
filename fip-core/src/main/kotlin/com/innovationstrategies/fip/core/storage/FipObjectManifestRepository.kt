package com.innovationstrategies.fip.core.storage

import com.innovationstrategies.fip.core.vault.FipObjectId
import com.innovationstrategies.fip.core.vault.FipObjectManifest

interface FipObjectManifestRepository {
    fun save(manifest: FipObjectManifest): FipObjectManifest

    fun load(objectId: FipObjectId): FipObjectManifest?

    fun list(): List<FipObjectManifest>

    fun delete(objectId: FipObjectId): Boolean
}
