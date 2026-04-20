package com.innovationstrategies.fip.cli

import com.innovationstrategies.fip.core.FipVersion
import com.innovationstrategies.fip.core.domain.IdentityShard
import com.innovationstrategies.fip.core.domain.IdentityShardId
import com.innovationstrategies.fip.core.domain.IdentitySubjectId
import com.innovationstrategies.fip.core.domain.ReconstructionPolicy
import com.innovationstrategies.fip.core.domain.ReconstructionRequest
import com.innovationstrategies.fip.core.domain.ShardType
import com.innovationstrategies.fip.core.reconstruction.FipReconstructionEngine
import com.innovationstrategies.fip.storage.file.FileIdentityShardRepository
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
    val shard = IdentityShard(
        id = IdentityShardId(options.required("id")),
        subjectId = IdentitySubjectId(options.required("subject")),
        type = ShardType.valueOf(options.required("type")),
        version = options.required("version").toInt(),
        payload = options.required("payload"),
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
        allowSystemMeta = options.flag("allow-system-meta")
    )
    val policy = ReconstructionPolicy(
        allowedShardTypes = request.allowedShardTypes,
        excludedShardTypes = options.optional("excluded-types")
            ?.toShardTypeSet()
            ?: ReconstructionPolicy.from(request).excludedShardTypes,
        allowSystemMeta = request.allowSystemMeta
    )
    val result = FipReconstructionEngine().reconstruct(
        request = request,
        shards = repository.listForSubject(request.subjectId),
        policy = policy
    )

    out.println("requestId=${result.requestId}")
    out.println("subjectId=${result.subjectId.value}")
    out.println("includedCount=${result.includedShards.size}")
    out.println("excludedCount=${result.excludedShardIds.size}")
    out.println("wasBounded=${result.wasBounded}")
    result.includedShards.forEach { shard ->
        out.println("included id=${shard.id.value} type=${shard.type.name} version=${shard.version}")
    }
    result.provenance.forEach { record ->
        out.println(
            "provenance shardId=${record.shardId.value} type=${record.shardType.name} " +
                "decision=${record.decision.name} reason=${record.reason.name}"
        )
    }
    return 0
}

private fun repositoryFor(options: CliOptions): FileIdentityShardRepository =
    FileIdentityShardRepository(Path.of(options.required("store")))

private fun printShard(out: PrintStream, shard: IdentityShard) {
    out.println(
        "shard id=${shard.id.value} subjectId=${shard.subjectId.value} type=${shard.type.name} " +
            "version=${shard.version} source=${shard.source} observedAt=${shard.observedAt} " +
            "tags=${shard.tags.sorted().joinToString(",")}"
    )
    out.println("payload=${shard.payload}")
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
    out.println("  save-shard --store <dir> --id <id> --subject <subject> --type <ShardType> --version <n> --payload <text> --source <source> --observed-at <instant> [--tag <tag>] [--tags a,b]")
    out.println("  load-shard --store <dir> --id <id>")
    out.println("  list-shards --store <dir> --subject <subject>")
    out.println("  delete-shard --store <dir> --id <id>")
    out.println("  reconstruct --store <dir> --request-id <id> --subject <subject> --task-type <type> --surface <surface> --max-shards <n> [--max-payload-bytes <n>] [--allowed-types A,B] [--excluded-types A,B] [--allow-system-meta]")
}
