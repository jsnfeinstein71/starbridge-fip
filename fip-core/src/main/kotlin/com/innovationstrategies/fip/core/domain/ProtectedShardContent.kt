package com.innovationstrategies.fip.core.domain

sealed interface ProtectedShardContent {
    val mode: ProtectedShardContentMode
    val value: String

    data class Plaintext(
        override val value: String
    ) : ProtectedShardContent {
        override val mode: ProtectedShardContentMode = ProtectedShardContentMode.PLAINTEXT

        init {
            require(value.isNotBlank()) { "Plaintext shard content must not be blank." }
        }
    }

    data class EncryptedPayload(
        override val value: String,
        val algorithm: String
    ) : ProtectedShardContent {
        override val mode: ProtectedShardContentMode = ProtectedShardContentMode.ENCRYPTED

        init {
            require(value.isNotBlank()) { "Encrypted shard content value must not be blank." }
            require(algorithm.isNotBlank()) { "Encrypted shard content algorithm must not be blank." }
        }
    }

    companion object {
        const val PROTECTED_PAYLOAD_PLACEHOLDER = "<protected-content>"
    }
}

enum class ProtectedShardContentMode {
    PLAINTEXT,
    ENCRYPTED
}
