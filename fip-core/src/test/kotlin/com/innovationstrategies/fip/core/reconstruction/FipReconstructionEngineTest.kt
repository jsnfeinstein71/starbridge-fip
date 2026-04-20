package com.innovationstrategies.fip.core.reconstruction

import com.innovationstrategies.fip.core.domain.IdentityShard
import com.innovationstrategies.fip.core.domain.IdentityShardId
import com.innovationstrategies.fip.core.domain.IdentitySubjectId
import com.innovationstrategies.fip.core.domain.PlaceholderEncryptedContentProtection
import com.innovationstrategies.fip.core.domain.ProtectedShardContent
import com.innovationstrategies.fip.core.domain.ProvenanceDecision
import com.innovationstrategies.fip.core.domain.ProvenanceReason
import com.innovationstrategies.fip.core.domain.ReconstructionPolicy
import com.innovationstrategies.fip.core.domain.ReconstructionRequest
import com.innovationstrategies.fip.core.domain.ShardContentExposure
import com.innovationstrategies.fip.core.domain.ShardType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class FipReconstructionEngineTest {
    private val subjectId = IdentitySubjectId("subject-1")
    private val otherSubjectId = IdentitySubjectId("subject-2")
    private val observedAt = Instant.parse("2026-04-20T00:00:00Z")
    private val engine = FipReconstructionEngine(
        Clock.fixed(Instant.parse("2026-04-20T12:00:00Z"), ZoneOffset.UTC)
    )

    @Test
    fun `system meta is excluded by default`() {
        val systemShard = shard("system-meta", subjectId, ShardType.SYSTEM_META)

        val result = engine.reconstruct(request(), listOf(systemShard))

        assertTrue(result.includedShards.isEmpty())
        assertEquals(setOf(systemShard.id), result.excludedShardIds)
        assertEquals(ProvenanceDecision.EXCLUDED, result.provenance.single().decision)
        assertEquals(ProvenanceReason.SYSTEM_META_DENIED, result.provenance.single().reason)
    }

    @Test
    fun `system meta is included only when explicitly allowed`() {
        val systemShard = shard("system-meta", subjectId, ShardType.SYSTEM_META)
        val request = request(
            allowedShardTypes = ReconstructionRequest.DEFAULT_ALLOWED_SHARD_TYPES + ShardType.SYSTEM_META,
            allowSystemMeta = true
        )

        val result = engine.reconstruct(request, listOf(systemShard))

        assertEquals(listOf(systemShard), result.includedShards)
        assertTrue(result.excludedShardIds.isEmpty())
        assertEquals(ProvenanceDecision.INCLUDED, result.provenance.single().decision)
        assertEquals(ProvenanceReason.WITHIN_BOUND, result.provenance.single().reason)
    }

    @Test
    fun `subject mismatch is excluded`() {
        val shard = shard("other-subject", otherSubjectId, ShardType.IDENTITY_CORE)

        val result = engine.reconstruct(request(), listOf(shard))

        assertTrue(result.includedShards.isEmpty())
        assertEquals(setOf(shard.id), result.excludedShardIds)
        assertEquals(ProvenanceReason.SUBJECT_MISMATCH, result.provenance.single().reason)
    }

    @Test
    fun `max shard count bounds reconstruction`() {
        val first = shard("first", subjectId, ShardType.IDENTITY_CORE)
        val second = shard("second", subjectId, ShardType.IDENTITY_PREFS)

        val result = engine.reconstruct(request(maxShardCount = 1), listOf(first, second))

        assertEquals(listOf(first), result.includedShards)
        assertEquals(setOf(second.id), result.excludedShardIds)
        assertTrue(result.wasBounded)
        assertEquals(ProvenanceReason.OUTSIDE_BOUND, result.provenance[0].reason)
        assertEquals(ProvenanceReason.WITHIN_BOUND, result.provenance[1].reason)
    }

    @Test
    fun `max payload bytes bounds reconstruction`() {
        val first = shard("first", subjectId, ShardType.IDENTITY_CORE, payload = "12345")
        val second = shard("second", subjectId, ShardType.IDENTITY_PREFS, payload = "6")

        val result = engine.reconstruct(request(maxPayloadBytes = 5), listOf(first, second))

        assertEquals(listOf(first), result.includedShards)
        assertEquals(setOf(second.id), result.excludedShardIds)
        assertTrue(result.wasBounded)
        assertEquals(ProvenanceReason.WITHIN_BOUND, result.provenance[0].reason)
        assertEquals(ProvenanceReason.PAYLOAD_LIMIT_EXCEEDED, result.provenance[1].reason)
    }

    @Test
    fun `content exposure is injectable for payload bounds`() {
        val exposingEngine = FipReconstructionEngine(
            clock = Clock.fixed(Instant.parse("2026-04-20T12:00:00Z"), ZoneOffset.UTC),
            contentExposure = ShardContentExposure { content -> "${content.value}-expanded" }
        )
        val shard = shard("first", subjectId, ShardType.IDENTITY_CORE, payload = "12345")

        val result = exposingEngine.reconstruct(request(maxPayloadBytes = 5), listOf(shard))

        assertTrue(result.includedShards.isEmpty())
        assertEquals(setOf(shard.id), result.excludedShardIds)
        assertEquals(ProvenanceReason.PAYLOAD_LIMIT_EXCEEDED, result.provenance.single().reason)
    }

    @Test
    fun `non-exposable protected content is excluded with provenance`() {
        val protectedContent = PlaceholderEncryptedContentProtection.protect("protected payload")
        val shard = IdentityShard(
            id = IdentityShardId("encrypted"),
            subjectId = subjectId,
            type = ShardType.IDENTITY_CORE,
            version = 1,
            payload = ProtectedShardContent.PROTECTED_PAYLOAD_PLACEHOLDER,
            protectedContent = protectedContent,
            source = "test-source",
            observedAt = observedAt
        )

        val result = engine.reconstruct(request(), listOf(shard))

        assertTrue(result.includedShards.isEmpty())
        assertEquals(setOf(shard.id), result.excludedShardIds)
        assertEquals(ProvenanceReason.CONTENT_NOT_EXPOSABLE, result.provenance.single().reason)
    }

    @Test
    fun `selection and reconstruction cooperate with explicit protected content priority`() {
        val plaintext = shard("a-plaintext", subjectId, ShardType.IDENTITY_CORE)
        val protectedContent = PlaceholderEncryptedContentProtection.protect("protected payload")
        val encrypted = IdentityShard(
            id = IdentityShardId("z-encrypted"),
            subjectId = subjectId,
            type = ShardType.IDENTITY_CORE,
            version = 1,
            payload = ProtectedShardContent.PROTECTED_PAYLOAD_PLACEHOLDER,
            protectedContent = protectedContent,
            source = "test-source",
            observedAt = observedAt
        )

        val result = engine.reconstruct(
            request(maxShardCount = 1, explicitShardIds = setOf(encrypted.id)),
            listOf(plaintext, encrypted)
        )

        assertTrue(result.includedShards.isEmpty())
        assertEquals(setOf(plaintext.id, encrypted.id), result.excludedShardIds)
        assertEquals(ProvenanceReason.OUTSIDE_BOUND, result.provenance[0].reason)
        assertEquals(ProvenanceReason.CONTENT_NOT_EXPOSABLE, result.provenance[1].reason)
    }

    @Test
    fun `provenance is emitted for inclusion and exclusion decisions`() {
        val included = shard("included", subjectId, ShardType.IDENTITY_CORE)
        val excluded = shard("excluded", subjectId, ShardType.MEMORY_EPHEMERAL)

        val result = engine.reconstruct(request(), listOf(included, excluded))

        assertEquals(2, result.provenance.size)
        assertTrue(result.provenance.all { it.requestId == "request-1" })
        assertTrue(result.provenance.any {
            it.decision == ProvenanceDecision.INCLUDED && it.shardId == included.id
        })
        assertTrue(result.provenance.any {
            it.decision == ProvenanceDecision.EXCLUDED && it.shardId == excluded.id
        })
    }

    @Test
    fun `explicitly excluded shard types are excluded by policy`() {
        val shard = shard("baseline", subjectId, ShardType.ACL_BASELINE)
        val request = request(allowedShardTypes = ReconstructionRequest.DEFAULT_ALLOWED_SHARD_TYPES + ShardType.ACL_BASELINE)
        val policy = ReconstructionPolicy(
            allowedShardTypes = request.allowedShardTypes,
            excludedShardTypes = setOf(ShardType.ACL_BASELINE),
            allowSystemMeta = false
        )

        val result = engine.reconstruct(request, listOf(shard), policy)

        assertTrue(result.includedShards.isEmpty())
        assertEquals(setOf(shard.id), result.excludedShardIds)
        assertEquals(ProvenanceReason.EXPLICITLY_EXCLUDED, result.provenance.single().reason)
    }

    private fun request(
        maxShardCount: Int = 10,
        maxPayloadBytes: Int? = null,
        allowedShardTypes: Set<ShardType> = ReconstructionRequest.DEFAULT_ALLOWED_SHARD_TYPES,
        allowSystemMeta: Boolean = false,
        explicitShardIds: Set<IdentityShardId> = emptySet()
    ): ReconstructionRequest =
        ReconstructionRequest(
            requestId = "request-1",
            subjectId = subjectId,
            taskType = "bounded-reconstruction-test",
            surface = "core-test",
            maxShardCount = maxShardCount,
            maxPayloadBytes = maxPayloadBytes,
            allowedShardTypes = allowedShardTypes,
            explicitShardIds = explicitShardIds,
            allowSystemMeta = allowSystemMeta
        )

    private fun shard(
        id: String,
        subjectId: IdentitySubjectId,
        type: ShardType,
        payload: String = "payload"
    ): IdentityShard =
        IdentityShard(
            id = IdentityShardId(id),
            subjectId = subjectId,
            type = type,
            version = 1,
            payload = payload,
            source = "test-source",
            observedAt = observedAt
        )
}
