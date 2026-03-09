package com.ethran.notable.editor.utils


import android.graphics.Color
import com.onyx.android.sdk.pen.style.StrokeStyle
import kotlinx.serialization.Serializable


enum class Pen(val penName: String) {
    BALLPEN("BALLPEN"),
    REDBALLPEN("REDBALLPEN"),
    GREENBALLPEN("GREENBALLPEN"),
    BLUEBALLPEN("BLUEBALLPEN"),
    PENCIL("PENCIL"),
    BRUSH("BRUSH"),
    MARKER("MARKER"),
    FOUNTAIN("FOUNTAIN"),
    DASHED("DASHED");

    val strokeStyle: Int
        get() = when (this) {
            BALLPEN, REDBALLPEN, GREENBALLPEN, BLUEBALLPEN -> StrokeStyle.PENCIL
            PENCIL -> StrokeStyle.CHARCOAL
            BRUSH -> StrokeStyle.NEO_BRUSH
            MARKER -> StrokeStyle.MARKER
            FOUNTAIN -> StrokeStyle.FOUNTAIN
            DASHED -> StrokeStyle.DASH
        }

    companion object {
        /** The pen selected by default on fresh install */
        val DEFAULT = FOUNTAIN

        /** Default pen settings for each pen type (fresh install) */
        val DEFAULT_SETTINGS: NamedSettings = mapOf(
            BALLPEN.penName to PenSetting(5f, Color.BLACK),
            REDBALLPEN.penName to PenSetting(5f, Color.RED),
            BLUEBALLPEN.penName to PenSetting(5f, Color.BLUE),
            GREENBALLPEN.penName to PenSetting(5f, Color.GREEN),
            PENCIL.penName to PenSetting(5f, Color.BLACK),
            BRUSH.penName to PenSetting(5f, Color.BLACK),
            MARKER.penName to PenSetting(40f, Color.LTGRAY),
            FOUNTAIN.penName to PenSetting(5f, Color.BLACK),
        )

        fun fromString(name: String?): Pen {
            return entries.find { it.penName.equals(name, ignoreCase = true) } ?: DEFAULT
        }
    }
}

@Serializable
data class PenSetting(
    var strokeSize: Float,
    //TODO: Rename to strokeColor
    var color: Int
)

typealias NamedSettings = Map<String, PenSetting>