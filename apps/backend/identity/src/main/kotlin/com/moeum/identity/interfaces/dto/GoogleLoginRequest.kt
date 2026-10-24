package com.moeum.identity.interfaces.dto

import java.util.UUID

data class GoogleLoginRequest(
    val idToken: String,
    // 앱 설치 단위로 클라이언트가 만들어 보관하는 식별자. 같은 기기에서 다시 로그인하면 이전 세션은 폐기된다.
    val deviceId: UUID,
)
