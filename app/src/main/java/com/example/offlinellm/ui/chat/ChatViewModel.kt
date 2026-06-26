package com.example.offlinellm.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.offlinellm.di.AppProvider
import com.example.offlinellm.domain.model.DownloadState
import com.example.offlinellm.domain.model.LlmModel
import com.example.offlinellm.domain.model.Message
import com.example.offlinellm.domain.repository.LlmRepository
import com.example.offlinellm.domain.repository.ModelRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch

class ChatViewModel(
    private val llmRepository: LlmRepository = AppProvider.llmRepository,
    private val modelRepository: ModelRepository = AppProvider.modelRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    init {
        loadModels()
    }

    private fun loadModels() {
        val models = modelRepository.getAvailableModels()
        _uiState.value = _uiState.value.copy(
            availableModels = models,
            selectedModel = models.firstOrNull { it.isDownloaded } ?: models.firstOrNull()
        )
    }

    fun sendMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        addMessage(Message(text = trimmed, sender = Message.Sender.USER))
        _uiState.value = _uiState.value.copy(inputText = "")

        generateResponse(trimmed)
    }

    private fun generateResponse(prompt: String) {
        viewModelScope.launch {
            var assistantMessage: Message? = null
            llmRepository.generateResponse(prompt)
                .onStart {
                    _uiState.value = _uiState.value.copy(isGenerating = true)
                }
                .catch { error ->
                    addMessage(
                        Message(
                            text = "Ошибка: ${error.localizedMessage ?: "Не удалось получить ответ"}",
                            sender = Message.Sender.SYSTEM
                        )
                    )
                }
                .onCompletion {
                    _uiState.value = _uiState.value.copy(isGenerating = false)
                }
                .collect { partialText ->
                    if (assistantMessage == null) {
                        assistantMessage = Message(text = partialText, sender = Message.Sender.LLM)
                        addMessage(assistantMessage!!)
                    } else {
                        assistantMessage = assistantMessage!!.copy(text = partialText)
                        updateLastMessage(assistantMessage!!)
                    }
                }
        }
    }

    fun updateInput(text: String) {
        _uiState.value = _uiState.value.copy(inputText = text)
    }

    fun toggleTheme() {
        _uiState.value = _uiState.value.copy(isDarkMode = !_uiState.value.isDarkMode)
    }

    fun setPrimaryColor(color: Color) {
        _uiState.value = _uiState.value.copy(primaryColor = color)
    }

    fun selectModel(model: LlmModel) {
        _uiState.value = _uiState.value.copy(selectedModel = model)
    }

    fun downloadSelectedModel() {
        val model = _uiState.value.selectedModel ?: return
        viewModelScope.launch {
            modelRepository.downloadModel(model.id)
                .onStart {
                    _uiState.value = _uiState.value.copy(downloadState = DownloadState.InProgress(0f))
                }
                .catch { error ->
                    _uiState.value = _uiState.value.copy(
                        downloadState = DownloadState.Failed(error.localizedMessage ?: "Ошибка загрузки")
                    )
                }
                .collect { progress ->
                    _uiState.value = _uiState.value.copy(
                        downloadState = if (progress >= 1f) DownloadState.Completed else DownloadState.InProgress(progress)
                    )
                }
            loadModels()
        }
    }

    private fun addMessage(message: Message) {
        _uiState.value = _uiState.value.copy(messages = _uiState.value.messages + message)
    }

    private fun updateLastMessage(message: Message) {
        val messages = _uiState.value.messages.toMutableList()
        if (messages.isNotEmpty()) {
            messages[messages.lastIndex] = message
            _uiState.value = _uiState.value.copy(messages = messages)
        }
    }

    data class ChatUiState(
        val messages: List<Message> = listOf(
            Message(
                text = "Привет! Я твой будущий оффлайн-помощник. Сейчас я в режиме имитации. Выбери модель в настройках и нажми 'Скачать', чтобы увидеть живой прогресс.",
                sender = Message.Sender.SYSTEM
            )
        ),
        val inputText: String = "",
        val isGenerating: Boolean = false,
        val isDarkMode: Boolean = true,
        val primaryColor: Color = Color(0xFF8E44AD),
        val availableModels: List<LlmModel> = emptyList(),
        val selectedModel: LlmModel? = null,
        val downloadState: DownloadState = DownloadState.Idle
    )

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return ChatViewModel() as T
            }
        }
    }
}
