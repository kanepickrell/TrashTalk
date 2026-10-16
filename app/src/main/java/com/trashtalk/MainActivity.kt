package com.trashtalk

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.trashtalk.ui.MainViewModel
import com.trashtalk.ui.TrashTalkRoot
import com.trashtalk.ui.theme.TrashTalkTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Edge to edge: the map is the screen, so it draws under the system bars.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as TrashTalkApp).container

        setContent {
            TrashTalkTheme {
                val vm: MainViewModel = viewModel(
                    factory = MainViewModel.Factory(container.repository),
                )
                TrashTalkRoot(vm)
            }
        }
    }
}
