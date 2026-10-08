package com.moeum.journal.domain

import com.moeum.journal.domain.model.JournalRevision

interface JournalRevisionRepository {
    // 리비전은 변경 이력이라 추가만 한다(수정·삭제 없음)
    fun append(revision: JournalRevision): JournalRevision
}
