package com.moeum.platform.llm.conversation

interface ConversationResponder {
    fun respond(request: ConversationRequest): ConversationResponse
}

class ConversationResponseException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
