package com.example.domain.model.ai.assistant

class AiAssistantRemoteException(
    val errorCode: String,
    val retryable: Boolean,
    val stage: String? = null,
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
