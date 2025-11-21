package com.pdfpoc.pdfmodule

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint

/**
 * Render PDF page content to a Bitmap using Android Canvas API.
 * This approach doesn't require Compose window attachment.
 */
fun renderComposableToBitmap(
    context: Context,
    widthPx: Int,
    heightPx: Int,
    title: String,
    body: String
): Bitmap {
    val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    // Fill white background
    canvas.drawColor(android.graphics.Color.WHITE)

    // Setup title paint
    val titlePaint = TextPaint().apply {
        color = android.graphics.Color.BLACK
        textSize = 22f * context.resources.displayMetrics.density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        isAntiAlias = true
    }

    // Setup body paint
    val bodyPaint = TextPaint().apply {
        color = android.graphics.Color.BLACK
        textSize = 16f * context.resources.displayMetrics.density
        typeface = Typeface.DEFAULT
        isAntiAlias = true
    }

    val paddingPx = (24 * context.resources.displayMetrics.density).toInt()
    val topPaddingPx = (12 * context.resources.displayMetrics.density).toInt()
    val availableWidth = widthPx - (paddingPx * 2)

    // Draw title
    val titleLayout = StaticLayout.Builder.obtain(
        title,
        0,
        title.length,
        titlePaint,
        availableWidth
    ).setAlignment(Layout.Alignment.ALIGN_NORMAL)
        .setLineSpacing(0f, 1f)
        .setIncludePad(false)
        .build()

    canvas.save()
    canvas.translate(paddingPx.toFloat(), paddingPx.toFloat())
    titleLayout.draw(canvas)
    canvas.restore()

    // Draw body
    val bodyYPosition = paddingPx + titleLayout.height + topPaddingPx
    val bodyLayout = StaticLayout.Builder.obtain(
        body,
        0,
        body.length,
        bodyPaint,
        availableWidth
    ).setAlignment(Layout.Alignment.ALIGN_NORMAL)
        .setLineSpacing(0f, 1f)
        .setIncludePad(false)
        .build()

    canvas.save()
    canvas.translate(paddingPx.toFloat(), bodyYPosition.toFloat())
    bodyLayout.draw(canvas)
    canvas.restore()

    return bitmap
}