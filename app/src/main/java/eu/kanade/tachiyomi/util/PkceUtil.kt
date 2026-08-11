package eu.kanade.tachiyomi.util

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Helpers for the OAuth 2.0 PKCE flow (RFC 7636), used by trackers that require a
 * code verifier/challenge pair.
 */
object PkceUtil {
    private const val PKCE_BASE64_ENCODE_SETTINGS =
        Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE

    fun generateCodeVerifier(): String {
        val codeVerifier = ByteArray(50)
        SecureRandom().nextBytes(codeVerifier)
        return Base64.encodeToString(codeVerifier, PKCE_BASE64_ENCODE_SETTINGS)
    }

    fun generateCodeChallenge(codeVerifier: String): String {
        val bytes = codeVerifier.toByteArray(Charsets.US_ASCII)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return Base64.encodeToString(digest, PKCE_BASE64_ENCODE_SETTINGS)
    }

    /**
     * Returns a `verifier to challenge` pair for the S256 challenge method.
     */
    fun generateS256Codes(): Pair<String, String> {
        val verifier = generateCodeVerifier()
        return verifier to generateCodeChallenge(verifier)
    }
}
