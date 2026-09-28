package com.moeum.conversation.domain

import com.moeum.conversation.domain.model.ConversationDayId
import com.moeum.conversation.domain.model.MomentSet

interface MomentSetRepository {
    fun save(momentSet: MomentSet): MomentSet

    // Moment 추출(지연 호출)이 이미 이 revision으로 추출한 적 있는지 확인할 때 쓴다.
    fun findByConversationDayIdAndSourceRevision(conversationDayId: ConversationDayId, sourceRevision: Long): MomentSet?
}
