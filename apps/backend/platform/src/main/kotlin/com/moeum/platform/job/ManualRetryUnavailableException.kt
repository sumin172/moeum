package com.moeum.platform.job

import java.time.Instant

/** null이면 누적 시도를 소진한 상태, 값이 있으면 그 시각까지 대기해야 한다. */
class ManualRetryUnavailableException(val nextAllowedAt: Instant?) : RuntimeException()
