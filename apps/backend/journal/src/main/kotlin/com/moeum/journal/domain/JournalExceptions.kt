package com.moeum.journal.domain

class JournalNotFoundException(message: String) : RuntimeException(message)

class InvalidJournalRequestException(message: String) : RuntimeException(message)

// 다른 기기에서 먼저 수정·확정했다 — 최신 일기를 다시 받아서 시도해야 한다
class JournalVersionConflictException(message: String) : RuntimeException(message)

// 요금제에 일기 수정 기능이 없다
class JournalEditNotAllowedException(message: String) : RuntimeException(message)

// 이미 생성됐거나 아직 진행 중이라 다시 생성을 요청할 수 없다
class JournalGenerationNotRetryableException(message: String) : RuntimeException(message)
