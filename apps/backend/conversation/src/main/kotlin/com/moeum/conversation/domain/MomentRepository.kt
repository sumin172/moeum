package com.moeum.conversation.domain

import com.moeum.conversation.domain.model.Moment
import com.moeum.conversation.domain.model.MomentSetId

interface MomentRepository {
    fun saveAll(moments: List<Moment>): List<Moment>

    fun findAllByMomentSetId(momentSetId: MomentSetId): List<Moment>
}
