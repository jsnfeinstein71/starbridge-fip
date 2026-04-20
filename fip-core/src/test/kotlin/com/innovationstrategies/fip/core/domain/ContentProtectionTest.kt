package com.innovationstrategies.fip.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ContentProtectionTest {
    @Test
    fun `plaintext development protection wraps and exposes content`() {
        val protectedContent = PlaintextContentProtection.protect("development payload")

        assertEquals(ProtectedShardContentMode.PLAINTEXT, protectedContent.mode)
        assertEquals("development payload", PlaintextContentProtection.expose(protectedContent))
    }

    @Test
    fun `plaintext development protection does not expose encrypted placeholder content`() {
        val encryptedContent = ProtectedShardContent.EncryptedPayload(
            value = "ciphertext-placeholder",
            algorithm = "future-algorithm"
        )

        assertFailsWith<IllegalStateException> {
            PlaintextContentProtection.expose(encryptedContent)
        }
    }

    @Test
    fun `placeholder encrypted protection represents content without plaintext exposure`() {
        val protectedContent = PlaceholderEncryptedContentProtection.protect("sensitive payload")

        assertEquals(ProtectedShardContentMode.ENCRYPTED, protectedContent.mode)
        assertEquals(
            ProtectedShardContent.PROTECTED_PAYLOAD_PLACEHOLDER,
            PlaceholderEncryptedContentProtection.compatibilityPayload(protectedContent)
        )
        assertFailsWith<IllegalStateException> {
            PlaceholderEncryptedContentProtection.expose(protectedContent)
        }
    }
}
