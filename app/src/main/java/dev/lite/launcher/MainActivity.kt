package dev.lite.launcher

import android.app.Activity
import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.AlertDialog
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentCallbacks2
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.MediaStore
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.LruCache
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextClock
import android.widget.TextView
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.abs

// ---- настройки ----
private const val MAX_FAV = 8                    // иконок на главном экране (2 ряда по 4)
private const val MAX_DOCK = 4                   // иконок в доке
private const val ICON_DP = 56                   // размер иконок на главном экране
private const val LIST_ICON_DP = 40              // размер иконок в списке
private const val ICON_CACHE_BYTES = 3 * 1024 * 1024
private const val AUTO_KEYBOARD = false          // клавиатура сразу при открытии списка
private const val EXTRA_GESTURES = true          // вниз = шторка, влево = камера, вправо = звонилка
private const val DOUBLE_TAP_LOCK = true        // двойной тап по пустому месту = блокировка экрана

private const val FAV = "fav"
private const val DOCK = "dock"

// ---- цвета (тёмная схема iOS) ----
private val C_CARD = Color.rgb(28, 28, 30)
private val C_GRAY = Color.rgb(142, 142, 147)
private val C_BLUE = Color.rgb(10, 132, 255)
private val C_ICON_BG = Color.rgb(44, 44, 46)

class AppEntry(val label: String, val component: ComponentName, val user: UserHandle) {
    val pkg: String get() = component.packageName
    val key: String = component.flattenToString() + "|" + user
}

// Пустой приёмник: он нужен системе, чтобы выдать право lockNow(). Ничего не запускает.
class AdminReceiver : DeviceAdminReceiver()

private class RowHolder(val icon: ImageView, val label: TextView)

/**
 * Лёгкий лаунчер в стиле iOS: карточка-виджет, избранное сеткой, док, список с поиском.
 * Блюра и анимаций нет. Иконки рисуются лениво и лежат в кэше не больше 3 МБ.
 * Шрифт Inter берётся из assets/fonts (regular.ttf, semibold.ttf); если файлов нет, используется системный.
 */
class MainActivity : Activity() {

    private lateinit var launcherApps: LauncherApps
    private lateinit var prefs: SharedPreferences
    private lateinit var tfRegular: Typeface
    private lateinit var tfSemi: Typeface

    private var apps: List<AppEntry> = emptyList()
    private var shown: List<AppEntry> = emptyList()

    private lateinit var home: LinearLayout
    private lateinit var favBox: LinearLayout
    private lateinit var emptyHint: TextView
    private lateinit var dock: LinearLayout
    private lateinit var ramText: TextView
    private lateinit var drawer: LinearLayout
    private lateinit var search: EditText
    private lateinit var list: ListView

    private val appAdapter = AppAdapter()
    private val handler = Handler(Looper.getMainLooper())
    private val reloadTask = Runnable { loadAsync() }

    private val iconCache = object : LruCache<String, Bitmap>(ICON_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    // Поток создаётся только когда есть работа и гаснет через 5 секунд простоя
    private val iconExec = ThreadPoolExecutor(0, 1, 5L, TimeUnit.SECONDS, LinkedBlockingQueue<Runnable>())

    private val gestures by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (home.visibility != View.VISIBLE) return false
                val ax = abs(vx)
                val ay = abs(vy)
                when {
                    ay > ax && vy < -1200f -> showDrawer()
                    EXTRA_GESTURES && ay > ax && vy > 1200f -> expandNotifications()
                    EXTRA_GESTURES && ax > ay && vx < -1200f -> startSafe(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
                    EXTRA_GESTURES && ax > ay && vx > 1200f -> startSafe(Intent(Intent.ACTION_DIAL))
                    else -> return false
                }
                return true
            }
        })
    }

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageAdded(packageName: String, user: UserHandle) = scheduleReload()
        override fun onPackageRemoved(packageName: String, user: UserHandle) = scheduleReload()
        override fun onPackageChanged(packageName: String, user: UserHandle) = scheduleReload()
        override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = scheduleReload()
        override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = scheduleReload()
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launcherApps = getSystemService(LauncherApps::class.java)
        prefs = getSharedPreferences("p", MODE_PRIVATE)

        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        window.setWindowAnimations(0)

        loadFonts()
        buildUi()
        launcherApps.registerCallback(callback)
        loadAsync()
    }

    override fun onResume() {
        super.onResume()
        updateRam()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_MAIN) showHome()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Ушли в другое приложение: отдаём системе кэш иконок
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) iconCache.evictAll()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (drawer.visibility == View.VISIBLE) showHome()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        gestures.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun onDestroy() {
        handler.removeCallbacks(reloadTask)
        launcherApps.unregisterCallback(callback)
        iconExec.shutdown()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- шрифты и помощники

    private fun font(vararg paths: String): Typeface? {
        for (p in paths) {
            try {
                return Typeface.createFromAsset(assets, p)
            } catch (e: Exception) {
            }
        }
        return null
    }

    private fun loadFonts() {
        tfRegular = font("fonts/regular.ttf", "fonts/regular.otf")
            ?: Typeface.create("sans-serif", Typeface.NORMAL)
        tfSemi = font("fonts/semibold.ttf", "fonts/semibold.otf")
            ?: Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
    }

    private fun tv(text: String, sp: Float, color: Int, semi: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = sp
        setTextColor(color)
        typeface = if (semi) tfSemi else tfRegular
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    // ---------------------------------------------------------------- интерфейс

    private fun buildUi() {
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        val side = dp(16)

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        // ----- главный экран -----
        home = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(side, dp(16), side, dp(16))
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(C_CARD, dp(22))
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        card.addView(TextClock(this).apply {
            textSize = 48f
            setTextColor(Color.WHITE)
            typeface = tfSemi
        })
        card.addView(TextClock(this).apply {
            format12Hour = "EEEE, d MMMM"
            format24Hour = "EEEE, d MMMM"
            textSize = 15f
            setTextColor(C_GRAY)
            typeface = tfRegular
        })
        ramText = tv("", 13f, C_GRAY).apply { setPadding(0, dp(10), 0, 0) }
        card.addView(ramText)
        home.addView(card, LinearLayout.LayoutParams(match, wrap))

        favBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        home.addView(favBox, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(20) })

        emptyHint = tv("Долгое нажатие на приложение в списке → на экран или в док", 14f, C_GRAY).apply {
            maxLines = 3
            visibility = View.GONE
            setPadding(dp(4), dp(20), dp(4), 0)
        }
        home.addView(emptyHint)

        // Пустое место между сеткой и доком; двойной тап здесь блокирует экран
        val spacer = View(this)
        if (DOUBLE_TAP_LOCK) {
            val tap = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    lockScreen()
                    return true
                }
            })
            spacer.setOnTouchListener { _, ev -> tap.onTouchEvent(ev); true }
        }
        home.addView(spacer, LinearLayout.LayoutParams(match, 0, 1f))

        val pill = tv("Поиск", 15f, C_GRAY).apply {
            background = rounded(C_CARD, dp(18))
            setPadding(dp(28), dp(8), dp(28), dp(8))
            setOnClickListener { showDrawer() }
        }
        home.addView(pill, LinearLayout.LayoutParams(wrap, wrap).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(12)
        })

        dock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(C_CARD, dp(30))
            setPadding(dp(10), dp(6), dp(10), dp(6))
            visibility = View.GONE
        }
        home.addView(dock, LinearLayout.LayoutParams(match, wrap))
        root.addView(home, FrameLayout.LayoutParams(match, match))

        // ----- список приложений -----
        drawer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(side, dp(12), side, 0)
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        search = EditText(this).apply {
            hint = "Поиск"
            setHintTextColor(C_GRAY)
            setTextColor(Color.WHITE)
            typeface = tfRegular
            textSize = 17f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_GO
            background = rounded(C_CARD, dp(12))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(14), 0)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) = applyFilter()
            })
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO) {
                    shown.firstOrNull()?.let { launch(it) }
                    true
                } else false
            }
        }
        top.addView(search, LinearLayout.LayoutParams(0, dp(44), 1f))
        top.addView(tv("Отмена", 17f, C_BLUE).apply {
            setPadding(dp(14), dp(10), 0, dp(10))
            setOnClickListener { showHome() }
        })

        list = ListView(this).apply {
            divider = null
            selector = ColorDrawable(0x22FFFFFF)
            overScrollMode = View.OVER_SCROLL_NEVER
            background = rounded(C_CARD, dp(16))
            clipToOutline = true
            adapter = appAdapter
            setOnItemClickListener { _, _, pos, _ -> launch(shown[pos]) }
            setOnItemLongClickListener { _, _, pos, _ -> showOptions(shown[pos]); true }
        }
        drawer.addView(top, LinearLayout.LayoutParams(match, wrap))
        drawer.addView(list, LinearLayout.LayoutParams(match, 0, 1f).apply {
            topMargin = dp(12)
            bottomMargin = dp(16)
        })
        root.addView(drawer, FrameLayout.LayoutParams(match, match))

        setContentView(root)
    }

    private fun showHome() {
        hideKeyboard()
        search.setText("")
        drawer.visibility = View.GONE
        home.visibility = View.VISIBLE
    }

    private fun showDrawer() {
        home.visibility = View.GONE
        drawer.visibility = View.VISIBLE
        list.setSelection(0)
        if (AUTO_KEYBOARD) {
            search.requestFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(search, 0)
        }
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(search.windowToken, 0)
    }

    private fun updateRam() {
        val mi = ActivityManager.MemoryInfo()
        (getSystemService(ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        fun gb(b: Long) = String.format("%.1f", b / 1073741824.0)
        ramText.text = "RAM свободно: ${gb(mi.availMem)} / ${gb(mi.totalMem)} ГБ"
    }

    // ---------------------------------------------------------------- главный экран: сетка и док

    private fun renderHome() {
        val byKey = apps.associateBy { it.key }
        val fav = keys(FAV).mapNotNull { byKey[it] }
        val dk = keys(DOCK).mapNotNull { byKey[it] }

        fillRows(favBox, fav, 4, true)
        fillRows(dock, dk, 4, false)
        dock.visibility = if (dk.isEmpty()) View.GONE else View.VISIBLE
        emptyHint.visibility = if (fav.isEmpty() && dk.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun fillRows(container: LinearLayout, items: List<AppEntry>, perRow: Int, labels: Boolean) {
        container.removeAllViews()
        for (chunk in items.chunked(perRow)) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (i in 0 until perRow) {
                val cell = chunk.getOrNull(i)?.let { homeCell(it, labels) } ?: View(this)
                row.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            container.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun homeCell(app: AppEntry, withLabel: Boolean): View {
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(8), 0, dp(8))
        }
        val icon = ImageView(this)
        cell.addView(icon, LinearLayout.LayoutParams(dp(ICON_DP), dp(ICON_DP)))
        if (withLabel) {
            cell.addView(tv(app.label, 11f, Color.WHITE).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(2), dp(5), dp(2), 0)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        bindIcon(icon, app, ICON_DP)
        cell.setOnClickListener { launch(app) }
        cell.setOnLongClickListener { showOptions(app); true }
        return cell
    }

    // ---------------------------------------------------------------- иконки

    // Иконка берётся из кэша, иначе рисуется в фоне и ставится в ImageView, если он ещё нужен
    private fun bindIcon(iv: ImageView, app: AppEntry, sizeDp: Int) {
        val px = dp(sizeDp)
        val ck = "${app.key}@$px"
        iv.tag = ck
        val cached = iconCache.get(ck)
        if (cached != null) {
            iv.setImageBitmap(cached)
            return
        }
        iv.setImageDrawable(null)
        iconExec.execute {
            if (iv.tag != ck) return@execute // строку уже пролистали
            val bmp = try { loadIcon(app, px) } catch (e: Exception) { null } ?: return@execute
            iconCache.put(ck, bmp)
            handler.post { if (iv.tag == ck) iv.setImageBitmap(bmp) }
        }
    }

    private fun loadIcon(app: AppEntry, px: Int): Bitmap? {
        val info = launcherApps.getActivityList(app.pkg, app.user)
            .firstOrNull { it.componentName == app.component } ?: return null
        return renderIcon(info.getIcon(0), px)
    }

    // Рисуем иконку в скруглённый квадрат (радиус ~22,5%, как в iOS)
    private fun renderIcon(d: Drawable, size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        if (d is AdaptiveIconDrawable) {
            // видимая область адаптивной иконки = 72 из 108 dp, остальное уходит за края
            val pad = (size * 0.25f).toInt()
            val lo = -pad
            val hi = size + pad
            d.background?.let { it.setBounds(lo, lo, hi, hi); it.draw(c) }
            d.foreground?.let { it.setBounds(lo, lo, hi, hi); it.draw(c) }
        } else {
            c.drawColor(C_ICON_BG)
            val inset = (size * 0.14f).toInt()
            d.setBounds(inset, inset, size - inset, size - inset)
            d.draw(c)
        }
        val r = size * 0.225f
        val outside = Path().apply {
            addRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), r, r, Path.Direction.CW)
            fillType = Path.FillType.INVERSE_WINDING
        }
        val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
        c.drawPath(outside, clear)
        return bmp
    }

    // ---------------------------------------------------------------- данные

    private fun scheduleReload() {
        handler.removeCallbacks(reloadTask)
        handler.postDelayed(reloadTask, 300)
    }

    private fun loadAsync() {
        Thread {
            val result = ArrayList<AppEntry>()
            for (user in launcherApps.profiles) {
                for (info in launcherApps.getActivityList(null, user)) {
                    result += AppEntry(info.label.toString(), info.componentName, user)
                }
            }
            result.sortBy { it.label.lowercase() }
            runOnUiThread {
                apps = result
                applyFilter()
                renderHome()
            }
        }.start()
    }

    private fun applyFilter() {
        val q = search.text.toString().trim()
        shown = if (q.isEmpty()) apps else apps.filter { it.label.contains(q, ignoreCase = true) }
        appAdapter.notifyDataSetChanged()
    }

    private fun keys(name: String): List<String> =
        prefs.getString(name, "")!!.split('\n').filter { it.isNotEmpty() }

    private fun toggle(name: String, max: Int, app: AppEntry) {
        val l = keys(name).toMutableList()
        if (!l.remove(app.key)) {
            if (l.size >= max) l.removeAt(0)
            l.add(app.key)
        }
        prefs.edit().putString(name, l.joinToString("\n")).apply()
        renderHome()
    }

    // ---------------------------------------------------------------- действия

    private fun launch(app: AppEntry) {
        try {
            val opts = ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle()
            launcherApps.startMainActivity(app.component, app.user, null, opts)
        } catch (e: Exception) {
        }
        showHome()
    }

    private fun startSafe(i: Intent) {
        try {
            startActivity(i)
        } catch (e: Exception) {
        }
    }

    // Первый двойной тап откроет системный запрос на права администратора устройства
    private fun lockScreen() {
        try {
            val dpm = getSystemService(DevicePolicyManager::class.java)
            val admin = ComponentName(this, AdminReceiver::class.java)
            if (dpm.isAdminActive(admin)) {
                dpm.lockNow()
            } else {
                startActivity(
                    Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                        .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                        .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Нужно только для блокировки экрана двойным тапом")
                )
            }
        } catch (e: Exception) {
        }
    }

    // Скрытый API: на части прошивок может не сработать
    private fun expandNotifications() {
        try {
            val sbm = getSystemService("statusbar")
            Class.forName("android.app.StatusBarManager")
                .getMethod("expandNotificationsPanel").invoke(sbm)
        } catch (e: Exception) {
        }
    }

    private fun showOptions(app: AppEntry) {
        val items = arrayOf(
            if (app.key in keys(FAV)) "Убрать с главного экрана" else "На главный экран",
            if (app.key in keys(DOCK)) "Убрать из дока" else "В док",
            "О приложении",
            "Удалить"
        )
        AlertDialog.Builder(this)
            .setTitle(app.label)
            .setItems(items) { _, which ->
                try {
                    when (which) {
                        0 -> toggle(FAV, MAX_FAV, app)
                        1 -> toggle(DOCK, MAX_DOCK, app)
                        2 -> launcherApps.startAppDetailsActivity(app.component, app.user, null, null)
                        3 -> startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.pkg}")))
                    }
                } catch (e: Exception) {
                }
            }
            .show()
    }

    // ---------------------------------------------------------------- адаптер списка

    private inner class AppAdapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int): Any = shown[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = convertView ?: makeRow()
            val h = row.tag as RowHolder
            val app = shown[position]
            h.label.text = app.label
            bindIcon(h.icon, app, LIST_ICON_DP)
            return row
        }

        private fun makeRow(): View {
            val icon = ImageView(this@MainActivity)
            val label = tv("", 17f, Color.WHITE)
            return LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(8), dp(16), dp(8))
                addView(icon, LinearLayout.LayoutParams(dp(LIST_ICON_DP), dp(LIST_ICON_DP)).apply {
                    rightMargin = dp(14)
                })
                addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                tag = RowHolder(icon, label)
            }
        }
    }
}
