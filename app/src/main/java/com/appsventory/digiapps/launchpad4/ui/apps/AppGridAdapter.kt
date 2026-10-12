package com.appsventory.digiapps.launchpad4.ui.apps

import android.content.Context
import android.content.pm.ApplicationInfo
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView
import com.appsventory.digiapps.launchpad4.R

class AppGridAdapter(
    private val context: Context,
    private val apps: List<ApplicationInfo>,          // hanya yang punya banner
    private val otherAppsCount: Int,                   // jumlah app tanpa banner
    private val binder: AppCardBinder,
    private val onClick: (ApplicationInfo) -> Unit,
    private val onLongClick: (ApplicationInfo) -> Unit,
    private val onOtherAppsClick: () -> Unit           // buka modal
) : BaseAdapter() {

    private val numColumns: Int by lazy {
        context.resources.getInteger(R.integer.apps_grid_num_columns)
    }

    private val showOtherCard = otherAppsCount > 0

    override fun getCount() = apps.size + if (showOtherCard) 1 else 0
    override fun getItem(position: Int): Any =
        if (position < apps.size) apps[position] else "OTHER"
    override fun getItemId(position: Int) = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view = convertView
            ?: LayoutInflater.from(context).inflate(R.layout.item_app, parent, false)

        view.animate().cancel()
        view.scaleX = 1f
        view.scaleY = 1f
        view.alpha = 0.92f
        view.elevation = 0f

        val nameBelow = view.findViewById<TextView>(R.id.appNameBelow)
        nameBelow?.alpha = 0f
        nameBelow?.translationY = 0f

        val col = if (numColumns > 0) position % numColumns else 0
        val pivotFractionX = when {
            col == 0 -> 0f
            col == numColumns - 1 -> 1f
            else -> 0.5f
        }

        if (position < apps.size) {
            // Kartu app biasa (punya banner)
            val app = apps[position]
            binder.bind(view, app) { onClick(app) }
            binder.applyRoundClip(view)
            binder.setupScaleFocusAnimation(view, pivotFractionX)
            view.setOnLongClickListener { onLongClick(app); true }
            view.tag = position
        } else {
            // Kartu "Other apps" — style banner, icon ic_grid
            bindOtherAppsCard(view, pivotFractionX)
        }

        if (view.isFocused) {
            view.scaleX = 1.18f
            view.scaleY = 1.18f
            view.alpha = 1f
            view.elevation = 8f
            val density = view.resources.displayMetrics.density
            nameBelow?.translationY = 28f * density
            nameBelow?.alpha = 1f
        }

        return view
    }

    private fun bindOtherAppsCard(view: View, pivotFractionX: Float) {
        val bannerView = view.findViewById<ImageView>(R.id.appBanner)
        val iconMode = view.findViewById<View>(R.id.appIconMode)
        val icon = view.findViewById<ImageView>(R.id.appIcon)
        val nameBelow = view.findViewById<TextView>(R.id.appNameBelow)

        // Mode icon (bukan banner image)
        bannerView?.visibility = View.GONE
        iconMode?.visibility = View.VISIBLE

        icon?.setImageResource(R.drawable.ic_grid)
        icon?.clearColorFilter()
        // Perbesar sedikit biar mirip banner center
        icon?.layoutParams = icon?.layoutParams?.apply {
            width = (56 * view.resources.displayMetrics.density).toInt()
            height = (56 * view.resources.displayMetrics.density).toInt()
        }

        nameBelow?.text = "Other apps ($otherAppsCount)"

        view.setOnClickListener { onOtherAppsClick() }
        view.setOnLongClickListener(null)
        view.tag = "other_apps"

        binder.applyRoundClip(view)
        binder.setupScaleFocusAnimation(view, pivotFractionX)
    }
}