package com.ethran.notable.editor.drawing

import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint

val selectPaint = Paint().apply {
    strokeWidth = 5f
    style = Paint.Style.STROKE
    pathEffect = DashPathEffect(floatArrayOf(20f, 10f), 0f)
    isAntiAlias = true
    color = Color.GRAY
}
