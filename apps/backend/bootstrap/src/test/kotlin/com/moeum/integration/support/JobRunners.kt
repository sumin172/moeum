package com.moeum.integration.support

import com.moeum.journal.application.command.GenerationExecutor

// 스케줄러 없이 일기 생성 작업 하나를 선점해 실행한다.
fun GenerationExecutor.executeNext(): Boolean {
    val claimed = claim(1).singleOrNull() ?: return false
    execute(claimed)
    return true
}
