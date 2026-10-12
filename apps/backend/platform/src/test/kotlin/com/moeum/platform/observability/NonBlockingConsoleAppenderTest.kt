package com.moeum.platform.observability

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.LoggingEvent
import ch.qos.logback.core.AppenderBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class NonBlockingConsoleAppenderTest {
    @Test fun `콘솔이 정체돼도 ERROR 로그까지 대기 없이 처리하고 포화 유실을 정확히 센다`() {
        val context = LoggerContext().apply { mdcAdapter = ch.qos.logback.classic.util.LogbackMDCAdapter() }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val console = object : AppenderBase<ILoggingEvent>() {
            override fun append(eventObject: ILoggingEvent) {
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        }.apply { this.context = context; start() }
        val async = NonBlockingConsoleAppender().apply {
            this.context = context
            queueSize = 1
            maxFlushTime = 100
            addAppender(console)
            start()
        }
        fun event() = LoggingEvent("test", context.getLogger("test"), Level.ERROR, "safe error", null, emptyArray())
        try {
            async.doAppend(event())
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue()
            Executors.newSingleThreadExecutor().use { pool ->
                pool.submit { async.doAppend(event()); async.doAppend(event()) }.get(1, TimeUnit.SECONDS)
            }
            assertThat(async.dropped.sum()).isEqualTo(1)
            assertThat(async.numberOfElementsInQueue).isEqualTo(1)
        } finally {
            release.countDown()
            async.stop()
            console.stop()
            context.stop()
        }
    }
}
