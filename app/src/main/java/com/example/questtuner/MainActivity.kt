package com.example.questtuner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import com.example.questtuner.ui.TunerApp
import com.example.questtuner.ui.TunerViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: TunerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TunerApp(viewModel = viewModel)
        }
    }

    override fun onStart() {
        super.onStart()
        // BUG-029：回到前台重探测能力快照（PC 授权后返回、戴回头显等旅程）
        viewModel.refreshCapabilities()
    }
}
