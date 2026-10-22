package com.moeum.conversation.domain.model
import com.moeum.kernel.UserId
import com.moeum.conversation.domain.InvalidConversationRequestException
import com.moeum.conversation.infrastructure.config.RecordDayProperties
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.*
class RecordDayTest {
    private val user = UserId.generate()
    private val seoul = ZoneId.of("Asia/Seoul")
    private val bounds = RecordDayProperties()
    private fun create(at: String, time: LocalTime = LocalTime.of(2,0)) =
        RecordDay.create(user, Instant.parse(at), seoul, time, bounds.eveningStart, bounds.morningEnd)
    @Test fun `마감 직전과 정각은 서로 다른 기록이다`() {
        val before = create("2026-08-01T16:59:59Z")
        val boundary = Instant.parse("2026-08-01T17:00:00Z")
        assertThat(before.closeAt).isEqualTo(boundary)
        assertThat(create(boundary.toString()).displayDate).isEqualTo(LocalDate.of(2026,8,2))
        assertThat(before.closeIfDue(boundary.plusSeconds(60)).closedAt).isEqualTo(boundary)
    }
    @Test fun `23시 마감 약속은 방콕 이동에도 같은 달력 날짜를 유지한다`() {
        val first = create("2026-08-01T12:00:00Z",LocalTime.of(23,0))
        assertThat(first.closeAt).isEqualTo(Instant.parse("2026-08-01T14:00:00Z"))
        val at = Instant.parse("2026-08-01T12:05:00Z")
        val moved = first.prepareForMessage(ZoneId.of("Asia/Bangkok"),at).append()
        assertThat(moved.closeAt).isEqualTo(Instant.parse("2026-08-01T16:00:00Z"))
        assertThat(moved.closeDate).isEqualTo(first.closeDate)
        assertThat(moved.displayDate).isEqualTo(first.displayDate)
    }
    @Test fun `날짜변경선 이동 후 같은 표시 날짜로 다른 기록을 만들 수 있다`() {
        val first = create("2026-08-01T12:00:00Z",LocalTime.of(23,0))
        val closed = first.closeIfDue(first.closeAt)
        val next = RecordDay.create(user,first.closeAt,ZoneId.of("Pacific/Honolulu"),LocalTime.of(23,0),bounds.eveningStart,bounds.morningEnd,closed)
        assertThat(next.id).isNotEqualTo(first.id)
        assertThat(next.displayDate).isEqualTo(first.displayDate)
        assertThat(next.startAt).isEqualTo(closed.closedAt)
        assertThat(next.startAt).isEqualTo(first.closeAt)
    }
    @Test fun `새벽 마감은 전날 일기이고 저녁 마감은 당일 일기다`() {
        assertThat(create("2026-08-01T15:00:00Z").displayDate).isEqualTo(LocalDate.of(2026,8,1))
        assertThat(create("2026-08-01T15:00:00Z", LocalTime.of(18,0)).displayDate).isEqualTo(LocalDate.of(2026,8,2))
        assertThat(create("2026-08-01T18:59:59Z", LocalTime.of(4,0)).displayDate).isEqualTo(LocalDate.of(2026,8,1))
        assertThat(create("2026-08-01T19:00:00Z", LocalTime.of(4,0)).displayDate).isEqualTo(LocalDate.of(2026,8,2))
    }
    @Test fun `설정 가능한 마감은 18시부터 자정을 지나 4시까지다`() {
        listOf("18:00", "23:59:59", "00:00", "04:00").forEach {
            assertThatCode { create("2026-08-01T12:00:00Z",LocalTime.parse(it)) }.doesNotThrowAnyException()
        }
        listOf("04:00:00.000000001", "12:00", "17:59:59.999999999").forEach {
            assertThatThrownBy { create("2026-08-01T12:00:00Z",LocalTime.parse(it)) }
                .isInstanceOf(InvalidConversationRequestException::class.java)
        }
    }
    @Test fun `마감을 2시에서 4시로 늦추면 새 기록은 다음날 4시에 닫힌다`() {
        val first = create("2026-08-01T12:00:00Z").closeIfDue(Instant.parse("2026-08-01T17:00:00Z"))
        val next = RecordDay.create(user, first.closedAt!!, seoul, LocalTime.of(4,0),
            bounds.eveningStart, bounds.morningEnd, first)
        assertThat(next.displayDate).isEqualTo(LocalDate.of(2026,8,2))
        assertThat(next.startAt).isEqualTo(first.closedAt)
        assertThat(next.closeAt).isEqualTo(Instant.parse("2026-08-02T19:00:00Z"))
    }
    @Test fun `설정된 시간 범위를 생성과 검증에 동일하게 사용한다`() {
        val custom = RecordDayProperties(LocalTime.of(19,0),LocalTime.of(3,0))
        assertThatCode { RecordDay.create(user,Instant.parse("2026-08-01T12:00:00Z"),seoul,LocalTime.of(3,0),custom.eveningStart,custom.morningEnd) }.doesNotThrowAnyException()
        assertThatThrownBy { RecordDay.validateCloseTime(LocalTime.of(4,0),custom.eveningStart,custom.morningEnd) }
            .isInstanceOf(InvalidConversationRequestException::class.java)
    }
    @Test fun `DST gap은 첫 유효 시각 overlap은 뒤 offset이다`() {
        val ny = ZoneId.of("America/New_York")
        assertThat(RecordDay.resolveClose(LocalDate.of(2026,3,8), LocalTime.of(2,30),ny)).isEqualTo(Instant.parse("2026-03-08T07:00:00Z"))
        assertThat(RecordDay.resolveClose(LocalDate.of(2026,11,1), LocalTime.of(1,30),ny)).isEqualTo(Instant.parse("2026-11-01T06:30:00Z"))
    }
    @Test fun `늦게 전송된 발화도 접수 순서로 마지막 위치를 갱신한다`() {
        val at = Instant.parse("2026-08-01T12:00:00Z")
        val record = create(at.toString()).append()
        val next = record.prepareForMessage(ZoneId.of("UTC"),at.plusSeconds(1)).append()
        assertThat(next.timezone).isEqualTo("UTC")
        assertThat(next.sourceRevision).isEqualTo(2)
    }
    @Test fun `동시 발화 시각은 저장 원본 버전 순으로 위치를 갱신한다`() {
        val at = Instant.parse("2026-08-01T12:00:00Z")
        val next = create(at.toString()).prepareForMessage(ZoneId.of("UTC"),at).append()
        assertThat(next.timezone).isEqualTo("UTC")
        assertThat(next.closeDate).isEqualTo(LocalDate.of(2026,8,2))
        assertThat(next.displayDate).isEqualTo(LocalDate.of(2026,8,1))
    }
    @Test fun `위치 이동 후 이미 지난 마감은 새 원본 추가 전에 확정한다`() {
        val at = Instant.parse("2026-08-01T16:00:00Z")
        val first = create(at.toString()).append()
        val next = first.prepareForMessage(ZoneId.of("Pacific/Auckland"),at)
        assertThat(next.closedAt).isEqualTo(at)
        assertThat(next.closeAt).isEqualTo(at)
        assertThat(next.sourceRevision).isEqualTo(first.sourceRevision)
        assertThat(next.timezone).isEqualTo(first.timezone)
    }
    @Test fun `서쪽 이동은 같은 현지 마감 날짜와 시각으로 예약한다`() {
        val at = Instant.parse("2026-08-01T12:00:00Z")
        val next = create(at.toString()).prepareForMessage(ZoneId.of("Pacific/Honolulu"),at)
        assertThat(next.closeAt).isEqualTo(Instant.parse("2026-08-02T12:00:00Z"))
        assertThat(next.closedAt).isNull()
    }
    @Test fun `기존 예약이 지난 기록은 서쪽 이동으로 다시 연장하지 않는다`() {
        val first = create("2026-08-01T12:00:00Z")
        val next = first.prepareForMessage(ZoneId.of("Pacific/Honolulu"),first.closeAt)
        assertThat(next.closedAt).isEqualTo(first.closeAt)
        assertThat(next.timezone).isEqualTo(first.timezone)
    }
    @Test fun `닫힌 기록에는 사용자 메시지를 추가하지 않는다`() {
        val day = create("2026-08-01T12:00:00Z").copy(sourceRevision = 3)
        val closed = day.closeIfDue(day.closeAt)
        assertThatThrownBy { closed.append() }.isInstanceOf(IllegalStateException::class.java)
    }
}
