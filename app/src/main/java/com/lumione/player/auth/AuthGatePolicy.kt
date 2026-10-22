package com.lumione.player.auth

object AuthGatePolicy {
    fun requiresSignIn(uid: String?): Boolean = uid.isNullOrBlank()
}
