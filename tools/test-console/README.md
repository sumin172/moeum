# 테스트 콘솔

로컬 백엔드에 Google 로그인하고 대화를 주고받는 개발용 페이지다. 운영 배포 대상이 아니다.

`local` 프로필의 백엔드가 이 디렉터리의 파일을 `/test-console/**` 경로로 직접 제공한다(`application-local.yml`의 `spring.web.resources`).
jar에는 포함되지 않으며, 다른 프로필에는 해당 리소스가 없다. 페이지와 API가 같은 origin이므로 CORS 설정이 필요 없다.

## 실행

1. `local` 프로필과 API 역할을 포함해 백엔드를 실행한다(예: `local,local-all`, 포트 8081). 실행 방법은 [실행 역할](../../docs/WORKER_ROLES.md#실행)을 따른다.
2. 브라우저에서 `http://localhost:8081/test-console/index.html`을 연다.

백엔드 실행 위치가 저장소 루트(IntelliJ)이거나 `apps/backend/bootstrap`(Gradle `bootRun`)일 때 파일을 찾는다.
다른 위치에서 실행하면 페이지는 404이고 API는 영향받지 않는다.

## Google 로그인

Google Cloud Console에서 사용할 OAuth Client ID의 승인된 JavaScript 원본에 `http://localhost:8081`이 있어야 한다.
`127.0.0.1`은 다른 origin이므로 `localhost`로 접속한다.
페이지의 Client ID는 `application-local.yml`의 `moeum.google.client-ids` 기본값과 같으며, 바꾸려면 `index.html`의 `GOOGLE_CLIENT_ID`를 수정한다.

## 동작

- 로그인 후 가장 최근의 마감 전 기록을 불러온다.
- 메시지 전송 후 응답 상태가 COMPLETED·FAILED가 될 때까지 기록을 1.5초 간격으로 다시 조회한다.
- access token이 만료되면 refresh token으로 한 번 갱신한다.
- 토큰과 device ID는 브라우저 localStorage에 저장한다.
