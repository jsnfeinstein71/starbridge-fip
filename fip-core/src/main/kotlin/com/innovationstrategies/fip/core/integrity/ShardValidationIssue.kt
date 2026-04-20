package com.innovationstrategies.fip.core.integrity

data class ShardValidationIssue(
    val code: ShardValidationIssueCode,
    val severity: ShardValidationSeverity,
    val message: String
) {
    init {
        require(message.isNotBlank()) { "ShardValidationIssue message must not be blank." }
    }
}

enum class ShardValidationIssueCode {
    REQUIRED_FIELD_MISSING,
    UNSUPPORTED_FORMAT_VERSION,
    INVALID_FORMAT_VERSION,
    INVALID_SHARD_ID,
    INVALID_SUBJECT_ID,
    INVALID_SHARD_TYPE,
    INVALID_VERSION,
    INVALID_OBSERVED_AT,
    INVALID_TAGS,
    INVALID_PAYLOAD,
    INVALID_SOURCE,
    PARSE_FAILURE,
    DUPLICATE_SHARD_ID
}

enum class ShardValidationSeverity {
    WARNING,
    ERROR
}
