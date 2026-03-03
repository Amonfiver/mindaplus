package com.mindaplus.android

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class TelegramNotifier {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    
    private var botToken: String = ""
    private var chatId: String = ""
    
    fun updateConfig(token: String, id: String) {
        botToken = token
        chatId = id
        Log.d("Mindaplus", "Telegram config updated")
    }
    
    suspend fun sendMessage(message: String): Boolean = withContext(Dispatchers.IO) {
        if (botToken.isBlank() || chatId.isBlank()) {
            Log.e("Mindaplus", "Telegram config not set")
            return@withContext false
        }
        
        try {
            val url = "https://api.telegram.org/bot$botToken/sendMessage"
            val json = JSONObject().apply {
                put("chat_id", chatId)
                put("text", message)
                put("parse_mode", "HTML")
            }
            
            val requestBody = json.toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())
            
            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()
            
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.d("Mindaplus", "Telegram message sent successfully: $message")
                    true
                } else {
                    Log.e("Mindaplus", "Failed to send Telegram message: ${response.code}")
                    false
                }
            }
        } catch (e: IOException) {
            Log.e("Mindaplus", "Error sending Telegram message", e)
            false
        } catch (e: Exception) {
            Log.e("Mindaplus", "Unexpected error sending Telegram message", e)
            false
        }
    }
}