package com.moeum.journal.infrastructure.jpa

import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper

// journals.content / journal_revisions.content(JSONB) ↔ 도메인의 일기 본문.
// 현재는 {"body"} 구조다. 재설계 R4에서 TEXT로 전환하면서 이 변환기를 제거한다.
@Component
class JournalContentCodec(private val jsonMapper: JsonMapper) {

    fun toJson(body: String): String = jsonMapper.writeValueAsString(JournalContent(body))

    fun bodyOf(json: String): String = jsonMapper.readValue(json, JournalContent::class.java).body

    private data class JournalContent(val body: String)
}
