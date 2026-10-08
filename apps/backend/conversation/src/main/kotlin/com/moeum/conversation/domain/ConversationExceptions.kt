package com.moeum.conversation.domain

class MessageNotFoundException(message: String) : RuntimeException(message)

class ResponseAlreadyCompletedException(message: String) : RuntimeException(message)

class AiQuotaExceededException(message: String) : RuntimeException(message)
