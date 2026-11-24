package us.lemesh.nodedb.data

import android.graphics.Bitmap
import android.graphics.Color
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * High-density polar RF visibility & RSSI field sampled from DEM terrain tiles.
 * Supports continuous bilinear sub-bin interpolation with zoom-adaptive anti-aliased
 * shadow boundaries so viewshed and bridge overlays stay crisp at high map zoom.
 */
class PolarVisibilityField(
    val originLat: Double,
    val originLon: Double,
    val radiusKm: Double,
    val rayCount: Int,
    val stepMeters: Double,
    val numSteps: Int,
    val sensitivityDbm: Double,
    val statusGrid: ByteArray,
    val rssiGrid: FloatArray,
    val coveredAreaKm2: Double,
    val northLat: Double,
    val southLat: Double,
    val westLon: Double,
    val eastLon: Double
) {
    private val cosOriginLat = cos(Math.toRadians(originLat)).coerceAtLeast(0.15)
    private val kmPerDegLat = 111.132
    private val kmPerDegLon = 111.320 * cosOriginLat
    private val stepKm = stepMeters / 1000.0
    private val dThetaRad = (2.0 * PI) / rayCount.coerceAtLeast(1)

    fun cellIndexAt(lat: Double, lon: Double): Int {
        val dyKm = (lat - originLat) * kmPerDegLat
        val dxKm = (lon - originLon) * kmPerDegLon
        val dKm = sqrt(dxKm * dxKm + dyKm * dyKm)
        if (dKm > radiusKm) return -1
        if (dKm * 1000.0 < min(50.0, stepMeters)) return -2

        val sIdx = ((dKm * 1000.0) / stepMeters).toInt().coerceIn(0, numSteps - 1)
        var bearingDeg = Math.toDegrees(atan2(dxKm, dyKm))
        if (bearingDeg < 0.0) bearingDeg += 360.0
        val rIdx = ((bearingDeg / 360.0) * rayCount).toInt().coerceIn(0, rayCount - 1)
        return rIdx * numSteps + sIdx
    }

    fun statusForIndex(cellIndex: Int): Byte = when {
        cellIndex == -1 -> ViewshedPointStatus.OBSTRUCTED.code
        cellIndex == -2 -> ViewshedPointStatus.CLEAR.code
        else -> statusGrid[cellIndex]
    }

    fun statusAt(lat: Double, lon: Double): Byte = statusForIndex(cellIndexAt(lat, lon))

    fun rssiForIndex(cellIndex: Int): Double = when {
        cellIndex == -1 -> Double.NEGATIVE_INFINITY
        cellIndex == -2 -> ViewshedEngine.STRONG_RSSI_DBM
        else -> rssiGrid[cellIndex].toDouble()
    }

    /**
     * Evaluates continuous sub-bin visibility and RSSI at geographic coordinates (lat, lon)
     * into a zero-allocation 3-element DoubleArray:
     * - out[0] = anti-aliased edge coverage weight in [0.0, 1.0]
     * - out[1] = grazing fraction in [0.0, 1.0]
     * - out[2] = interpolated RSSI in dBm
     * Returns true if coverage weight > 0.
     */
    fun sampleContinuous(
        lat: Double,
        lon: Double,
        pixelMeters: Double,
        out: DoubleArray
    ): Boolean {
        val dyKm = (lat - originLat) * kmPerDegLat
        val dxKm = (lon - originLon) * kmPerDegLon
        return sampleContinuousFromKm(dxKm, dyKm, pixelMeters, out)
    }

    fun sampleContinuousFromKm(
        dxKm: Double,
        dyKm: Double,
        pixelMeters: Double,
        out: DoubleArray
    ): Boolean {
        val pixelKm = (pixelMeters / 1000.0).coerceAtLeast(0.0005)
        val outerEdgeKm = radiusKm + pixelKm * 1.25
        val dKmSq = dxKm * dxKm + dyKm * dyKm
        if (dKmSq > outerEdgeKm * outerEdgeKm) {
            out[0] = 0.0
            return false
        }
        val dKm = sqrt(dKmSq)
        val distM = dKm * 1000.0

        // Smooth anti-aliased outer radius clip (~1.5 screen pixels wide)
        val radiusAaKm = (pixelKm * 1.25).coerceIn(0.0015, max(0.02, stepKm * 0.6))
        val outerFactor = if (dKm <= radiusKm - radiusAaKm) {
            1.0
        } else {
            smoothstep(radiusKm + radiusAaKm, radiusKm - radiusAaKm, dKm)
        }
        if (outerFactor <= 0.001) {
            out[0] = 0.0
            return false
        }

        // Seamless origin core right at the TX/RX antenna (scales down for 1.5m LiDAR fields)
        val originCoreKm = min(0.012, stepKm * 1.1)
        if (dKm < originCoreKm) {
            out[0] = outerFactor
            out[1] = 0.0
            out[2] = ViewshedEngine.STRONG_RSSI_DBM
            return true
        }

        var bearingDeg = Math.toDegrees(atan2(dxKm, dyKm))
        if (bearingDeg < 0.0) bearingDeg += 360.0
        val rCont = (bearingDeg / 360.0) * rayCount
        val r0Raw = floor(rCont).toInt()
        val r0 = ((r0Raw % rayCount) + rayCount) % rayCount
        val r1 = (r0 + 1) % rayCount
        val tr = (rCont - r0Raw).coerceIn(0.0, 1.0)

        val sCont = ((distM / stepMeters) - 1.0).coerceIn(0.0, (numSteps - 1).toDouble())
        val s0 = floor(sCont).toInt().coerceIn(0, numSteps - 1)
        val s1 = (s0 + 1).coerceAtMost(numSteps - 1)
        val ts = (sCont - s0).coerceIn(0.0, 1.0)

        val oneMinusTr = 1.0 - tr
        val oneMinusTs = 1.0 - ts
        val w00 = oneMinusTr * oneMinusTs
        val w10 = tr * oneMinusTs
        val w01 = oneMinusTr * ts
        val w11 = tr * ts

        val idx00 = r0 * numSteps + s0
        val idx10 = r1 * numSteps + s0
        val idx01 = r0 * numSteps + s1
        val idx11 = r1 * numSteps + s1

        val st00 = statusGrid[idx00]
        val st10 = statusGrid[idx10]
        val st01 = statusGrid[idx01]
        val st11 = statusGrid[idx11]

        var visWeight = 0.0
        var grazingWeight = 0.0
        var rssiSum = 0.0

        if (st00 != ViewshedPointStatus.OBSTRUCTED.code) {
            visWeight += w00
            if (st00 == ViewshedPointStatus.GRAZING.code) grazingWeight += w00
            rssiSum += w00 * rssiGrid[idx00]
        }
        if (st10 != ViewshedPointStatus.OBSTRUCTED.code) {
            visWeight += w10
            if (st10 == ViewshedPointStatus.GRAZING.code) grazingWeight += w10
            rssiSum += w10 * rssiGrid[idx10]
        }
        if (st01 != ViewshedPointStatus.OBSTRUCTED.code) {
            visWeight += w01
            if (st01 == ViewshedPointStatus.GRAZING.code) grazingWeight += w01
            rssiSum += w01 * rssiGrid[idx01]
        }
        if (st11 != ViewshedPointStatus.OBSTRUCTED.code) {
            visWeight += w11
            if (st11 == ViewshedPointStatus.GRAZING.code) grazingWeight += w11
            rssiSum += w11 * rssiGrid[idx11]
        }

        if (visWeight <= 0.001) {
            out[0] = 0.0
            return false
        }

        // Keep terrain boundary ~1.5 screen pixels crisp at any zoom while tracing smooth sub-cell curves
        val arcMeters = (distM * dThetaRad).coerceAtLeast(stepMeters * 0.35)
        val cellScaleMeters = max(stepMeters, arcMeters)
        val aaHalf = ((pixelMeters / cellScaleMeters) * 0.85).coerceIn(0.006, 0.22)
        val edgeFactor = smoothstep(0.5 - aaHalf, 0.5 + aaHalf, visWeight) * outerFactor
        if (edgeFactor <= 0.001) {
            out[0] = 0.0
            return false
        }

        out[0] = edgeFactor
        out[1] = (grazingWeight / visWeight).coerceIn(0.0, 1.0)
        out[2] = rssiSum / visWeight
        return true
    }

    /**
     * Rasterizes a Mercator-projected sub-rectangle of this polar field at exact target pixel dimensions.
     * Used both for high-res base preview bitmaps and 1:1 viewport-matched dynamic overlays.
     */
    fun rasterizeSubRegion(
        renderNorthLat: Double = northLat,
        renderSouthLat: Double = southLat,
        renderWestLon: Double = westLon,
        renderEastLon: Double = eastLon,
        width: Int,
        height: Int,
        tintColorArgb: Int? = null
    ): Bitmap {
        val safeW = width.coerceAtLeast(1)
        val safeH = height.coerceAtLeast(1)
        val pixels = IntArray(safeW * safeH)

        val mercNorth = latToMercY(renderNorthLat)
        val mercSouth = latToMercY(renderSouthLat)
        val mercSpan = mercSouth - mercNorth
        val lonSpan = renderEastLon - renderWestLon

        val midLatCos = cos(Math.toRadians((renderNorthLat + renderSouthLat) * 0.5)).coerceAtLeast(0.15)
        val heightMeters = (renderNorthLat - renderSouthLat).coerceAtLeast(1e-6) * kmPerDegLat * 1000.0
        val widthMeters = lonSpan.coerceAtLeast(1e-6) * 111.320 * midLatCos * 1000.0
        val pixelMeters = max(widthMeters / safeW, heightMeters / safeH)

        val dxKmCol = DoubleArray(safeW) { px ->
            val xFrac = (px + 0.5) / safeW.toDouble()
            val lon = renderWestLon + xFrac * lonSpan
            (lon - originLon) * kmPerDegLon
        }

        val tintR = tintColorArgb?.let { Color.red(it) } ?: 0
        val tintG = tintColorArgb?.let { Color.green(it) } ?: 0
        val tintB = tintColorArgb?.let { Color.blue(it) } ?: 0
        val useTint = tintColorArgb != null

        val sampleOut = DoubleArray(3)
        for (py in 0 until safeH) {
            val yFrac = (py + 0.5) / safeH.toDouble()
            val lat = mercYToLat(mercNorth + yFrac * mercSpan)
            val dyKm = (lat - originLat) * kmPerDegLat
            val rowOffset = py * safeW

            for (px in 0 until safeW) {
                val dxKm = dxKmCol[px]
                if (!sampleContinuousFromKm(dxKm, dyKm, pixelMeters, sampleOut)) continue

                val edgeFactor = sampleOut[0]
                val grazingFrac = sampleOut[1]
                val rssi = sampleOut[2]

                val baseAlpha = 225.0 - grazingFrac * (225.0 - 165.0)
                val alpha = (baseAlpha * edgeFactor).roundToInt().coerceIn(0, 255)
                if (alpha <= 0) continue

                if (useTint) {
                    pixels[rowOffset + px] = (alpha shl 24) or (tintR shl 16) or (tintG shl 8) or tintB
                } else {
                    val rgb = ViewshedEngine.colorForRssi(
                        rssi = rssi,
                        sensitivityDbm = sensitivityDbm,
                        isGrazing = grazingFrac >= 0.5
                    )
                    pixels[rowOffset + px] = (alpha shl 24) or (rgb and 0x00FFFFFF)
                }
            }
        }

        return Bitmap.createBitmap(pixels, safeW, safeH, Bitmap.Config.ARGB_8888)
    }

    fun writeToStream(rawOut: OutputStream) {
        GZIPOutputStream(BufferedOutputStream(rawOut)).use { gz ->
            DataOutputStream(gz).use { out ->
                out.writeInt(BINARY_MAGIC)
                out.writeInt(BINARY_VERSION)
                out.writeDouble(originLat)
                out.writeDouble(originLon)
                out.writeDouble(radiusKm)
                out.writeInt(rayCount)
                out.writeDouble(stepMeters)
                out.writeInt(numSteps)
                out.writeDouble(sensitivityDbm)
                out.writeDouble(coveredAreaKm2)
                out.writeDouble(northLat)
                out.writeDouble(southLat)
                out.writeDouble(westLon)
                out.writeDouble(eastLon)

                val total = rayCount * numSteps
                out.write(statusGrid, 0, total)
                // Quantize RSSI at 0.05 dBm resolution (Short) for compact, fast persistence
                for (i in 0 until total) {
                    val q = (rssiGrid[i] * 20f).roundToInt()
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    out.writeShort(q)
                }
                out.flush()
            }
        }
    }

    companion object {
        private const val BINARY_MAGIC = 0x504C5231 // "PLR1"
        private const val BINARY_VERSION = 1

        fun readFromStream(rawIn: InputStream): PolarVisibilityField? {
            return runCatching {
                GZIPInputStream(BufferedInputStream(rawIn)).use { gz ->
                    DataInputStream(gz).use { inp ->
                        val magic = inp.readInt()
                        val version = inp.readInt()
                        if (magic != BINARY_MAGIC || version != BINARY_VERSION) return null

                        val originLat = inp.readDouble()
                        val originLon = inp.readDouble()
                        val radiusKm = inp.readDouble()
                        val rayCount = inp.readInt()
                        val stepMeters = inp.readDouble()
                        val numSteps = inp.readInt()
                        val sensitivityDbm = inp.readDouble()
                        val coveredAreaKm2 = inp.readDouble()
                        val northLat = inp.readDouble()
                        val southLat = inp.readDouble()
                        val westLon = inp.readDouble()
                        val eastLon = inp.readDouble()

                        if (rayCount <= 0 || numSteps <= 0 || rayCount * numSteps > 4_000_000) {
                            return null
                        }
                        val total = rayCount * numSteps
                        val statusGrid = ByteArray(total)
                        inp.readFully(statusGrid)
                        val rssiGrid = FloatArray(total)
                        for (i in 0 until total) {
                            rssiGrid[i] = inp.readShort() / 20f
                        }

                        PolarVisibilityField(
                            originLat = originLat,
                            originLon = originLon,
                            radiusKm = radiusKm,
                            rayCount = rayCount,
                            stepMeters = stepMeters,
                            numSteps = numSteps,
                            sensitivityDbm = sensitivityDbm,
                            statusGrid = statusGrid,
                            rssiGrid = rssiGrid,
                            coveredAreaKm2 = coveredAreaKm2,
                            northLat = northLat,
                            southLat = southLat,
                            westLon = westLon,
                            eastLon = eastLon
                        )
                    }
                }
            }.getOrNull()
        }

        /**
         * Rasterizes a Mercator-projected sub-rectangle of a dual TX+RX Bridge coverage field
         * with anti-aliased transitions across TX-only (Amber), RX-only (Sky Blue), and Mutual Overlap.
         */
        fun rasterizeDualBridgeSubRegion(
            txField: PolarVisibilityField,
            rxField: PolarVisibilityField,
            sensitivityDbm: Double,
            renderNorthLat: Double,
            renderSouthLat: Double,
            renderWestLon: Double,
            renderEastLon: Double,
            width: Int,
            height: Int
        ): Bitmap {
            val safeW = width.coerceAtLeast(1)
            val safeH = height.coerceAtLeast(1)
            val pixels = IntArray(safeW * safeH)

            val mercNorth = latToMercY(renderNorthLat)
            val mercSouth = latToMercY(renderSouthLat)
            val mercSpan = mercSouth - mercNorth
            val lonSpan = renderEastLon - renderWestLon

            val midLatCos = cos(Math.toRadians((renderNorthLat + renderSouthLat) * 0.5)).coerceAtLeast(0.15)
            val heightMeters = (renderNorthLat - renderSouthLat).coerceAtLeast(1e-6) * 111_132.0
            val widthMeters = lonSpan.coerceAtLeast(1e-6) * 111_320.0 * midLatCos
            val pixelMeters = max(widthMeters / safeW, heightMeters / safeH)

            val txDxCol = DoubleArray(safeW) { px ->
                val xFrac = (px + 0.5) / safeW.toDouble()
                val lon = renderWestLon + xFrac * lonSpan
                (lon - txField.originLon) * txField.kmPerDegLon
            }
            val rxDxCol = DoubleArray(safeW) { px ->
                val xFrac = (px + 0.5) / safeW.toDouble()
                val lon = renderWestLon + xFrac * lonSpan
                (lon - rxField.originLon) * rxField.kmPerDegLon
            }

            val txOut = DoubleArray(3)
            val rxOut = DoubleArray(3)

            for (py in 0 until safeH) {
                val yFrac = (py + 0.5) / safeH.toDouble()
                val lat = mercYToLat(mercNorth + yFrac * mercSpan)
                val txDyKm = (lat - txField.originLat) * txField.kmPerDegLat
                val rxDyKm = (lat - rxField.originLat) * rxField.kmPerDegLat
                val rowOffset = py * safeW

                for (px in 0 until safeW) {
                    val hasTx = txField.sampleContinuousFromKm(txDxCol[px], txDyKm, pixelMeters, txOut)
                    val hasRx = rxField.sampleContinuousFromKm(rxDxCol[px], rxDyKm, pixelMeters, rxOut)
                    if (!hasTx && !hasRx) continue

                    val txEdge = if (hasTx) txOut[0] else 0.0
                    val rxEdge = if (hasRx) rxOut[0] else 0.0
                    val mutualEdge = min(txEdge, rxEdge)

                    val mutualThresholdDbm = max(txField.sensitivityDbm, rxField.sensitivityDbm)
                    if (mutualEdge >= 0.99) {
                        val txMargin = txOut[2] - txField.sensitivityDbm
                        val rxMargin = rxOut[2] - rxField.sensitivityDbm
                        val worstMargin = min(txMargin, rxMargin)
                        val displayRssi = mutualThresholdDbm + worstMargin
                        val grazingFrac = max(txOut[1], rxOut[1])
                        val baseAlpha = 225.0 - grazingFrac * (225.0 - 165.0)
                        val alpha = (baseAlpha * mutualEdge).roundToInt().coerceIn(0, 255)
                        val rgb = ViewshedEngine.colorForRssi(
                            rssi = displayRssi,
                            sensitivityDbm = mutualThresholdDbm,
                            isGrazing = grazingFrac >= 0.5
                        )
                        pixels[rowOffset + px] = (alpha shl 24) or (rgb and 0x00FFFFFF)
                    } else if (mutualEdge <= 0.01) {
                        if (txEdge >= rxEdge) {
                            val p = ViewshedEngine.rssiNormalized(txOut[2], txField.sensitivityDbm)
                            val clearA = 95.0 + p * 90.0
                            val grazA = 70.0 + p * 65.0
                            val baseA = clearA - txOut[1] * (clearA - grazA)
                            val alpha = (baseA * txEdge).roundToInt().coerceIn(0, 255)
                            if (alpha > 0) {
                                pixels[rowOffset + px] = (alpha shl 24) or 0x00F59E0B
                            }
                        } else {
                            val p = ViewshedEngine.rssiNormalized(rxOut[2], rxField.sensitivityDbm)
                            val clearA = 95.0 + p * 90.0
                            val grazA = 70.0 + p * 65.0
                            val baseA = clearA - rxOut[1] * (clearA - grazA)
                            val alpha = (baseA * rxEdge).roundToInt().coerceIn(0, 255)
                            if (alpha > 0) {
                                pixels[rowOffset + px] = (alpha shl 24) or 0x0038BDF8
                            }
                        }
                    } else {
                        // Crisp anti-aliased blend across the ~1.5px transition into/out of mutual bridge zone
                        val singleIsTx = txEdge >= rxEdge
                        val sR: Int
                        val sG: Int
                        val sB: Int
                        val singleBaseA: Double
                        if (singleIsTx) {
                            val p = ViewshedEngine.rssiNormalized(txOut[2], txField.sensitivityDbm)
                            val clearA = 95.0 + p * 90.0
                            val grazA = 70.0 + p * 65.0
                            singleBaseA = clearA - txOut[1] * (clearA - grazA)
                            sR = 0xF5; sG = 0x9E; sB = 0x0B
                        } else {
                            val p = ViewshedEngine.rssiNormalized(rxOut[2], rxField.sensitivityDbm)
                            val clearA = 95.0 + p * 90.0
                            val grazA = 70.0 + p * 65.0
                            singleBaseA = clearA - rxOut[1] * (clearA - grazA)
                            sR = 0x38; sG = 0xBD; sB = 0xF8
                        }

                        val txMargin = txOut[2] - txField.sensitivityDbm
                        val rxMargin = rxOut[2] - rxField.sensitivityDbm
                        val worstMargin = min(txMargin, rxMargin)
                        val displayRssi = mutualThresholdDbm + worstMargin
                        val grazingFrac = max(txOut[1], rxOut[1])
                        val mutualRgb = ViewshedEngine.colorForRssi(
                            rssi = displayRssi,
                            sensitivityDbm = mutualThresholdDbm,
                            isGrazing = grazingFrac >= 0.5
                        )
                        val mR = Color.red(mutualRgb)
                        val mG = Color.green(mutualRgb)
                        val mB = Color.blue(mutualRgb)
                        val mutualBaseA = 225.0 - grazingFrac * (225.0 - 165.0)

                        val maxEdge = max(txEdge, rxEdge)
                        val blend = (mutualEdge / maxEdge.coerceAtLeast(1e-6)).coerceIn(0.0, 1.0)
                        val r = (sR + (mR - sR) * blend).roundToInt().coerceIn(0, 255)
                        val g = (sG + (mG - sG) * blend).roundToInt().coerceIn(0, 255)
                        val b = (sB + (mB - sB) * blend).roundToInt().coerceIn(0, 255)
                        val alpha = ((singleBaseA + (mutualBaseA - singleBaseA) * blend) * maxEdge)
                            .roundToInt()
                            .coerceIn(0, 255)
                        if (alpha > 0) {
                            pixels[rowOffset + px] = (alpha shl 24) or (r shl 16) or (g shl 8) or b
                        }
                    }
                }
            }

            return Bitmap.createBitmap(pixels, safeW, safeH, Bitmap.Config.ARGB_8888)
        }

        /**
         * Combines multiple overlapping node PolarVisibilityFields across a Mercator-projected
         * viewport sub-rectangle using non-coherent RF power-sum aggregation (dBm) and smooth
         * anti-aliased union edges.
         */
        fun rasterizeAggregateSubRegion(
            fields: List<PolarVisibilityField>,
            sensitivityDbm: Double,
            renderNorthLat: Double,
            renderSouthLat: Double,
            renderWestLon: Double,
            renderEastLon: Double,
            width: Int,
            height: Int
        ): Bitmap {
            val safeW = width.coerceAtLeast(1)
            val safeH = height.coerceAtLeast(1)
            val pixels = IntArray(safeW * safeH)
            if (fields.isEmpty()) {
                return Bitmap.createBitmap(pixels, safeW, safeH, Bitmap.Config.ARGB_8888)
            }

            val mercNorth = latToMercY(renderNorthLat)
            val mercSouth = latToMercY(renderSouthLat)
            val mercSpan = mercSouth - mercNorth
            val lonSpan = renderEastLon - renderWestLon

            val midLatCos = cos(Math.toRadians((renderNorthLat + renderSouthLat) * 0.5)).coerceAtLeast(0.15)
            val heightMeters = (renderNorthLat - renderSouthLat).coerceAtLeast(1e-6) * 111_132.0
            val widthMeters = lonSpan.coerceAtLeast(1e-6) * 111_320.0 * midLatCos
            val pixelMeters = max(widthMeters / safeW, heightMeters / safeH)
            val pixelKm = (pixelMeters / 1000.0).coerceAtLeast(0.0005)

            val fieldCount = fields.size
            val originLats = DoubleArray(fieldCount)
            val originLons = DoubleArray(fieldCount)
            val kmPerDegLons = DoubleArray(fieldCount)
            val outerMaxSqKm = DoubleArray(fieldCount)
            val norths = DoubleArray(fieldCount)
            val souths = DoubleArray(fieldCount)
            val wests = DoubleArray(fieldCount)
            val easts = DoubleArray(fieldCount)

            for (i in 0 until fieldCount) {
                val f = fields[i]
                originLats[i] = f.originLat
                originLons[i] = f.originLon
                kmPerDegLons[i] = f.kmPerDegLon
                val outer = f.radiusKm + pixelKm * 1.25
                outerMaxSqKm[i] = outer * outer
                norths[i] = f.northLat + 0.001
                souths[i] = f.southLat - 0.001
                wests[i] = f.westLon - 0.001
                easts[i] = f.eastLon + 0.001
            }

            val colLons = DoubleArray(safeW) { px ->
                val xFrac = (px + 0.5) / safeW.toDouble()
                renderWestLon + xFrac * lonSpan
            }

            val activeRowFields = IntArray(fieldCount)
            val sampleOut = DoubleArray(3)

            for (py in 0 until safeH) {
                val yFrac = (py + 0.5) / safeH.toDouble()
                val lat = mercYToLat(mercNorth + yFrac * mercSpan)

                var activeCount = 0
                for (i in 0 until fieldCount) {
                    if (lat <= norths[i] && lat >= souths[i]) {
                        activeRowFields[activeCount++] = i
                    }
                }
                if (activeCount == 0) continue

                val rowOffset = py * safeW
                for (px in 0 until safeW) {
                    val lon = colLons[px]

                    var linearPowerSum = 0.0
                    var unblockedComplement = 1.0
                    var clearStrength = 0.0
                    var totalEdge = 0.0

                    for (k in 0 until activeCount) {
                        val idx = activeRowFields[k]
                        if (lon < wests[idx] || lon > easts[idx]) continue

                        val dyKm = (lat - originLats[idx]) * 111.132
                        val dxKm = (lon - originLons[idx]) * kmPerDegLons[idx]
                        if (dxKm * dxKm + dyKm * dyKm > outerMaxSqKm[idx]) continue

                        val field = fields[idx]
                        if (field.sampleContinuousFromKm(dxKm, dyKm, pixelMeters, sampleOut)) {
                            val edge = sampleOut[0]
                            val grazing = sampleOut[1]
                            val rssi = sampleOut[2]

                            linearPowerSum += edge * 10.0.pow((rssi + 100.0) * 0.1)
                            unblockedComplement *= (1.0 - edge)
                            clearStrength += edge * (1.0 - grazing)
                            totalEdge += edge
                        }
                    }

                    if (totalEdge <= 0.001 || linearPowerSum <= 0.0) continue

                    val unionEdge = (1.0 - unblockedComplement).coerceIn(0.0, 1.0)
                    if (unionEdge <= 0.001) continue

                    // Normalize by unionEdge so AA boundary pixels keep true RSSI color instead of fading to red
                    val normalizedPower = linearPowerSum / unionEdge.coerceAtLeast(1e-6)
                    val aggRssiDbm = (10.0 * log10(normalizedPower.coerceAtLeast(1e-12))) - 100.0
                    val grazingFrac = (1.0 - (clearStrength / totalEdge.coerceAtLeast(1e-6))).coerceIn(0.0, 1.0)

                    val baseAlpha = 225.0 - grazingFrac * (225.0 - 165.0)
                    val alpha = (baseAlpha * unionEdge).roundToInt().coerceIn(0, 255)
                    if (alpha <= 0) continue

                    val rgb = ViewshedEngine.colorForRssi(
                        rssi = aggRssiDbm,
                        sensitivityDbm = sensitivityDbm,
                        isGrazing = grazingFrac >= 0.5
                    )
                    pixels[rowOffset + px] = (alpha shl 24) or (rgb and 0x00FFFFFF)
                }
            }

            return Bitmap.createBitmap(pixels, safeW, safeH, Bitmap.Config.ARGB_8888)
        }

        private fun smoothstep(edge0: Double, edge1: Double, x: Double): Double {
            val span = edge1 - edge0
            if (kotlin.math.abs(span) < 1e-12) return if (x >= edge1) 1.0 else 0.0
            val t = ((x - edge0) / span).coerceIn(0.0, 1.0)
            return t * t * (3.0 - 2.0 * t)
        }

        fun latToMercY(latDeg: Double): Double {
            val latRad = Math.toRadians(latDeg.coerceIn(-85.05112878, 85.05112878))
            return ln(tan(PI / 4.0 + latRad / 2.0))
        }

        fun mercYToLat(mercY: Double): Double {
            return Math.toDegrees(2.0 * atan2(exp(mercY), 1.0) - PI / 2.0)
        }
    }
}
