package us.lemesh.nodedb.data

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.osmdroid.views.MapView
import us.lemesh.nodedb.R
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Bounded-memory headless PNG snapshot renderer and SAF / FileProvider delivery helpers.
 * Uses bounded target dimensions, RGB_565 opaque backing where lossless transparency isn't needed,
 * and strict try/finally recycling of intermediate bitmaps on failure.
 */
object MapSnapshotRenderer {

    internal const val MAX_EXPORT_EDGE_PX = 2400

    /**
     * Must be called on the Android Main thread because MapView & osmdroid overlays are UI-owned.
     */
    fun captureMapViewBitmap(
        mapView: MapView,
        scaleFactor: Float = 2f,
        maxEdgePx: Int = MAX_EXPORT_EDGE_PX
    ): Bitmap? {
        val w = mapView.width
        val h = mapView.height
        if (w <= 0 || h <= 0) return null

        val maxDim = max(w, h).toFloat()
        val effectiveScale = min(scaleFactor, maxEdgePx.toFloat() / maxDim).coerceAtLeast(1f)
        val targetW = (w * effectiveScale).roundToInt().coerceAtLeast(1)
        val targetH = (h * effectiveScale).roundToInt().coerceAtLeast(1)

        return try {
            renderMapToBitmap(mapView, targetW, targetH, effectiveScale, Bitmap.Config.RGB_565)
        } catch (_: OutOfMemoryError) {
            val fallbackScale = 1f.coerceAtMost(maxEdgePx.toFloat() / maxDim)
            val fbW = (w * fallbackScale).roundToInt().coerceAtLeast(1)
            val fbH = (h * fallbackScale).roundToInt().coerceAtLeast(1)
            runCatching {
                renderMapToBitmap(mapView, fbW, fbH, fallbackScale, Bitmap.Config.RGB_565)
            }.getOrNull()
        }
    }

    private fun renderMapToBitmap(
        mapView: MapView,
        width: Int,
        height: Int,
        scale: Float,
        config: Bitmap.Config
    ): Bitmap {
        val bitmap = createBitmap(width, height, config)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(0xFF0A0F14.toInt())
            canvas.save()
            canvas.scale(scale, scale)
            mapView.draw(canvas)
            canvas.restore()
            return bitmap
        } catch (t: Throwable) {
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
            throw t
        }
    }

    /**
     * Decodes a right-sized Lake Effect Mesh brand emblem bitmap for PNG exports.
     * Caller owns recycling the returned [Bitmap].
     */
    fun decodeBrandEmblemBitmap(
        context: Context,
        maxEdgePx: Int = 192
    ): Bitmap? = runCatching {
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeResource(context.resources, R.drawable.lemesh_brand_emblem, boundsOpts)
        var sampleSize = 1
        val maxSrc = max(boundsOpts.outWidth, boundsOpts.outHeight)
        while (maxSrc > 0 && (maxSrc / (sampleSize * 2)) >= maxEdgePx) {
            sampleSize *= 2
        }
        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        BitmapFactory.decodeResource(context.resources, R.drawable.lemesh_brand_emblem, decodeOpts)
    }.getOrNull()

    private fun drawCircularEmblemBadge(
        canvas: Canvas,
        emblemBitmap: Bitmap,
        centerX: Float,
        centerY: Float,
        radius: Float,
        borderColorArgb: Int,
        borderWidthPx: Float,
        alphaInt: Int = 255,
        haloColorArgb: Int? = null
    ) {
        if (emblemBitmap.isRecycled || radius <= 1f) return
        if (haloColorArgb != null) {
            val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = haloColorArgb
                style = Paint.Style.FILL
            }
            canvas.drawCircle(centerX, centerY, radius + borderWidthPx * 2.2f, haloPaint)
        }
        val destRect = RectF(centerX - radius, centerY - radius, centerX + radius, centerY + radius)
        val clipPath = Path().apply {
            addCircle(centerX, centerY, radius, Path.Direction.CW)
        }
        val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            alpha = alphaInt.coerceIn(0, 255)
        }
        canvas.save()
        canvas.clipPath(clipPath)
        canvas.drawBitmap(emblemBitmap, null, destRect, bmpPaint)
        canvas.restore()

        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = borderColorArgb
            style = Paint.Style.STROKE
            strokeWidth = borderWidthPx
            alpha = alphaInt.coerceIn(0, 255)
        }
        canvas.drawCircle(centerX, centerY, radius, ringPaint)
    }

    fun composeAnnotatedExportBitmap(
        mapBitmap: Bitmap,
        summary: MapExportSummary,
        emblemBitmap: Bitmap? = null
    ): Bitmap {
        val width = max(640, mapBitmap.width).coerceAtMost(MAX_EXPORT_EDGE_PX)
        val mapScale = if (mapBitmap.width > width) width.toFloat() / mapBitmap.width.toFloat() else 1f
        val mapHeight = (mapBitmap.height * mapScale).roundToInt().coerceAtLeast(1)
        val s = (width / 1080f).coerceIn(0.65f, 2.2f)

        val padH = (24f * s)
        val titleSize = 28f * s
        val subSize = 18f * s
        val metricSize = 16.5f * s
        val legendSize = 15.5f * s
        val metricLineHeight = 23f * s

        val metricCount = summary.metricLines.size.coerceAtLeast(1)
        val headerHeight = (30f * s + titleSize + 10f * s + subSize + 14f * s + metricCount * metricLineHeight + 18f * s)
            .roundToInt()

        val legendPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = legendSize
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val itemGap = 20f * s
        val swatchW = 18f * s
        val maxContentW = width - padH * 2f
        var rowCount = 1
        var curRowX = 0f
        summary.legendItems.forEach { item ->
            val itemW = swatchW + 8f * s + legendPaint.measureText(item.label) + itemGap
            if (curRowX > 0f && curRowX + itemW > maxContentW) {
                rowCount++
                curRowX = itemW
            } else {
                curRowX += itemW
            }
        }
        val legendRowH = 28f * s
        val rssiBarExtra = if (summary.showRssiGradient) (38f * s) else 0f
        val footerHeight = (18f * s + rowCount * legendRowH + rssiBarExtra + 16f * s).roundToInt()

        val totalHeight = headerHeight + mapHeight + footerHeight
        val outBitmap = createBitmap(width, totalHeight, Bitmap.Config.RGB_565)
        try {
            val canvas = Canvas(outBitmap)

            canvas.drawColor(0xFF0A0F14.toInt())

            val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF111821.toInt()
            }
            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF233042.toInt()
                strokeWidth = max(1.5f, 2f * s)
                style = Paint.Style.STROKE
            }
            canvas.drawRect(0f, 0f, width.toFloat(), headerHeight.toFloat(), panelPaint)
            canvas.drawLine(0f, headerHeight.toFloat() - 1f, width.toFloat(), headerHeight.toFloat() - 1f, borderPaint)

            val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFF8FAFC.toInt()
                textSize = titleSize
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            }
            val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF22C55E.toInt()
                textSize = subSize
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                textAlign = Paint.Align.RIGHT
            }
            val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF94A3B8.toInt()
                textSize = subSize
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            }
            val timestampPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF94A3B8.toInt()
                textSize = subSize * 0.92f
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
                textAlign = Paint.Align.RIGHT
            }
            val metricPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFE2E8F0.toInt()
                textSize = metricSize
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            }

            var y = 26f * s + titleSize
            canvas.drawText(summary.screenTitle, padH, y, titlePaint)
            canvas.drawText(summary.baseLayerName.uppercase(Locale.US), width - padH, y - 2f * s, badgePaint)

            y += 8f * s + subSize
            canvas.drawText(summary.subtitle, padH, y, subPaint)
            canvas.drawText(summary.timestampLabel, width - padH, y, timestampPaint)

            y += 14f * s
            summary.metricLines.forEach { line ->
                y += metricLineHeight * 0.85f
                val clipped = ellipsizeText(line, metricPaint, maxContentW)
                canvas.drawText(clipped, padH, y, metricPaint)
                y += metricLineHeight * 0.15f
            }

            if (mapScale == 1f) {
                val mapX = ((width - mapBitmap.width) / 2f).coerceAtLeast(0f)
                canvas.drawBitmap(mapBitmap, mapX, headerHeight.toFloat(), null)
            } else {
                val drawW = mapBitmap.width * mapScale
                val mapX = ((width - drawW) / 2f).coerceAtLeast(0f)
                val destRect = RectF(
                    mapX,
                    headerHeight.toFloat(),
                    mapX + drawW,
                    headerHeight.toFloat() + mapHeight.toFloat()
                )
                val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
                canvas.drawBitmap(mapBitmap, null, destRect, bitmapPaint)
            }

            if (emblemBitmap != null && !emblemBitmap.isRecycled) {
                val wmRadius = (30f * s).coerceIn(22f, 52f)
                val wmMargin = 18f * s
                val wmCx = width - wmMargin - wmRadius
                val wmCy = headerHeight + mapHeight - wmMargin - wmRadius
                drawCircularEmblemBadge(
                    canvas = canvas,
                    emblemBitmap = emblemBitmap,
                    centerX = wmCx,
                    centerY = wmCy,
                    radius = wmRadius,
                    borderColorArgb = 0xFF334155.toInt(),
                    borderWidthPx = max(1.5f, 2f * s),
                    alphaInt = 216,
                    haloColorArgb = 0x990A0F14.toInt()
                )
            }

            val footerTop = (headerHeight + mapHeight).toFloat()
            canvas.drawRect(0f, footerTop, width.toFloat(), totalHeight.toFloat(), panelPaint)
            canvas.drawLine(0f, footerTop + 1f, width.toFloat(), footerTop + 1f, borderPaint)

            legendPaint.color = 0xFFE2E8F0.toInt()
            val swatchPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            var drawX = padH
            var rowY = footerTop + 22f * s + (legendSize * 0.6f)

            summary.legendItems.forEach { item ->
                val labelW = legendPaint.measureText(item.label)
                val totalItemW = swatchW + 8f * s + labelW + itemGap
                if (drawX > padH && drawX + totalItemW > width - padH) {
                    drawX = padH
                    rowY += legendRowH
                }
                drawLegendSwatch(
                    canvas = canvas,
                    paint = swatchPaint,
                    shape = item.shape,
                    colorArgb = item.colorArgb,
                    cx = drawX + swatchW / 2f,
                    cy = rowY - legendSize * 0.34f,
                    radius = 6.5f * s
                )
                canvas.drawText(item.label, drawX + swatchW + 8f * s, rowY, legendPaint)
                drawX += totalItemW
            }

            if (summary.showRssiGradient) {
                val barTop = footerTop + footerHeight - 28f * s
                val barHeight = 10f * s
                val barLeft = padH + 110f * s
                val barRight = (width - padH - 85f * s).coerceAtLeast(barLeft + 100f * s)
                val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0xFF94A3B8.toInt()
                    textSize = 13.5f * s
                    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                }
                canvas.drawText("RSSI -130", padH, barTop + barHeight, labelPaint)
                val gradPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = LinearGradient(
                        barLeft,
                        barTop,
                        barRight,
                        barTop,
                        intArrayOf(
                            0xFFF59E0B.toInt(),
                            0xFF38BDF8.toInt(),
                            0xFF22C55E.toInt()
                        ),
                        null,
                        Shader.TileMode.CLAMP
                    )
                }
                canvas.drawRoundRect(
                    RectF(barLeft, barTop, barRight, barTop + barHeight),
                    barHeight / 2f,
                    barHeight / 2f,
                    gradPaint
                )
                canvas.drawText("-60 dBm", barRight + 10f * s, barTop + barHeight, labelPaint)
            }

            return outBitmap
        } catch (t: Throwable) {
            if (!outBitmap.isRecycled) {
                outBitmap.recycle()
            }
            throw t
        }
    }

    /**
     * Renders a clean, property-owner-friendly Host Outreach Spec Card PNG for lemesh.us.
     * Displays candidate viewshed/relay map, estimated coverage radius, key fixed relay link
     * distances, privacy sanitization notice (personal nodes omitted, portable sites masked),
     * and hardware badge (Self-Contained Solar • Zero Utility Power • Non-Penetrating Mount).
     * Never embeds property photos.
     */
    fun composeHostOutreachCardBitmap(
        mapBitmap: Bitmap,
        spec: HostOutreachSpec,
        emblemBitmap: Bitmap? = null
    ): Bitmap {
        val width = max(840, mapBitmap.width).coerceAtMost(MAX_EXPORT_EDGE_PX)
        val mapScale = if (mapBitmap.width > width) width.toFloat() / mapBitmap.width.toFloat() else 1f
        val mapHeight = (mapBitmap.height * mapScale).roundToInt().coerceAtLeast(1)
        val s = (width / 1080f).coerceIn(0.7f, 2.0f)

        val padH = 28f * s
        val headerHeight = (152f * s).roundToInt()
        val linksCount = spec.relayLinks.size.coerceAtMost(4)
        val footerHeight = (164f * s + linksCount * 26f * s).roundToInt()
        val totalHeight = headerHeight + mapHeight + footerHeight

        val outBitmap = createBitmap(width, totalHeight, Bitmap.Config.RGB_565)
        try {
            val canvas = Canvas(outBitmap)
            canvas.drawColor(0xFF0A0F14.toInt())

            val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0F172A.toInt() }
            val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF22C55E.toInt() }
            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF233042.toInt()
                strokeWidth = max(1.5f, 2f * s)
                style = Paint.Style.STROKE
            }

            // Header panel + top emerald bar
            canvas.drawRect(0f, 0f, width.toFloat(), headerHeight.toFloat(), panelPaint)
            canvas.drawRect(0f, 0f, width.toFloat(), 6f * s, accentPaint)
            canvas.drawLine(0f, headerHeight.toFloat() - 1f, width.toFloat(), headerHeight.toFloat() - 1f, borderPaint)

            val brandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF22C55E.toInt()
                textSize = 15.5f * s
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            }
            val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFF8FAFC.toInt()
                textSize = 30f * s
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            }
            val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF94A3B8.toInt()
                textSize = 16.5f * s
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            }
            val rightStampPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF94A3B8.toInt()
                textSize = 14.5f * s
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
                textAlign = Paint.Align.RIGHT
            }

            val activeEmblem = emblemBitmap?.takeIf { !it.isRecycled }
            val hasEmblem = activeEmblem != null
            val emblemRadius = if (hasEmblem) (44f * s) else 0f
            if (activeEmblem != null) {
                val emblemCx = padH + emblemRadius
                val emblemCy = 6f * s + (headerHeight - 6f * s) / 2f
                drawCircularEmblemBadge(
                    canvas = canvas,
                    emblemBitmap = activeEmblem,
                    centerX = emblemCx,
                    centerY = emblemCy,
                    radius = emblemRadius,
                    borderColorArgb = 0xFF22C55E.toInt(),
                    borderWidthPx = max(2f, 2.2f * s),
                    alphaInt = 255,
                    haloColorArgb = 0x660A0F14.toInt()
                )
            }

            val textStartX = if (hasEmblem) padH + emblemRadius * 2f + 18f * s else padH
            val maxContentW = (width - textStartX - padH).coerceAtLeast(120f)

            var y = 32f * s
            val timestampW = rightStampPaint.measureText(spec.timestampLabel) + 14f * s
            val brandLine = ellipsizeText(
                "LEMESH HORIZON  •  LEMESH.US  •  HOST OUTREACH SPEC CARD",
                brandPaint,
                (maxContentW - timestampW).coerceAtLeast(80f)
            )
            canvas.drawText(brandLine, textStartX, y, brandPaint)
            canvas.drawText(spec.timestampLabel, width - padH, y, rightStampPaint)

            y += 38f * s
            canvas.drawText(ellipsizeText(spec.siteTitle, titlePaint, maxContentW), textStartX, y, titlePaint)

            y += 28f * s
            val radiusMiles = MeshGeoUtils.kmToMiles(spec.estimatedRadiusKm)
            val areaPart = spec.coveredAreaKm2?.let {
                String.format(Locale.US, "  •  Est. Coverage %.1f km²", it)
            }.orEmpty()
            val statsLine = String.format(
                Locale.US,
                "Est. Reach: %.1f mi (%.1f km)%s",
                radiusMiles,
                spec.estimatedRadiusKm,
                areaPart
            )
            canvas.drawText(ellipsizeText(statsLine, subPaint, maxContentW), textStartX, y, subPaint)

            y += 28f * s
            val privacyNotice = "Privacy Filter: ${spec.omittedPersonalNodeCount} personal node(s) omitted • ${spec.maskedPortableSiteCount} portable site(s) masked (~1 km) • No property photos"
            canvas.drawText(ellipsizeText(privacyNotice, rightStampPaint.apply { textAlign = Paint.Align.LEFT }, maxContentW), textStartX, y, rightStampPaint)

            // Map viewport image
            if (mapScale == 1f) {
                val mapX = ((width - mapBitmap.width) / 2f).coerceAtLeast(0f)
                canvas.drawBitmap(mapBitmap, mapX, headerHeight.toFloat(), null)
            } else {
                val drawW = mapBitmap.width * mapScale
                val mapX = ((width - drawW) / 2f).coerceAtLeast(0f)
                val destRect = RectF(
                    mapX,
                    headerHeight.toFloat(),
                    mapX + drawW,
                    headerHeight.toFloat() + mapHeight.toFloat()
                )
                canvas.drawBitmap(mapBitmap, null, destRect, Paint(Paint.FILTER_BITMAP_FLAG))
            }

            // Footer: Hardware Summary Pills + Key Relay Link Distances
            val footerTop = (headerHeight + mapHeight).toFloat()
            canvas.drawRect(0f, footerTop, width.toFloat(), totalHeight.toFloat(), panelPaint)
            canvas.drawLine(0f, footerTop + 1f, width.toFloat(), footerTop + 1f, borderPaint)

            val pillBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF172536.toInt() }
            val pillStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF22C55E.toInt()
                style = Paint.Style.STROKE
                strokeWidth = 1.6f * s
            }
            val pillTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF86EFAC.toInt()
                textSize = 15.5f * s
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }

            val badges = spec.hardwareBadgeLines.ifEmpty { GeoJsonReportExporter.HOST_OUTREACH_HARDWARE_BADGES }
            val pillGap = 12f * s
            val pillH = 38f * s
            val pillW = (maxContentW - pillGap * (badges.size - 1)) / badges.size.coerceAtLeast(1)
            val pillTop = footerTop + 20f * s
            badges.forEachIndexed { idx, label ->
                val left = padH + idx * (pillW + pillGap)
                val rect = RectF(left, pillTop, left + pillW, pillTop + pillH)
                canvas.drawRoundRect(rect, 10f * s, 10f * s, pillBgPaint)
                canvas.drawRoundRect(rect, 10f * s, 10f * s, pillStrokePaint)
                val baseY = rect.centerY() - (pillTextPaint.descent() + pillTextPaint.ascent()) / 2f
                canvas.drawText(ellipsizeText(label, pillTextPaint, pillW - 14f * s), rect.centerX(), baseY, pillTextPaint)
            }

            var rowY = pillTop + pillH + 34f * s
            val sectionHeaderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF38BDF8.toInt()
                textSize = 15f * s
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            }
            val linkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFE2E8F0.toInt()
                textSize = 15.5f * s
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            }

            canvas.drawText(
                if (spec.relayLinks.isNotEmpty()) "KEY RELAY LINK DISTANCES" else "COMMUNITY MESH DEPLOYMENT SUMMARY",
                padH,
                rowY,
                sectionHeaderPaint
            )
            rowY += 26f * s

            if (spec.relayLinks.isEmpty()) {
                val summary = "Fixed infrastructure relay candidate (${spec.includedSites.size} public infrastructure site(s) in region) • 915 MHz unlicensed ISM band."
                canvas.drawText(ellipsizeText(summary, linkPaint, maxContentW), padH, rowY, linkPaint)
            } else {
                spec.relayLinks.take(4).forEach { link ->
                    val line = String.format(
                        Locale.US,
                        "• %s  —  %.1f mi (%.1f km)  •  %s",
                        link.label,
                        link.distanceMiles,
                        link.distanceKm,
                        link.statusSummary
                    )
                    canvas.drawText(ellipsizeText(line, linkPaint, maxContentW), padH, rowY, linkPaint)
                    rowY += 26f * s
                }
            }

            val footBrandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF64748B.toInt()
                textSize = 13.5f * s
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            }
            canvas.drawText(
                "${MapExportEngine.EXPORT_BRAND_ATTRIBUTION}  •  Zero utility power draw • Non-penetrating weighted/clamp mount",
                padH,
                totalHeight - 18f * s,
                footBrandPaint
            )

            return outBitmap
        } catch (t: Throwable) {
            if (!outBitmap.isRecycled) {
                outBitmap.recycle()
            }
            throw t
        }
    }

    private fun drawLegendSwatch(
        canvas: Canvas,
        paint: Paint,
        shape: ExportLegendShape,
        colorArgb: Int,
        cx: Float,
        cy: Float,
        radius: Float
    ) {
        paint.color = colorArgb
        when (shape) {
            ExportLegendShape.CIRCLE -> {
                paint.style = Paint.Style.FILL
                canvas.drawCircle(cx, cy, radius, paint)
            }
            ExportLegendShape.PIN -> {
                paint.style = Paint.Style.FILL
                canvas.drawCircle(cx, cy - radius * 0.15f, radius * 0.9f, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = max(1.5f, radius * 0.25f)
                paint.color = 0xFFF8FAFC.toInt()
                canvas.drawCircle(cx, cy - radius * 0.15f, radius * 0.9f, paint)
            }
            ExportLegendShape.DIAMOND -> {
                paint.style = Paint.Style.FILL
                val path = Path().apply {
                    moveTo(cx, cy - radius * 1.15f)
                    lineTo(cx + radius * 1.15f, cy)
                    lineTo(cx, cy + radius * 1.15f)
                    lineTo(cx - radius * 1.15f, cy)
                    close()
                }
                canvas.drawPath(path, paint)
            }
            ExportLegendShape.LINE -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = max(2f, radius * 0.55f)
                paint.strokeCap = Paint.Cap.ROUND
                canvas.drawLine(cx - radius * 1.3f, cy, cx + radius * 1.3f, cy, paint)
            }
            ExportLegendShape.DASHED_LINE -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = max(2f, radius * 0.5f)
                canvas.drawLine(cx - radius * 1.3f, cy, cx - radius * 0.25f, cy, paint)
                canvas.drawLine(cx + radius * 0.25f, cy, cx + radius * 1.3f, cy, paint)
            }
        }
    }

    fun encodeBitmapToPngBytes(bitmap: Bitmap): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        return stream.toByteArray()
    }

    suspend fun writeBytesToUri(
        context: Context,
        uri: Uri,
        bytes: ByteArray
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val out = context.contentResolver.openOutputStream(uri)
                ?: error("Unable to open destination file stream")
            out.use { stream ->
                stream.write(bytes)
                stream.flush()
            }
        }
    }

    suspend fun writeShareCacheFile(
        context: Context,
        fileName: String,
        bytes: ByteArray
    ): Result<Uri> = withContext(Dispatchers.IO) {
        runCatching {
            val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
            exportDir.listFiles()
                ?.sortedByDescending { it.lastModified() }
                ?.drop(12)
                ?.forEach { it.delete() }

            val targetFile = File(exportDir, fileName)
            targetFile.outputStream().use { stream ->
                stream.write(bytes)
                stream.flush()
            }
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                targetFile
            )
        }
    }

    fun launchShareChooser(
        context: Context,
        uri: Uri,
        mimeType: String,
        title: String
    ) {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(sendIntent, title).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    private fun ellipsizeText(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var end = text.length
        while (end > 1 && paint.measureText(text.substring(0, end) + "…") > maxWidth) {
            end--
        }
        return text.substring(0, end) + "…"
    }
}
