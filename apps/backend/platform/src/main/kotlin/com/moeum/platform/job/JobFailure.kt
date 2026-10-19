package com.moeum.platform.job

import com.moeum.platform.llm.LlmException
import java.time.Duration

enum class JobFailureKind { TRANSIENT, RATE_LIMITED, INVALID_OUTPUT, PERMANENT }

data class JobFailure(val kind: JobFailureKind, val retryAfter: Duration? = null) {
    companion object {
        fun classify(error: Exception): JobFailure {
            if (error is LlmException) {
                val code = error.errorCode
                return when {
                    code == "HTTP_429" -> JobFailure(JobFailureKind.RATE_LIMITED, error.retryAfter)
                    code == "EMPTY_CONTENT" -> JobFailure(JobFailureKind.INVALID_OUTPUT)
                    code == null || code in setOf("TIMEOUT", "IO_ERROR", "BULKHEAD_FULL", "HTTP_408", "HTTP_409") ||
                        code.startsWith("HTTP_5") || code == "CallNotPermittedException" -> JobFailure(JobFailureKind.TRANSIENT)
                    else -> JobFailure(JobFailureKind.PERMANENT)
                }
            }
            return when (error) {
                is java.io.IOException, is java.util.concurrent.TimeoutException, is java.sql.SQLTransientException -> JobFailure(JobFailureKind.TRANSIENT)
                else -> JobFailure(JobFailureKind.PERMANENT)
            }
        }
    }
}
