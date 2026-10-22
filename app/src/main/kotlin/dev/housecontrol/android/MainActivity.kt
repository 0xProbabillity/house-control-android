package dev.housecontrol.android

import android.content.Intent
import android.os.Bundle
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.housecontrol.android.core.HomeAction
import dev.housecontrol.android.ui.HouseTheme
import dev.housecontrol.android.ui.Root

/** The one window: the 3D home full screen, with the native screens over it. */
class MainActivity : ComponentActivity() {
    private val model get() = HouseApp.model(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        // The app is dark whatever the system's mode: light status and navigation bar icons.
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        // Back on the home itself: the page goes back a step (a device sheet, a room), else the
        // app goes to the background as it would after its last screen. Screens over the home
        // register their own, which come first.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                model.web.back { handled -> if (!handled) moveTaskToBack(true) }
            }
        })
        setContent { HouseTheme { Root(model) } }
        if (savedInstanceState == null) handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onStart() {
        super.onStart()
        model.web.setVisible(true)
        model.editorWeb?.setVisible(true)
    }

    override fun onStop() {
        model.web.setVisible(false)
        model.editorWeb?.setVisible(false)
        super.onStop()
    }

    /** `housecontrol://…` links: pairing, a widget's device, an app shortcut's action. */
    private fun handle(intent: Intent?) {
        // A debug build can be pointed at a Mac directly (an emulator cannot see Bonjour):
        // adb shell am start -n dev.housecontrol.android.debug/dev.housecontrol.android.MainActivity --es syncEndpoint 10.0.2.2:PORT
        if (BuildConfig.DEBUG) intent?.getStringExtra("syncEndpoint")?.let { model.useSyncEndpoint(it.ifEmpty { null }) }
        val data = intent?.data ?: return
        if (data.scheme != "housecontrol") return
        if (data.host == "action") {
            val kind = data.getQueryParameter("kind") ?: return
            val action = HomeAction(kind, data.getQueryParameter("id"), data.getQueryParameter("on")?.toBooleanStrictOrNull())
            model.runQuickAction(action, data.getQueryParameter("title") ?: "Done")
            return
        }
        model.open(data.toString())
    }
}
