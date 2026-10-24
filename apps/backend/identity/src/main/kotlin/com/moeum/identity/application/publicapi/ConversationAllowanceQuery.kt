package com.moeum.identity.application.publicapi

import com.moeum.kernel.UserId

/** 현재 권한만 반환한다. 구독 이력·영속 엔티티는 공개하지 않는다. */
fun interface ConversationAllowanceQuery {
    fun recordLimit(userId: UserId): Int
}
