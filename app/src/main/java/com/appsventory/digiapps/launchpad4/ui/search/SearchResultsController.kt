package com.appsventory.digiapps.launchpad4.ui.search

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.appsventory.digiapps.launchpad4.MainActivity
import com.appsventory.digiapps.launchpad4.R
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger

/**
 * Floating Search results panel under the Shell bar.
 * Sections: Apps (horizontal row) → Files (horizontal row) → Web.
 * Does not participate in mainContent layout flow.
 */
class SearchResultsController(private val activity: MainActivity) {

    data class AppHit(val packageName: String, val label: String, val score: Int)
    data class FileHit(val file: File, val score: Int)

    private val density = activity.resources.displayMetrics.density
    private val mainHandler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var fileJob: Future<*>? = null
    private val queryGen = AtomicInteger(0)

    private var panel: LinearLayout? = null
    private var listHost: LinearLayout? = null
    private var statusView: TextView? = null
    private var scrollView: ScrollView? = null
    private var visible = false
    /** Invoked after a result is activated so the Shell can fully dismiss Search. */
    var onRequestClose: (() -> Unit)? = null
    private var lastQuery = ""
    private var contentWidthPx = 0

    private val colorWhite = 0xFFFFFFFF.toInt()
    private val colorMuted = 0x99FFFFFF.toInt()
    private val colorAccent = 0xFF4FC3F7.toInt()

    fun isVisible(): Boolean = visible

    fun show() {
        ensurePanel()
        val p = panel
        if (p != null) {
            p.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            p.visibility = View.VISIBLE
        }
        visible = true
        syncPanelBounds()
    }

    fun hide() {
        visible = false
        // Invalidate any in-flight search so delayed callbacks cannot repopulate the panel
        queryGen.incrementAndGet()
        cancelFileSearch()
        lastQuery = ""
        listHost?.removeAllViews()
        val p = panel
        if (p != null) {
            // Drop focus so no hidden child keeps intercepting D-pad
            if (p.hasFocus() || p.isFocused) p.clearFocus()
            val focused = p.findFocus()
            focused?.clearFocus()
            p.visibility = View.GONE
            // Prevent GONE-but-still-attached views from participating in focus search
            p.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            p.isFocusable = false
            p.isClickable = false
        }
    }

    fun onQueryChanged(raw: String) {
        // Ignore updates after Search has been dismissed
        if (!visible && raw.isEmpty()) return
        val q = raw.trim()
        lastQuery = q
        if (q.isEmpty()) {
            cancelFileSearch()
            // Only refresh empty UI while the panel is already open
            if (visible) {
                statusView?.text = "Type to search apps, files, and the web"
                listHost?.removeAllViews()
            }
            return
        }
        if (!visible) return // do not reopen after close
        val gen = queryGen.incrementAndGet()
        renderResults(q, filesLoading = true)
        scheduleFileSearch(q, gen)
    }

    fun focusFirstResult(): Boolean {
        ensurePanel()
        if (!visible) show()
        val host = listHost ?: return false
        val first = findFirstFocusable(host) ?: return false
        first.requestFocus()
        scrollVerticallyTo(first)
        return true
    }

    fun openWebSearch(query: String, closeAfter: Boolean = true) {
        val q = query.trim()
        if (q.isEmpty()) return
        val encoded = try {
            URLEncoder.encode(q, "UTF-8")
        } catch (_: Exception) {
            q.replace(" ", "+")
        }
        try {
            activity.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=$encoded"))
            )
            if (closeAfter) notifyCloseAfterSelection()
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(activity, "No browser available", Toast.LENGTH_SHORT).show()
        }
    }

    // ─── Panel (floating on root) ───

    private fun ensurePanel() {
        if (panel != null) return
        val root = activity.findViewById<ViewGroup>(R.id.root) ?: return
        val d = density

        val p = object : LinearLayout(activity) {
            override fun focusSearch(focused: View?, direction: Int): View? {
                if (!visible) return super.focusSearch(focused, direction)
                val next = super.focusSearch(focused, direction)
                if (next != null && isUnder(this, next)) return next
                // Outer boundary: keep focus inside the panel
                if (direction == View.FOCUS_LEFT || direction == View.FOCUS_RIGHT ||
                    direction == View.FOCUS_DOWN || direction == View.FOCUS_UP
                ) {
                    return focused
                }
                return next
            }
            private fun isUnder(root: ViewGroup, child: View): Boolean {
                var c: View? = child
                while (c != null) {
                    if (c === root) return true
                    c = c.parent as? View
                }
                return false
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            elevation = 28 * d
            translationZ = 28 * d
            clipChildren = true
            clipToPadding = true
            isFocusable = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 14 * d
                setColor(0xF0121418.toInt())
                setStroke((1 * d).toInt(), 0x33FFFFFF)
            }
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (10 * d).toInt())
        }

        statusView = TextView(activity).apply {
            setTextColor(colorMuted)
            textSize = 12f
            setPadding(0, 0, 0, (6 * d).toInt())
            isFocusable = false
        }
        p.addView(statusView)

        val maxH = (activity.resources.displayMetrics.heightPixels * 0.42f).toInt()
            .coerceIn((160 * d).toInt(), (380 * d).toInt())
        scrollView = ScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, maxH
            )
            isFocusable = false
            clipToPadding = true
            clipChildren = true
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        listHost = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = false
            clipChildren = true
        }
        scrollView!!.addView(listHost)
        p.addView(scrollView)
        root.addView(p)
        panel = p
        p.post { syncPanelBounds() }
    }

    private fun measureShellChromeWidth(): Pair<Int, Int> {
        val d = density
        val profile = activity.findViewById<View>(R.id.profileButton)
        val action = activity.findViewById<View>(R.id.actionPill)
        val main = activity.findViewById<View>(R.id.mainContent)
        val fallback = ((main?.width ?: activity.resources.displayMetrics.widthPixels) * 0.62f)
            .toInt().coerceIn((300 * d).toInt(), (720 * d).toInt())
        if (profile == null || action == null) return fallback to (28 * d).toInt()
        val locP = IntArray(2)
        val locA = IntArray(2)
        profile.getLocationInWindow(locP)
        action.getLocationInWindow(locA)
        val width = (locA[0] + action.width - locP[0]).coerceIn((260 * d).toInt(), (800 * d).toInt())
        val root = activity.findViewById<View>(R.id.root)
        val locR = IntArray(2)
        root?.getLocationInWindow(locR)
        val left = (locP[0] - (locR.getOrNull(0) ?: 0)).coerceAtLeast(0)
        return width to left
    }

    private fun syncPanelBounds() {
        val p = panel ?: return
        val d = density
        val (w, left) = measureShellChromeWidth()
        val topBar = activity.findViewById<View>(R.id.topBar)
        val root = activity.findViewById<View>(R.id.root)
        val lp = p.layoutParams as? FrameLayout.LayoutParams ?: return
        lp.width = w
        lp.leftMargin = left
        if (topBar != null && root != null) {
            val locT = IntArray(2)
            val locR = IntArray(2)
            topBar.getLocationInWindow(locT)
            root.getLocationInWindow(locR)
            lp.topMargin = locT[1] - locR[1] + topBar.height + (8 * d).toInt()
        } else {
            lp.topMargin = (72 * d).toInt()
        }
        p.layoutParams = lp
        contentWidthPx = (w - p.paddingLeft - p.paddingRight).coerceAtLeast((200 * d).toInt())
    }

    private fun showEmptyState(msg: String) {
        if (!visible) return
        statusView?.text = msg
        listHost?.removeAllViews()
    }

    // ─── Render ───

    private fun renderResults(q: String, filesLoading: Boolean, files: List<FileHit> = emptyList()) {
        ensurePanel()
        syncPanelBounds()
        val host = listHost ?: return
        host.removeAllViews()

        val apps = searchApps(q)
        statusView?.text = when {
            filesLoading -> "Searching…"
            apps.isEmpty() && files.isEmpty() -> "No results for \"$q\""
            else -> {
                val parts = mutableListOf<String>()
                if (apps.isNotEmpty()) parts += "${apps.size} apps"
                if (files.isNotEmpty()) parts += "${files.size} files"
                parts.joinToString(" · ")
            }
        }

        val appTiles = mutableListOf<View>()
        val fileTiles = mutableListOf<View>()
        var web: View? = null

        if (apps.isNotEmpty()) {
            host.addView(sectionHeader("Apps"))
            val row = horizontalRow()
            apps.take(24).forEach { hit ->
                val t = appTile(hit)
                row.addView(t)
                appTiles.add(t)
            }
            host.addView(wrapHorizontalScroll(row))
        }

        when {
            filesLoading -> {
                host.addView(sectionHeader("Files"))
                host.addView(hintRow("Searching local storage…"))
            }
            files.isNotEmpty() -> {
                host.addView(sectionHeader("Files"))
                val row = horizontalRow()
                files.take(24).forEach { hit ->
                    val t = fileTile(hit)
                    row.addView(t)
                    fileTiles.add(t)
                }
                host.addView(wrapHorizontalScroll(row))
            }
            q.isNotEmpty() -> {
                host.addView(sectionHeader("Files"))
                host.addView(hintRow("No matching files in accessible storage"))
            }
        }

        host.addView(sectionHeader("Web"))
        web = webRow(q)
        host.addView(web)

        wireCrossSection(appTiles, fileTiles, web)
    }

    private fun searchApps(q: String): List<AppHit> {
        val needle = q.lowercase()
        val pm = activity.packageManager
        return activity.installedApps.mapNotNull { app ->
            val label = try {
                pm.getApplicationLabel(app).toString()
            } catch (_: Exception) {
                app.packageName
            }
            val lower = label.lowercase()
            val score = when {
                lower == needle -> 100
                lower.startsWith(needle) -> 80
                lower.contains(needle) -> 50
                app.packageName.lowercase().contains(needle) -> 30
                else -> return@mapNotNull null
            }
            AppHit(app.packageName, label, score)
        }.sortedByDescending { it.score }
    }

    private fun horizontalRow() = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        isFocusable = false
        clipChildren = false
        clipToPadding = false
    }

    private fun wrapHorizontalScroll(row: LinearLayout): HorizontalScrollView {
        val d = density
        // Fixed single-row height — never grows with result count
        val rowH = (96 * d).toInt()
        return HorizontalScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                rowH
            )
            isHorizontalScrollBarEnabled = false
            isFocusable = false
            clipToPadding = false
            clipChildren = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            setPadding(0, (2 * d).toInt(), 0, (6 * d).toInt())
            // Row itself is horizontal only — no wrapping
            row.orientation = LinearLayout.HORIZONTAL
            addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            ))
        }
    }

    // ─── Tiles (compact) ───

    private fun appTile(hit: AppHit): View {
        val d = density
        val tileW = (72 * d).toInt()
        val tile = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(tileW, LinearLayout.LayoutParams.WRAP_CONTENT)
                .also { it.marginEnd = (8 * d).toInt() }
            setPadding((4 * d).toInt(), (6 * d).toInt(), (4 * d).toInt(), (6 * d).toInt())
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            background = tileBg(false)
        }
        val iconS = (40 * d).toInt()
        tile.addView(ImageView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(iconS, iconS).also { it.gravity = Gravity.CENTER_HORIZONTAL }
            scaleType = ImageView.ScaleType.FIT_CENTER
            isFocusable = false
            try {
                setImageDrawable(activity.packageManager.getApplicationIcon(hit.packageName))
            } catch (_: Exception) {
                setImageResource(R.drawable.ic_android_robot)
            }
        })
        tile.addView(TextView(activity).apply {
            text = hit.label
            setTextColor(colorWhite)
            textSize = 10f
            maxLines = 1
            isSingleLine = true
            gravity = Gravity.CENTER_HORIZONTAL
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, (4 * d).toInt(), 0, 0)
            isFocusable = false
        })
        tile.setOnFocusChangeListener { v, has ->
            v.background = tileBg(has)
            if (has) {
                scrollHorizontallyTo(v)
                scrollVerticallyTo(v)
            }
        }
        tile.setOnClickListener { launchApp(hit.packageName) }
        return tile
    }

    private fun fileTile(hit: FileHit): View {
        val d = density
        val tileW = (88 * d).toInt()
        val tile = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(tileW, LinearLayout.LayoutParams.WRAP_CONTENT)
                .also { it.marginEnd = (8 * d).toInt() }
            setPadding((4 * d).toInt(), (6 * d).toInt(), (4 * d).toInt(), (6 * d).toInt())
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            background = tileBg(false)
        }
        tile.addView(ImageView(activity).apply {
            val s = (28 * d).toInt()
            layoutParams = LinearLayout.LayoutParams(s, s).also { it.gravity = Gravity.CENTER_HORIZONTAL }
            setImageResource(R.drawable.ic_grid)
            setColorFilter(if (hit.file.isDirectory) colorAccent else colorWhite)
            isFocusable = false
        })
        tile.addView(TextView(activity).apply {
            text = hit.file.name
            setTextColor(colorWhite)
            textSize = 10f
            maxLines = 1
            isSingleLine = true
            gravity = Gravity.CENTER_HORIZONTAL
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, (4 * d).toInt(), 0, 0)
            isFocusable = false
        })
        tile.addView(TextView(activity).apply {
            text = if (hit.file.isDirectory) "Folder" else (hit.file.parentFile?.name ?: "File")
            setTextColor(colorMuted)
            textSize = 9f
            maxLines = 1
            gravity = Gravity.CENTER_HORIZONTAL
            ellipsize = android.text.TextUtils.TruncateAt.END
            isFocusable = false
        })
        tile.setOnFocusChangeListener { v, has ->
            v.background = tileBg(has)
            if (has) {
                scrollHorizontallyTo(v)
                scrollVerticallyTo(v)
            }
        }
        tile.setOnClickListener { openFile(hit.file) }
        return tile
    }

    private fun webRow(q: String): View {
        val d = density
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (44 * d).toInt()
            )
            setPadding((8 * d).toInt(), 0, (8 * d).toInt(), 0)
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            background = tileBg(false)
        }
        row.addView(ImageView(activity).apply {
            val s = (22 * d).toInt()
            layoutParams = LinearLayout.LayoutParams(s, s)
            setImageResource(R.drawable.ic_search)
            setColorFilter(colorAccent)
            isFocusable = false
        })
        val texts = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .also { it.marginStart = (10 * d).toInt() }
            isFocusable = false
        }
        texts.addView(TextView(activity).apply {
            text = "Search the web for \"$q\""
            setTextColor(colorWhite)
            textSize = 13f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            isFocusable = false
        })
        texts.addView(TextView(activity).apply {
            text = "Opens in your default browser"
            setTextColor(colorMuted)
            textSize = 10f
            isFocusable = false
        })
        row.addView(texts)
        row.setOnFocusChangeListener { v, has ->
            v.background = tileBg(has)
            if (has) scrollVerticallyTo(v)
        }
        row.setOnClickListener { openWebSearch(q) }
        return row
    }

    private fun tileBg(focused: Boolean) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 8 * density
        setColor(if (focused) 0x334FC3F7 else 0x14FFFFFF)
        if (focused) setStroke((2 * density).toInt(), colorAccent)
        else setStroke((1 * density).toInt(), 0x22FFFFFF)
    }

    private fun sectionHeader(title: String): View {
        val d = density
        return TextView(activity).apply {
            text = title.uppercase()
            setTextColor(colorAccent)
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.06f
            setPadding(0, (8 * d).toInt(), 0, (4 * d).toInt())
            isFocusable = false
        }
    }

    private fun hintRow(text: String): View {
        val d = density
        return TextView(activity).apply {
            this.text = text
            setTextColor(colorMuted)
            textSize = 12f
            setPadding((4 * d).toInt(), (4 * d).toInt(), (4 * d).toInt(), (4 * d).toInt())
            isFocusable = false
        }
    }

    // ─── Focus: one region, cross-section ───

    private fun wireCrossSection(apps: List<View>, files: List<View>, web: View?) {
        val inputId = activity.findViewById<View>(R.id.searchInput)?.let {
            ensureId(it); it.id
        } ?: View.NO_ID

        // Ensure every tile has an id before linking
        apps.forEach { ensureId(it) }
        files.forEach { ensureId(it) }
        web?.let { ensureId(it) }

        val firstApp: View? = apps.firstOrNull()
        val firstFile: View? = files.firstOrNull()

        fun wireHorizontal(tiles: List<View>) {
            tiles.forEachIndexed { i, v ->
                v.nextFocusLeftId = if (i == 0) v.id else tiles[i - 1].id
                v.nextFocusRightId = if (i == tiles.lastIndex) v.id else tiles[i + 1].id
            }
        }
        wireHorizontal(apps)
        wireHorizontal(files)

        // Vertical nextFocus* for framework path
        apps.forEach { a ->
            a.nextFocusUpId = if (inputId != View.NO_ID) inputId else a.id
            a.nextFocusDownId = when {
                files.isNotEmpty() -> files.first().id
                web != null -> web.id
                else -> a.id
            }
        }
        files.forEach { f ->
            f.nextFocusUpId = when {
                apps.isNotEmpty() -> apps.first().id
                inputId != View.NO_ID -> inputId
                else -> f.id
            }
            f.nextFocusDownId = if (web != null) web.id else f.id
        }
        if (web != null) {
            web.nextFocusLeftId = web.id
            web.nextFocusRightId = web.id
            web.nextFocusDownId = web.id
            web.nextFocusUpId = when {
                files.isNotEmpty() -> files.first().id
                apps.isNotEmpty() -> apps.first().id
                inputId != View.NO_ID -> inputId
                else -> web.id
            }
        }

        apps.forEachIndexed { i, v ->
            v.setOnKeyListener { view, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        view.performClick(); true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> if (i == 0) true else false
                    KeyEvent.KEYCODE_DPAD_RIGHT -> if (i == apps.lastIndex) true else false
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        if (inputId != View.NO_ID) {
                            activity.findViewById<View>(R.id.searchInput)?.requestFocus()
                            true
                        } else true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        val target = firstFile ?: web
                        if (target != null) {
                            target.requestFocus()
                            scrollVerticallyTo(target)
                            true
                        } else true
                    }
                    else -> false
                }
            }
        }
        files.forEachIndexed { i, v ->
            v.setOnKeyListener { view, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        view.performClick(); true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> if (i == 0) true else false
                    KeyEvent.KEYCODE_DPAD_RIGHT -> if (i == files.lastIndex) true else false
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        val target = firstApp
                        if (target != null) {
                            target.requestFocus()
                            scrollVerticallyTo(target)
                            scrollHorizontallyTo(target)
                            true
                        } else if (inputId != View.NO_ID) {
                            activity.findViewById<View>(R.id.searchInput)?.requestFocus()
                            true
                        } else true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (web != null) {
                            web.requestFocus()
                            scrollVerticallyTo(web)
                            true
                        } else true
                    }
                    else -> false
                }
            }
        }
        web?.setOnKeyListener { view, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    view.performClick(); true
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN -> true
                KeyEvent.KEYCODE_DPAD_UP -> {
                    val target = firstFile ?: firstApp
                    if (target != null) {
                        target.requestFocus()
                        scrollVerticallyTo(target)
                        scrollHorizontallyTo(target)
                        true
                    } else if (inputId != View.NO_ID) {
                        activity.findViewById<View>(R.id.searchInput)?.requestFocus()
                        true
                    } else true
                }
                else -> false
            }
        }
    }

    private fun ensureId(v: View) {
        if (v.id == View.NO_ID) v.id = View.generateViewId()
    }

    private fun findFirstFocusable(root: ViewGroup): View? {
        for (i in 0 until root.childCount) {
            val c = root.getChildAt(i)
            if (c.isFocusable) return c
            if (c is ViewGroup) findFirstFocusable(c)?.let { return it }
        }
        return null
    }

    private fun scrollHorizontallyTo(tile: View) {
        var p = tile.parent
        while (p is ViewGroup && p !is HorizontalScrollView) p = p.parent
        val hsv = p as? HorizontalScrollView ?: return
        hsv.post {
            val left = tile.left - (16 * density).toInt()
            hsv.smoothScrollTo(left.coerceAtLeast(0), 0)
        }
    }

    private fun scrollVerticallyTo(tile: View) {
        val scroll = scrollView ?: return
        val host = listHost ?: return
        scroll.post {
            val rect = android.graphics.Rect()
            tile.getDrawingRect(rect)
            try {
                host.offsetDescendantRectToMyCoords(tile, rect)
            } catch (_: Exception) {
                return@post
            }
            scroll.requestChildRectangleOnScreen(host, rect, false)
        }
    }

    // ─── File search ───

    private fun scheduleFileSearch(q: String, gen: Int) {
        cancelFileSearch()
        fileJob = io.submit {
            val hits = scanFiles(q)
            mainHandler.post {
                if (!visible || gen != queryGen.get() || lastQuery != q) return@post
                renderResults(q, filesLoading = false, files = hits)
            }
        }
    }

    private fun cancelFileSearch() {
        fileJob?.cancel(true)
        fileJob = null
    }

    private fun scanFiles(q: String): List<FileHit> {
        val needle = q.lowercase()
        val roots = mutableListOf<File>()
        try {
            Environment.getExternalStorageDirectory()?.let { roots += it }
        } catch (_: Exception) {}
        try {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)?.let { roots += it }
        } catch (_: Exception) {}
        try {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)?.let { roots += it }
        } catch (_: Exception) {}
        try {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)?.let { roots += it }
        } catch (_: Exception) {}
        try {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)?.let { roots += it }
        } catch (_: Exception) {}
        activity.getExternalFilesDir(null)?.let { roots += it }

        val out = ArrayList<FileHit>(32)
        val visited = HashSet<String>()
        for (root in roots.distinctBy { it.absolutePath }) {
            if (Thread.currentThread().isInterrupted) break
            walk(root, needle, out, visited, 0, 4, 40)
            if (out.size >= 40) break
        }
        return out.sortedByDescending { it.score }.take(24)
    }

    private fun walk(
        dir: File, needle: String, out: MutableList<FileHit>,
        visited: MutableSet<String>, depth: Int, maxDepth: Int, maxHits: Int
    ) {
        if (depth > maxDepth || out.size >= maxHits) return
        val path = try { dir.absolutePath } catch (_: Exception) { return }
        if (!visited.add(path)) return
        val children = try { dir.listFiles() ?: return } catch (_: Exception) { return }
        for (f in children) {
            if (Thread.currentThread().isInterrupted || out.size >= maxHits) return
            val name = f.name ?: continue
            val lower = name.lowercase()
            if (lower.startsWith(".")) continue
            val score = when {
                lower == needle -> 100
                lower.startsWith(needle) -> 80
                lower.contains(needle) -> 50
                else -> 0
            }
            if (score > 0) out += FileHit(f, score)
            if (f.isDirectory && depth < maxDepth) {
                if (name.equals("Android", true) && depth == 0) continue
                walk(f, needle, out, visited, depth + 1, maxDepth, maxHits)
            }
        }
    }

    private fun notifyCloseAfterSelection() {
        // Prefer Shell-level full close (bar + query + panel + focus)
        val cb = onRequestClose
        if (cb != null) cb() else hide()
    }

    private fun launchApp(pkg: String) {
        try {
            val intent = activity.packageManager.getLaunchIntentForPackage(pkg)
                ?: activity.appRepository.launchIntentFor(pkg)
            if (intent != null) {
                activity.startActivity(intent)
                notifyCloseAfterSelection()
            } else Toast.makeText(activity, "Cannot launch app", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(activity, "Cannot launch: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openFile(file: File) {
        if (file.isDirectory) {
            // Open this exact directory in the launcher's file manager
            try {
                activity.showTab(MainActivity.Tab.FILES)
                activity.fileManager.openDirectory(file, focusFirst = true)
                notifyCloseAfterSelection()
            } catch (e: Exception) {
                Toast.makeText(activity, "Cannot open folder: ${e.message}", Toast.LENGTH_SHORT).show()
            }
            return
        }
        val mime = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
        try {
            @Suppress("DEPRECATION")
            val uri = Uri.fromFile(file)
            activity.startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            notifyCloseAfterSelection()
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(activity, "No app can open this file", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(activity, "Cannot open: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
