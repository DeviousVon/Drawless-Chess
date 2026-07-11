package com.drawlesschess

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import com.drawlesschess.ui.DrawlessApp
import com.drawlesschess.ui.DrawlessAppViewModel
import com.drawlesschess.ui.DrawlessTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val drawlessApplication = application as DrawlessApplication
        val viewModel = ViewModelProvider(
            this,
            DrawlessAppViewModel.factory(this, drawlessApplication.checkpointStore),
        )[DrawlessAppViewModel::class.java]
        setContent {
            DrawlessTheme {
                DrawlessApp(viewModel)
            }
        }
    }
}
