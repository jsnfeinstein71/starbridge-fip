package com.innovationstrategies.fip.core.integrity

data class ShardValidationResult(
    val storageLocation: String,
    val shardId: String?,
    val subjectId: String?,
    val issues: List<ShardValidationIssue>
) {
    init {
        require(storageLocation.isNotBlank()) { "storageLocation must not be blank." }
    }

    val isValid: Boolean
        get() = issues.none { it.severity == ShardValidationSeverity.ERROR }
}
