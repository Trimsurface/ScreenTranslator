package com.personal.screentranslator

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

class MainActivity : ComponentActivity() {

    /** Меняется при каждом возвращении на экран — перечитать статус сервиса, батареи, пакетов. */
    private var resumeTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                SettingsScreen(resumeTick)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isVisible = true
        resumeTick++
    }

    override fun onPause() {
        isVisible = false
        super.onPause()
    }

    companion object {
        /** Пока открыты настройки, авто-режим на паузе. */
        @Volatile
        var isVisible = false
            private set
    }
}
