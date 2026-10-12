package com.appsventory.digiapps.launchpad4.ui.files

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.appsventory.digiapps.launchpad4.R

class FilesFilterRow @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val icon: TextView
    private val label: TextView

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isFocusable = true

        val padding = (5 * resources.displayMetrics.density).toInt()
        setPadding(padding, padding, padding, padding)

        setBackgroundResource(R.drawable.bg_hover_solid)

        inflate(context, R.layout.view_files_sidebar_filter_row, this)

        icon = findViewById(R.id.filterIcon)
        label = findViewById(R.id.filterLabel)

        context.obtainStyledAttributes(
            attrs,
            R.styleable.FilesFilterRow
        ).apply {
            try {
                icon.text = getString(
                    R.styleable.FilesFilterRow_iconText
                ) ?: ""

                label.text = getString(
                    R.styleable.FilesFilterRow_labelText
                ) ?: ""

                getDrawable(
                    R.styleable.FilesFilterRow_iconBackground
                )?.let {
                    icon.background = it
                }
            } finally {
                recycle()
            }
        }
    }
}