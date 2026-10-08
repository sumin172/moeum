package com.moeum.journal.domain

import com.moeum.journal.domain.model.JournalRevision

interface JournalRevisionRepository {
    fun save(revision: JournalRevision): JournalRevision
}
