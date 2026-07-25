package com.moeum.identity.infrastructure.google

import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.moeum.identity.domain.GoogleIdTokenVerifierPort
import com.moeum.identity.domain.InvalidGoogleTokenException
import com.moeum.identity.domain.model.GoogleProfile
import com.moeum.identity.infrastructure.config.GoogleClientProperties
import org.springframework.stereotype.Component

@Component
class GoogleIdTokenVerifierAdapter(
    googleClientProperties: GoogleClientProperties,
) : GoogleIdTokenVerifierPort {

    private val verifier = GoogleIdTokenVerifier.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance())
        .setAudience(googleClientProperties.clientIds)
        .build()

    override fun verify(idToken: String): GoogleProfile {
        val googleIdToken = runCatching { verifier.verify(idToken) }.getOrNull()
            ?: throw InvalidGoogleTokenException("유효하지 않은 Google ID 토큰입니다")

        val payload = googleIdToken.payload
        return GoogleProfile(googleId = payload.subject, email = payload.email)
    }
}
