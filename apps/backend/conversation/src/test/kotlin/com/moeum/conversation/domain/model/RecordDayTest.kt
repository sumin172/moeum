package com.moeum.conversation.domain.model
import com.moeum.kernel.UserId
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.*
class RecordDayTest {
    private val user = UserId.generate()
    private val seoul = ZoneId.of("Asia/Seoul")
    private fun create(at: String, time: LocalTime = LocalTime.of(2,0)) =
        RecordDay.create(user, Instant.parse(at), seoul, time)
    @Test fun `마감 직전과 정각은 서로 다른 기록이다`() {
        val before = create("2026-08-01T16:59:59Z")
        val boundary = Instant.parse("2026-08-01T17:00:00Z")
        assertThat(before.contains(boundary)).isFalse()
        assertThat(create(boundary.toString()).displayDate).isEqualTo(LocalDate.of(2026,8,2))
        assertThat(before.closeIfDue(boundary.plusSeconds(60)).closedAt).isEqualTo(boundary)
    }
    @Test fun `23시 마감 약속은 방콕 이동에도 같은 달력 날짜를 유지한다`() {
        val first = create("2026-08-01T12:00:00Z",LocalTime.of(23,0))
        assertThat(first.closeAt).isEqualTo(Instant.parse("2026-08-01T14:00:00Z"))
        val at = Instant.parse("2026-08-01T12:05:00Z")
        val moved = first.append(at,ZoneId.of("Asia/Bangkok"),at)
        assertThat(moved.closeAt).isEqualTo(Instant.parse("2026-08-01T16:00:00Z"))
        assertThat(moved.closeDate).isEqualTo(first.closeDate)
        assertThat(moved.displayDate).isEqualTo(first.displayDate)
    }
    @Test fun `날짜변경선 이동 후 같은 표시 날짜로 다른 기록을 만들 수 있다`() {
        val first = create("2026-08-01T12:00:00Z",LocalTime.of(23,0))
        val closed = first.closeIfDue(first.closeAt)
        val next = RecordDay.create(user,first.closeAt,ZoneId.of("Pacific/Honolulu"),LocalTime.of(23,0),closed.closedAt)
        assertThat(next.id).isNotEqualTo(first.id)
        assertThat(next.displayDate).isEqualTo(first.displayDate)
        assertThat(next.startAt).isEqualTo(closed.closedAt)
        assertThat(next.contains(first.closeAt)).isTrue()
    }
    @Test fun `새벽 표시 날짜와 정오 이후 표시 날짜를 구분한다`() {
        assertThat(create("2026-08-01T15:00:00Z").displayDate).isEqualTo(LocalDate.of(2026,8,1))
        assertThat(create("2026-08-01T15:00:00Z", LocalTime.of(18,0)).displayDate).isEqualTo(LocalDate.of(2026,8,2))
    }
    @Test fun `DST gap은 첫 유효 시각 overlap은 뒤 offset이다`() {
        val ny = ZoneId.of("America/New_York")
        assertThat(RecordDay.resolveClose(LocalDate.of(2026,3,8), LocalTime.of(2,30),ny)).isEqualTo(Instant.parse("2026-03-08T07:00:00Z"))
        assertThat(RecordDay.resolveClose(LocalDate.of(2026,11,1), LocalTime.of(1,30),ny)).isEqualTo(Instant.parse("2026-11-01T06:30:00Z"))
    }
    @Test fun `늦은 오프라인 발화는 마지막 위치를 되돌리지 않는다`() {
        val record = create("2026-08-01T12:00:00Z").append(Instant.parse("2026-08-01T12:00:00Z"),seoul,Instant.parse("2026-08-01T12:00:00Z"))
        val next = record.append(Instant.parse("2026-08-01T11:00:00Z"),ZoneId.of("UTC"),Instant.parse("2026-08-01T12:00:00Z"))
        assertThat(next.timezone).isEqualTo(seoul.id)
        assertThat(next.sourceRevision).isEqualTo(2)
    }
    @Test fun `동시 발화 시각은 저장 원본 버전 순으로 위치를 갱신한다`() {
        val at = Instant.parse("2026-08-01T12:00:00Z")
        val next = create(at.toString()).append(at,ZoneId.of("UTC"),at)
        assertThat(next.timezone).isEqualTo("UTC")
        assertThat(next.closeDate).isEqualTo(LocalDate.of(2026,8,2))
        assertThat(next.displayDate).isEqualTo(LocalDate.of(2026,8,1))
    }
    @Test fun `위치 이동 후 이미 지난 예약은 현재 시각에 마감한다`() {
        val at = Instant.parse("2026-08-01T16:00:00Z")
        val next = create(at.toString()).append(at,ZoneId.of("Pacific/Auckland"),at)
        assertThat(next.closedAt).isEqualTo(at)
        assertThat(next.closeReason).isEqualTo(CloseReason.TIMEZONE_PAST)
    }
    @Test fun `서쪽 이동으로 연장해도 36시간을 넘지 않는다`() {
        val at = Instant.parse("2026-08-01T12:00:00Z")
        val next = create(at.toString()).append(at,ZoneId.of("Pacific/Honolulu"),at)
        assertThat(next.closeAt).isEqualTo(next.startAt.plus(Duration.ofHours(36)))
        assertThat(next.closeReason).isEqualTo(CloseReason.MAX_DURATION)
    }
    @Test fun `동결은 닫힌 기록에만 가능하며 재시도 버전은 같다`() {
        val day = create("2026-08-01T12:00:00Z").copy(sourceRevision = 3)
        assertThatThrownBy { day.freeze() }.isInstanceOf(IllegalStateException::class.java)
        val frozen = day.closeIfDue(day.closeAt).freeze()
        assertThat(frozen.freeze().frozenSourceRevision).isEqualTo(3)
        assertThatThrownBy { frozen.append(day.lastUserOccurredAt,seoul,day.closeAt) }.isInstanceOf(IllegalStateException::class.java)
    }
}
