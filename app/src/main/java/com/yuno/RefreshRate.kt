package com.yuno

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.lang.ref.WeakReference
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Screen refresh rate (Settings › Appearance).
 *
 * Auto leaves the choice to Android, which is what the app did until now. A fixed rate asks the
 * window for that exact display mode (same resolution, so the screen never changes size). Only the
 * rates the screen itself offers are listed: a 60 Hz panel has nothing to pick (the row is hidden),
 * a 120 Hz one offers 60 and 120, a 90 Hz one 60 and 90. They are requests, not orders: Android can
 * still lower the rate for battery saver or heat.
 *
 * The rates are read again when the display changes (a foldable's other screen, an external display),
 * so the list and the applied mode follow the screen that is actually in use.
 */
object RefreshRateSetting {
    private const val PREFS = "appearance"
    private const val KEY = "refresh_rate"

    /** Stored (and picked) when the system chooses. Any other value is a rate in Hz. */
    const val Auto = 0

    private var prefs: SharedPreferences? = null
    private var activity: WeakReference<Activity>? = null

    /** What was picked last: [Auto] or a rate in Hz. May name a rate this screen doesn't have. */
    private var hz by mutableStateOf(Auto)

    /** The fixed rates (≥ 60 Hz, at the screen's current resolution) this screen can run, ascending. */
    var rates by mutableStateOf<List<Int>>(emptyList())
        private set

    /** Only worth a row when the screen can run more than one rate. */
    val available: Boolean get() = rates.size >= 2

    /** What is in effect: the pick, or [Auto] if this screen has no such rate. */
    val selected: Int get() = hz.takeIf { it in rates } ?: Auto

    fun init(app: Application) {
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        hz = prefs?.getInt(KEY, Auto) ?: Auto

        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) = attach(activity)
            override fun onActivityDestroyed(activity: Activity) {
                if (this@RefreshRateSetting.activity?.get() === activity) this@RefreshRateSetting.activity = null
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        })

        // A foldable's other screen, or an external display: new modes, so new rates.
        app.getSystemService(DisplayManager::class.java)?.registerDisplayListener(
            object : DisplayManager.DisplayListener {
                override fun onDisplayAdded(displayId: Int) {}
                override fun onDisplayRemoved(displayId: Int) {}
                override fun onDisplayChanged(displayId: Int) {
                    activity?.get()?.let(::attach)
                }
            },
            Handler(Looper.getMainLooper()),
        )
    }

    /** Picks [Auto] or a fixed rate; kept, and applied to the open window right away. */
    fun choose(value: Int) {
        val picked = if (value in rates) value else Auto
        hz = picked
        prefs?.edit()?.putInt(KEY, picked)?.apply()
        activity?.get()?.let(::apply)
    }

    private fun attach(activity: Activity) {
        this.activity = WeakReference(activity)
        val display = displayOf(activity) ?: return
        rates = ratesOf(display)
        apply(activity)
    }

    private fun apply(activity: Activity) {
        val display = displayOf(activity) ?: return
        val window = activity.window ?: return
        val fixed = selected
        val mode = if (fixed == Auto) null else modeFor(display, fixed)
        val modeId = mode?.modeId ?: 0
        // 0 = no preference: that is Auto.
        val rate = if (mode != null) fixed.toFloat() else 0f
        val lp = window.attributes
        val rangeSet = Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
            (lp.preferredMinDisplayRefreshRate == rate && lp.preferredMaxDisplayRefreshRate == rate)
        // Nothing to change: don't make the window lay out again for it.
        if (lp.preferredDisplayModeId == modeId && rangeSet) return
        lp.preferredDisplayModeId = modeId
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            lp.preferredMinDisplayRefreshRate = rate
            lp.preferredMaxDisplayRefreshRate = rate
        }
        window.attributes = lp
    }

    @Suppress("DEPRECATION")
    private fun displayOf(activity: Activity): Display? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) activity.display else activity.windowManager.defaultDisplay

    /** The modes at the screen's current resolution: switching rate must never switch size. */
    private fun sameSize(display: Display): List<Display.Mode> {
        val now = display.mode
        return display.supportedModes.filter { it.physicalWidth == now.physicalWidth && it.physicalHeight == now.physicalHeight }
    }

    private fun ratesOf(display: Display): List<Int> =
        sameSize(display).map { it.refreshRate.roundToInt() }.filter { it >= 60 }.distinct().sorted()

    /** The mode closest to [hz] (59.94 counts as 60, 119.88 as 120). */
    private fun modeFor(display: Display, hz: Int): Display.Mode? =
        sameSize(display).minByOrNull { abs(it.refreshRate - hz) }?.takeIf { abs(it.refreshRate - hz) < 1.5f }
}
