package com.example.offlinellm.di

import com.example.offlinellm.data.repository.FakeLlmRepository
import com.example.offlinellm.data.repository.FakeModelRepository
import com.example.offlinellm.domain.repository.LlmRepository
import com.example.offlinellm.domain.repository.ModelRepository

object AppProvider {
    val llmRepository: LlmRepository by lazy { FakeLlmRepository() }
    val modelRepository: ModelRepository by lazy { FakeModelRepository() }
}
