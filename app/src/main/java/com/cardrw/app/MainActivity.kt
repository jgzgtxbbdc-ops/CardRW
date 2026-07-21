package com.cardrw.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.cardrw.app.ui.navigation.CardRwNavHost
import com.cardrw.app.ui.theme.CardRwTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CardRwTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CardRwNavHost()
                }
            }
        }
    }
}
