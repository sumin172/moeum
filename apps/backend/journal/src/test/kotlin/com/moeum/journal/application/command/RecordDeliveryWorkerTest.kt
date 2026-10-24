package com.moeum.journal.application.command

import com.moeum.conversation.application.publicapi.JournalInput
import com.moeum.conversation.application.publicapi.RecordDayOperations
import com.moeum.conversation.application.publicapi.RecordDelivery
import com.moeum.kernel.RecordDayId
import com.moeum.kernel.UserId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.time.Instant

class RecordDeliveryWorkerTest {
    @Test
    fun `한 번의 실행에서 첫 100건 뒤에 남은 전달 작업도 처리한다`() {
        val user = UserId.generate()
        val pending = ArrayDeque((1..101).map { RecordDelivery(RecordDayId.generate(), user, Instant.now(), 1) })
        var acknowledged = 0
        val records = object : RecordDayOperations {
            override fun closeNext() = false
            override fun claimDelivery(): RecordDelivery? = pending.removeFirstOrNull()
            override fun acknowledge(delivery: RecordDelivery): Boolean {
                acknowledged++
                return true
            }
            override fun readJournalInput(userId: UserId, recordDayId: RecordDayId): JournalInput = error("unused")
        }
        val registration = mock(RegisterRecordGenerationService::class.java)
        val deliveries = pending.toList()
        deliveries.forEach { `when`(registration.register(it)).thenReturn(true) }

        RecordDeliveryWorker(records, registration).runBatch()

        assertThat(pending).isEmpty()
        assertThat(acknowledged).isEqualTo(101)
    }
}
