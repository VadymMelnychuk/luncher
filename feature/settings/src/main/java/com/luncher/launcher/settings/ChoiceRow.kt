package com.luncher.launcher.settings

import android.view.View
import android.widget.TextView
import com.luncher.domain.appearance.Appearance

/**
 * A row of the background editor for a value that isn't an option of the catalog (the
 * slideshow's source and interval, which [OptionRow] can't show): its name ([label]), and its
 * value, marked as [OptionRow] marks a listed option's; OK opens its [values] in a list
 * ([OptionList]) over it, each [set] into the draft's appearance and named by [name], the preview
 * following the focus as for an option. It's dimmed, and the focus passes it by, while it doesn't
 * [apply][applies].
 */
internal class ChoiceRow<T>(
    val view: View,
    label: Int,
    private val values: () -> List<T>,
    private val get: (Appearance) -> T,
    private val set: (Appearance, T) -> Appearance,
    private val name: (T) -> String,
    private val appearance: () -> Appearance,
    private val change: (Appearance) -> Unit,
    private val applies: (Appearance) -> Boolean = { true },
) {

    private val value = view.findViewById<TextView>(R.id.settings_option_value)
    private var list: OptionList? = null

    init {
        view.findViewById<TextView>(R.id.settings_option_label).setText(label)
        view.findViewById<View>(R.id.settings_option_previous).visibility = View.GONE
        view.findViewById<View>(R.id.settings_option_next).visibility = View.GONE
        value.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.settings_option_list_mark, 0)
        value.compoundDrawablePadding = view.resources.getDimensionPixelSize(R.dimen.settings_option_list_mark_gap)
        view.setOnClickListener { open() }
    }

    private fun open() {
        val appearance = appearance()
        val values = values()
        list = OptionList(view, values.map { set(appearance, it) }, values.map(name), values.indexOf(get(appearance)).coerceAtLeast(0), appearance, change)
    }

    /** Closes the values' list if it shows, the row keeping the value it shows: the screen is going. */
    fun closeList() {
        list?.close()
        list = null
    }

    fun show() {
        val appearance = appearance()
        val applies = applies(appearance)
        value.text = name(get(appearance))
        view.alpha = if (applies) 1f else DIMMED
        view.isFocusable = applies
        view.isFocusableInTouchMode = applies
    }

    private companion object {
        const val DIMMED = 0.4f
    }
}
