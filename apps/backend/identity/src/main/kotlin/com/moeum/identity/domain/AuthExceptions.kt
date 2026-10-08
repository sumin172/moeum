package com.moeum.identity.domain

// 다시 로그인해야 한다(형식 오류·만료·폐기·재사용 감지 모두 — 클라이언트 입장에서 할 일이 같다)
class InvalidRefreshTokenException(message: String) : RuntimeException(message)

// 같은 refresh token으로 거의 동시에 들어온 요청 중 늦은 쪽. 세션은 살아 있으니 먼저 받은 새 토큰을 쓰면 된다.
class RefreshTokenAlreadyRotatedException(message: String) : RuntimeException(message)

class UserDeletedException(message: String) : RuntimeException(message)
