package com.moeum.identity.interfaces

import com.moeum.identity.application.command.GoogleLoginService
import com.moeum.identity.domain.InvalidGoogleTokenException
import com.moeum.identity.interfaces.dto.GoogleLoginRequest
import com.moeum.identity.interfaces.dto.GoogleLoginResponse
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val googleLoginService: GoogleLoginService,
) {

    @PostMapping("/google")
    fun loginWithGoogle(@RequestBody request: GoogleLoginRequest): GoogleLoginResponse =
        GoogleLoginResponse(jwt = googleLoginService.login(request.idToken))

    @ExceptionHandler(InvalidGoogleTokenException::class)
    fun handleInvalidGoogleToken(e: InvalidGoogleTokenException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(mapOf("code" to "INVALID_GOOGLE_TOKEN", "message" to "유효하지 않은 Google 로그인입니다"))
}
