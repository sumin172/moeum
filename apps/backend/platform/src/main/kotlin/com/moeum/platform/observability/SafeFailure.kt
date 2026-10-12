package com.moeum.platform.observability

import com.moeum.platform.llm.LlmException
import org.springframework.web.client.RestClientResponseException
import java.util.Collections
import java.util.IdentityHashMap

/** 메시지·응답 본문·URL을 사용하지 않고 제한된 기술 분류만 반환한다. */
object SafeFailure {
    fun code(error: Throwable): String {
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        var current: Throwable? = error
        var root = error
        while (current != null && seen.add(current)) {
            if (current is LlmException && current.errorCode != null) return current.errorCode
            if (current is java.sql.SQLException) {
                val state = current.sqlState
                if (state != null && state.matches(Regex("[A-Z0-9]{5}"))) return "SQL_" + state
            }
            if (current is RestClientResponseException) return "HTTP_" + current.statusCode.value()
            root = current
            current = current.cause
        }
        return when (root) {
            is java.net.SocketTimeoutException, is java.util.concurrent.TimeoutException -> "TIMEOUT"
            is java.io.IOException -> "IO_ERROR"
            else -> root.javaClass.simpleName.take(80).ifBlank { "UNKNOWN" }
        }
    }

    /** 스택 위치는 남기되 예외 메시지나 cause 객체는 로거에 전달하지 않는다. */
    fun location(error: Throwable): String = error.stackTrace.firstOrNull()?.let {
        "${it.className}.${it.methodName}:${it.lineNumber}"
    } ?: "UNKNOWN"
}
