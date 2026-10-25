package ng.pidgingpt

import android.os.Bundle
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.*
import ng.pidgingpt.ui.*

class MainActivity : ComponentActivity() {
    private val vm: ChatViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContent { ViNTTheme { AppNav(vm) } }
    }
    override fun onStart() { super.onStart(); vm.connectivity.start() }          // the network callback lives only while the app is visible
    override fun onStop() { super.onStop(); vm.onAppBackgrounded(); vm.connectivity.stop() }      // leaving the app ends voice mode: mic, player and speech models are released
    override fun onTrimMemory(level: Int) { super.onTrimMemory(level); if (level >= TRIM_MEMORY_RUNNING_CRITICAL && !vm.generating) vm.unloadModel() }
}
