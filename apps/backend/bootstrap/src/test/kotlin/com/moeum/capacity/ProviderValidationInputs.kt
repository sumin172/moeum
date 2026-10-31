package com.moeum.capacity

import com.moeum.conversation.application.publicapi.MessageSnapshot
import java.time.Instant

/** 합성 대화만 사용한다. 품질 판정은 기대 사실과 생성 결과를 함께 검토한다. */
internal object ProviderValidationInputs {
    val qualityCases = listOf("representative", "negation", "changed-plans")
    fun expectedFacts(case: String): List<String> = when (case) {
        "negation" -> listOf("발표가 아니라 면접을 봤다.", "합격 결과는 아직 모른다.", "동생과 김치찌개를 먹었고 긴장이 풀렸다.")
        "changed-plans" -> listOf("비 때문에 등산을 취소했다.", "지수와 도서관에 갔고 영화는 보지 않았다.", "분실한 지갑을 안내 데스크에서 찾았고 안도했다.")
        else -> expectedFacts
    }
    private val expectedFacts = listOf(
        "아침에 한강이 아니라 서울숲에서 산책했다.",
        "오후 발표를 마쳤지만 팀장에게 칭찬받은 것은 아니다.",
        "저녁에 친구 민지와 버섯 파스타를 먹고 안도감을 느꼈다.",
    )

    private val dialogue = listOf(
        "USER" to "아침에 한강에서 산책했어. 바람이 시원해서 머리가 맑아졌어.",
        "ASSISTANT" to "잠깐 걷는 시간이 도움이 됐구나. 산책 장소는 한강이었어?",
        "USER" to "아, 장소를 잘못 말했네. 한강이 아니라 서울숲이었어.",
        "ASSISTANT" to "서울숲이었구나. 오늘 오후 발표는 어땠어?",
        "USER" to "발표를 무사히 끝냈어. 준비할 때 많이 긴장했는데 끝나니 마음이 놓였어.",
        "ASSISTANT" to "고생했어. 팀장님에게 칭찬도 받았어?",
        "USER" to "아니, 칭찬받은 건 아니야. 실수 없이 발표를 끝낸 것만으로 만족했어.",
        "ASSISTANT" to "스스로 만족할 수 있는 마무리였네. 저녁에는 누구를 만났어?",
        "USER" to "민지랑 저녁을 먹었어. 나 혼자 고민하던 얘기를 나누니 안심이 됐어.",
        "ASSISTANT" to "민지와 이야기해서 한결 편안해졌구나. 뭘 먹었어?",
        "USER" to "버섯 파스타. 집에 돌아오는 길에는 오늘 하루가 꽤 괜찮았다고 느꼈어.",
    )

    fun create(case: String, freeLimit: Int, premiumLimit: Int, maxMessageLength: Int): List<MessageSnapshot> {
        val entries = when (case) {
            "representative" -> dialogue
            "negation" -> listOf(
                "USER" to "오늘 면접 보고 왔어. 정말 긴장했어.",
                "ASSISTANT" to "발표 잘 마쳤구나. 합격도 한 거야?",
                "USER" to "아니. 발표 아니고 면접이야. 결과는 아직 몰라.",
                "ASSISTANT" to "면접이었고 결과는 기다리는 중이구나. 끝나고 누구를 만났어?",
                "USER" to "동생. 김치찌개 먹으면서 이야기하니까 긴장이 풀렸어.")
            "changed-plans" -> listOf(
                "USER" to "오늘 지수랑 등산하고 영화 볼 계획이야.",
                "ASSISTANT" to "즐거운 계획이네. 실제로는 어떻게 보냈어?",
                "USER" to "비 와서 등산 취소했어. 도서관에서 같이 책 읽었어. 영화도 안 봤어.",
                "ASSISTANT" to "계획이 달라졌구나. 다른 기억나는 일이 있어?",
                "USER" to "지갑을 잃어버려 당황했는데 안내 데스크에서 찾았어. 너무 안심했어.")
            "free-max" -> maximum(freeLimit, maxMessageLength)
            "premium-max" -> maximum(premiumLimit, maxMessageLength)
            else -> error("지원하지 않는 합성 사례")
        }
        val start = Instant.parse("2026-10-23T00:00:00Z")
        return entries.mapIndexed { index, (role, content) -> MessageSnapshot(role, content, start.plusSeconds(index * 60L)) }
    }

    private fun maximum(count: Int, length: Int): List<Pair<String, String>> {
        require(count >= 6 && length >= 300)
        val notes = listOf(
            "출근길 골목에서 작은 빵집을 보고 다음에 들러볼까 생각했다. 사람들 발걸음이 분주했고 나는 천천히 걸었다.",
            "창가에 앉아 잠깐 쉬었다. 유리창에 햇빛이 닿고 있었고 따뜻한 차를 마시면서 마음을 가라앉혔다.",
            "책상 위 메모를 정리하고 해야 할 일을 순서대로 적었다. 모두 끝내려 서두르기보다 한 가지씩 하려고 했다.",
            "점심 무렵 동료와 날씨 이야기를 했다. 특별한 사건은 없었지만 평범한 대화 덕분에 긴장이 조금 풀렸다.",
            "잠시 창밖의 나무를 보았다. 잎이 바람에 움직이는 모습이 편안해서 아무 생각 없이 바라봤다.",
        )
        return (0 until count).flatMap { index ->
            val anchor = when (index) {
                0 -> "오늘 가장 기억할 세 가지 중 첫 번째는 아침 산책이다. 한강에서 걸었다고 생각하며 이야기를 시작했다. "
                1 -> "산책 장소를 정정한다. 한강이 아니라 서울숲에서 걸었다. 바람이 시원해 머리가 맑아졌다. "
                count / 2 -> "오늘 두 번째로 중요한 일은 오후 발표를 실수 없이 끝낸 것이다. 긴장이 풀렸고 만족스러웠다. 팀장에게 칭찬받았다고 말한 적은 없다. "
                count / 2 + 1 -> "팀장의 칭찬을 받았냐는 질문에는 아니라고 답한다. 발표를 마친 것 자체가 기뻤다. "
                count - 2 -> "오늘 세 번째로 중요한 일은 저녁에 친구 민지와 대화한 것이다. 혼자 고민하던 얘기를 하니 안도감이 들었다. "
                count - 1 -> "마지막으로 기억할 것은 민지와 함께 먹은 버섯 파스타와 돌아오는 길에 느낀 안도감이다. "
                else -> "오늘 틈틈이 떠올린 평범한 순간에 관한 ${index + 1}번째 메모다. "
            }
            val content = buildString {
                append(anchor)
                var paragraph = 0
                while (this.length < length) {
                    append(" ${index + 1}-${++paragraph}. ")
                    append(notes[(index + paragraph) % notes.size])
                }
            }.take(length)
            val response = when (index) {
                count / 2 -> "발표를 무사히 끝냈구나. 팀장님에게 칭찬도 받았어?"
                else -> "이야기해줘서 고마워. 그때 어떤 기분이었는지도 편하게 들려줘."
            }
            listOf("USER" to content, "ASSISTANT" to response)
        }.dropLast(1) // 생성 입력은 마지막 USER까지만 포함한다.
    }
}
