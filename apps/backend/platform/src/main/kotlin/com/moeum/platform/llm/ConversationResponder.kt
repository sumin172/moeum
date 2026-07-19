package com.moeum.platform.llm

interface ConversationResponder {
    fun respond(request: ConversationRequest): ConversationResponse
}
