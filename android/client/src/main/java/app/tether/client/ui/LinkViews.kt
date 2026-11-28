package app.tether.client.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.tether.client.R

/** Views and dimensions for the Link tab, in the app's palette (res/values/colors.xml). */
internal class LinkViews(private val context: Context) {
    val background = context.getColor(R.color.background)
    private val surface = context.getColor(R.color.surface)
    private val secondary = context.getColor(R.color.background)   // icon tiles: the page colour on a card
    private val divider = (context.getColor(R.color.muted) and 0x00FFFFFF) or (0x40 shl 24)   // muted at 25 %
    val accent = context.getColor(R.color.accent)
    private val muted = context.getColor(R.color.muted)
    private val connected = context.getColor(R.color.ok)
    private val textColor = context.getColor(R.color.text)
    private val radius = 23
    private val gap = 15

    fun shape(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = context.dp(radius).toFloat()
    }

    private fun text(value: CharSequence, size: Float, color: Int = textColor, bold: Boolean = false) =
        TextView(context).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun icon(res: Int, size: Int, color: Int = textColor) = ImageView(context).apply {
        setImageResource(res)
        setColorFilter(color)
        layoutParams = LinearLayout.LayoutParams(context.dp(size), context.dp(size))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun tile(res: Int, size: Int, glyph: Int, color: Int, tint: Int = textColor) =
        FrameLayout(context).apply {
            background = shape(color, size / 2)
            layoutParams = LinearLayout.LayoutParams(context.dp(size), context.dp(size))
            addView(icon(res, glyph, tint), FrameLayout.LayoutParams(context.dp(glyph), context.dp(glyph), Gravity.CENTER))
        }

    private fun horizontal() = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    private fun space(width: Int, height: Int) = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(context.dp(width), context.dp(height))
    }

    fun card(padding: Int = 0, margin: Int = gap) = column().apply {
        background = shape(surface, radius)
        setPadding(context.dp(padding), context.dp(padding), context.dp(padding), context.dp(padding))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = context.dp(margin) }
    }

    fun heading(
        parent: LinearLayout,
        title: Int = R.string.tab_link,
        subtitle: Int = R.string.link_subtitle,
        compact: Boolean = false,
        overflow: (View) -> Unit,
    ) {
        parent.addView(horizontal().apply {
            addView(icon(R.drawable.ic_link_chain, if (compact) 23 else 28, accent))
            addView(space(if (compact) 8 else 12, 0))
            addView(text(context.getString(R.string.link_brand), if (compact) 18f else 23f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
            addView(tile(R.drawable.ic_link_overflow, 48, if (compact) 19 else 22, Color.TRANSPARENT).apply {
                contentDescription = context.getString(R.string.link_more_options)
                isFocusable = true
                setOnClickListener(overflow)
            })
        })
        parent.addView(space(0, if (compact) 3 else 8))
        parent.addView(text(context.getString(title), if (compact) 28f else 36f, bold = true))
        parent.addView(text(context.getString(subtitle), if (compact) 14f else 18f, muted))
        parent.addView(space(0, if (compact) 8 else 14))
    }

    fun host(name: String, state: String, isConnected: Boolean, battery: String?, transport: String?) =
        card(14).apply {
            addView(horizontal().apply {
                addView(icon(R.drawable.ic_link_phone, 48, accent), LinearLayout.LayoutParams(context.dp(48), context.dp(64)))
                addView(space(20, 0))
                addView(column().apply {
                    addView(text(name, 21f, bold = true))
                    addView(horizontal().apply {
                        val tint = if (isConnected) connected else muted
                        addView(View(context).apply { background = shape(tint, 5) }, LinearLayout.LayoutParams(context.dp(9), context.dp(9)))
                        addView(space(9, 0))
                        addView(text(state, 16f, tint, true))
                    })
                    if (battery != null || transport != null) addView(horizontal().apply {
                        setPadding(0, context.dp(8), 0, 0)
                        battery?.let {
                            addView(icon(R.drawable.ic_link_battery, 16, accent))
                            addView(space(7, 0))
                            addView(text(it, 15f))
                        }
                        if (battery != null && transport != null) addView(space(12, 0))
                        transport?.let {
                            addView(icon(R.drawable.ic_link_transport, 16, muted))
                            addView(space(7, 0))
                            addView(text(it, 15f))
                        }
                    })
                }, LinearLayout.LayoutParams(0, -2, 1f))
            })
        }

    fun metrics(rtt: String, transport: String) = card(12).apply {
        addView(horizontal().apply {
            addView(metric(R.drawable.ic_link_latency, rtt, R.string.link_latency), LinearLayout.LayoutParams(0, -2, 1f))
            addView(View(context).apply { setBackgroundColor(divider) }, LinearLayout.LayoutParams(context.dp(1), context.dp(38)))
            addView(metric(R.drawable.ic_link_transport, transport, R.string.link_transport), LinearLayout.LayoutParams(0, -2, 1f))
        })
    }

    private fun metric(res: Int, value: String, title: Int) = horizontal().apply {
        setPadding(context.dp(8), 0, context.dp(5), 0)
        addView(icon(res, 23, accent))
        addView(space(11, 0))
        addView(column().apply {
            addView(text(value, 20f, bold = true))
            addView(text(context.getString(title), 14f, muted))
        }, LinearLayout.LayoutParams(0, -2, 1f))
    }

    fun setting(res: Int, title: Int, subtitle: String, onClick: () -> Unit) = horizontal().apply {
        minimumHeight = context.dp(64)
        setPadding(context.dp(16), context.dp(7), context.dp(16), context.dp(7))
        addView(tile(res, 44, 24, secondary))
        addView(space(14, 0))
        addView(column().apply {
            addView(text(context.getString(title), 19f, bold = true))
            addView(text(subtitle, 15f, muted))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(icon(R.drawable.ic_link_chevron, 20))
        isFocusable = true
        setOnClickListener { onClick() }
    }

    fun separator() = View(context).apply {
        setBackgroundColor(divider)
        layoutParams = LinearLayout.LayoutParams(-1, context.dp(1)).apply {
            marginStart = context.dp(16)
            marginEnd = context.dp(16)
        }
    }

}
