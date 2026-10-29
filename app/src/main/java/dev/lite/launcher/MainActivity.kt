package dev.lite.launcher

import android.app.Activity
import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.LauncherApps
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextClock
import android.widget.TextView
import kotlin.math.abs

private const val MAX_FAVS = 7          // сколько приложений на главном экране
private const val AUTO_KEYBOARD = false // открывать клавиатуру сразу при входе в список

class AppEntry(val label: String, val component: ComponentName, val user: UserHandle) {
    val pkg: String get() = component.packageName
    val key: String = component.flattenToString() + "|" + user
}

/**
 * Минимальный лаунчер: чёрный фон, часы, избранное текстом, список приложений с поиском.
 * Никаких фоновых сервисов, виджетов, кэшей иконок, анимаций и сторонних библиотек.
 */
class MainActivity : Activity() {

    private lateinit var launcherApps: LauncherApps
    private lateinit var prefs: SharedPreferences

    private var apps: List<AppEntry> = emptyList()   // все приложения
    private var shown: List<AppEntry> = emptyList()  // после фильтра поиска

    private lateinit var home: LinearLayout
    private lateinit var favBox: LinearLayout
    private lateinit var ramText: TextView
    private lateinit var drawer: LinearLayout
    private lateinit var search: EditText
    private lateinit var list: ListView

    private val appAdapter = AppAdapter()
    private val handler = Handler(Looper.getMainLooper())
    private val reloadTask = Runnable { loadAsync() }

    // Свайп вверх по главному экрану открывает список приложений
    private val gestures by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (home.visibility == View.VISIBLE && vy < -1200f && abs(vy) > abs(vx)) {
                    showDrawer()
                    return true
                }
                return false
            }
        })
    }

    // Системный способ узнавать об установке/удалении приложений (без BroadcastReceiver)
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

        buildUi()
        launcherApps.registerCallback(callback)
        loadAsync()
    }

    override fun onResume() {
        super.onResume()
        updateRam()
    }

    // Кнопка/жест «Домой», когда лаунчер уже запущен
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_MAIN) showHome()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        // Лаунчер не должен закрываться. Из списка возвращаемся домой.
        if (drawer.visibility == View.VISIBLE) showHome()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        gestures.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun onDestroy() {
        handler.removeCallbacks(reloadTask)
        launcherApps.unregisterCallback(callback)
        super.onDestroy()
    }

    // ---------------------------------------------------------------- UI

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun row(text: String, size: Float, color: Int = Color.WHITE) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        setPadding(0, dp(10), 0, dp(10))
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun buildUi() {
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        val pad = dp(28)

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        // ----- главный экран -----
        home = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(32), pad, dp(24))
        }
        home.addView(TextClock(this).apply {
            textSize = 56f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        })
        home.addView(TextClock(this).apply {
            format12Hour = "EEEE, d MMMM"
            format24Hour = "EEEE, d MMMM"
            textSize = 16f
            setTextColor(Color.LTGRAY)
        })
        ramText = row("", 13f, Color.GRAY)
        home.addView(ramText)
        home.addView(View(this), LinearLayout.LayoutParams(match, 0, 1f)) // распорка
        favBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        home.addView(favBox)
        home.addView(row("Все приложения ↑", 16f, Color.GRAY).apply {
            setPadding(0, dp(20), 0, dp(10))
            setOnClickListener { showDrawer() }
        })
        root.addView(home, FrameLayout.LayoutParams(match, match))

        // ----- список приложений -----
        drawer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(pad, dp(16), pad, 0)
        }
        search = EditText(this).apply {
            hint = "Поиск"
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            textSize = 20f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_GO
            background = null
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) = applyFilter()
            })
            // Enter на клавиатуре запускает первое найденное приложение
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO) {
                    shown.firstOrNull()?.let { launch(it) }
                    true
                } else false
            }
        }
        list = ListView(this).apply {
            divider = null
            selector = ColorDrawable(0x22FFFFFF)
            overScrollMode = View.OVER_SCROLL_NEVER
            adapter = appAdapter
            setOnItemClickListener { _, _, pos, _ -> launch(shown[pos]) }
            setOnItemLongClickListener { _, _, pos, _ -> showOptions(shown[pos]); true }
        }
        drawer.addView(search, LinearLayout.LayoutParams(match, wrap))
        drawer.addView(list, LinearLayout.LayoutParams(match, 0, 1f))
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

    // ---------------------------------------------------------------- данные

    private fun scheduleReload() {
        handler.removeCallbacks(reloadTask)
        handler.postDelayed(reloadTask, 300)
    }

    // Загрузка списка в короткоживущем потоке; после неё в памяти остаются только имена и компоненты
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
                renderFavorites()
            }
        }.start()
    }

    private fun applyFilter() {
        val q = search.text.toString().trim()
        shown = if (q.isEmpty()) apps else apps.filter { it.label.contains(q, ignoreCase = true) }
        appAdapter.notifyDataSetChanged()
    }

    // ---------------------------------------------------------------- избранное

    private fun favKeys(): List<String> =
        prefs.getString("fav", "")!!.split('\n').filter { it.isNotEmpty() }

    private fun saveFavs(keys: List<String>) {
        prefs.edit().putString("fav", keys.joinToString("\n")).apply()
    }

    private fun toggleFav(app: AppEntry) {
        val keys = favKeys().toMutableList()
        if (!keys.remove(app.key)) {
            if (keys.size >= MAX_FAVS) keys.removeAt(0)
            keys.add(app.key)
        }
        saveFavs(keys)
        renderFavorites()
    }

    private fun renderFavorites() {
        favBox.removeAllViews()
        val byKey = apps.associateBy { it.key }
        for (k in favKeys()) {
            val app = byKey[k] ?: continue
            favBox.addView(row(app.label, 26f).apply {
                setOnClickListener { launch(app) }
                setOnLongClickListener { showOptions(app); true }
            })
        }
        if (favBox.childCount == 0) {
            favBox.addView(row("Долгое нажатие на приложение в списке → в избранное", 14f, Color.GRAY))
        }
    }

    // ---------------------------------------------------------------- действия

    private fun launch(app: AppEntry) {
        try {
            // makeCustomAnimation(0, 0) = запуск без анимации перехода (MIUI/HyperOS может игнорировать)
            val opts = ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle()
            launcherApps.startMainActivity(app.component, app.user, null, opts)
        } catch (e: Exception) {
            // приложение удалено/недоступно — просто ничего не делаем
        }
        showHome()
    }

    private fun showOptions(app: AppEntry) {
        val items = arrayOf(
            if (app.key in favKeys()) "Убрать из избранного" else "В избранное",
            "О приложении",
            "Удалить"
        )
        AlertDialog.Builder(this)
            .setTitle(app.label)
            .setItems(items) { _, which ->
                try {
                    when (which) {
                        0 -> toggleFav(app)
                        1 -> launcherApps.startAppDetailsActivity(app.component, app.user, null, null)
                        2 -> startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.pkg}")))
                    }
                } catch (e: Exception) {
                }
            }
            .show()
    }

    // ---------------------------------------------------------------- адаптер

    private inner class AppAdapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int): Any = shown[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val tv = (convertView as? TextView) ?: row("", 20f)
            tv.text = shown[position].label
            return tv
        }
    }
}
