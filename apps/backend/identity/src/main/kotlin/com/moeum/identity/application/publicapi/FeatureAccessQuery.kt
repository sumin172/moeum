package com.moeum.identity.application.publicapi

import com.moeum.kernel.UserId

// 요금제에 따라 열리고 닫히는 기능. 업무 모듈은 구독 등급이 아니라 "이 기능을 쓸 수 있는가"만 묻는다 —
// 요금제 구성(체험판·프로모션 등)이 바뀌어도 업무 코드는 그대로다.
enum class Feature {
    // 생성된 일기 직접 수정
    JOURNAL_EDIT,
}

interface FeatureAccessQuery {
    // 지금 유효한 구독의 요금제 기준. 구독 상태는 JWT에 넣지 않고 매번 조회한다(PRINCIPLES #10).
    fun isEnabled(userId: UserId, feature: Feature): Boolean
}
