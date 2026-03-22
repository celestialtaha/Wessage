package com.wapp.wearmessage.presentation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.wapp.wearmessage.presentation.theme.WessageTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WessageTheme {
                WearMessagingApp()
            }
        }
    }
}
