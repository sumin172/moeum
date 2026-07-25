package com.moeum.identity.domain

import com.moeum.identity.domain.model.GoogleProfile

interface GoogleIdTokenVerifierPort {
    fun verify(idToken: String): GoogleProfile
}

class InvalidGoogleTokenException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
