# 인증·구독 계약

현재 서버의 인증 세션과 구독 권한 계약이다. 인증 API는 API 역할에서만 등록된다.

## 로그인과 세션

Google ID 토큰을 검증한 뒤 내부 사용자와 기기별 인증 세션을 발급한다.
deviceId는 앱 설치 단위로 클라이언트가 만들어 보관하는 UUID다.
같은 기기에서 다시 로그인하면 해당 기기의 기존 세션을 교체한다. 기기별 활성 세션은 유일 인덱스로 하나만 유지한다.

| API                    | 요청              | 응답·동작                                                                                         |
|------------------------|-------------------|---------------------------------------------------------------------------------------------------|
| POST /api/auth/google  | idToken, deviceId | accessToken, accessTokenExpiresAt, refreshToken, refreshTokenExpiresAt                            |
| POST /api/auth/refresh | refreshToken      | 새 토큰 쌍. 진위 검증 실패 401 INVALID_REFRESH_TOKEN, 유예 내 중복 갱신 409 REFRESH_TOKEN_ROTATED |
| POST /api/auth/logout  | refreshToken      | 204                                                                                               |

Google 토큰 검증 실패는 401 INVALID_GOOGLE_TOKEN이다.
웹은 BFF가 refreshToken을 httpOnly 쿠키로 감싸 브라우저에 노출하지 않는 구성을 전제로 한다.

## 토큰

access token은 서버가 상태 없이 검증하는 JWT이며 기본 수명은 15분이다.
이미 발급된 access token은 세션 폐기 후에도 만료까지 유효하다.
refresh token은 로그인·갱신 시점부터 30일이며 갱신할 때마다 회전한다.

refresh token은 세션 ID와 무작위 비밀값을 HMAC-SHA256으로 보호한다. 진위 검증에 실패한 토큰은 세션을 변경하지 않고 거부한다.
직전 토큰이 30초 안에 다시 들어오면 동시 요청으로 보고 충돌로 응답하며 세션을 유지한다.
유예 조건 밖에서 서버가 발급한 과거 토큰이 재사용되면 탈취로 보고 해당 세션을 폐기한다.
로그아웃은 현재 토큰 또는 유예 시간 내 직전 토큰이 확인된 경우 세션을 폐기한다. 그 밖의 입력도 204를 반환하지만 세션은 변경하지 않는다.

## 키와 설정

| 설정                                 | 기본값 | 의미                                 |
|--------------------------------------|--------|--------------------------------------|
| moeum.jwt.secret                     | 없음   | access token 서명 키                 |
| moeum.jwt.expiration-seconds         | 900    | access token 수명                    |
| moeum.auth.session.signing-secret    | 없음   | refresh token 전용 HMAC 키           |
| moeum.auth.session.refresh-token-ttl | 30일   | refresh token 수명                   |
| moeum.auth.session.rotation-grace    | 30초   | 직전 토큰의 동시 갱신 유예           |
| moeum.google.client-ids              | 없음   | 허용하는 Google OAuth Client ID 목록 |

refresh 키는 `MOEUM_AUTH_SESSION_SIGNINGSECRET`으로 주입한다.
운영에서는 무작위로 생성한 최소 32바이트 키를 사용하고 access token 키와 분리한다.
모든 API 인스턴스와 재시작 전후에 같은 키를 유지한다.
키를 교체하면 기존 refresh token은 모두 진위 검증에 실패하므로 재로그인이 필요하다. 이전 키를 함께 허용하는 키 순환은 지원하지 않는다.
사용자 대상 로그인 UX는 미확정이다.

## 구독과 기능 권한

구독 상태는 JWT에 넣지 않는다. 요청 시 현재 유효한 구독 엔티티를 DB에서 조회해 권한과 한도를 계산한다.
Conversation과 Journal은 Identity publicapi의 ConversationAllowanceQuery와 FeatureAccessQuery로 한도와 권한을 조회한다.

| 요금제  | 기능                     | 기록당 대화 한도 |
|---------|--------------------------|-----------------:|
| FREE    | 대화·자동 일기·실패 복구 |               20 |
| PREMIUM | 위 기능 + JOURNAL_EDIT   |              100 |

요금제별 기능은 moeum.features.plans, 대화 한도는 moeum.identity.conversation 설정으로 바꾼다.
결제·구독 구매·해지 API는 미구현이며, 서버는 저장된 유효 구독 기간과 요금제로 권한을 집행한다.

## 검증

AuthSessionIntegrationTest는 회전 후 갱신된 토큰 사용, 동시 갱신 충돌, 과거 토큰 재사용 폐기, 변조 토큰 거부, 로그아웃을 검사한다.
JwtProviderTest는 access token 발급과 검증을 검사한다.
