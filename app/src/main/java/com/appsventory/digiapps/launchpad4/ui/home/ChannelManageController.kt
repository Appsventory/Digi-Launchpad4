package com.appsventory.digiapps.launchpad4.ui.home

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.appsventory.digiapps.launchpad4.MainActivity
import com.appsventory.digiapps.launchpad4.R
import com.appsventory.digiapps.launchpad4.data.PrefsKeys

/**
 * Channel order sidebar (list-based).
 *
 * Open: Left on first card of a Home channel row.
 * Normal: vertical list of currently visible channels (icon + name).
 * OK: enter Edit Order mode (↕ beside each row).
 * Up/Down in edit mode: move selected channel, update Home, auto-save.
 * Right / Back: close sidebar (edit mode exits first on Back if desired —
 *   here Right and Back both close; changes already saved).
 */
class ChannelManageController(private val activity: MainActivity) {

    private enum class State { CLOSED, LIST, EDIT }

    private var state = State.CLOSED
    private val density = activity.resources.displayMetrics.density

    private var panel: LinearLayout? = null
    private var listHost: LinearLayout? = null
    private var hintView: TextView? = null
    private val shiftViews = mutableListOf<View>()
    /** Home channel id that opened the sidebar (stable across Home rebuilds). */
    private var restoreChannelId: Long = -1L
    private var restoreFocusView: View? = null

    /** Ordered channel ids currently shown in the sidebar (= Home order). */
    private val order = mutableListOf<Long>()
    private var selectedId: Long = -1L
    private val rowViews = mutableMapOf<Long, View>()

    /** Max content width; actual shift uses measured width after layout. */
    private val panelMaxWidthPx get() = (152 * density).toInt()

    fun isOpen(): Boolean = state != State.CLOSED
    fun isReorderMode(): Boolean = state == State.EDIT

    // ─── Open / close ───

    fun open(from: View?, channelId: Long) {
        if (state != State.CLOSED) return
        restoreChannelId = channelId
        restoreFocusView = from
        selectedId = channelId
        ensurePanel()
        reloadOrderFromHome()
        if (order.isEmpty()) {
            // Nothing to manage
            restoreChannelId = -1L
            restoreFocusView = null
            return
        }
        if (selectedId !in order) selectedId = order.first()
        renderList()
        state = State.LIST
        animateOpen()
        panel?.post { rowViews[selectedId]?.requestFocus() }
    }

    fun close(restore: Boolean = true) {
        if (state == State.CLOSED) return
        state = State.CLOSED
        animateClose()
        if (restore) {
            val id = restoreChannelId
            val fallback = restoreFocusView
            restoreChannelId = -1L
            restoreFocusView = null
            // Prefer stable channel id — Home rows may have been rebuilt during reorder
            activity.restoreFocusToChannel(id, fallback)
        } else {
            restoreChannelId = -1L
            restoreFocusView = null
        }
    }

    fun handleBack(): Boolean {
        if (state == State.CLOSED) return false
        // Changes already auto-saved; close sidebar
        close(restore = true)
        return true
    }

    /**
     * Keys that landed on Home channel cards while the sidebar is open.
     * Consume them so focus/actions stay with the sidebar.
     */
    fun handleReorderKey(keyCode: Int, event: KeyEvent): Boolean {
        if (state == State.CLOSED) return false
        if (event.action != KeyEvent.ACTION_DOWN) return true
        // Prefer returning focus to the sidebar selection
        rowViews[selectedId]?.requestFocus()
        if (state == State.EDIT) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> moveSelected(-1)
                KeyEvent.KEYCODE_DPAD_DOWN -> moveSelected(+1)
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> exitEdit()
                KeyEvent.KEYCODE_DPAD_RIGHT -> close(restore = true)
                else -> {}
            }
        } else if (state == State.LIST) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> enterEdit()
                KeyEvent.KEYCODE_DPAD_RIGHT -> close(restore = true)
                else -> {}
            }
        }
        return true
    }

    // ─── Panel UI ───

    private fun ensurePanel() {
        if (panel != null) return
        val root = activity.findViewById<ViewGroup>(R.id.root) ?: return
        shiftViews.clear()
        // Single authoritative Shell offset: translate ONLY mainContent.
        // topBar (Shell Navigation) is a child of mainContent (activity_main.xml
        // includes view_top_bar inside it), so it already moves with its parent.
        // Translating topBar as well would apply the shift twice to the nav.
        val main = activity.findViewById<View>(R.id.mainContent)
        if (main != null) {
            shiftViews += main
        } else {
            // Fallback only when mainContent is missing: topBar and homeContent
            // are then independent, so shift each once.
            activity.findViewById<View>(R.id.topBar)?.let { shiftViews += it }
            activity.findViewById<View>(R.id.homeContent)?.let { shiftViews += it }
        }

        val d = density
        val maxW = panelMaxWidthPx

        val p = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(maxW, FrameLayout.LayoutParams.MATCH_PARENT)
                .apply { gravity = Gravity.START }
            setBackgroundColor(0xF0121418.toInt())
            elevation = 22 * d
            visibility = View.GONE
            translationX = -maxW.toFloat()
            setPadding((10 * d).toInt(), (24 * d).toInt(), (10 * d).toInt(), (12 * d).toInt())
            isFocusable = false
            minimumWidth = (120 * d).toInt()
        }

        p.addView(TextView(activity).apply {
            text = "Channel Order"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, (12 * d).toInt())
            isFocusable = false
        })

        val scroll = ScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            isFocusable = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        listHost = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = false
        }
        scroll.addView(listHost)
        p.addView(scroll)

        hintView = TextView(activity).apply {
            text = "OK: Edit order  ·  Right: Close"
            setTextColor(0x99FFFFFF.toInt())
            textSize = 11f
            setPadding(0, (12 * d).toInt(), 0, 0)
            isFocusable = false
        }
        p.addView(hintView)

        root.addView(p)
        panel = p
    }

    /** One offset for the whole Shell (nav + content). Never stack translations. */
    private fun shellShiftPx(): Float {
        val p = panel
        val measured = p?.width ?: 0
        // Use measured width only — never a screen fraction or stacked offset
        return if (measured > 0) measured.toFloat() else panelMaxWidthPx.toFloat()
    }

    private fun applyShellShift(distance: Float, animate: Boolean, duration: Long) {
        // Clear any leftover translation on homeContent (old partial targets)
        activity.findViewById<View>(R.id.homeContent)?.translationX = 0f
        shiftViews.forEach { v ->
            if (animate) {
                v.animate().translationX(distance).setDuration(duration)
                    .setInterpolator(DecelerateInterpolator()).start()
            } else {
                v.animate().cancel()
                v.translationX = distance
            }
        }
    }

    private fun animateOpen() {
        val p = panel ?: return
        val approx = panelMaxWidthPx.toFloat()
        p.visibility = View.VISIBLE
        p.translationX = -approx
        // Measure after visibility so shift equals real sidebar width only
        p.post {
            val shift = shellShiftPx()
            p.translationX = -shift
            p.animate().translationX(0f).setDuration(200)
                .setInterpolator(DecelerateInterpolator()).start()
            applyShellShift(shift, animate = true, duration = 200)
        }
    }

    private fun animateClose() {
        val p = panel ?: return
        val shift = shellShiftPx().coerceAtLeast(panelMaxWidthPx.toFloat())
        p.animate().translationX(-shift).setDuration(180)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                p.visibility = View.GONE
                p.translationX = 0f
            }.start()
        applyShellShift(0f, animate = true, duration = 180)
    }

    // ─── Data ───

    private fun reloadOrderFromHome() {
        order.clear()
        order.addAll(enabledOrder())
    }

    private fun enabledOrder(): List<Long> {
        val enabled = activity.customChannelIds.toList()
        val pref = activity.prefs.getString(PrefsKeys.CUSTOM_CHANNEL_ORDER, null)
            ?.split(",")?.mapNotNull { it.trim().toLongOrNull() }.orEmpty()
        if (pref.isEmpty()) return enabled
        val set = enabled.toSet()
        val ordered = pref.filter { it in set }.toMutableList()
        for (id in enabled) if (id !in ordered) ordered += id
        return ordered
    }

    private fun persistOrder() {
        activity.customChannelIds.clear()
        activity.customChannelIds.addAll(order)
        activity.customChannelEnabled = order.isNotEmpty()
        activity.prefs.edit()
            .putString(PrefsKeys.CUSTOM_CHANNEL_ORDER, order.joinToString(","))
            .putString(PrefsKeys.CUSTOM_CHANNEL_IDS, order.joinToString(","))
            .putBoolean(PrefsKeys.CUSTOM_CHANNEL_ENABLED, order.isNotEmpty())
            .apply()
    }

    private fun channelMeta(id: Long): Pair<String, android.graphics.drawable.Drawable?> {
        return try {
            val ch = activity.tvChannelRepository.listChannels().find { it.id == id }
            val name = ch?.displayName ?: "Channel"
            val icon = try {
                ch?.packageName?.let { pkg ->
                    activity.packageManager.getApplicationIcon(pkg)
                }
            } catch (_: Exception) {
                null
            }
            name to icon
        } catch (_: Exception) {
            "Channel" to null
        }
    }

    // ─── List render ───

    private fun renderList() {
        val host = listHost ?: return
        host.removeAllViews()
        rowViews.clear()
        val editing = state == State.EDIT
        hintView?.text = if (editing) {
            "Up/Down: Move  ·  Right: Close"
        } else {
            "OK: Edit order  ·  Right: Close"
        }
        order.forEach { id ->
            host.addView(buildRow(id, editing))
        }
    }

    private fun buildRow(channelId: Long, editing: Boolean): View {
        val d = density
        val (name, icon) = channelMeta(channelId)
        val selected = channelId == selectedId

        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (48 * d).toInt()
            ).also { it.bottomMargin = (6 * d).toInt() }
            setPadding((10 * d).toInt(), (6 * d).toInt(), (10 * d).toInt(), (6 * d).toInt())
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            background = rowBg(focused = false, selected = selected && editing)
            tag = channelId
        }

        if (editing) {
            row.addView(TextView(activity).apply {
                text = "↕"
                textSize = 16f
                setTextColor(0xFF4FC3F7.toInt())
                setPadding(0, 0, (8 * d).toInt(), 0)
                isFocusable = false
            })
        }

        row.addView(ImageView(activity).apply {
            layoutParams = LinearLayout.LayoutParams((28 * d).toInt(), (28 * d).toInt())
                .also { it.marginEnd = (10 * d).toInt() }
            if (icon != null) setImageDrawable(icon)
            else setImageResource(R.drawable.ic_grid)
            scaleType = ImageView.ScaleType.CENTER_CROP
            isFocusable = false
        })

        row.addView(TextView(activity).apply {
            text = name
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            maxWidth = (110 * d).toInt()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            isFocusable = false
        })

        row.setOnFocusChangeListener { v, has ->
            if (has) selectedId = channelId
            v.background = rowBg(has, selected = editing && (has || channelId == selectedId))
            // White focus pill → dark text/icon for contrast
            val ll = v as LinearLayout
            for (i in 0 until ll.childCount) {
                when (val c = ll.getChildAt(i)) {
                    is TextView -> c.setTextColor(
                        if (has) 0xFF15161B.toInt() else if (c.text == "↕") 0xFF4FC3F7.toInt()
                        else 0xFFFFFFFF.toInt()
                    )
                    is ImageView -> c.imageAlpha = if (has) 255 else 255
                }
            }
        }

        row.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (state) {
                State.LIST -> when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        enterEdit(); true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        close(restore = true); true
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        moveFocus(-1); true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        moveFocus(+1); true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> true
                    else -> false
                }
                State.EDIT -> when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        moveSelected(-1); true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        moveSelected(+1); true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        // Exit edit mode; keep sidebar open so another channel can be selected
                        exitEdit(); true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        close(restore = true); true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> true
                    else -> false
                }
                else -> false
            }
        }

        rowViews[channelId] = row
        return row
    }

    private fun rowBg(focused: Boolean, selected: Boolean) = GradientDrawable().apply {
        cornerRadius = 10 * density
        when {
            focused -> {
                setColor(0xFFFFFFFF.toInt())
                setStroke((2 * density).toInt(), 0xFF4FC3F7.toInt())
            }
            selected -> {
                setColor(0x334FC3F7)
                setStroke((1 * density).toInt(), 0xFF4FC3F7.toInt())
            }
            else -> {
                setColor(0x18FFFFFF)
                setStroke((1 * density).toInt(), 0x22FFFFFF)
            }
        }
    }

    private fun moveFocus(delta: Int) {
        val i = order.indexOf(selectedId)
        if (i < 0) return
        val ni = (i + delta).coerceIn(0, order.lastIndex)
        selectedId = order[ni]
        rowViews[selectedId]?.requestFocus()
    }

    private fun enterEdit() {
        if (state != State.LIST) return
        // Update indicators in place — do NOT removeAllViews (that sends focus to Profile)
        state = State.EDIT
        applyEditChrome(editing = true)
    }

    /** OK in Edit Order: leave edit mode, keep sidebar open. */
    private fun exitEdit() {
        if (state != State.EDIT) return
        state = State.LIST
        applyEditChrome(editing = false)
    }

    /**
     * Toggle ↕ indicators and hint without recreating focusable rows.
     * Recreating rows is what caused the Profile focus flicker.
     */
    private fun applyEditChrome(editing: Boolean) {
        hintView?.text = if (editing) {
            "Up/Down: Move  ·  OK: Done  ·  Right: Close"
        } else {
            "OK: Edit order  ·  Right: Close"
        }
        val d = density
        order.forEach { id ->
            val row = rowViews[id] as? LinearLayout ?: return@forEach
            val first = row.getChildAt(0)
            val isArrow = first is TextView && (first as TextView).text == "↕"
            if (editing && !isArrow) {
                row.addView(TextView(activity).apply {
                    text = "↕"
                    textSize = 16f
                    setTextColor(0xFF4FC3F7.toInt())
                    setPadding(0, 0, (8 * d).toInt(), 0)
                    isFocusable = false
                    tag = "edit_arrow"
                }, 0)
            } else if (!editing && isArrow) {
                row.removeViewAt(0)
            }
            // Refresh background for selected state without stealing focus
            val has = row.isFocused
            row.background = rowBg(has, selected = editing && (has || id == selectedId))
        }
        // Focus stays on the already-focused row — no requestFocus needed
    }

    private fun moveSelected(delta: Int) {
        val from = order.indexOf(selectedId)
        if (from < 0) return
        val to = (from + delta).coerceIn(0, order.lastIndex)
        if (from == to) return
        val id = order.removeAt(from)
        order.add(to, id)
        selectedId = id

        // Hold focus on the panel root while Home tears down channel views.
        // Otherwise removeAllViews() sends focus to Profile (first Shell focusable).
        val anchor = panel
        if (anchor != null) {
            anchor.isFocusable = true
            anchor.isFocusableInTouchMode = true
            anchor.requestFocus()
        }

        persistOrder()
        activity.loadCustomChannelRow()

        state = State.EDIT
        renderList()

        val target = rowViews[selectedId] ?: rowViews.values.firstOrNull()
        if (target != null) {
            target.requestFocus()
            // Re-assert after layout so Profile never wins the race
            target.post {
                if (state == State.EDIT && !target.isFocused) {
                    target.requestFocus()
                }
            }
        }
        // Panel itself need not remain focusable once a row has focus
        anchor?.isFocusable = false
    }
}
