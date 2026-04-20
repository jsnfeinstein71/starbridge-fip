package com.innovationstrategies.fip.core.integrity

data class IntegrityCheckResult(
    val checkedRecordCount: Int,
    val records: List<ShardValidationResult>
) {
    init {
        require(checkedRecordCount >= 0) { "checkedRecordCount must not be negative." }
        require(checkedRecordCount == records.size) {
            "checkedRecordCount must match records size."
        }
    }

    val validRecordCount: Int
        get() = records.count { it.isValid }

    val invalidRecordCount: Int
        get() = records.count { !it.isValid }

    val isValid: Boolean
        get() = invalidRecordCount == 0
}
