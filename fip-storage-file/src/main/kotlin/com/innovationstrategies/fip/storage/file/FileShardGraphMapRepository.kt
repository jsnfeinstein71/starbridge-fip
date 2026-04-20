package com.innovationstrategies.fip.storage.file

import com.innovationstrategies.fip.core.domain.IdentityShardId
import com.innovationstrategies.fip.core.domain.IdentitySubjectId
import com.innovationstrategies.fip.core.domain.ShardType
import com.innovationstrategies.fip.core.selection.ShardGraphLink
import com.innovationstrategies.fip.core.selection.ShardGraphMap
import com.innovationstrategies.fip.core.selection.ShardGraphNode
import com.innovationstrategies.fip.core.storage.ShardGraphMapRepository
import java.io.BufferedReader
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.Base64
import java.util.Properties
import kotlin.io.path.exists

class FileShardGraphMapRepository(
    private val root: Path
) : ShardGraphMapRepository {
    init {
        Files.createDirectories(root)
    }

    override fun save(subjectId: IdentitySubjectId, graphMap: ShardGraphMap): ShardGraphMap {
        requireSubjectConsistency(subjectId, graphMap)
        Files.createDirectories(root)
        val target = pathFor(subjectId)
        val temporary = Files.createTempFile(root, target.fileName.toString(), ".tmp")

        try {
            Files.newBufferedWriter(temporary, StandardCharsets.UTF_8).use { writer ->
                propertiesFor(subjectId, graphMap).store(writer, "FIP shard graph map")
            }
            moveIntoPlace(temporary, target)
        } finally {
            Files.deleteIfExists(temporary)
        }

        return graphMap
    }

    override fun load(subjectId: IdentitySubjectId): ShardGraphMap? {
        val path = pathFor(subjectId)
        if (!path.exists()) {
            return null
        }
        return readGraphMap(subjectId, path)
    }

    private fun readGraphMap(expectedSubjectId: IdentitySubjectId, path: Path): ShardGraphMap {
        val properties = Properties()
        Files.newBufferedReader(path, StandardCharsets.UTF_8).use { reader: BufferedReader ->
            properties.load(reader)
        }

        val formatVersion = properties.required("formatVersion").toInt()
        require(formatVersion == FORMAT_VERSION) {
            "Unsupported shard graph map storage format version: $formatVersion."
        }

        val storedSubjectId = IdentitySubjectId(properties.required("subjectId"))
        require(storedSubjectId == expectedSubjectId) {
            "Stored shard graph map subject does not match requested subject."
        }

        val nodeCount = properties.required("nodeCount").toInt()
        require(nodeCount >= 0) { "nodeCount must not be negative." }
        val nodes = (0 until nodeCount).map { index -> readNode(properties, index) }

        val linkCount = properties.required("linkCount").toInt()
        require(linkCount >= 0) { "linkCount must not be negative." }
        val links = (0 until linkCount).map { index -> readLink(properties, index) }

        return ShardGraphMap.of(nodes, links)
    }

    private fun readNode(properties: Properties, index: Int): ShardGraphNode {
        val linkedShardIdCount = properties.required("node.$index.linkedShardIdCount").toInt()
        require(linkedShardIdCount >= 0) { "node.$index.linkedShardIdCount must not be negative." }
        val linkedShardIds = (0 until linkedShardIdCount)
            .map { linkedIndex -> IdentityShardId(properties.required("node.$index.linkedShardId.$linkedIndex")) }
            .toSet()

        return ShardGraphNode(
            shardId = IdentityShardId(properties.required("node.$index.shardId")),
            subjectId = IdentitySubjectId(properties.required("node.$index.subjectId")),
            shardType = ShardType.valueOf(properties.required("node.$index.shardType")),
            priority = properties.required("node.$index.priority").toInt(),
            linkedShardIds = linkedShardIds
        )
    }

    private fun readLink(properties: Properties, index: Int): ShardGraphLink =
        ShardGraphLink(
            fromShardId = IdentityShardId(properties.required("link.$index.fromShardId")),
            toShardId = IdentityShardId(properties.required("link.$index.toShardId")),
            weight = properties.required("link.$index.weight").toInt()
        )

    private fun propertiesFor(subjectId: IdentitySubjectId, graphMap: ShardGraphMap): Properties =
        Properties().apply {
            setProperty("formatVersion", FORMAT_VERSION.toString())
            setProperty("subjectId", subjectId.value)

            val nodes = graphMap.nodes.values.sortedBy { it.shardId.value }
            setProperty("nodeCount", nodes.size.toString())
            nodes.forEachIndexed { index, node ->
                setProperty("node.$index.shardId", node.shardId.value)
                setProperty("node.$index.subjectId", node.subjectId.value)
                setProperty("node.$index.shardType", node.shardType.name)
                setProperty("node.$index.priority", node.priority.toString())

                val linkedShardIds = node.linkedShardIds.sortedBy { it.value }
                setProperty("node.$index.linkedShardIdCount", linkedShardIds.size.toString())
                linkedShardIds.forEachIndexed { linkedIndex, linkedShardId ->
                    setProperty("node.$index.linkedShardId.$linkedIndex", linkedShardId.value)
                }
            }

            val links = graphMap.links.sortedWith(
                compareBy<ShardGraphLink> { it.fromShardId.value }
                    .thenBy { it.toShardId.value }
                    .thenByDescending { it.weight }
            )
            setProperty("linkCount", links.size.toString())
            links.forEachIndexed { index, link ->
                setProperty("link.$index.fromShardId", link.fromShardId.value)
                setProperty("link.$index.toShardId", link.toShardId.value)
                setProperty("link.$index.weight", link.weight.toString())
            }
        }

    private fun requireSubjectConsistency(subjectId: IdentitySubjectId, graphMap: ShardGraphMap) {
        val mismatchedNode = graphMap.nodes.values.firstOrNull { it.subjectId != subjectId }
        require(mismatchedNode == null) {
            "Shard graph map node subject does not match graph map subject: ${mismatchedNode?.shardId?.value}."
        }
    }

    private fun pathFor(subjectId: IdentitySubjectId): Path =
        root.resolve("${fileNameTokenFor(subjectId)}$GRAPH_FILE_SUFFIX").normalize()

    private fun fileNameTokenFor(subjectId: IdentitySubjectId): String =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(subjectId.value.toByteArray(StandardCharsets.UTF_8))

    private fun moveIntoPlace(source: Path, target: Path) {
        try {
            Files.move(source, target, REPLACE_EXISTING, ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target, REPLACE_EXISTING)
        }
    }

    private fun Properties.required(key: String): String =
        requireNotNull(getProperty(key)) { "Stored shard graph map is missing required field: $key." }

    companion object {
        private const val FORMAT_VERSION = 1
        private const val GRAPH_FILE_SUFFIX = ".graph.properties"
    }
}
