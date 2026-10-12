package com.moeum.platform.observability

import ch.qos.logback.classic.AsyncAppender
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import io.micrometer.core.instrument.FunctionCounter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.MeterBinder
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.LongAdder

/** 콘솔 정체가 업무 로그를 통해 워커로 역전파되지 않도록 한다. */
class NonBlockingConsoleAppender : AsyncAppender() {
    val dropped = LongAdder()

    override fun start() {
        isNeverBlock = true
        discardingThreshold = 0
        super.start()
    }

    @Synchronized
    override fun append(eventObject: ILoggingEvent) {
        // 생산자만 직렬화한다. 소비자는 큐를 비우기만 하므로 검사와 offer 사이 포화로 인한 미집계 유실이 없다.
        if (remainingCapacity == 0) dropped.increment()
        else super.append(eventObject)
    }
}

@Component
class ConsoleBufferMetrics : MeterBinder {
    override fun bindTo(registry: MeterRegistry) {
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as? Logger ?: return
        // 로깅 재구성으로 appender가 교체돼도 약한 참조가 사라진 NaN이나 이전 큐를 읽지 않는다.
        FunctionCounter.builder("moeum.logging.dropped", root) {
            (it.getAppender("ASYNC_CONSOLE") as? NonBlockingConsoleAppender)?.dropped?.sum()?.toDouble() ?: 0.0
        }.register(registry)
        Gauge.builder("moeum.logging.buffer.size", root) {
            (it.getAppender("ASYNC_CONSOLE") as? NonBlockingConsoleAppender)?.numberOfElementsInQueue?.toDouble() ?: 0.0
        }.strongReference(true).register(registry)
    }
}
