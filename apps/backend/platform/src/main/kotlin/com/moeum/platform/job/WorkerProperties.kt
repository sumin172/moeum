package com.moeum.platform.job

import org.springframework.boot.context.properties.ConfigurationProperties

// 이 인스턴스가 비동기 작업(LLM 호출을 포함한 AI 작업)을 실행하는 워커 역할인지.
// false면 스케줄러(poller, Planner/Executor)도, 요청 직후 즉시 실행도 하지 않고 작업을 쌓기만 한다 —
// 같은 jar를 API 전용 인스턴스와 워커 인스턴스로 나눠 띄우기 위한 스위치.
@ConfigurationProperties(prefix = "moeum.worker")
data class WorkerProperties(
    val enabled: Boolean = true,
)
