package com.tuptup.speech

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.tuptup.speech.ui.MainScreen
import com.tuptup.speech.ui.MainViewModel
import com.tuptup.speech.ui.theme.TuptupTheme

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { TuptupTheme { MainScreen(vm) } }
    }
}
