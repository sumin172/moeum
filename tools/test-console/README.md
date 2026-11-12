# 테스트 콘솔

로컬 백엔드에 Google 로그인하고 대화를 주고받는 개발용 페이지다. 운영 배포 대상이 아니다.

`server.py`가 `index.html`을 제공하고 `/api/*` 요청을 백엔드로 전달한다. 페이지와 API가 같은 origin이므로 백엔드에 CORS 설정이 필요 없다.
Python 3 표준 라이브러리만 사용한다.

## 실행

1. API 역할을 포함해 백엔드를 실행한다(예: `local,local-all`, 포트 8081). 실행 방법은 [실행 역할](../../docs/WORKER_ROLES.md#실행)을 따른다.
2. 콘솔 서버를 실행하고 브라우저에서 `http://localhost:3000`을 연다.

```shell
python3 tools/test-console/server.py
```

| 환경변수          | 기본값                  | 의미             |
|-------------------|-------------------------|------------------|
| MOEUM_BACKEND     | `http://localhost:8081` | 전달할 백엔드    |
| TEST_CONSOLE_PORT | `3000`                  | 콘솔 서버 포트   |

## Google 로그인

Google Cloud Console에서 사용할 OAuth Client ID의 승인된 JavaScript 원본에 `http://localhost:3000`이 있어야 한다.
`127.0.0.1`은 다른 origin이므로 `localhost`로 접속한다.
페이지의 Client ID는 `application-local.yml`의 `moeum.google.client-ids` 기본값과 같으며, 바꾸려면 `index.html`의 `GOOGLE_CLIENT_ID`를 수정한다.

## 동작

- 로그인 후 가장 최근의 마감 전 기록을 불러온다.
- 메시지 전송 후 응답 상태가 COMPLETED·FAILED가 될 때까지 기록을 1.5초 간격으로 다시 조회한다.
- access token이 만료되면 refresh token으로 한 번 갱신한다.
- 토큰과 device ID는 브라우저 localStorage에 저장한다.
