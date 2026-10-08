package com.moeum.journal.infrastructure.jpa

import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper

// journals.content / journal_revisions.content(JSONB) ↔ 도메인의 일기 본문.
// 지금은 {"body"} 최소 구조 — 섹션·태그 등 구조 확장은 Stage 3에서 이 변환만 바꾼다.
@Component
class JournalContentCodec(private val jsonMapper: JsonMapper) {

    fun toJson(body: String): String = jsonMapper.writeValueAsString(JournalContent(body))

    fun bodyOf(json: String): String = jsonMapper.readValue(json, JournalContent::class.java).body

    private data class JournalContent(val body: String)
}
