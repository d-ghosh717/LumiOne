package com.lumione.player.auth

/**
 * Represents the current Firebase authentication state in LumiOne.
 */
sealed class AuthState {
    object SignedOut : AuthState()
    object SigningIn : AuthState()
    data class SignedIn(
        val uid: String,
        val displayName: String?,
        val email: String?,
        val photoUrl: String?
    ) : AuthState()
    data class Error(val message: String) : AuthState()
}
