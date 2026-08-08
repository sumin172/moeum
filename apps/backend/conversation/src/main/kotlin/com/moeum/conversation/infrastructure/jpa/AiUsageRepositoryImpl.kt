package com.moeum.conversation.infrastructure.jpa

import com.moeum.conversation.domain.AiUsageRepository
import com.moeum.kernel.UserId
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

// Postgres의 INSERT ... ON CONFLICT ... RETURNING으로 일별 사용 횟수를 원자적으로 증가시키고
// 증가된 값을 즉시 반환한다. Spring Data @Query는 @Modifying 없이는 "not a SELECT" 검증에 걸려
// 기동 실패하고, @Modifying을 붙이면 executeUpdate() 경로라 RETURNING 값을 못 받아 EntityManager를 직접 쓴다.
@Component
class AiUsageRepositoryImpl(
    private val entityManager: EntityManager,
) : AiUsageRepository {

    @Transactional
    override fun recordAttempt(userId: UserId, date: LocalDate): Int {
        val result = entityManager.createNativeQuery(
            """
            INSERT INTO conversation.ai_usage_daily (user_id, usage_date, message_count, input_tokens, output_tokens)
            VALUES (:userId, :usageDate, 1, 0, 0)
            ON CONFLICT (user_id, usage_date)
            DO UPDATE SET message_count = ai_usage_daily.message_count + 1
            RETURNING message_count
            """.trimIndent(),
        )
            .setParameter("userId", userId.value)
            .setParameter("usageDate", date)
            .singleResult

        return (result as Number).toInt()
    }
}
