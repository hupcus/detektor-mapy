package cz.hh.detektormapy

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import cz.hh.detektormapy.ui.DetektorMapyRoot
import cz.hh.detektormapy.ui.theme.DetektorMapyTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            DetektorMapyTheme {
                DetektorMapyRoot()
            }
        }
    }
}
