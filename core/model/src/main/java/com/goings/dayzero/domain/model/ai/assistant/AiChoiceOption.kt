package com.goings.dayzero.domain.model.ai.assistant

data class AiChoiceOption(
    val id: String,
    val label: String,
    val action: AiChoiceAction
)
