package com.moeum.identity.interfaces

import com.moeum.identity.application.command.GoogleLoginService
import com.moeum.identity.interfaces.dto.GoogleLoginRequest
import com.moeum.identity.interfaces.dto.GoogleLoginResponse
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
}
