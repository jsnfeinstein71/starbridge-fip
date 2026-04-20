package com.innovationstrategies.fip.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MainTest {
    @Test
    fun `save and load shard through cli`() {
        val store = Files.createTempDirectory("fip-cli-test").toString()

        val save = run(
            "save-shard",
            "--store", store,
            "--id", "shard-1",
            "--subject", "subject-1",
            "--type", "IDENTITY_CORE",
            "--version", "1",
            "--payload", "core payload",
            "--source", "cli-test",
            "--observed-at", "2026-04-20T00:00:00Z",
            "--tag", "core"
        )
        val load = run("load-shard", "--store", store, "--id", "shard-1")

        assertEquals(0, save.exitCode)
        assertEquals(0, load.exitCode)
        assertTrue(save.out.contains("saved id=shard-1"))
        assertTrue(load.out.contains("shard id=shard-1 subjectId=subject-1 type=IDENTITY_CORE"))
        assertTrue(load.out.contains("contentMode=PLAINTEXT"))
        assertTrue(load.out.contains("contentAlgorithm=PLAINTEXT-DEVELOPMENT"))
        assertTrue(load.out.contains("payload=core payload"))
    }

    @Test
    fun `save and load encrypted placeholder shard through cli`() {
        val store = Files.createTempDirectory("fip-cli-test").toString()

        val save = run(
            "save-shard",
            "--store", store,
            "--id", "encrypted-1",
            "--subject", "subject-1",
            "--type", "IDENTITY_CORE",
            "--version", "1",
            "--payload", "protected operator payload",
            "--source", "cli-test",
            "--observed-at", "2026-04-20T00:00:00Z",
            "--content-mode", "ENCRYPTED_PLACEHOLDER"
        )
        val load = run("load-shard", "--store", store, "--id", "encrypted-1")
        val list = run("list-shards", "--store", store, "--subject", "subject-1")

        assertEquals(0, save.exitCode)
        assertEquals(0, load.exitCode)
        assertTrue(load.out.contains("contentMode=ENCRYPTED"))
        assertTrue(load.out.contains("contentAlgorithm=FIP-PLACEHOLDER-NO-CRYPTO"))
        assertTrue(load.out.contains("payload=<protected-content>"))
        assertTrue(list.out.contains("contentMode=ENCRYPTED"))
    }

    @Test
    fun `list and delete shards through cli`() {
        val store = Files.createTempDirectory("fip-cli-test").toString()
        runSave(store, "shard-1", "subject-1")
        runSave(store, "shard-2", "subject-2")

        val listBeforeDelete = run("list-shards", "--store", store, "--subject", "subject-1")
        val delete = run("delete-shard", "--store", store, "--id", "shard-1")
        val listAfterDelete = run("list-shards", "--store", store, "--subject", "subject-1")

        assertEquals(0, listBeforeDelete.exitCode)
        assertTrue(listBeforeDelete.out.contains("count=1"))
        assertTrue(listBeforeDelete.out.contains("shard id=shard-1"))
        assertEquals(0, delete.exitCode)
        assertTrue(delete.out.contains("deleted id=shard-1"))
        assertTrue(listAfterDelete.out.contains("count=0"))
    }

    @Test
    fun `reconstruct through cli emits included shards and provenance`() {
        val store = Files.createTempDirectory("fip-cli-test").toString()
        runSave(store, "core", "subject-1", "IDENTITY_CORE")
        runSave(store, "ephemeral", "subject-1", "MEMORY_EPHEMERAL")

        val result = run(
            "reconstruct",
            "--store", store,
            "--request-id", "request-1",
            "--subject", "subject-1",
            "--task-type", "cli-test",
            "--surface", "terminal",
            "--max-shards", "10"
        )

        assertEquals(0, result.exitCode)
        assertTrue(result.out.contains("requestId=request-1"))
        assertTrue(result.out.contains("includedCount=1"))
        assertTrue(result.out.contains("excludedCount=1"))
        assertTrue(result.out.contains("included id=core type=IDENTITY_CORE version=1"))
        assertTrue(result.out.contains("provenance shardId=core type=IDENTITY_CORE decision=INCLUDED"))
        assertTrue(result.out.contains("provenance shardId=ephemeral type=MEMORY_EPHEMERAL decision=EXCLUDED"))
    }

    @Test
    fun `reconstruct through cli excludes non-exposable protected content explicitly`() {
        val store = Files.createTempDirectory("fip-cli-test").toString()
        runSave(
            store = store,
            id = "encrypted",
            subject = "subject-1",
            type = "IDENTITY_CORE",
            extraArgs = arrayOf("--content-mode", "ENCRYPTED_PLACEHOLDER")
        )

        val result = run(
            "reconstruct",
            "--store", store,
            "--request-id", "request-1",
            "--subject", "subject-1",
            "--task-type", "cli-test",
            "--surface", "terminal",
            "--max-shards", "10"
        )

        assertEquals(0, result.exitCode)
        assertTrue(result.out.contains("includedCount=0"))
        assertTrue(result.out.contains("excludedCount=1"))
        assertTrue(result.out.contains("provenance shardId=encrypted type=IDENTITY_CORE decision=EXCLUDED reason=CONTENT_NOT_EXPOSABLE"))
    }

    @Test
    fun `write-back creates a replacement shard in storage`() {
        val store = Files.createTempDirectory("fip-cli-test").toString()
        runSave(store, "prior-core", "subject-1", "IDENTITY_CORE")

        val writeBack = runWriteBack(store)
        val list = run("list-shards", "--store", store, "--subject", "subject-1")

        assertEquals(0, writeBack.exitCode)
        assertTrue(writeBack.out.contains("createdCount=1"))
        assertTrue(list.out.contains("count=1"))
        assertTrue(list.out.contains("payload=updated payload"))
        assertTrue(list.out.contains("tags=write-back"))
    }

    @Test
    fun `write-back deletes prior shard ids when replaced`() {
        val store = Files.createTempDirectory("fip-cli-test").toString()
        runSave(store, "prior-core", "subject-1", "IDENTITY_CORE")

        val writeBack = runWriteBack(store)
        val loadPrior = run("load-shard", "--store", store, "--id", "prior-core")

        assertEquals(0, writeBack.exitCode)
        assertTrue(writeBack.out.contains("deleted id=prior-core"))
        assertTrue(loadPrior.out.contains("not-found id=prior-core"))
    }

    @Test
    fun `write-back summary includes created replaced and deleted counts`() {
        val store = Files.createTempDirectory("fip-cli-test").toString()
        runSave(store, "prior-core", "subject-1", "IDENTITY_CORE")

        val writeBack = runWriteBack(store)

        assertEquals(0, writeBack.exitCode)
        assertTrue(writeBack.out.contains("requestId=request-1"))
        assertTrue(writeBack.out.contains("subjectId=subject-1"))
        assertTrue(writeBack.out.contains("createdCount=1"))
        assertTrue(writeBack.out.contains("replacedCount=1"))
        assertTrue(writeBack.out.contains("deletedCount=1"))
        assertTrue(writeBack.out.contains("wasBounded=false"))
        assertTrue(writeBack.out.contains("created id=shard-"))
        assertTrue(writeBack.out.contains("deleted id=prior-core"))
    }

    @Test
    fun `write-back explicit replace-id path works`() {
        val store = Files.createTempDirectory("fip-cli-test").toString()
        runSave(store, "replace-me", "subject-1", "IDENTITY_PREFS")
        runSave(store, "keep-me", "subject-1", "IDENTITY_PREFS")

        val writeBack = runWriteBack(
            store = store,
            type = "IDENTITY_PREFS",
            extraArgs = arrayOf("--replace-id", "replace-me")
        )
        val loadReplaced = run("load-shard", "--store", store, "--id", "replace-me")
        val loadKept = run("load-shard", "--store", store, "--id", "keep-me")
        val list = run("list-shards", "--store", store, "--subject", "subject-1")

        assertEquals(0, writeBack.exitCode)
        assertTrue(writeBack.out.contains("replacedCount=1"))
        assertTrue(writeBack.out.contains("deleted id=replace-me"))
        assertTrue(loadReplaced.out.contains("not-found id=replace-me"))
        assertTrue(loadKept.out.contains("shard id=keep-me"))
        assertTrue(list.out.contains("count=2"))
        assertTrue(list.out.contains("payload=updated payload"))
    }

    @Test
    fun `write-back can create encrypted placeholder replacement through cli`() {
        val store = Files.createTempDirectory("fip-cli-test").toString()
        runSave(store, "prior-core", "subject-1", "IDENTITY_CORE")

        val writeBack = runWriteBack(
            store = store,
            extraArgs = arrayOf("--content-mode", "ENCRYPTED_PLACEHOLDER")
        )
        val list = run("list-shards", "--store", store, "--subject", "subject-1")

        assertEquals(0, writeBack.exitCode)
        assertTrue(writeBack.out.contains("createdCount=1"))
        assertTrue(list.out.contains("contentMode=ENCRYPTED"))
        assertTrue(list.out.contains("payload=<protected-content>"))
    }

    @Test
    fun `check-integrity reports valid shard store`() {
        val store = Files.createTempDirectory("fip-cli-test").toString()
        runSave(store, "shard-1", "subject-1")

        val result = run("check-integrity", "--store", store)

        assertEquals(0, result.exitCode)
        assertTrue(result.out.contains("checkedCount=1"))
        assertTrue(result.out.contains("validCount=1"))
        assertTrue(result.out.contains("invalidCount=0"))
        assertTrue(result.out.contains("isValid=true"))
    }

    @Test
    fun `check-integrity reports invalid shard record with issue lines`() {
        val store = Files.createTempDirectory("fip-cli-test")
        writeShardFile(
            store.resolve("invalid.shard.properties"),
            """
            formatVersion=1
            id=bad-shard
            subjectId=subject-1
            type=NOT_A_TYPE
            version=1
            source=cli-test
            observedAt=2026-04-20T00:00:00Z
            contentMode=PLAINTEXT
            contentValue=payload
            contentAlgorithm=PLAINTEXT-DEVELOPMENT
            tagCount=0
            """.trimIndent()
        )

        val result = run("check-integrity", "--store", store.toString())

        assertEquals(0, result.exitCode)
        assertTrue(result.out.contains("checkedCount=1"))
        assertTrue(result.out.contains("validCount=0"))
        assertTrue(result.out.contains("invalidCount=1"))
        assertTrue(result.out.contains("isValid=false"))
        assertTrue(result.out.contains("issue location="))
        assertTrue(result.out.contains("severity=ERROR"))
        assertTrue(result.out.contains("code=INVALID_SHARD_TYPE"))
        assertTrue(result.out.contains("message=Invalid shard type: NOT_A_TYPE."))
        assertTrue(result.out.contains("shardId=bad-shard"))
        assertTrue(result.out.contains("subjectId=subject-1"))
    }

    @Test
    fun `check-integrity reports duplicate shard ids`() {
        val store = Files.createTempDirectory("fip-cli-test")
        writeShardFile(store.resolve("first.shard.properties"), validShardRecord("duplicate-id"))
        writeShardFile(store.resolve("second.shard.properties"), validShardRecord("duplicate-id"))

        val result = run("check-integrity", "--store", store.toString())

        assertEquals(0, result.exitCode)
        assertTrue(result.out.contains("checkedCount=2"))
        assertTrue(result.out.contains("invalidCount=2"))
        assertTrue(result.out.contains("isValid=false"))
        assertTrue(result.out.contains("code=DUPLICATE_SHARD_ID"))
        assertTrue(result.out.contains("shardId=duplicate-id"))
    }

    private fun runSave(
        store: String,
        id: String,
        subject: String,
        type: String = "IDENTITY_CORE",
        extraArgs: Array<String> = emptyArray()
    ): CliRun =
        run(
            *arrayOf(
                "save-shard",
                "--store", store,
                "--id", id,
                "--subject", subject,
                "--type", type,
                "--version", "1",
                "--payload", "payload for $id",
                "--source", "cli-test",
                "--observed-at", "2026-04-20T00:00:00Z"
            ),
            *extraArgs
        )

    private fun runWriteBack(
        store: String,
        type: String = "IDENTITY_CORE",
        extraArgs: Array<String> = emptyArray()
    ): CliRun =
        run(
            *arrayOf(
                "write-back",
                "--store", store,
                "--request-id", "request-1",
                "--subject", "subject-1",
                "--task-type", "cli-test",
                "--surface", "terminal",
                "--type", type,
                "--payload", "updated payload",
                "--source", "cli-test",
                "--tag", "write-back"
            ),
            *extraArgs
        )

    private fun validShardRecord(id: String): String =
        """
        formatVersion=1
        id=$id
        subjectId=subject-1
        type=IDENTITY_CORE
        version=1
        source=cli-test
        observedAt=2026-04-20T00:00:00Z
        contentMode=PLAINTEXT
        contentValue=payload
        contentAlgorithm=PLAINTEXT-DEVELOPMENT
        tagCount=0
        """.trimIndent()

    private fun writeShardFile(path: Path, contents: String) {
        path.writeText(contents)
    }

    private fun run(vararg args: String): CliRun {
        val outBytes = ByteArrayOutputStream()
        val errBytes = ByteArrayOutputStream()
        val exitCode = runCli(
            args = arrayOf(*args),
            out = PrintStream(outBytes, true, StandardCharsets.UTF_8),
            err = PrintStream(errBytes, true, StandardCharsets.UTF_8)
        )
        return CliRun(
            exitCode = exitCode,
            out = outBytes.toString(StandardCharsets.UTF_8),
            err = errBytes.toString(StandardCharsets.UTF_8)
        )
    }

    private data class CliRun(
        val exitCode: Int,
        val out: String,
        val err: String
    )
}
