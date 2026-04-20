package com.innovationstrategies.fip.storage.file

import com.innovationstrategies.fip.core.domain.IdentityShard
import com.innovationstrategies.fip.core.domain.IdentityShardId
import com.innovationstrategies.fip.core.domain.IdentitySubjectId
import com.innovationstrategies.fip.core.domain.ShardType
import com.innovationstrategies.fip.core.integrity.ShardValidationIssueCode
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileIdentityShardIntegrityCheckerTest {
    private val observedAt = Instant.parse("2026-04-20T00:00:00Z")

    @Test
    fun `valid stored shard passes integrity check`() {
        val root = Files.createTempDirectory("fip-integrity-test")
        val repository = FileIdentityShardRepository(root)
        repository.save(shard("shard-1"))

        val result = FileIdentityShardIntegrityChecker(root).check()

        assertTrue(result.isValid)
        assertEquals(1, result.checkedRecordCount)
        assertEquals(1, result.validRecordCount)
        assertEquals(0, result.invalidRecordCount)
        assertEquals("shard-1", result.records.single().shardId)
    }

    @Test
    fun `missing required field is reported`() {
        val root = Files.createTempDirectory("fip-integrity-test")
        writeShardFile(
            root.resolve("missing-content-value.shard.properties"),
            """
            formatVersion=1
            id=shard-1
            subjectId=subject-1
            type=IDENTITY_CORE
            version=1
            source=test-source
            observedAt=2026-04-20T00:00:00Z
            contentMode=PLAINTEXT
            contentAlgorithm=PLAINTEXT-DEVELOPMENT
            tagCount=0
            """.trimIndent()
        )

        val record = FileIdentityShardIntegrityChecker(root).check().records.single()

        assertFalse(record.isValid)
        assertTrue(record.hasIssue(ShardValidationIssueCode.REQUIRED_FIELD_MISSING))
    }

    @Test
    fun `unsupported format version is reported`() {
        val root = Files.createTempDirectory("fip-integrity-test")
        writeShardFile(
            root.resolve("unsupported-version.shard.properties"),
            validRecord(id = "shard-1").replace("formatVersion=1", "formatVersion=99")
        )

        val record = FileIdentityShardIntegrityChecker(root).check().records.single()

        assertFalse(record.isValid)
        assertTrue(record.hasIssue(ShardValidationIssueCode.UNSUPPORTED_FORMAT_VERSION))
    }

    @Test
    fun `parse and domain validity issues are reported`() {
        val root = Files.createTempDirectory("fip-integrity-test")
        writeShardFile(
            root.resolve("invalid-record.shard.properties"),
            """
            formatVersion=not-an-int
            id=
            subjectId=subject-1
            type=NOT_A_SHARD_TYPE
            version=0
            contentMode=NOT_A_CONTENT_MODE
            contentValue=
            contentAlgorithm=
            source=
            observedAt=not-an-instant
            tagCount=1
            tag.0=
            """.trimIndent()
        )

        val record = FileIdentityShardIntegrityChecker(root).check().records.single()

        assertFalse(record.isValid)
        assertTrue(record.hasIssue(ShardValidationIssueCode.INVALID_FORMAT_VERSION))
        assertTrue(record.hasIssue(ShardValidationIssueCode.INVALID_SHARD_TYPE))
        assertTrue(record.hasIssue(ShardValidationIssueCode.INVALID_VERSION))
        assertTrue(record.hasIssue(ShardValidationIssueCode.INVALID_PAYLOAD))
        assertTrue(record.hasIssue(ShardValidationIssueCode.INVALID_SOURCE))
        assertTrue(record.hasIssue(ShardValidationIssueCode.INVALID_OBSERVED_AT))
        assertTrue(record.hasIssue(ShardValidationIssueCode.INVALID_TAGS))
    }

    @Test
    fun `encrypted placeholder content passes file integrity validation`() {
        val root = Files.createTempDirectory("fip-integrity-test")
        writeShardFile(
            root.resolve("encrypted-placeholder.shard.properties"),
            """
            formatVersion=1
            id=encrypted-shard
            subjectId=subject-1
            type=IDENTITY_CORE
            version=1
            source=test-source
            observedAt=2026-04-20T00:00:00Z
            contentMode=ENCRYPTED
            contentValue=placeholder\:17\:1234
            contentAlgorithm=FIP-PLACEHOLDER-NO-CRYPTO
            tagCount=0
            """.trimIndent()
        )

        val result = FileIdentityShardIntegrityChecker(root).check()

        assertTrue(result.isValid)
        assertEquals(1, result.validRecordCount)
    }

    @Test
    fun `duplicate shard ids are reported on each duplicate record`() {
        val root = Files.createTempDirectory("fip-integrity-test")
        writeShardFile(root.resolve("first.shard.properties"), validRecord(id = "duplicate-id"))
        writeShardFile(root.resolve("second.shard.properties"), validRecord(id = "duplicate-id"))

        val result = FileIdentityShardIntegrityChecker(root).check()

        assertFalse(result.isValid)
        assertEquals(2, result.invalidRecordCount)
        assertTrue(result.records.all { it.hasIssue(ShardValidationIssueCode.DUPLICATE_SHARD_ID) })
    }

    private fun shard(id: String): IdentityShard =
        IdentityShard(
            id = IdentityShardId(id),
            subjectId = IdentitySubjectId("subject-1"),
            type = ShardType.IDENTITY_CORE,
            version = 1,
            payload = "payload",
            source = "test-source",
            observedAt = observedAt,
            tags = setOf("core")
        )

    private fun validRecord(id: String): String =
        """
        formatVersion=1
        id=$id
        subjectId=subject-1
        type=IDENTITY_CORE
        version=1
        source=test-source
        observedAt=2026-04-20T00:00:00Z
        contentMode=PLAINTEXT
        contentValue=payload
        contentAlgorithm=PLAINTEXT-DEVELOPMENT
        tagCount=0
        """.trimIndent()

    private fun writeShardFile(path: Path, contents: String) {
        path.writeText(contents)
    }

    private fun com.innovationstrategies.fip.core.integrity.ShardValidationResult.hasIssue(
        code: ShardValidationIssueCode
    ): Boolean =
        issues.any { it.code == code }
}
