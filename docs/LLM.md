# AI 공급자 계약

업무 모듈은 platform의 LlmClient 하나로 LLM을 호출한다.
platform은 공급자·모델·토큰 상한·장애 격리·호출 관측·출력의 정상 종료 판정을 소유한다.
프롬프트와 업무 응답 형식의 해석은 각 업무 모듈이 소유한다.

## 용도별 경로

요청의 purpose로 `moeum.llm.routes.<purpose>`의 공급자·모델·출력 상한을 고른다.
모델 교체나 공급자 전환은 이 설정만 바꾼다. 없는 공급자를 지정하면 기동 시 실패한다.

| purpose               | 공급자     | 모델                          | 출력 상한 | 프롬프트 |
|-----------------------|------------|-------------------------------|----------:|----------|
| conversation-response | cloudflare | @cf/qwen/qwen3-30b-a3b-fp8    |       150 | 대화 v3  |
| journal-generation    | gemini     | gemini-3.1-flash-lite         |     2,000 | 일기 v4  |

대화는 thinking-budget 0으로 Qwen에 `/no_think`를 요청한다. 추론 비활성화를 강제하지는 않는다.
일기는 JSON 응답을 요청한다. Gemini는 응답 형식 옵션으로 강제하고, Claude는 프롬프트 지시에 맡긴다.
Claude 공급자는 등록돼 있지만 기본 경로에서 사용하지 않으며 실제 호출은 검증하지 않았다.
한 공급자가 실패해도 다른 공급자로 자동 전환하지 않는다. 모델 선택 배경은 [공급자 분리](engineering/llm-provider-split.md)에 있다.
프롬프트를 바꾸면 소유 모듈이 프롬프트 버전을 올린다. 버전은 호출 관측에 남는다.

## 장애 격리

| 장치           | 범위                       | 기본값                                                        |
|----------------|----------------------------|---------------------------------------------------------------|
| 동시 호출 상한 | 프로세스·공급자별 세마포어 | 대화 워커 8, 일기 워커 2. 획득 대기 5초 초과 시 BULKHEAD_FULL |
| 서킷브레이커   | 프로세스·공급자별          | 최근 10회 중 최소 5회, 실패율 50%에서 열림. 30초 후 반개방    |

서킷은 IO·타임아웃·5xx·429와 응답 본문이 없는 공급자 오류를 실패로 센다.
출력 판정 실패는 서킷 밖에서 처리하므로 공급자 장애로 집계하지 않는다.
외부 예외의 응답 본문·URL·인증 정보는 업무 예외와 로그로 전달하지 않는다.
429 응답의 Retry-After는 초 또는 HTTP 날짜로 해석해 재시도 예약에 전달한다.

## 출력 판정

공급자 어댑터가 종료 사유를 해석하고, 라우터는 정상 완료된 출력만 업무 모듈에 반환한다.

| 공급자     | 정상 종료 | 잘림                                     | 차단                                                                                  |
|------------|-----------|------------------------------------------|---------------------------------------------------------------------------------------|
| Gemini     | STOP      | MAX_TOKENS                               | SAFETY·RECITATION·BLOCKLIST·PROHIBITED_CONTENT·SPII·IMAGE_SAFETY, promptFeedback 차단 |
| Claude     | end_turn  | max_tokens·model_context_window_exceeded | refusal                                                                               |
| Cloudflare | stop      | length                                   | content_filter                                                                        |

정상 종료여도 본문이 비어 있으면 EMPTY_CONTENT다. Cloudflare 본문에 추론 블록이 남으면 UNEXPECTED_FINISH_REASON이다.
Gemini의 thinking 토큰은 출력 토큰으로 함께 센다.

| errorCode                | 의미                                                      | 자동 재시도                    |
|--------------------------|-----------------------------------------------------------|--------------------------------|
| OUTPUT_TRUNCATED         | 출력 토큰 상한 또는 모델 문맥 한계로 중단                 | 중단                           |
| OUTPUT_BLOCKED           | 입력·출력 차단 또는 공급자의 명시적 거절                  | 중단                           |
| UNEXPECTED_FINISH_REASON | 종료 사유 누락 또는 현재 요청에서 지원하지 않는 종료 사유 | 중단                           |
| EMPTY_CONTENT            | 정상 종료했으나 본문이 비어 있음                          | 잘못된 출력 정책 적용          |

## 재시도 분류

작업 실패는 platform의 JobFailure로 분류하고 각 작업 정책이 재예약 여부를 정한다.

| 분류           | 대상                                                           | 처리                                       |
|----------------|----------------------------------------------------------------|--------------------------------------------|
| TRANSIENT      | 타임아웃·IO·5xx·408·409·BULKHEAD_FULL·서킷 개방·일시적 DB 오류 | backoff로 재예약                           |
| RATE_LIMITED   | 429                                                            | 최소 대기·Retry-After·backoff 중 늦은 시각 |
| INVALID_OUTPUT | EMPTY_CONTENT, 일기 결과 검사 실패                             | 두 번째 시도부터 재예약하지 않음           |
| PERMANENT      | 인증·입력 오류, 출력 잘림·차단, 그 밖의 오류                   | 자동 반복 중단                             |

작업별 시도 횟수·대기·수동 재시도는 [대화](CONVERSATION.md)와 [일기](JOURNAL.md) 계약을 따른다.
호출 관측 필드와 메트릭은 [관측·보존](OBSERVABILITY_RETENTION.md)을 따른다.

## 검증

GeminiProviderTest·ClaudeProviderTest·CloudflareProviderTest는 요청 매핑과 정상 종료·잘림·차단·종료 사유 누락·사용량 누락의 응답 해석을 검사한다.
CloudflareProviderTest는 인증·역할·출력 상한·소프트 추론 제어와 추론 블록도 검사한다.
RoutingLlmClientTest는 미완료 출력 거절·오류 분류·실패 관측의 실제 모델과 토큰 수 보존을 검사한다.
GeminiProviderTest는 출력 판정 실패가 공급자 서킷의 실패 횟수에 반영되지 않는지도 검사한다.
실제 공급자 검증 범위는 [검증 현황](CAPACITY_RESULTS.md)을 따른다.
