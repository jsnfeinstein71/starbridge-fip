package com.innovationstrategies.fip.core.domain

fun interface ShardContentExposure {
    fun expose(content: ProtectedShardContent): String
}

interface ContentProtection : ShardContentExposure {
    fun protect(plaintext: String): ProtectedShardContent

    fun compatibilityPayload(content: ProtectedShardContent): String =
        expose(content)
}

object PlaintextContentProtection : ContentProtection {
    override fun protect(plaintext: String): ProtectedShardContent =
        ProtectedShardContent.Plaintext(plaintext)

    override fun expose(content: ProtectedShardContent): String =
        when (content) {
            is ProtectedShardContent.Plaintext -> content.value
            is ProtectedShardContent.EncryptedPayload -> error(
                "Encrypted shard content cannot be exposed by plaintext development protection."
            )
        }
}

object PlaceholderEncryptedContentProtection : ContentProtection {
    private const val PLACEHOLDER_ALGORITHM = "FIP-PLACEHOLDER-NO-CRYPTO"

    override fun protect(plaintext: String): ProtectedShardContent {
        require(plaintext.isNotBlank()) { "plaintext must not be blank." }
        return ProtectedShardContent.EncryptedPayload(
            value = "placeholder:${plaintext.length}:${plaintext.hashCode()}",
            algorithm = PLACEHOLDER_ALGORITHM
        )
    }

    override fun expose(content: ProtectedShardContent): String =
        when (content) {
            is ProtectedShardContent.Plaintext -> content.value
            is ProtectedShardContent.EncryptedPayload -> error(
                "Encrypted placeholder shard content cannot be exposed without a real content protector."
            )
        }

    override fun compatibilityPayload(content: ProtectedShardContent): String =
        when (content) {
            is ProtectedShardContent.Plaintext -> content.value
            is ProtectedShardContent.EncryptedPayload -> ProtectedShardContent.PROTECTED_PAYLOAD_PLACEHOLDER
        }
}
