package com.moeum.platform.llm.conversation

interface ConversationResponder {
    fun respond(request: ConversationRequest): ConversationResponse
}
