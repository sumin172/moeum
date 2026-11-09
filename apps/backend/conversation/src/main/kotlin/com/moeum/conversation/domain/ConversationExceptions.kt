package com.moeum.conversation.domain

class AiQuotaExceededException(val quota: ConversationQuota) : RuntimeException("대화 한도를 초과했습니다")
class ConversationRateExceededException(message: String) : RuntimeException(message)
