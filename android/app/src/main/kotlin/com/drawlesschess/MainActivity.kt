package com.drawlesschess

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import com.drawlesschess.ui.AppRoute
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
            val useDarkStatusIcons = viewModel.route != AppRoute.HOME && !isSystemInDarkTheme()
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView)
                    .isAppearanceLightStatusBars = useDarkStatusIcons
            }
            DrawlessTheme(theme = viewModel.selectedTheme) {
                DrawlessApp(viewModel, drawlessApplication.gameSoundPlayer)
            }
        }
    }
}
