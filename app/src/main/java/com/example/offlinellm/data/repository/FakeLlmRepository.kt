package com.example.offlinellm.data.repository

import com.example.offlinellm.domain.repository.LlmRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.random.Random

class FakeLlmRepository : LlmRepository {

    private val responses = listOf(
        "Я пока в тестовом режиме, но скоро буду настоящим оффлайн-ассистентом.",
        "Интересный вопрос. Записал в контекст, подумаю над ним локально.",
        "Оффлайн-режим активен. Никакие данные в облако не уходят.",
        "Можешь сменить цвет темы в настройках — это тоже часть прототипа.",
        "Пока я не загружен реальной моделью, я отвечаю заготовками, но архитектура готова."
    )

    override suspend fun generateResponse(
        prompt: String,
        systemPrompt: String?
    ): Flow<String> = flow {
        val response = responses.random()
        val words = response.split(" ")
        var emitted = ""
        words.forEach { word ->
            delay(Random.nextLong(40, 120))
            emitted += if (emitted.isEmpty()) word else " $word"
            emit(emitted)
        }
    }

    override suspend fun loadSystemPrompt(): String {
        return "Ты — локальный оффлайн-ассистент. Отвечай кратко и по делу."
    }
}
