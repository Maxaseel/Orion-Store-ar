package com.orion.store

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.view.Surface
import com.orion.store.data.Device
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.animation.PathInterpolator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.orion.store.platform.ResultHost
import com.orion.store.ui.MainViewModel
import com.orion.store.ui.AppLock
import com.orion.store.ui.LockScreen
import com.orion.store.ui.showBiometricPrompt
import com.orion.store.ui.OrionRoot
import com.orion.store.ui.Store
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {
    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(Lang.wrap(newBase))
    private val vm: MainViewModel by viewModels()
    private var pending: ((Int, Intent?) -> Unit)? = null
    private var locked by mutableStateOf(false)
    private var stoppedAt = 0L
    private val results = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r -> pending?.also { pending = null }?.invoke(r.resultCode, r.data) }
    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen().run {
            // Hold the splash until the cached catalog has painted (capped), so the first frame is the store, not a skeleton.
            val t0 = SystemClock.uptimeMillis()
            setKeepOnScreenCondition { vm.loading && SystemClock.uptimeMillis() - t0 < 1200 }
            setOnExitAnimationListener { p ->
                val emphasized = PathInterpolator(.3f, 0f, .8f, .15f) // M3 emphasized accelerate
                runCatching { p.iconView.animate().scaleX(1.6f).scaleY(1.6f).setInterpolator(emphasized).setDuration(320).start() }
                p.view.animate().alpha(0f).setInterpolator(emphasized).setDuration(320).withEndAction(p::remove).start()
            }
        }
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Phones stay portrait; tablets, foldables and TVs follow their screen. A manifest lock forced tablets into a letterboxed portrait window.
        requestedOrientation = if (phoneShaped()) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        ResultHost.launch = { i, cb -> pending = cb; results.launch(i) }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { Store.settings.flow.map { it.highRefreshRate || it.perf == "boost" }.distinctUntilChanged().collect { vm.tracker.setHighRefreshRate(this@MainActivity, it) } }
        }
        if (savedInstanceState == null) handle(intent)
        locked = shouldLockOnLaunch()
        setContent {
            if (locked) LockScreen(vm.s.lockMethod, AppLock.biometricAvailable(this),
                onBiometric = { showBiometricPrompt(this) { if (it) unlock() } },
                onUnlock = { pin -> AppLock.verify(this, pin).also { if (it) unlock() } })
            else OrionRoot(vm)
        }
        // Compose paints every pixel from the first frame on, so the window background underneath is never seen: drop it to save a full-screen fill per frame
        window.decorView.post { window.setBackgroundDrawable(null) }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handle(intent) }

    override fun onResume() { super.onResume(); vm.onResume() }
    override fun onStart() {
        super.onStart(); AutoUpdate.uiVisible = true
        if (stoppedAt != 0L && shouldLockAfter(stoppedAt)) locked = true
    }
    override fun onPause() {
        if (vm.s.lockEnabled && AppLock.hasPin(this) && vm.s.lockPolicy in setOf("screen_off", "leave_app")) locked = true
        super.onPause()
    }
    override fun onStop() {
        AutoUpdate.uiVisible = false
        stoppedAt = System.currentTimeMillis()
        if (vm.s.lockEnabled && AppLock.hasPin(this) && vm.s.lockPolicy == "app_close") locked = true
        super.onStop()
    }

    // Full screen: status bar stays hidden, a swipe from the top peeks it. Re-applied on focus since old APIs drop the flag.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) WindowCompat.getInsetsController(window, window.decorView).run {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.statusBars())
        }
    }

    override fun onDestroy() { if (ResultHost.launch != null && isFinishing) ResultHost.launch = null; super.onDestroy() }

    /** A small touch screen that is portrait at its natural rotation. TVs report about 540dp as their smallest width, so the
     *  width check alone locked them sideways; boxes that skip the leanback flag still have a landscape natural rotation. */
    private fun phoneShaped(): Boolean {
        val c = resources.configuration
        if (Device.isTv || !packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN) || c.smallestScreenWidthDp >= 600) return false
        @Suppress("DEPRECATION") val rot = (if (Build.VERSION.SDK_INT >= 30) display else windowManager.defaultDisplay)?.rotation ?: Surface.ROTATION_0
        val sideways = rot == Surface.ROTATION_90 || rot == Surface.ROTATION_270
        return (c.orientation == Configuration.ORIENTATION_LANDSCAPE) == sideways
    }


    private fun shouldLockOnLaunch(): Boolean = vm.s.lockEnabled && AppLock.hasPin(this)
    private fun shouldLockAfter(at: Long): Boolean {
        if (!vm.s.lockEnabled || !AppLock.hasPin(this)) return false
        return when (vm.s.lockPolicy) {
            "timeout" -> System.currentTimeMillis() - at >= vm.s.lockTimeoutMinutes.coerceAtLeast(1) * 60_000L
            else -> true
        }
    }
    private fun unlock() { locked = false; stoppedAt = 0L }

    private fun handle(i: Intent?) {
        vm.deepLink(i?.data); vm.onNotificationIntent(i)
        if (i?.action == Intent.ACTION_SHOW_APP_INFO) i.getStringExtra(Intent.EXTRA_PACKAGE_NAME)?.let(vm::showAppInfo)
    }
}
