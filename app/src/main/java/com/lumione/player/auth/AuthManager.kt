package com.lumione.player.auth

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.lumione.player.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages Google Sign-In and Firebase Authentication lifecycle for LumiOne.
 */
class AuthManager(private val context: Context) {

    private val firebaseAuth: FirebaseAuth = FirebaseAuth.getInstance()
    private val _authState = MutableStateFlow<AuthState>(AuthState.SignedOut)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val googleSignInClient: GoogleSignInClient by lazy {
        val webClientId = try {
            context.getString(R.string.default_web_client_id)
        } catch (e: Exception) {
            // Fallback to the Web Client ID from google-services.json
            "1022113700024-dt8s5m40ip2hpb3a0v344sv8ajitdf57.apps.googleusercontent.com"
        }

        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(webClientId)
            .requestEmail()
            .build()

        GoogleSignIn.getClient(context, gso)
    }

    private val authStateListener = FirebaseAuth.AuthStateListener { auth ->
        val user = auth.currentUser
        if (user != null) {
            _authState.value = AuthState.SignedIn(
                uid = user.uid,
                displayName = user.displayName,
                email = user.email,
                photoUrl = user.photoUrl?.toString()
            )
        } else {
            _authState.value = AuthState.SignedOut
        }
    }

    init {
        firebaseAuth.addAuthStateListener(authStateListener)
        // Initial state sync
        val initialUser = firebaseAuth.currentUser
        if (initialUser != null) {
            _authState.value = AuthState.SignedIn(
                uid = initialUser.uid,
                displayName = initialUser.displayName,
                email = initialUser.email,
                photoUrl = initialUser.photoUrl?.toString()
            )
        } else {
            _authState.value = AuthState.SignedOut
        }
    }

    fun getSignInIntent(): Intent {
        _authState.value = AuthState.SigningIn
        return googleSignInClient.signInIntent
    }

    fun handleSignInResult(
        data: Intent?,
        onSuccess: (FirebaseUser) -> Unit = {},
        onError: (String) -> Unit = {},
        onCancelled: () -> Unit = {}
    ) {
        val task = GoogleSignIn.getSignedInAccountFromIntent(data)
        try {
            val account = task.getResult(ApiException::class.java)
            val idToken = account?.idToken
            if (idToken != null) {
                firebaseAuthWithGoogle(idToken, onSuccess, onError)
            } else {
                val errorMsg = "Google Sign-In returned empty credentials"
                _authState.value = AuthState.Error(errorMsg)
                onError(errorMsg)
            }
        } catch (e: ApiException) {
            // Check for user cancellation
            if (e.statusCode == 12501 || e.statusCode == 16) { // SIGN_IN_CANCELLED or CANCELED
                // Reset state back to current user or signed out
                val user = firebaseAuth.currentUser
                if (user != null) {
                    _authState.value = AuthState.SignedIn(
                        uid = user.uid,
                        displayName = user.displayName,
                        email = user.email,
                        photoUrl = user.photoUrl?.toString()
                    )
                } else {
                    _authState.value = AuthState.SignedOut
                }
                onCancelled()
            } else {
                val errorMsg = "Google Sign-In error (Code: ${e.statusCode})"
                _authState.value = AuthState.Error(errorMsg)
                onError(errorMsg)
            }
        } catch (e: Exception) {
            val errorMsg = e.message ?: "Authentication failed"
            _authState.value = AuthState.Error(errorMsg)
            onError(errorMsg)
        }
    }

    private fun firebaseAuthWithGoogle(
        idToken: String,
        onSuccess: (FirebaseUser) -> Unit,
        onError: (String) -> Unit
    ) {
        _authState.value = AuthState.SigningIn
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        firebaseAuth.signInWithCredential(credential)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val user = firebaseAuth.currentUser
                    if (user != null) {
                        _authState.value = AuthState.SignedIn(
                            uid = user.uid,
                            displayName = user.displayName,
                            email = user.email,
                            photoUrl = user.photoUrl?.toString()
                        )
                        onSuccess(user)
                    } else {
                        val errorMsg = "User is null after Firebase sign-in"
                        _authState.value = AuthState.Error(errorMsg)
                        onError(errorMsg)
                    }
                } else {
                    val errorMsg = task.exception?.localizedMessage ?: "Firebase authentication failed"
                    _authState.value = AuthState.Error(errorMsg)
                    onError(errorMsg)
                }
            }
    }

    fun signOut(onComplete: () -> Unit = {}) {
        firebaseAuth.signOut()
        googleSignInClient.signOut().addOnCompleteListener {
            _authState.value = AuthState.SignedOut
            onComplete()
        }
    }

    fun getCurrentUser(): FirebaseUser? = firebaseAuth.currentUser

    fun isUserSignedIn(): Boolean = firebaseAuth.currentUser != null

    suspend fun getIdToken(forceRefresh: Boolean = false): String? = kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
        val user = firebaseAuth.currentUser
        if (user == null) {
            continuation.resumeWith(Result.success(null))
            return@suspendCancellableCoroutine
        }
        user.getIdToken(forceRefresh)
            .addOnSuccessListener { result ->
                if (continuation.isActive) {
                    continuation.resumeWith(Result.success(result.token))
                }
            }
            .addOnFailureListener {
                if (continuation.isActive) {
                    continuation.resumeWith(Result.success(null))
                }
            }
    }

    fun cleanup() {
        firebaseAuth.removeAuthStateListener(authStateListener)
    }
}
