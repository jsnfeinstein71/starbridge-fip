package com.innovationstrategies.fip.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
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
        assertTrue(load.out.contains("payload=core payload"))
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

    private fun runSave(
        store: String,
        id: String,
        subject: String,
        type: String = "IDENTITY_CORE"
    ): CliRun =
        run(
            "save-shard",
            "--store", store,
            "--id", id,
            "--subject", subject,
            "--type", type,
            "--version", "1",
            "--payload", "payload for $id",
            "--source", "cli-test",
            "--observed-at", "2026-04-20T00:00:00Z"
        )

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
