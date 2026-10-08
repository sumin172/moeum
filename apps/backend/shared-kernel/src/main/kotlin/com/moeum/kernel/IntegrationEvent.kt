package com.moeum.kernel

import java.time.Instant
import java.util.UUID

// 모듈 밖(다른 모듈, 향후 다른 서비스)에 공개하는 이벤트 계약의 공통 필드. 구체 이벤트는 생산자 모듈이 소유한다
// (예: journal/application/publicapi/events/JournalConfirmedV1). 이벤트는 이미 일어난 사실이고, 계약을 깨는 변경은 새 버전(V2)으로 낸다.
interface IntegrationEvent {
    // 이 이벤트 자신의 식별자 — 소비자 멱등 처리 기준(UUIDv7)
    val eventId: UUID
    val eventType: String
    val eventVersion: Int
    val occurredAt: Instant

    // 하나의 업무 흐름을 잇는 식별자. 기본값으로 새로 만들지 않고 흐름을 시작한 쪽(요청 처리, 배치 실행)이 넘긴다.
    val correlationId: UUID

    // 이 이벤트를 일으킨 직전 이벤트. 사용자 요청처럼 이벤트가 아닌 것에서 시작했으면 null.
    val causationId: UUID?
}
