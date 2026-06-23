package com.example.offlinellm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                ChatScreen()
            }
        }
    }
}

@Composable
fun ChatScreen() {
    var text by remember { mutableStateOf("") }
    var messages by remember { mutableStateOf(listOf("Привет! Я имитация локальной LLM. Нажми 'Скачать', чтобы проверить прогресс, или напиши мне что-нибудь.")) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(messages) { msg ->
                Text(text = msg, modifier = Modifier.padding(vertical = 4.dp))
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextField(value = text, onValueChange = { text = it }, modifier = Modifier.weight(1f))
            Button(onClick = {
                if (text.isNotBlank()) {
                    messages = messages + "Ты: $text" + "Бот: [Имитация ответа на: $text]"
                    text = ""
                }
            }) { Text("Отправить") }
        }
        Button(onClick = { messages = messages + "Система: Начинаю имитацию загрузки модели с Hugging Face... [||||||----] 60%" }, modifier = Modifier.fillMaxWidth()) {
            Text("Скачать модель (Имитация)")
        }
    }
}
