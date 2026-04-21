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
        assertTrue(result.out.contains("requestedExplicitCount=0"))
        assertTrue(result.out.contains("unresolvedExplicitCount=0"))
        assertTrue(result.out.contains("selectedCount=1"))
        assertTrue(result.out.contains("includedCount=1"))
        assertTrue(result.out.contains("skippedAtSelectionCount=1"))
        assertTrue(result.out.contains("excludedAtReconstructionCount=0"))
        assertTrue(result.out.contains("excludedCount=1"))
        assertTrue(result.out.contains("selected id=core type=IDENTITY_CORE version=1"))
        assertTrue(result.out.contains("included id=core type=IDENTITY_CORE version=1"))
        assertTrue(result.out.contains("selection-skip id=ephemeral type=MEMORY_EPHEMERAL reason=EXPLICITLY_EXCLUDED"))
        assertTrue(result.out.contains("provenance shardId=core type=IDENTITY_CORE decision=INCLUDED"))
        assertTrue(result.out.contains("provenance shardId=ephemeral type=MEMORY_EPHEMERAL decision=EXCLUDED"))
    }

    @Test
    fun `reconstruct can export structured artifact`() {
        val store = Files.createTempDirectory("fip-cli-test")
        val artifact = store.resolve("artifacts/reconstruction.properties")
        runSave(store.toString(), "core", "subject-1", "IDENTITY_CORE")

        val result = run(
            "reconstruct",
            "--store", store.toString(),
            "--request-id", "request-1",
            "--subject", "subject-1",
            "--task-type", "cli-test",
            "--surface", "terminal",
            "--max-shards", "10",
            "--artifact-out", artifact.toString()
        )
        val artifactText = Files.readString(artifact)

        assertEquals(0, result.exitCode)
        assertTrue(result.out.contains("artifactOut=$artifact"))
        assertTrue(artifactText.contains("formatVersion=1"))
        assertTrue(artifactText.contains("operationType=RECONSTRUCTION"))
        assertTrue(artifactText.contains("field.requestId=request-1"))
        assertTrue(artifactText.contains("field.subjectId=subject-1"))
        assertTrue(artifactText.contains("field.includedCount=1"))
        assertTrue(artifactText.contains("entry.0.kind=selected"))
        assertTrue(artifactText.contains("shardId=core"))
        assertTrue(artifactText.contains("kind=provenance"))
    }

    @Test
    fun `reconstruct reports missing explicit shard id clearly`() {
        val store = Files.createTempDirectory("fip-cli-test").toString()
        runSave(store, "core", "subject-1", "IDENTITY_CORE")

        val result = run(
            "reconstruct",
            "--store", store,
            "--request-id", "request-1",
            "--subject", "subject-1",
            "--task-type", "cli-test",
            "--surface", "terminal",
            "--max-shards", "10",
            "--shard-id", "missing-explicit",
            "--use-graph-map"
        )

        assertEquals(0, result.exitCode)
        assertTrue(result.out.contains("requestedExplicitCount=1"))
        assertTrue(result.out.contains("unresolvedExplicitCount=1"))
        assertTrue(result.out.contains("explicit-unresolved id=missing-explicit reason=SHARD_NOT_AVAILABLE"))
        assertTrue(!result.out.contains("influences=EXPLICIT_SEED"))
        assertTrue(result.out.contains("included id=core type=IDENTITY_CORE version=1"))
    }

    @Test
    fun `save graph map reports invalid non integer priority clearly`() {
        val store = Files.createTempDirectory("fip-cli-test").toString()

        val result = run(
            "save-graph-map",
            "--store", store,
            "--subject", "subject-1",
            "--node", "seed:IDENTITY_CORE:not-a-number"
        )

        assertEquals(1, result.exitCode)
        assertTrue(result.err.contains("Error: --node priority must be a non-negative integer."))
    }

    @Test
    fun `save and load graph map through cli`() {
        val store = Files.createTempDirectory("fip-cli-test").toString()

        val save = run(
            "save-graph-map",
            "--store", store,
            "--subject", "subject-1",
            "--node", "seed:IDENTITY_CORE:100",
            "--node", "linked:IDENTITY_PREFS:80",
            "--node-link", "seed:linked",
            "--link", "seed:linked:75"
        )
        val load = run("load-graph-map", "--store", store, "--subject", "subject-1")

        assertEquals(0, save.exitCode)
        assertEquals(0, load.exitCode)
        assertTrue(save.out.contains("savedGraphMap subjectId=subject-1"))
        assertTrue(save.out.contains("nodeCount=2"))
        assertTrue(save.out.contains("linkCount=1"))
        assertTrue(load.out.contains("graphMap subjectId=subject-1"))
        assertTrue(load.out.contains("node shardId=seed subjectId=subject-1 type=IDENTITY_CORE priority=100 linkedShardIds=linked"))
        assertTrue(load.out.contains("link fromShardId=seed toShardId=linked weight=75"))
    }

    @Test
    fun `graph-aware reconstruct through cli uses stored graph map`() {
        val store = Files.createTempDirectory("fip-cli-test").toString()
        runSave(store, "seed", "subject-1", "IDENTITY_CORE")
        runSave(store, "linked", "subject-1", "IDENTITY_PREFS")
        runSave(store, "unrelated", "subject-1", "BEHAVIOR_RULES")
        run(
            "save-graph-map",
            "--store", store,
            "--subject", "subject-1",
            "--node", "seed:IDENTITY_CORE:100",
            "--node", "linked:IDENTITY_PREFS:80",
            "--node", "unrelated:BEHAVIOR_RULES:90",
            "--node-link", "seed:linked"
        )

        val result = run(
            "reconstruct",
            "--store", store,
            "--request-id", "request-1",
            "--subject", "subject-1",
            "--task-type", "cli-test",
            "--surface", "terminal",
            "--max-shards", "10",
            "--shard-id", "seed",
            "--use-graph-map"
        )

        assertEquals(0, result.exitCode)
        assertTrue(result.out.contains("selectedCount=2"))
        assertTrue(result.out.contains("includedCount=2"))
        assertTrue(result.out.contains("excludedCount=1"))
        assertTrue(result.out.contains("selected id=seed type=IDENTITY_CORE version=1 influences=EXPLICIT_SEED,WEIGHTED_PRIORITY"))
        assertTrue(result.out.contains("selected id=linked type=IDENTITY_PREFS version=1 influences=GRAPH_LINKED,WEIGHTED_PRIORITY"))
        assertTrue(result.out.contains("included id=seed type=IDENTITY_CORE version=1"))
        assertTrue(result.out.contains("included id=linked type=IDENTITY_PREFS version=1"))
        assertTrue(result.out.contains("selection-skip id=unrelated type=BEHAVIOR_RULES reason=NOT_IN_GRAPH_SELECTION"))
        assertTrue(result.out.contains("provenance shardId=unrelated type=BEHAVIOR_RULES decision=EXCLUDED reason=NOT_IN_GRAPH_SELECTION"))
    }

    @Test
    fun `graph-aware reconstruct through cli falls back when graph map is missing`() {
        val store = Files.createTempDirectory("fip-cli-test").toString()
        runSave(store, "core", "subject-1", "IDENTITY_CORE")

        val result = run(
            "reconstruct",
            "--store", store,
            "--request-id", "request-1",
            "--subject", "subject-1",
            "--task-type", "cli-test",
            "--surface", "terminal",
            "--max-shards", "10",
            "--use-graph-map"
        )

        assertEquals(0, result.exitCode)
        assertTrue(result.out.contains("selectedCount=1"))
        assertTrue(result.out.contains("includedCount=1"))
        assertTrue(result.out.contains("included id=core type=IDENTITY_CORE version=1"))
    }

    @Test
    fun `rebuild graph map creates persisted graph from subject shards`() {
        val store = Files.createTempDirectory("fip-cli-test").toString()
        runSave(store, "core", "subject-1", "IDENTITY_CORE")
        runSave(store, "prefs", "subject-1", "IDENTITY_PREFS")

        val rebuild = run("rebuild-graph-map", "--store", store, "--subject", "subject-1")
        val load = run("load-graph-map", "--store", store, "--subject", "subject-1")

        assertEquals(0, rebuild.exitCode)
        assertTrue(rebuild.out.contains("rebuiltGraphMap subjectId=subject-1"))
        assertTrue(rebuild.out.contains("shardCount=2"))
        assertTrue(rebuild.out.contains("nodeCount=2"))
        assertTrue(rebuild.out.contains("refreshedExisting=false"))
        assertTrue(rebuild.out.contains("regenerationRule=SUBJECT_SHARDS_WITH_ANCHOR_LINKS"))
        assertTrue(load.out.contains("node shardId=core subjectId=subject-1 type=IDENTITY_CORE"))
        assertTrue(load.out.contains("node shardId=prefs subjectId=subject-1 type=IDENTITY_PREFS"))
        assertTrue(load.out.contains("link fromShardId=core toShardId=prefs"))
    }

    @Test
    fun `rebuilt graph map supports graph-aware reconstruction through cli`() {
        val store = Files.createTempDirectory("fip-cli-test").toString()
        runSave(store, "core", "subject-1", "IDENTITY_CORE")
        runSave(store, "prefs", "subject-1", "IDENTITY_PREFS")
        run("rebuild-graph-map", "--store", store, "--subject", "subject-1")

        val result = run(
            "reconstruct",
            "--store", store,
            "--request-id", "request-1",
            "--subject", "subject-1",
            "--task-type", "cli-test",
            "--surface", "terminal",
            "--max-shards", "10",
            "--shard-id", "core",
            "--use-graph-map"
        )

        assertEquals(0, result.exitCode)
        assertTrue(result.out.contains("includedCount=2"))
        assertTrue(result.out.contains("included id=core type=IDENTITY_CORE version=1"))
        assertTrue(result.out.contains("included id=prefs type=IDENTITY_PREFS version=1"))
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
        assertTrue(result.out.contains("selectedCount=1"))
        assertTrue(result.out.contains("includedCount=0"))
        assertTrue(result.out.contains("excludedAtReconstructionCount=1"))
        assertTrue(result.out.contains("nonExposableCount=1"))
        assertTrue(result.out.contains("excludedCount=1"))
        assertTrue(result.out.contains("reconstruction-exclusion id=encrypted type=IDENTITY_CORE reason=CONTENT_NOT_EXPOSABLE nonExposable=true"))
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
    fun `write-back can export structured artifact`() {
        val store = Files.createTempDirectory("fip-cli-test")
        val artifact = store.resolve("artifacts/write-back.properties")
        runSave(store.toString(), "prior-core", "subject-1", "IDENTITY_CORE")

        val writeBack = runWriteBack(
            store = store.toString(),
            extraArgs = arrayOf("--artifact-out", artifact.toString())
        )
        val artifactText = Files.readString(artifact)

        assertEquals(0, writeBack.exitCode)
        assertTrue(writeBack.out.contains("artifactOut=$artifact"))
        assertTrue(artifactText.contains("operationType=WRITE_BACK"))
        assertTrue(artifactText.contains("field.requestId=request-1"))
        assertTrue(artifactText.contains("field.subjectId=subject-1"))
        assertTrue(artifactText.contains("field.createdCount=1"))
        assertTrue(artifactText.contains("field.replacedCount=1"))
        assertTrue(artifactText.contains("field.deletedCount=1"))
        assertTrue(artifactText.contains("kind=created"))
        assertTrue(artifactText.contains("kind=replaced"))
        assertTrue(artifactText.contains("kind=deleted"))
        assertTrue(artifactText.contains("shardId=prior-core"))
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
    fun `write-back updates stored graph map when one exists`() {
        val store = Files.createTempDirectory("fip-cli-test").toString()
        runSave(store, "prior-core", "subject-1", "IDENTITY_CORE")
        runSave(store, "linked-prefs", "subject-1", "IDENTITY_PREFS")
        run(
            "save-graph-map",
            "--store", store,
            "--subject", "subject-1",
            "--node", "prior-core:IDENTITY_CORE:100",
            "--node", "linked-prefs:IDENTITY_PREFS:80",
            "--node-link", "prior-core:linked-prefs",
            "--link", "prior-core:linked-prefs:75"
        )

        val writeBack = runWriteBack(store, extraArgs = arrayOf("--replace-id", "prior-core"))
        val createdId = writeBack.createdShardId()
        val graphMap = run("load-graph-map", "--store", store, "--subject", "subject-1")

        assertEquals(0, writeBack.exitCode)
        assertTrue(writeBack.out.contains("graphMapUpdated=true"))
        assertTrue(graphMap.out.contains("node shardId=$createdId subjectId=subject-1 type=IDENTITY_CORE"))
        assertTrue(graphMap.out.contains("linkedShardIds=linked-prefs"))
        assertTrue(graphMap.out.contains("link fromShardId=$createdId toShardId=linked-prefs weight=75"))
        assertTrue(!graphMap.out.contains("node shardId=prior-core "))
    }

    @Test
    fun `write-back falls back when no graph map exists`() {
        val store = Files.createTempDirectory("fip-cli-test").toString()
        runSave(store, "prior-core", "subject-1", "IDENTITY_CORE")

        val writeBack = runWriteBack(store)

        assertEquals(0, writeBack.exitCode)
        assertTrue(writeBack.out.contains("createdCount=1"))
        assertTrue(writeBack.out.contains("graphMapUpdated=false"))
    }

    @Test
    fun `graph-aware reconstruction works after write-back updates graph map`() {
        val store = Files.createTempDirectory("fip-cli-test").toString()
        runSave(store, "prior-core", "subject-1", "IDENTITY_CORE")
        runSave(store, "linked-prefs", "subject-1", "IDENTITY_PREFS")
        run(
            "save-graph-map",
            "--store", store,
            "--subject", "subject-1",
            "--node", "prior-core:IDENTITY_CORE:100",
            "--node", "linked-prefs:IDENTITY_PREFS:80",
            "--node-link", "prior-core:linked-prefs"
        )

        val writeBack = runWriteBack(store, extraArgs = arrayOf("--replace-id", "prior-core"))
        val createdId = writeBack.createdShardId()
        val reconstruction = run(
            "reconstruct",
            "--store", store,
            "--request-id", "request-graph-after-write-back",
            "--subject", "subject-1",
            "--task-type", "cli-test",
            "--surface", "terminal",
            "--max-shards", "10",
            "--shard-id", createdId,
            "--use-graph-map"
        )

        assertEquals(0, reconstruction.exitCode)
        assertTrue(reconstruction.out.contains("includedCount=2"))
        assertTrue(reconstruction.out.contains("included id=$createdId type=IDENTITY_CORE version=2"))
        assertTrue(reconstruction.out.contains("included id=linked-prefs type=IDENTITY_PREFS version=1"))
    }

    @Test
    fun `rebuild graph map refreshes graph after write-back changes`() {
        val store = Files.createTempDirectory("fip-cli-test").toString()
        runSave(store, "prior-core", "subject-1", "IDENTITY_CORE")
        runSave(store, "prefs", "subject-1", "IDENTITY_PREFS")
        run("rebuild-graph-map", "--store", store, "--subject", "subject-1")

        val writeBack = runWriteBack(store, extraArgs = arrayOf("--replace-id", "prior-core"))
        val createdId = writeBack.createdShardId()
        val rebuild = run("rebuild-graph-map", "--store", store, "--subject", "subject-1")
        val load = run("load-graph-map", "--store", store, "--subject", "subject-1")

        assertEquals(0, rebuild.exitCode)
        assertTrue(rebuild.out.contains("refreshedExisting=true"))
        assertTrue(load.out.contains("node shardId=$createdId subjectId=subject-1 type=IDENTITY_CORE"))
        assertTrue(load.out.contains("node shardId=prefs subjectId=subject-1 type=IDENTITY_PREFS"))
        assertTrue(load.out.contains("link fromShardId=$createdId toShardId=prefs"))
        assertTrue(!load.out.contains("node shardId=prior-core "))
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

    @Test
    fun `check-integrity can export structured artifact`() {
        val store = Files.createTempDirectory("fip-cli-test")
        val artifact = store.resolve("artifacts/integrity.properties")
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

        val result = run(
            "check-integrity",
            "--store", store.toString(),
            "--artifact-out", artifact.toString()
        )
        val artifactText = Files.readString(artifact)

        assertEquals(0, result.exitCode)
        assertTrue(result.out.contains("artifactOut=$artifact"))
        assertTrue(artifactText.contains("operationType=INTEGRITY_CHECK"))
        assertTrue(artifactText.contains("field.checkedCount=1"))
        assertTrue(artifactText.contains("field.validCount=0"))
        assertTrue(artifactText.contains("field.invalidCount=1"))
        assertTrue(artifactText.contains("field.isValid=false"))
        assertTrue(artifactText.contains("kind=record"))
        assertTrue(artifactText.contains("kind=issue"))
        assertTrue(artifactText.contains("code=INVALID_SHARD_TYPE"))
        assertTrue(artifactText.contains("shardId=bad-shard"))
        assertTrue(artifactText.contains("subjectId=subject-1"))
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

    private fun CliRun.createdShardId(): String =
        Regex("""created id=([^\r\n]+)""")
            .find(out)
            ?.groupValues
            ?.get(1)
            ?: error("No created shard id in output: $out")
}
