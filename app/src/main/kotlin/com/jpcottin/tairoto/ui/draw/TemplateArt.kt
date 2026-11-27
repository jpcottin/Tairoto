package com.jpcottin.tairoto.ui.draw

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import com.jpcottin.tairoto.world.FishTemplate
import com.jpcottin.tairoto.world.TemplatePage

/**
 * Draws the page to colour in onto a canvas [widthPx] by [heightPx] (the page
 * is fitted inside, centred): the four corner squares, the fish outline and a
 * line of words. The same drawing is printed and shown on screen.
 */
object TemplateArt {
    fun draw(canvas: Canvas, widthPx: Float, heightPx: Float) {
        val k = minOf(widthPx / TemplatePage.WIDTH_MM, heightPx / TemplatePage.HEIGHT_MM)
        canvas.save()
        canvas.translate((widthPx - TemplatePage.WIDTH_MM * k) / 2f, (heightPx - TemplatePage.HEIGHT_MM * k) / 2f)
        canvas.scale(k, k)
        val paper = Paint().apply { color = Color.WHITE }
        canvas.drawRect(0f, 0f, TemplatePage.WIDTH_MM, TemplatePage.HEIGHT_MM, paper)
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        for ((i, m) in TemplatePage.markers.withIndex()) {
            val h = TemplatePage.MARKER_MM / 2
            canvas.drawRect(RectF(m.x - h, m.y - h, m.x + h, m.y + h), ink)
            if (i == 0) {
                val hole = TemplatePage.HOLE_MM / 2
                canvas.drawRect(RectF(m.x - hole, m.y - hole, m.x + hole, m.y + hole), paper)
            }
        }
        // The outline: a firm line to colour up to (thin enough to stay mostly inside the cut-out).
        val path = Path()
        FishTemplate.outline().forEachIndexed { i, p ->
            val at = TemplatePage.fishToPage(p.x, p.y)
            if (i == 0) path.moveTo(at.x, at.y) else path.lineTo(at.x, at.y)
        }
        path.close()
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(40, 40, 40)
            style = Paint.Style.STROKE
            strokeWidth = 0.9f
            strokeJoin = Paint.Join.ROUND
        }
        canvas.drawPath(path, line)
        val eye = TemplatePage.fishToPage(FishTemplate.eye.x, FishTemplate.eye.y)
        canvas.drawCircle(eye.x, eye.y, FishTemplate.EYE_RADIUS * TemplatePage.FISH_HALF_HEIGHT_MM, line)
        val words = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(90, 90, 90)
            textSize = 6f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("Colour in your fish, then let it swim in Tairoto", TemplatePage.WIDTH_MM / 2, 24f, words)
        words.textSize = 4.2f
        canvas.drawText("Keep the four black squares clean and in the photo", TemplatePage.WIDTH_MM / 2, 193f, words)
        canvas.restore()
    }
}
