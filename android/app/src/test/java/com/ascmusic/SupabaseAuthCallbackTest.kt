package com.ascmusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SupabaseAuthCallbackTest {
    @Test
    fun parsesRecoveryTokensOnlyForRecoveryRedirect() {
        val callback = parseSupabaseAuthCallback(
            "ascmusic://auth/recovery#access_token=access.value&refresh_token=refresh.value&type=recovery&token_type=bearer",
        )

        assertEquals("recovery", callback.type)
        assertEquals("access.value", callback.accessToken)
        assertEquals("refresh.value", callback.refreshToken)
    }

    @Test
    fun parsesEmailConfirmationCallback() {
        val callback = parseSupabaseAuthCallback(
            "ascmusic://auth/confirm#access_token=access.value&refresh_token=refresh.value&type=signup",
        )

        assertEquals("signup", callback.type)
    }

    @Test
    fun rejectsForeignRedirectAndMismatchedCallbackType() {
        assertThrows(IllegalArgumentException::class.java) {
            parseSupabaseAuthCallback(
                "https://attacker.example/auth/recovery#access_token=a&refresh_token=r&type=recovery",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseSupabaseAuthCallback(
                "ascmusic://auth/recovery#access_token=a&refresh_token=r&type=signup",
            )
        }
    }

    @Test
    fun rejectsCallbackWithoutRefreshCredential() {
        assertThrows(IllegalArgumentException::class.java) {
            parseSupabaseAuthCallback("ascmusic://auth/recovery#access_token=a&type=recovery")
        }
    }
}
