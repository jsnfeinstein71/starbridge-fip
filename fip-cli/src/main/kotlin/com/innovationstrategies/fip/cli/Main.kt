package com.innovationstrategies.fip.cli

import com.innovationstrategies.fip.core.FipVersion
import com.innovationstrategies.fip.core.domain.IdentityShard
import com.innovationstrategies.fip.core.domain.IdentityShardId
import com.innovationstrategies.fip.core.domain.IdentitySubjectId
import com.innovationstrategies.fip.core.domain.ContentProtection
import com.innovationstrategies.fip.core.domain.PlaceholderEncryptedContentProtection
import com.innovationstrategies.fip.core.domain.PlaintextContentProtection
import com.innovationstrategies.fip.core.domain.ProtectedShardContent
import com.innovationstrategies.fip.core.domain.ReconstructionPolicy
import com.innovationstrategies.fip.core.domain.ReconstructionRequest
import com.innovationstrategies.fip.core.domain.ShardType
import com.innovationstrategies.fip.core.reconstruction.FipReconstructionEngine
import com.innovationstrategies.fip.core.selection.RepositoryBackedGraphAwareShardSelector
import com.innovationstrategies.fip.core.selection.ShardGraphLink
import com.innovationstrategies.fip.core.selection.ShardGraphMap
import com.innovationstrategies.fip.core.selection.ShardGraphMapRegenerator
import com.innovationstrategies.fip.core.selection.ShardGraphNode
import com.innovationstrategies.fip.core.writeback.FipWriteBackEngine
import com.innovationstrategies.fip.core.writeback.IdentityUpdateRequest
import com.innovationstrategies.fip.core.writeback.ShardGraphMapWriteBackUpdater
import com.innovationstrategies.fip.storage.file.FileIdentityShardIntegrityChecker
import com.innovationstrategies.fip.storage.file.FileIdentityShardRepository
import com.innovationstrategies.fip.storage.file.FileShardGraphMapRepository
import java.io.PrintStream
import java.nio.file.Path
import java.time.Instant
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    exitProcess(runCli(args, System.out, System.err))
}

fun runCli(args: Array<String>, out: PrintStream, err: PrintStream): Int {
    if (args.isEmpty()) {
        printUsage(out)
        return 0
    }

    return try {
        when (val command = args[0]) {
            "save-shard" -> saveShard(args.drop(1), out)
            "load-shard" -> loadShard(args.drop(1), out)
            "list-shards" -> listShards(args.drop(1), out)
            "delete-shard" -> deleteShard(args.drop(1), out)
            "reconstruct" -> reconstruct(args.drop(1), out)
            "write-back" -> writeBack(args.drop(1), out)
            "check-integrity" -> checkIntegrity(args.drop(1), out)
            "save-graph-map" -> saveGraphMap(args.drop(1), out)
            "load-graph-map" -> loadGraphMap(args.drop(1), out)
            "rebuild-graph-map" -> rebuildGraphMap(args.drop(1), out)
            "version" -> {
                out.println("FIP CLI v${FipVersion.VALUE}")
                0
            }
            "help", "--help", "-h" -> {
                printUsage(out)
                0
            }
            else -> {
                err.println("Unknown command: $command")
                printUsage(err)
                1
            }
        }
    } catch (error: IllegalArgumentException) {
        err.println("Error: ${error.message}")
        1
    }
}

private fun saveShard(args: List<String>, out: PrintStream): Int {
    val options = parseOptions(args)
    val repository = repositoryFor(options)
    val contentProtection = contentProtectionFor(options)
    val protectedContent = contentProtection.protect(options.required("payload"))
    val shard = IdentityShard(
        id = IdentityShardId(options.required("id")),
        subjectId = IdentitySubjectId(options.required("subject")),
        type = ShardType.valueOf(options.required("type")),
        version = options.required("version").toInt(),
        payload = contentProtection.compatibilityPayload(protectedContent),
        protectedContent = protectedContent,
        source = options.required("source"),
        observedAt = Instant.parse(options.required("observed-at")),
        tags = options.values("tag").toSet() + options.optional("tags").csvValues()
    )

    repository.save(shard)
    out.println("saved id=${shard.id.value}")
    return 0
}

private fun loadShard(args: List<String>, out: PrintStream): Int {
    val options = parseOptions(args)
    val repository = repositoryFor(options)
    val shard = repository.load(IdentityShardId(options.required("id")))
    if (shard == null) {
        out.println("not-found id=${options.required("id")}")
    } else {
        printShard(out, shard)
    }
    return 0
}

private fun listShards(args: List<String>, out: PrintStream): Int {
    val options = parseOptions(args)
    val repository = repositoryFor(options)
    val shards = repository.listForSubject(IdentitySubjectId(options.required("subject")))
    out.println("count=${shards.size}")
    shards.forEach { printShard(out, it) }
    return 0
}

private fun deleteShard(args: List<String>, out: PrintStream): Int {
    val options = parseOptions(args)
    val repository = repositoryFor(options)
    val id = IdentityShardId(options.required("id"))
    val deleted = repository.delete(id)
    out.println("${if (deleted) "deleted" else "not-found"} id=${id.value}")
    return 0
}

private fun reconstruct(args: List<String>, out: PrintStream): Int {
    val options = parseOptions(args)
    val repository = repositoryFor(options)
    val request = ReconstructionRequest(
        requestId = options.required("request-id"),
        subjectId = IdentitySubjectId(options.required("subject")),
        taskType = options.required("task-type"),
        surface = options.required("surface"),
        maxShardCount = options.required("max-shards").toInt(),
        maxPayloadBytes = options.optional("max-payload-bytes")?.toInt(),
        allowedShardTypes = options.optional("allowed-types")
            ?.toShardTypeSet()
            ?: ReconstructionRequest.DEFAULT_ALLOWED_SHARD_TYPES,
        explicitShardIds = options.values("shard-id").map { IdentityShardId(it) }.toSet(),
        allowSystemMeta = options.flag("allow-system-meta")
    )
    val policy = ReconstructionPolicy(
        allowedShardTypes = request.allowedShardTypes,
        excludedShardTypes = options.optional("excluded-types")
            ?.toShardTypeSet()
            ?: ReconstructionPolicy.from(request).excludedShardTypes,
        allowSystemMeta = request.allowSystemMeta
    )
    val engine = if (options.flag("use-graph-map")) {
        FipReconstructionEngine(
            shardSelector = RepositoryBackedGraphAwareShardSelector(graphMapRepositoryFor(options))
        )
    } else {
        FipReconstructionEngine()
    }
    val result = engine.reconstruct(
        request = request,
        shards = repository.listForSubject(request.subjectId),
        policy = policy
    )

    out.println("requestId=${result.requestId}")
    out.println("subjectId=${result.subjectId.value}")
    out.println("selectedCount=${result.selectedShards.size}")
    out.println("includedCount=${result.includedShards.size}")
    out.println("skippedAtSelectionCount=${result.skippedAtSelection.size}")
    out.println("excludedAtReconstructionCount=${result.excludedAtReconstruction.size}")
    out.println("nonExposableCount=${result.nonExposableProtectedContentShards.size}")
    out.println("excludedCount=${result.excludedShardIds.size}")
    out.println("wasBounded=${result.wasBounded}")
    result.selectedShardReports.forEach { report ->
        out.println(
            "selected id=${report.shard.id.value} type=${report.shard.type.name} version=${report.shard.version} " +
                "influences=${report.influences.sortedBy { it.name }.joinToString(",") { it.name }}"
        )
    }
    result.includedShards.forEach { shard ->
        out.println(
            "included id=${shard.id.value} type=${shard.type.name} version=${shard.version} " +
                "contentMode=${shard.protectedContent.mode.name}"
        )
    }
    result.skippedAtSelection.forEach { skip ->
        out.println(
            "selection-skip id=${skip.shard.id.value} type=${skip.shard.type.name} " +
                "reason=${skip.reason.name} influences=${skip.influences.sortedBy { it.name }.joinToString(",") { it.name }}"
        )
    }
    result.excludedAtReconstruction.forEach { exclusion ->
        out.println(
            "reconstruction-exclusion id=${exclusion.shard.id.value} type=${exclusion.shard.type.name} " +
                "reason=${exclusion.reason.name} nonExposable=${exclusion.reason == com.innovationstrategies.fip.core.domain.ProvenanceReason.CONTENT_NOT_EXPOSABLE} " +
                "influences=${exclusion.influences.sortedBy { it.name }.joinToString(",") { it.name }}"
        )
    }
    result.provenance.forEach { record ->
        out.println(
            "provenance shardId=${record.shardId.value} type=${record.shardType.name} " +
                "decision=${record.decision.name} reason=${record.reason.name}"
        )
    }
    return 0
}

private fun saveGraphMap(args: List<String>, out: PrintStream): Int {
    val options = parseOptions(args)
    val subjectId = IdentitySubjectId(options.required("subject"))
    val nodeLinks = options.values("node-link")
        .map { it.toShardIdPair("--node-link") }
        .groupBy({ it.first }, { it.second })
    val nodes = options.values("node").map { rawNode ->
        val parts = rawNode.split(":")
        require(parts.size == 3) {
            "--node must use <shardId>:<ShardType>:<priority>"
        }
        val shardId = IdentityShardId(parts[0])
        ShardGraphNode(
            shardId = shardId,
            subjectId = subjectId,
            shardType = ShardType.valueOf(parts[1]),
            priority = parts[2].toInt(),
            linkedShardIds = nodeLinks[shardId].orEmpty().toSet()
        )
    }
    val links = options.values("link").map { it.toShardGraphLink() }
    val graphMap = ShardGraphMap.of(nodes, links)

    graphMapRepositoryFor(options).save(subjectId, graphMap)

    out.println("savedGraphMap subjectId=${subjectId.value}")
    out.println("nodeCount=${graphMap.nodes.size}")
    out.println("linkCount=${graphMap.links.size}")
    return 0
}

private fun loadGraphMap(args: List<String>, out: PrintStream): Int {
    val options = parseOptions(args)
    val subjectId = IdentitySubjectId(options.required("subject"))
    val graphMap = graphMapRepositoryFor(options).load(subjectId)

    if (graphMap == null) {
        out.println("not-found subjectId=${subjectId.value}")
        return 0
    }

    printGraphMap(out, subjectId, graphMap)
    return 0
}

private fun rebuildGraphMap(args: List<String>, out: PrintStream): Int {
    val options = parseOptions(args)
    val subjectId = IdentitySubjectId(options.required("subject"))
    val repository = repositoryFor(options)
    val graphRepository = graphMapRepositoryFor(options)
    val shards = repository.listForSubject(subjectId)
    val existingGraphMap = graphRepository.load(subjectId)
    val graphMap = ShardGraphMapRegenerator().regenerate(
        subjectId = subjectId,
        shards = shards,
        existingGraphMap = existingGraphMap
    )

    graphRepository.replaceForSubject(subjectId, graphMap)

    out.println("rebuiltGraphMap subjectId=${subjectId.value}")
    out.println("shardCount=${shards.size}")
    out.println("nodeCount=${graphMap.nodes.size}")
    out.println("linkCount=${graphMap.links.size}")
    out.println("refreshedExisting=${existingGraphMap != null}")
    return 0
}

private fun writeBack(args: List<String>, out: PrintStream): Int {
    val options = parseOptions(args)
    val repository = repositoryFor(options)
    val replaceShardIds = options.values("replace-id").map { IdentityShardId(it) }.toSet()
    val contentProtection = contentProtectionFor(options)
    val request = IdentityUpdateRequest(
        requestId = options.required("request-id"),
        subjectId = IdentitySubjectId(options.required("subject")),
        taskType = options.required("task-type"),
        surface = options.required("surface"),
        shardType = ShardType.valueOf(options.required("type")),
        payload = options.required("payload"),
        source = options.required("source"),
        replaceShardIds = replaceShardIds,
        tags = options.values("tag").toSet() + options.optional("tags").csvValues(),
        maxReplacementSourceShards = options.optional("max-replacement-source-shards")?.toInt() ?: 16,
        maxPayloadBytes = options.optional("max-payload-bytes")?.toInt(),
        allowSystemMeta = options.flag("allow-system-meta")
    )
    val availableShards = availableShardsForWriteBack(repository, request)
    val result = FipWriteBackEngine(contentProtection = contentProtection).writeBack(request, availableShards)

    result.plan.replacementShards.forEach { repository.save(it) }
    result.deletedShardIds.forEach { repository.delete(it) }
    val graphMapUpdated = updateGraphMapForWriteBack(options, result)

    out.println("requestId=${result.requestId}")
    out.println("subjectId=${result.subjectId.value}")
    out.println("createdCount=${result.createdShardIds.size}")
    out.println("replacedCount=${result.replacedShardIds.size}")
    out.println("deletedCount=${result.deletedShardIds.size}")
    out.println("wasBounded=${result.wasBounded}")
    out.println("graphMapUpdated=$graphMapUpdated")
    result.createdShardIds.sortedBy { it.value }.forEach { out.println("created id=${it.value}") }
    result.deletedShardIds.sortedBy { it.value }.forEach { out.println("deleted id=${it.value}") }
    return 0
}

private fun updateGraphMapForWriteBack(
    options: CliOptions,
    result: com.innovationstrategies.fip.core.writeback.WriteBackResult
): Boolean {
    val graphRepository = graphMapRepositoryFor(options)
    val graphMap = graphRepository.load(result.subjectId) ?: return false
    val updatedGraphMap = ShardGraphMapWriteBackUpdater().update(graphMap, result)
    graphRepository.replaceForSubject(result.subjectId, updatedGraphMap)
    return true
}

private fun checkIntegrity(args: List<String>, out: PrintStream): Int {
    val options = parseOptions(args)
    val result = FileIdentityShardIntegrityChecker(Path.of(options.required("store"))).check()

    out.println("checkedCount=${result.checkedRecordCount}")
    out.println("validCount=${result.validRecordCount}")
    out.println("invalidCount=${result.invalidRecordCount}")
    out.println("isValid=${result.isValid}")
    result.records.forEach { record ->
        record.issues.forEach { issue ->
            out.println(
                "issue location=${record.storageLocation} severity=${issue.severity.name} " +
                    "code=${issue.code.name} message=${issue.message} " +
                    "shardId=${record.shardId ?: ""} subjectId=${record.subjectId ?: ""}"
            )
        }
    }
    return 0
}

private fun repositoryFor(options: CliOptions): FileIdentityShardRepository =
    FileIdentityShardRepository(Path.of(options.required("store")))

private fun graphMapRepositoryFor(options: CliOptions): FileShardGraphMapRepository =
    FileShardGraphMapRepository(Path.of(options.required("store")))

private fun contentProtectionFor(options: CliOptions): ContentProtection =
    when (options.optional("content-mode") ?: "PLAINTEXT") {
        "PLAINTEXT" -> PlaintextContentProtection
        "ENCRYPTED_PLACEHOLDER" -> PlaceholderEncryptedContentProtection
        else -> throw IllegalArgumentException(
            "Unsupported --content-mode. Use PLAINTEXT or ENCRYPTED_PLACEHOLDER."
        )
    }

private fun availableShardsForWriteBack(
    repository: FileIdentityShardRepository,
    request: IdentityUpdateRequest
): List<IdentityShard> {
    val shardsById = linkedMapOf<IdentityShardId, IdentityShard>()
    repository.listForSubject(request.subjectId).forEach { shardsById[it.id] = it }
    request.replaceShardIds.forEach { id ->
        repository.load(id)?.let { shardsById[it.id] = it }
    }
    return shardsById.values.toList()
}

private fun printShard(out: PrintStream, shard: IdentityShard) {
    out.println(
        "shard id=${shard.id.value} subjectId=${shard.subjectId.value} type=${shard.type.name} " +
            "version=${shard.version} source=${shard.source} observedAt=${shard.observedAt} " +
            "contentMode=${shard.protectedContent.mode.name} contentAlgorithm=${contentAlgorithmFor(shard.protectedContent)} " +
            "tags=${shard.tags.sorted().joinToString(",")}"
    )
    when (shard.protectedContent) {
        is ProtectedShardContent.Plaintext -> out.println("payload=${shard.payload}")
        is ProtectedShardContent.EncryptedPayload -> out.println("payload=${ProtectedShardContent.PROTECTED_PAYLOAD_PLACEHOLDER}")
    }
}

private fun contentAlgorithmFor(content: ProtectedShardContent): String =
    when (content) {
        is ProtectedShardContent.Plaintext -> "PLAINTEXT-DEVELOPMENT"
        is ProtectedShardContent.EncryptedPayload -> content.algorithm
    }

private fun printGraphMap(out: PrintStream, subjectId: IdentitySubjectId, graphMap: ShardGraphMap) {
    out.println("graphMap subjectId=${subjectId.value}")
    out.println("nodeCount=${graphMap.nodes.size}")
    out.println("linkCount=${graphMap.links.size}")
    graphMap.nodes.values.sortedBy { it.shardId.value }.forEach { node ->
        out.println(
            "node shardId=${node.shardId.value} subjectId=${node.subjectId.value} " +
                "type=${node.shardType.name} priority=${node.priority} " +
                "linkedShardIds=${node.linkedShardIds.sortedBy { it.value }.joinToString(",") { it.value }}"
        )
    }
    graphMap.links.sortedWith(
        compareBy<ShardGraphLink> { it.fromShardId.value }
            .thenBy { it.toShardId.value }
            .thenByDescending { it.weight }
    ).forEach { link ->
        out.println(
            "link fromShardId=${link.fromShardId.value} toShardId=${link.toShardId.value} weight=${link.weight}"
        )
    }
}

private fun String.toShardIdPair(optionName: String): Pair<IdentityShardId, IdentityShardId> {
    val parts = split(":")
    require(parts.size == 2) { "$optionName must use <fromShardId>:<toShardId>" }
    return IdentityShardId(parts[0]) to IdentityShardId(parts[1])
}

private fun String.toShardGraphLink(): ShardGraphLink {
    val parts = split(":")
    require(parts.size == 3) { "--link must use <fromShardId>:<toShardId>:<weight>" }
    return ShardGraphLink(
        fromShardId = IdentityShardId(parts[0]),
        toShardId = IdentityShardId(parts[1]),
        weight = parts[2].toInt()
    )
}

private fun parseOptions(args: List<String>): CliOptions {
    val values = linkedMapOf<String, MutableList<String>>()
    var index = 0
    while (index < args.size) {
        val token = args[index]
        require(token.startsWith("--")) { "Expected option but found: $token" }
        val key = token.removePrefix("--")
        if (index + 1 >= args.size || args[index + 1].startsWith("--")) {
            values.getOrPut(key) { mutableListOf() }.add("true")
            index += 1
        } else {
            values.getOrPut(key) { mutableListOf() }.add(args[index + 1])
            index += 2
        }
    }
    return CliOptions(values)
}

private data class CliOptions(
    private val values: Map<String, List<String>>
) {
    fun required(key: String): String =
        optional(key) ?: throw IllegalArgumentException("Missing required option --$key")

    fun optional(key: String): String? =
        values[key]?.lastOrNull()

    fun values(key: String): List<String> =
        values[key].orEmpty()

    fun flag(key: String): Boolean =
        values[key]?.lastOrNull()?.toBooleanStrictOrNull() ?: false
}

private fun String?.csvValues(): Set<String> =
    this?.split(",")
        ?.map { it.trim() }
        ?.filter { it.isNotBlank() }
        ?.toSet()
        .orEmpty()

private fun String.toShardTypeSet(): Set<ShardType> =
    csvValues().map { ShardType.valueOf(it) }.toSet()

private fun printUsage(out: PrintStream) {
    out.println("FIP CLI v${FipVersion.VALUE}")
    out.println("Commands:")
    out.println("  save-shard --store <dir> --id <id> --subject <subject> --type <ShardType> --version <n> --payload <text> --source <source> --observed-at <instant> [--content-mode PLAINTEXT|ENCRYPTED_PLACEHOLDER] [--tag <tag>] [--tags a,b]")
    out.println("  load-shard --store <dir> --id <id>")
    out.println("  list-shards --store <dir> --subject <subject>")
    out.println("  delete-shard --store <dir> --id <id>")
    out.println("  reconstruct --store <dir> --request-id <id> --subject <subject> --task-type <type> --surface <surface> --max-shards <n> [--max-payload-bytes <n>] [--allowed-types A,B] [--excluded-types A,B] [--shard-id <id>] [--allow-system-meta] [--use-graph-map]")
    out.println("  write-back --store <dir> --request-id <id> --subject <subject> --task-type <type> --surface <surface> --type <ShardType> --payload <text> --source <source> [--content-mode PLAINTEXT|ENCRYPTED_PLACEHOLDER] [--replace-id <id>] [--tag <tag>] [--tags a,b] [--max-replacement-source-shards <n>] [--max-payload-bytes <n>] [--allow-system-meta]")
    out.println("  check-integrity --store <dir>")
    out.println("  save-graph-map --store <dir> --subject <subject> [--node <shardId>:<ShardType>:<priority>] [--node-link <fromShardId>:<toShardId>] [--link <fromShardId>:<toShardId>:<weight>]")
    out.println("  load-graph-map --store <dir> --subject <subject>")
    out.println("  rebuild-graph-map --store <dir> --subject <subject>")
}
