package dev.lite.launcher

import android.accessibilityservice.AccessibilityService
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

// ---- настройки жестов ----
private const val EDGE_DP = 18          // ширина зоны у левого и правого края
private const val BOTTOM_DP = 20        // высота зоны у нижнего края
private const val SWIPE_DP = 56         // на сколько надо провести пальцем
private const val SLOW_MS = 350L        // свайп вверх дольше этого = «недавние», быстрее = «домой»
private const val BOTTOM_GESTURE = true // false = только «назад» от боковых краёв

private const val EDGE_L = 0
private const val EDGE_R = 1
private const val EDGE_B = 2

/**
 * Жесты как в системной навигации для лаунчера, который HyperOS не поддерживает:
 *  - свайп от левого/правого края = назад
 *  - свайп вверх от нижнего края = домой, медленный/с удержанием = недавние
 * Служба не читает содержимое экрана и не подписана ни на какие события: она только рисует
 * три невидимые полосы у краёв и вызывает системные действия.
 */
class EdgeGestureService : AccessibilityService() {

    private val strips = ArrayList<View>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        rebuild()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        rebuild() // поворот экрана: пересчитываем зоны
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        removeStrips()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- зоны

    private fun rebuild() {
        removeStrips()
        val wm = getSystemService(WindowManager::class.java)
        val (w, h) = screenSize(wm)
        val d = resources.displayMetrics.density
        val edge = (EDGE_DP * d).toInt()
        val bottom = if (BOTTOM_GESTURE) (BOTTOM_DP * d).toInt() else 0
        val lift = navInset(wm) // зоны стоят выше панели навигации, чтобы не перекрывать кнопки
        val sideH = h - lift - bottom

        add(wm, EdgeTouch(EDGE_L), edge, sideH, Gravity.TOP or Gravity.START, 0)
        add(wm, EdgeTouch(EDGE_R), edge, sideH, Gravity.TOP or Gravity.END, 0)
        if (BOTTOM_GESTURE) {
            add(wm, EdgeTouch(EDGE_B), w, bottom, Gravity.BOTTOM or Gravity.START, lift)
        }
    }

    private fun add(wm: WindowManager, touch: EdgeTouch, w: Int, h: Int, gravity: Int, y: Int) {
        val v = View(this)
        v.setOnTouchListener(touch)
        val lp = WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSPARENT
        )
        lp.gravity = gravity
        lp.y = y
        try {
            wm.addView(v, lp)
            strips.add(v)
        } catch (e: Exception) {
        }
    }

    private fun removeStrips() {
        val wm = getSystemService(WindowManager::class.java)
        for (v in strips) {
            try {
                wm.removeView(v)
            } catch (e: Exception) {
            }
        }
        strips.clear()
    }

    private fun screenSize(wm: WindowManager): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels to dm.heightPixels
        }

    private fun navInset(wm: WindowManager): Int =
        if (Build.VERSION.SDK_INT >= 30) {
            wm.currentWindowMetrics.windowInsets
                .getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars()).bottom
        } else {
            val id = resources.getIdentifier("navigation_bar_height", "dimen", "android")
            if (id > 0) resources.getDimensionPixelSize(id) else 0
        }

    // ---------------------------------------------------------------- распознавание свайпа

    private inner class EdgeTouch(private val edge: Int) : View.OnTouchListener {
        private var x0 = 0f
        private var y0 = 0f
        private var t0 = 0L
        private var fired = false
        private val threshold = SWIPE_DP * resources.displayMetrics.density

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    x0 = e.rawX
                    y0 = e.rawY
                    t0 = e.eventTime
                    fired = false
                }
                MotionEvent.ACTION_MOVE -> if (!fired && edge != EDGE_B) {
                    val dx = e.rawX - x0
                    if ((edge == EDGE_L && dx > threshold) || (edge == EDGE_R && dx < -threshold)) {
                        fired = true
                        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    }
                }
                MotionEvent.ACTION_UP -> if (!fired && edge == EDGE_B && y0 - e.rawY > threshold) {
                    val slow = e.eventTime - t0 > SLOW_MS
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    performGlobalAction(
                        if (slow) AccessibilityService.GLOBAL_ACTION_RECENTS
                        else AccessibilityService.GLOBAL_ACTION_HOME
                    )
                }
            }
            return true
        }
    }
}
