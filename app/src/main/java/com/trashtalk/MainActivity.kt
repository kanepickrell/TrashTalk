package com.trashtalk

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.trashtalk.ui.MainViewModel
import com.trashtalk.ui.TrashTalkRoot
import com.trashtalk.core.JankMonitor
import com.trashtalk.data.walk.WalkSync
import com.trashtalk.ui.theme.TrashTalkTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Edge to edge: the map is the screen, so it draws under the system bars.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as TrashTalkApp).container

        // Anything left pending from a previous run -- a crash, a force-stop,
        // a submit that timed out in a dead spot -- gets another chance now.
        WalkSync.enqueue(this)

        setContent {
            TrashTalkTheme {
                // One per window: the frame stream is the window's, and screens
                // distinguish themselves with TrackJank.
                JankMonitor()
                val vm: MainViewModel = viewModel(
                    factory = MainViewModel.Factory(container.repository),
                )
                TrashTalkRoot(vm)
            }
        }
    }
}
