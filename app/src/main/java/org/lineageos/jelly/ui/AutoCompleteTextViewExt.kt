/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import com.google.android.material.textfield.MaterialAutoCompleteTextView

class AutoCompleteTextViewExt : MaterialAutoCompleteTextView {
    private var positionX = 0

    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyle: Int) :
            super(context, attrs, defStyle)

    override fun onScrollChanged(x: Int, y: Int, oldX: Int, oldY: Int) {
        super.onScrollChanged(x, y, oldX, oldY)
        positionX = x
        requestLayout()
    }

    /** The box colour, faded over the text where it runs past the edge. */
    var fadeColor: Int = Color.TRANSPARENT

    private val fadePaint = Paint()

    public override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val lineWidth = layout?.getLineWidth(0) ?: return
        val width = measuredWidth.toFloat()
        if (text.isNullOrEmpty() || lineWidth <= width || Color.alpha(fadeColor) == 0) return
        // Drawn over the text, so coloured parts of the address keep their colours
        val fade = width * 0.15f
        val end = positionX + width
        fadePaint.shader = LinearGradient(
            end - fade, 0f, end, 0f,
            fadeColor and 0x00FFFFFF, fadeColor, Shader.TileMode.CLAMP
        )
        canvas.drawRect(end - fade, 0f, end, height.toFloat(), fadePaint)
        if (positionX > 0) {
            fadePaint.shader = LinearGradient(
                positionX.toFloat(), 0f, positionX + fade, 0f,
                fadeColor, fadeColor and 0x00FFFFFF, Shader.TileMode.CLAMP
            )
            canvas.drawRect(positionX.toFloat(), 0f, positionX + fade, height.toFloat(), fadePaint)
        }
    }
}
