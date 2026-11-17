package com.example.fluxgalatic

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.Surface
import android.view.SurfaceHolder
import android.view.WindowManager
import java.util.Locale
import java.util.Random
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import kotlin.math.abs

private const val SEG = 20
private const val CAM = 4.5f
private const val LX = -0.352f
private const val LY = 0.5535f
private const val LZ = 0.7547f
private const val MAX_LOC_AGE_MS = 30 * 60_000L
private const val ECL_RINGS = 3
private const val ECL_SEG = 72
private const val ECL_RAYS = 12
private const val ECL_RADIUS = 1.1f
private const val GAL_RADIUS = 1.7f
private const val R0_KM = 8275.0 * 3.0856775814913673e13
private const val AU_KM = 149597870.7
private const val SUN_APPROACH_KMS = 11.1
class GalaxyArrowWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = ArrowEngine()

    private class Tri(val v: FloatArray, val n: FloatArray, val head: Boolean)

    private inner class ArrowEngine : Engine(), SensorEventListener {

        private val handler = Handler(Looper.getMainLooper())
        private val sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        private val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        private val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        private val density = resources.displayMetrics.density

        private var visible = false
        private var width = 0
        private var height = 0

        // ---------- Sensoren ----------
        private val rotMat = FloatArray(9).also { it[0] = 1f; it[4] = 1f; it[8] = 1f }
        private val tmpRot = FloatArray(9)
        private val grav = FloatArray(3)
        private val mag = FloatArray(3)
        private var haveGrav = false
        private var haveMag = false
        private var sensorAccuracy = -1
        // ---------- Ort ----------
        private var latDeg = 50.0
        private var lonDeg = TimeZone.getDefault().rawOffset / 3600000.0 * 15.0
        private var hasRealLocation = false
        private var declDeg = 0f
        private var declTime = 0L
        private var locCheckTime = 0L
        private var lastLoc: Location? = null
        private var freshRequestTime = 0L
        private var cancelSignal: CancellationSignal? = null
        // ---------- Richtung ----------
        private val dirS = DoubleArray(3)
        private val upS = DoubleArray(3)
        private val sd = DoubleArray(3)
        private val su = DoubleArray(3)
        private val ub = DoubleArray(3)
        private val wb = DoubleArray(3)
        private var smoothInit = false
        private var altDeg = 0.0
        private var azDeg = 0.0

        // ---------- Zeichnen ----------
        private val bgPaint = Paint()
        private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL_AND_STROKE
            strokeWidth = 1.2f
            strokeJoin = Paint.Join.ROUND
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(170, 200, 212, 255)
            textAlign = Paint.Align.CENTER
        }
        private val path = Path()

        // ---------- Ekliptik ----------
        private val gridBack = Path()
        private val gridFront = Path()
        private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f * density
            strokeCap = Paint.Cap.ROUND
        }
        private val sunPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val eclPole = DoubleArray(3)
        private val eclEquinox = DoubleArray(3)
        private val eclN = DoubleArray(3)
        private val eclX = DoubleArray(3)
        private val eclY = DoubleArray(3)
        private var eclInit = false
        private var sunLonRad = 0.0
        private val ringCos = FloatArray(ECL_SEG + 1) { cos(2.0 * PI * it / ECL_SEG).toFloat() }
        private val ringSin = FloatArray(ECL_SEG + 1) { sin(2.0 * PI * it / ECL_SEG).toFloat() }
        private val ex = FloatArray(ECL_RINGS * (ECL_SEG + 1))
        private val ey = FloatArray(ECL_RINGS * (ECL_SEG + 1))
        private val ez = FloatArray(ECL_RINGS * (ECL_SEG + 1))
        private var sunPx = 0f
        private var sunPy = 0f
        private var sunZ = 0f
        private var eclCx = 0f
        private var eclCy = 0f

        private var distTime = 0L

        private val r0Long = R0_KM.toLong()
        private var distLine2 = ""
        private var distLine3 = ""
        // ---------- Milchstraßenebene ----------
        private val galFill = Path()
        private val galBack = Path()
        private val galFront = Path()
        private val galFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val galPole = DoubleArray(3)
        private val galN = DoubleArray(3)
        private val galY = DoubleArray(3)
        private var galInit = false
        private val gx = FloatArray(2 * (ECL_SEG + 1))
        private val gy = FloatArray(2 * (ECL_SEG + 1))
        private val gz = FloatArray(2 * (ECL_SEG + 1))
        private val hintPath = Path()
        private val hintLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
            strokeCap = Paint.Cap.ROUND
        }
        private val hintDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        // ---------- Sterne ----------
        private val starCount = 420
        private val starX = FloatArray(starCount)
        private val starY = FloatArray(starCount)
        private val starR = FloatArray(starCount)
        private val starPhase = FloatArray(starCount)
        private val starSpeed = FloatArray(starCount)
        private val starRgb = IntArray(starCount)
        private val starAlpha = IntArray(starCount)

        // ---------- Pfeil-Mesh ----------
        private val mesh = ArrayList<Tri>()
        private val maxTris = SEG * 6
        private val tv = FloatArray(9)
        private val pr = FloatArray(maxTris * 6)
        private val depth = FloatArray(maxTris)
        private val col = IntArray(maxTris)
        private val order = IntArray(maxTris)

        init {
            buildMesh()
            initStars()
        }

        private val locationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                lastLoc = location
                applyLocation(location)
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

            override fun onProviderEnabled(provider: String) {}

            override fun onProviderDisabled(provider: String) {}
        }

        private val frameRunnable = object : Runnable {
            override fun run() {
                drawFrame()
                if (visible) handler.postDelayed(this, 33)
            }
        }

        // =====================================================
        // Aufbau
        // =====================================================

        private fun buildMesh() {
            val rs = 0.13f
            val rh = 0.38f
            val zBack = -0.9f
            val zNeck = 0.25f
            val zTip = 1.0f
            val slope = rh / (zTip - zNeck)
            for (i in 0 until SEG) {
                val a0 = (2.0 * PI * i / SEG).toFloat()
                val a1 = (2.0 * PI * (i + 1) / SEG).toFloat()
                val am = (a0 + a1) / 2f
                val c0 = cos(a0); val s0 = sin(a0)
                val c1 = cos(a1); val s1 = sin(a1)
                val cm = cos(am); val sm = sin(am)

                // Schaft-Mantel (2 Dreiecke)
                val nSide = floatArrayOf(cm, sm, 0f)
                mesh.add(Tri(floatArrayOf(
                    rs * c0, rs * s0, zBack,
                    rs * c1, rs * s1, zBack,
                    rs * c1, rs * s1, zNeck), nSide, false))
                mesh.add(Tri(floatArrayOf(
                    rs * c0, rs * s0, zBack,
                    rs * c1, rs * s1, zNeck,
                    rs * c0, rs * s0, zNeck), nSide, false))

                // Schaft-Rückseite
                mesh.add(Tri(floatArrayOf(
                    0f, 0f, zBack,
                    rs * c1, rs * s1, zBack,
                    rs * c0, rs * s0, zBack), floatArrayOf(0f, 0f, -1f), false))

                // Ring unter dem Pfeilkopf (2 Dreiecke)
                val nDown = floatArrayOf(0f, 0f, -1f)
                mesh.add(Tri(floatArrayOf(
                    rs * c0, rs * s0, zNeck,
                    rh * c0, rh * s0, zNeck,
                    rh * c1, rh * s1, zNeck), nDown, true))
                mesh.add(Tri(floatArrayOf(
                    rs * c0, rs * s0, zNeck,
                    rh * c1, rh * s1, zNeck,
                    rs * c1, rs * s1, zNeck), nDown, true))

                // Kegel
                val nl = sqrt(1f + slope * slope)
                val nCone = floatArrayOf(cm / nl, sm / nl, slope / nl)
                mesh.add(Tri(floatArrayOf(
                    rh * c0, rh * s0, zNeck,
                    rh * c1, rh * s1, zNeck,
                    0f, 0f, zTip), nCone, true))
            }
        }

        private fun initStars() {
            val rnd = Random(2024)
            val palette = intArrayOf(0xFFFFFF, 0xCFE0FF, 0xFFF1D0, 0xFFD8B0, 0xB8C8FF)
            for (i in 0 until starCount) {
                starX[i] = rnd.nextFloat()
                starY[i] = rnd.nextFloat()
                val m = rnd.nextFloat()
                starR[i] = (0.5f + m * m * m * 1.8f) * density
                starAlpha[i] = (90 + m * 165).toInt()
                starPhase[i] = rnd.nextFloat() * 6.2831f
                starSpeed[i] = 0.4f + rnd.nextFloat() * 1.8f
                starRgb[i] = palette[rnd.nextInt(palette.size)]
            }
        }

        // =====================================================
        // Lebenszyklus
        // =====================================================

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
            super.onSurfaceChanged(holder, format, w, h)
            width = w
            height = h
            bgPaint.shader = LinearGradient(
                0f, 0f, 0f, h.toFloat(),
                intArrayOf(Color.rgb(2, 4, 14), Color.rgb(9, 8, 28), Color.rgb(3, 3, 10)),
                null, Shader.TileMode.CLAMP
            )
        }

        override fun onVisibilityChanged(isVisible: Boolean) {
            visible = isVisible
            handler.removeCallbacks(frameRunnable)
            if (isVisible) {
                registerSensors()
                registerLocation()
                requestFreshLocation()
                locCheckTime = 0L
                handler.post(frameRunnable)
            } else {
                unregisterAll()
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            visible = false
            handler.removeCallbacks(frameRunnable)
            unregisterAll()
        }

        override fun onDestroy() {
            super.onDestroy()
            visible = false
            handler.removeCallbacks(frameRunnable)
            unregisterAll()
        }

        private fun registerSensors() {
            val rv = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            if (rv != null) {
                sensorManager.registerListener(this, rv, SensorManager.SENSOR_DELAY_GAME)
            } else {
                val acc = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
                val mg = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
                if (acc != null) sensorManager.registerListener(this, acc, SensorManager.SENSOR_DELAY_GAME)
                if (mg != null) sensorManager.registerListener(this, mg, SensorManager.SENSOR_DELAY_GAME)
            }
        }

        private fun hasLocationPermission(): Boolean {
            val svc = this@GalaxyArrowWallpaperService
            return svc.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                    svc.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }

        private fun registerLocation() {
            if (!hasLocationPermission()) return
            try {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER, 10 * 60_000L, 5000f,
                    locationListener, Looper.getMainLooper()
                )
            } catch (e: SecurityException) {
                // Berechtigung wurde zur Laufzeit entzogen: Standort bleibt geschätzt
            } catch (e: Exception) {
                // Provider nicht vorhanden o. Ä.
            }
        }

        private fun requestFreshLocation() {
            if (!hasLocationPermission()) return
            val now = System.currentTimeMillis()
            if (now - freshRequestTime < 5 * 60_000L) return
            freshRequestTime = now
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val signal = CancellationSignal()
                    cancelSignal = signal
                    locationManager.getCurrentLocation(
                        LocationManager.NETWORK_PROVIDER,
                        signal,
                        this@GalaxyArrowWallpaperService.mainExecutor
                    ) { loc ->
                        if (loc != null) {
                            lastLoc = loc
                            applyLocation(loc)
                        }
                    }
                } else {
                    @Suppress("DEPRECATION")
                    locationManager.requestSingleUpdate(
                        LocationManager.NETWORK_PROVIDER,
                        locationListener,
                        Looper.getMainLooper()
                    )
                }
            } catch (e: SecurityException) {
                // Berechtigung fehlt: Standort bleibt geschätzt
            } catch (e: Exception) {
                // Provider nicht vorhanden oder abgeschaltet
            }
        }

        private fun unregisterAll() {
            try { sensorManager.unregisterListener(this) } catch (e: Exception) {}
            try { locationManager.removeUpdates(locationListener) } catch (e: Exception) {}
            try { cancelSignal?.cancel() } catch (e: Exception) {}
            cancelSignal = null
            haveGrav = false
            haveMag = false
        }

        // =====================================================
        // Sensoren
        // =====================================================

        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_ROTATION_VECTOR -> {
                    SensorManager.getRotationMatrixFromVector(rotMat, event.values)
                    sensorAccuracy = event.accuracy
                }
                Sensor.TYPE_ACCELEROMETER -> {
                    lowPass(event.values, grav, haveGrav)
                    haveGrav = true
                    updateFallbackMatrix()
                }
                Sensor.TYPE_MAGNETIC_FIELD -> {
                    lowPass(event.values, mag, haveMag)
                    haveMag = true
                    sensorAccuracy = event.accuracy
                    updateFallbackMatrix()
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            if (sensor != null &&
                (sensor.type == Sensor.TYPE_ROTATION_VECTOR || sensor.type == Sensor.TYPE_MAGNETIC_FIELD)
            ) {
                sensorAccuracy = accuracy
            }
        }

        private fun lowPass(input: FloatArray, out: FloatArray, init: Boolean) {
            for (i in 0 until 3) {
                out[i] = if (init) out[i] + 0.15f * (input[i] - out[i]) else input[i]
            }
        }

        private fun updateFallbackMatrix() {
            if (haveGrav && haveMag) {
                if (SensorManager.getRotationMatrix(tmpRot, null, grav, mag)) {
                    System.arraycopy(tmpRot, 0, rotMat, 0, 9)
                }
            }
        }


        // =====================================================
        // Ort
        // =====================================================

        private fun applyLocation(loc: Location) {
            if (loc.latitude != latDeg || loc.longitude != lonDeg || !hasRealLocation) {
                latDeg = loc.latitude
                lonDeg = loc.longitude
                hasRealLocation = true
                declTime = 0L
            }
        }

        private fun ageMs(l: Location): Long {
            val e = l.elapsedRealtimeNanos
            return if (e > 0L) {
                (SystemClock.elapsedRealtimeNanos() - e) / 1_000_000L
            } else {
                System.currentTimeMillis() - l.time
            }
        }

        private fun isRecent(l: Location): Boolean {
            return ageMs(l) <= MAX_LOC_AGE_MS
        }

        private fun refreshLocation(now: Long) {
            if (now - locCheckTime > 60_000L) {
                locCheckTime = now
                if (hasLocationPermission()) {
                    val old = lastLoc
                    var best: Location? = if (old != null && isRecent(old)) old else null
                    val providers = listOf(
                        LocationManager.NETWORK_PROVIDER,
                        LocationManager.PASSIVE_PROVIDER,
                        LocationManager.GPS_PROVIDER
                    )
                    for (p in providers) {
                        try {
                            val l = locationManager.getLastKnownLocation(p)
                            if (l != null && isRecent(l) && (best == null || l.time > best.time)) best = l
                        } catch (e: SecurityException) {
                            // Berechtigung fehlt für diesen Provider (z. B. GPS nur mit FINE)
                        } catch (e: Exception) {
                            // Provider nicht vorhanden o. Ä.
                        }
                    }
                    if (best != null) {
                        lastLoc = best
                        applyLocation(best)
                    }
                }
            }
            if (declTime == 0L || now - declTime > 3600_000L) {
                declDeg = try {
                    GeomagneticField(latDeg.toFloat(), lonDeg.toFloat(), 0f, now).declination
                } catch (e: Exception) {
                    0f
                }
                declTime = now
            }
        }

        // =====================================================
        // Astronomie + Richtung
        // =====================================================

        private fun toScreen(x: Double, y: Double, z: Double, out: DoubleArray) {
            @Suppress("DEPRECATION")
            val rot = windowManager.defaultDisplay.rotation
            when (rot) {
                Surface.ROTATION_90 -> { out[0] = y; out[1] = -x; out[2] = z }
                Surface.ROTATION_180 -> { out[0] = -x; out[1] = -y; out[2] = z }
                Surface.ROTATION_270 -> { out[0] = -y; out[1] = x; out[2] = z }
                else -> { out[0] = x; out[1] = y; out[2] = z }
            }
        }

        private fun normalize(a: DoubleArray) {
            val l = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])
            if (l > 1e-9) {
                a[0] /= l; a[1] /= l; a[2] /= l
            }
        }

        private fun computeDirection(now: Long) {
            // Galaktisches Zentrum (Sgr A*), J2000: RA 266.40499°, Dec -28.93617°
            val d = now / 86400000.0 + 2440587.5 - 2451545.0
            var gmst = (280.46061837 + 360.98564736629 * d) % 360.0
            if (gmst < 0) gmst += 360.0
            val lst = Math.toRadians(gmst + lonDeg)
            val ra = Math.toRadians(266.40499)
            val dec = Math.toRadians(-28.93617)
            val ha = lst - ra
            val lat = Math.toRadians(latDeg)

            // Lokales Horizontsystem: Ost, Nord, Oben (wahr Nord)
            val east = -cos(dec) * sin(ha)
            val north = sin(dec) * cos(lat) - cos(dec) * sin(lat) * cos(ha)
            val up = sin(dec) * sin(lat) + cos(dec) * cos(lat) * cos(ha)
            altDeg = Math.toDegrees(asin(up.coerceIn(-1.0, 1.0)))
            azDeg = (Math.toDegrees(atan2(east, north)) + 360.0) % 360.0

            // Missweisung: wahr Nord -> magnetisch Nord (Sensor-Welt zeigt zum Magnetpol)
            val decl = Math.toRadians(declDeg.toDouble())
            val eM = east * cos(decl) - north * sin(decl)
            val nM = north * cos(decl) + east * sin(decl)

            // Welt (Ost, Nord, Oben) -> Gerät: v_dev = R^T * v_world
            val r = rotMat
            val dvx = r[0] * eM + r[3] * nM + r[6] * up
            val dvy = r[1] * eM + r[4] * nM + r[7] * up
            val dvz = r[2] * eM + r[5] * nM + r[8] * up
            toScreen(dvx, dvy, dvz, sd)
            // "Oben" der Welt im Geräte-System (aus der Schwerkraft) als Roll-Referenz
            toScreen(r[6].toDouble(), r[7].toDouble(), r[8].toDouble(), su)

            if (!smoothInit) {
                for (k in 0 until 3) { dirS[k] = sd[k]; upS[k] = su[k] }
                smoothInit = true
            } else {
                for (k in 0 until 3) {
                    dirS[k] += (sd[k] - dirS[k]) * 0.2
                    upS[k] += (su[k] - upS[k]) * 0.2
                }
            }
            normalize(dirS)
            normalize(upS)
        }

        // =====================================================
        // Zeichnen
        // =====================================================

        private fun drawFrame() {
            val holder = surfaceHolder
            var canvas: Canvas? = null
            try {
                canvas = holder.lockCanvas()
                if (canvas != null) render(canvas)
            } catch (e: Exception) {
            } finally {
                if (canvas != null) {
                    try { holder.unlockCanvasAndPost(canvas) } catch (e: Exception) {}
                }
            }
        }

        private fun render(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val now = System.currentTimeMillis()
            refreshLocation(now)
            computeDirection(now)
            computeEcliptic(now)
            computeGalactic(now)
            buildEcliptic(w, h)
            buildGalactic(w, h)

            canvas.drawPaint(bgPaint)
            drawStars(canvas, w, h, now)
            drawGalactic(canvas, false, w)
            drawEcliptic(canvas, false)
            drawArrow(canvas, w, h)
            drawGalactic(canvas, true, w)
            drawEcliptic(canvas, true)
            drawHint(canvas, w, h, now)
            drawDistance(canvas, w, h, now)
        }

        private fun computeGalactic(now: Long) {
            val d = now / 86400000.0 + 2440587.5 - 2451545.0
            var gmst = (280.46061837 + 360.98564736629 * d) % 360.0
            if (gmst < 0) gmst += 360.0
            val lst = Math.toRadians(gmst + lonDeg)
            val lat = Math.toRadians(latDeg)
            val decl = Math.toRadians(declDeg.toDouble())

            // galaktischer Nordpol (J2000): RA 192,85948°, Dec +27,12825°
            celestialToScreen(
                Math.toRadians(192.85948), Math.toRadians(27.12825),
                lst, lat, decl, galPole
            )

            if (!galInit) {
                for (k in 0 until 3) galN[k] = galPole[k]
                galInit = true
            } else {
                for (k in 0 until 3) galN[k] += (galPole[k] - galN[k]) * 0.2
            }
            normalize(galN)

            // senkrecht zur Pfeilachse (galaktisches Zentrum), damit der Pfeil exakt in der Ebene liegt
            val dot = galN[0] * dirS[0] + galN[1] * dirS[1] + galN[2] * dirS[2]
            for (k in 0 until 3) galN[k] -= dot * dirS[k]
            normalize(galN)

            // galaktische Länge 90° = Nordpol x Pfeilachse
            cross(galN[0], galN[1], galN[2], dirS[0], dirS[1], dirS[2], galY)
        }

        private fun buildGalactic(w: Float, h: Float) {
            // gleiche Maße wie beim Pfeil
            val size = min(w, h) * 0.28f
            val cx = w / 2f
            val cy = h * 0.46f
            galFill.reset()
            galBack.reset()
            galFront.reset()

            // Ring 0 = Innenring, Ring 1 = Rand
            for (k in 0 until 2) {
                val r = GAL_RADIUS * (k + 1) / 2
                for (j in 0..ECL_SEG) {
                    val c = ringCos[j] * r
                    val s = ringSin[j] * r
                    // Achse 1 = Pfeilrichtung (Länge 0°), Achse 2 = Länge 90°
                    val x = (c * dirS[0] + s * galY[0]).toFloat()
                    val y = (c * dirS[1] + s * galY[1]).toFloat()
                    val z = (c * dirS[2] + s * galY[2]).toFloat()
                    val p = CAM / (CAM - z)
                    val i = k * (ECL_SEG + 1) + j
                    gx[i] = cx + x * size * p
                    gy[i] = cy - y * size * p
                    gz[i] = z
                }
            }

            val rim = ECL_SEG + 1

            // Fläche (Handtuch)
            galFill.moveTo(gx[rim], gy[rim])
            for (j in 1 until ECL_SEG) galFill.lineTo(gx[rim + j], gy[rim + j])
            galFill.close()

            // Ringe
            for (k in 0 until 2) {
                for (j in 0 until ECL_SEG) {
                    val i0 = k * (ECL_SEG + 1) + j
                    val i1 = i0 + 1
                    val target = if (gz[i0] + gz[i1] < 0f) galBack else galFront
                    target.moveTo(gx[i0], gy[i0])
                    target.lineTo(gx[i1], gy[i1])
                }
            }

            // vier Speichen: Länge 0°, 90°, 180°, 270°
            for (a in 0 until 4) {
                val i = rim + a * (ECL_SEG / 4)
                val target = if (gz[i] < 0f) galBack else galFront
                target.moveTo(cx, cy)
                target.lineTo(gx[i], gy[i])
            }
        }

        private fun drawGalacticText(canvas: Canvas, text: String, x: Float, y: Float, w: Float) {
            val half = textPaint.measureText(text) / 2f
            val margin = 4f * density
            val tx = x.coerceIn(half + margin, w - half - margin)
            canvas.drawText(text, tx, y, textPaint)
        }

        private fun drawGalactic(canvas: Canvas, front: Boolean, w: Float) {
            if (!front) {
                galFillPaint.color = Color.argb(34, 200, 140, 255)
                canvas.drawPath(galFill, galFillPaint)
            }
            gridPaint.color = Color.argb(if (front) 150 else 70, 210, 150, 255)
            canvas.drawPath(if (front) galFront else galBack, gridPaint)

            if (front) {
                textPaint.textSize = 11f * density
                val rim = ECL_SEG + 1
                val iRot = rim + ECL_SEG / 4
                val iAnti = rim + ECL_SEG / 2
                drawGalacticText(canvas, "Rotationsrichtung", gx[iRot], gy[iRot] - 8f * density, w)
                drawGalacticText(canvas, "Antizentrum", gx[iAnti], gy[iAnti] - 8f * density, w)
            }
        }

        // veränderlicher Anteil der Entfernung in km (ohne den großen Basiswert, damit die Rechengenauigkeit reicht)
        private fun distanceVarKm(now: Long): Double {
            val d = now / 86400000.0 + 2440587.5 - 2451545.0

            // Sonne (geozentrisch, ekliptikal), einfache Näherung
            val g = Math.toRadians(357.528 + 0.9856003 * d)
            val lam = Math.toRadians(280.460 + 0.9856474 * d + 1.915 * sin(g) + 0.020 * sin(2.0 * g))
            val rAu = 1.00014 - 0.01671 * cos(g) - 0.00014 * cos(2.0 * g)
            val eps = Math.toRadians(23.4365)

            // Sonnenvektor im Äquatorsystem (in AE)
            val sx = rAu * cos(lam)
            val sy = rAu * sin(lam) * cos(eps)
            val sz = rAu * sin(lam) * sin(eps)

            // Einheitsvektor zum galaktischen Zentrum
            val ra = Math.toRadians(266.40499)
            val dec = Math.toRadians(-28.93617)
            val ux = cos(dec) * cos(ra)
            val uy = cos(dec) * sin(ra)
            val uz = sin(dec)

            // steht die Sonne in Richtung Zentrum, ist die Erde dahinter und damit weiter weg
            val earthTerm = (sx * ux + sy * uy + sz * uz) * AU_KM

            // Eigenbewegung der Sonne zum Zentrum hin, seit J2000
            val driftKm = SUN_APPROACH_KMS * d * 86400.0

            return earthTerm - driftKm
        }

        private fun drawDistance(canvas: Canvas, w: Float, h: Float, now: Long) {
            // nur alle 2 Sekunden neu berechnen
            if (distTime == 0L || now - distTime >= 1500L) {
                distTime = now
                // Basiswert als ganze Zahl, nur der kleine veränderliche Anteil wird in Double gerechnet
                val km = r0Long + Math.round(distanceVarKm(now))
                // Änderung pro Sekunde in km/s (aus dem veränderlichen Anteil, daher genau)
                val v = distanceVarKm(now + 1000L) - distanceVarKm(now)
                distLine2 = String.format(Locale.GERMAN, "%,d km", km)
                distLine3 = if (v < 0) {
                    String.format(Locale.GERMAN, "nähert sich mit %.1f km/s", -v)
                } else {
                    String.format(Locale.GERMAN, "entfernt sich mit %.1f km/s", v)
                }
            }

            textPaint.textSize = 12f * density
            val y0 = h * 0.78f
            val lh = 16f * density

            canvas.drawText("Entfernung zum Zentrum (Sgr A*)", w / 2f, y0, textPaint)
            canvas.drawText(distLine2, w / 2f, y0 + lh, textPaint)
            canvas.drawText(distLine3, w / 2f, y0 + 2f * lh, textPaint)
        }

        private fun drawStars(canvas: Canvas, w: Float, h: Float, now: Long) {
            val t = now / 1000.0
            for (i in 0 until starCount) {
                val tw = 0.6 + 0.4 * sin(t * starSpeed[i] + starPhase[i])
                val a = (starAlpha[i] * tw).toInt().coerceIn(0, 255)
                starPaint.color = (a shl 24) or starRgb[i]
                val x = starX[i] * w
                val y = starY[i] * h
                canvas.drawCircle(x, y, starR[i], starPaint)
                if (starR[i] > 1.6f * density) {
                    starPaint.color = ((a / 5) shl 24) or starRgb[i]
                    canvas.drawCircle(x, y, starR[i] * 2.4f, starPaint)
                }
            }
        }
        private fun celestialToScreen(
            ra: Double, dec: Double, lst: Double, lat: Double, decl: Double, out: DoubleArray
        ) {
            val ha = lst - ra
            val east = -cos(dec) * sin(ha)
            val north = sin(dec) * cos(lat) - cos(dec) * sin(lat) * cos(ha)
            val up = sin(dec) * sin(lat) + cos(dec) * cos(lat) * cos(ha)
            // wahrer Norden -> magnetischer Norden
            val eM = east * cos(decl) - north * sin(decl)
            val nM = north * cos(decl) + east * sin(decl)
            val r = rotMat
            toScreen(
                r[0] * eM + r[3] * nM + r[6] * up,
                r[1] * eM + r[4] * nM + r[7] * up,
                r[2] * eM + r[5] * nM + r[8] * up,
                out
            )
        }

        private fun computeEcliptic(now: Long) {
            val d = now / 86400000.0 + 2440587.5 - 2451545.0
            var gmst = (280.46061837 + 360.98564736629 * d) % 360.0
            if (gmst < 0) gmst += 360.0
            val lst = Math.toRadians(gmst + lonDeg)
            val lat = Math.toRadians(latDeg)
            val decl = Math.toRadians(declDeg.toDouble())

            // Nordpol der Ekliptik: RA 270°, Dec 90° minus Schiefe der Ekliptik (23,4365°)
            celestialToScreen(Math.toRadians(270.0), Math.toRadians(90.0 - 23.4365), lst, lat, decl, eclPole)
            // Frühlingspunkt (Ekliptiklänge 0°): RA 0°, Dec 0°
            celestialToScreen(0.0, 0.0, lst, lat, decl, eclEquinox)

            if (!eclInit) {
                for (k in 0 until 3) {
                    eclN[k] = eclPole[k]
                    eclX[k] = eclEquinox[k]
                }
                eclInit = true
            } else {
                for (k in 0 until 3) {
                    eclN[k] += (eclPole[k] - eclN[k]) * 0.2
                    eclX[k] += (eclEquinox[k] - eclX[k]) * 0.2
                }
            }
            normalize(eclN)
            val dot = eclX[0] * eclN[0] + eclX[1] * eclN[1] + eclX[2] * eclN[2]
            for (k in 0 until 3) eclX[k] -= dot * eclN[k]
            normalize(eclX)
            // Ekliptiklänge 90° = Nordpol x Frühlingspunkt
            cross(eclN[0], eclN[1], eclN[2], eclX[0], eclX[1], eclX[2], eclY)

            // Sonne (einfache Näherung, für die Anzeige völlig ausreichend)
            val g = Math.toRadians(357.528 + 0.9856003 * d)
            val meanLon = 280.460 + 0.9856474 * d
            sunLonRad = Math.toRadians(meanLon + 1.915 * sin(g) + 0.020 * sin(2.0 * g))
        }

        private fun buildEcliptic(w: Float, h: Float) {
            // gleiche Maße wie beim Pfeil
            val size = min(w, h) * 0.28f
            val cx = w / 2f
            val cy = h * 0.46f
            eclCx = cx
            eclCy = cy
            gridBack.reset()
            gridFront.reset()

            for (k in 0 until ECL_RINGS) {
                val r = ECL_RADIUS * (k + 1) / ECL_RINGS
                for (j in 0..ECL_SEG) {
                    val c = ringCos[j] * r
                    val s = ringSin[j] * r
                    val x = (c * eclX[0] + s * eclY[0]).toFloat()
                    val y = (c * eclX[1] + s * eclY[1]).toFloat()
                    val z = (c * eclX[2] + s * eclY[2]).toFloat()
                    val p = CAM / (CAM - z)
                    val i = k * (ECL_SEG + 1) + j
                    ex[i] = cx + x * size * p
                    ey[i] = cy - y * size * p
                    ez[i] = z
                }
            }

            // Ringe
            for (k in 0 until ECL_RINGS) {
                for (j in 0 until ECL_SEG) {
                    val i0 = k * (ECL_SEG + 1) + j
                    val i1 = i0 + 1
                    val target = if (ez[i0] + ez[i1] < 0f) gridBack else gridFront
                    target.moveTo(ex[i0], ey[i0])
                    target.lineTo(ex[i1], ey[i1])
                }
            }

            // Strahlen vom Mittelpunkt zum äußeren Ring
            val outer = (ECL_RINGS - 1) * (ECL_SEG + 1)
            val step = ECL_SEG / ECL_RAYS
            for (a in 0 until ECL_RAYS) {
                val i = outer + a * step
                val target = if (ez[i] < 0f) gridBack else gridFront
                target.moveTo(cx, cy)
                target.lineTo(ex[i], ey[i])
            }

            // Sonne auf dem äußeren Ring
            val cs = cos(sunLonRad)
            val sn = sin(sunLonRad)
            val sx = (ECL_RADIUS * (cs * eclX[0] + sn * eclY[0])).toFloat()
            val sy = (ECL_RADIUS * (cs * eclX[1] + sn * eclY[1])).toFloat()
            val sz = (ECL_RADIUS * (cs * eclX[2] + sn * eclY[2])).toFloat()
            val p = CAM / (CAM - sz)
            sunPx = cx + sx * size * p
            sunPy = cy - sy * size * p
            sunZ = sz
        }

        private fun drawEcliptic(canvas: Canvas, front: Boolean) {
            gridPaint.color = Color.argb(if (front) 120 else 55, 110, 190, 255)
            canvas.drawPath(if (front) gridFront else gridBack, gridPaint)

            if ((sunZ >= 0f) == front) {
                val a = if (front) 255 else 150
                gridPaint.color = Color.argb(a / 2, 255, 214, 120)
                canvas.drawLine(eclCx, eclCy, sunPx, sunPy, gridPaint)
                sunPaint.color = Color.argb(a / 4, 255, 214, 120)
                canvas.drawCircle(sunPx, sunPy, 13f * density, sunPaint)
                sunPaint.color = Color.argb(a, 255, 226, 150)
                canvas.drawCircle(sunPx, sunPy, 6f * density, sunPaint)
                textPaint.textSize = 11f * density
                canvas.drawText("Sonne", sunPx, sunPy - 18f * density, textPaint)
            }
        }
        private fun cross(
            ax: Double, ay: Double, az: Double,
            bx: Double, by: Double, bz: Double,
            out: DoubleArray
        ): Double {
            out[0] = ay * bz - az * by
            out[1] = az * bx - ax * bz
            out[2] = ax * by - ay * bx
            return sqrt(out[0] * out[0] + out[1] * out[1] + out[2] * out[2])
        }

        private fun shade(base: Int, inten: Float, spec: Float): Int {
            val r = (Color.red(base) * inten + 255f * spec).toInt().coerceIn(0, 255)
            val g = (Color.green(base) * inten + 255f * spec).toInt().coerceIn(0, 255)
            val b = (Color.blue(base) * inten + 255f * spec).toInt().coerceIn(0, 255)
            return Color.rgb(r, g, b)
        }

        private fun drawArrow(canvas: Canvas, w: Float, h: Float) {
            val dx = dirS[0]
            val dy = dirS[1]
            val dz = dirS[2]

            // Orthonormale Basis: d = Pfeilachse, u, wv
            var len = cross(upS[0], upS[1], upS[2], dx, dy, dz, ub)
            if (len < 0.15) {
                len = cross(0.0, 1.0, 0.0, dx, dy, dz, ub)
                if (len < 0.15) len = cross(1.0, 0.0, 0.0, dx, dy, dz, ub)
            }
            ub[0] /= len; ub[1] /= len; ub[2] /= len
            cross(dx, dy, dz, ub[0], ub[1], ub[2], wb)

            val uxf = ub[0].toFloat(); val uyf = ub[1].toFloat(); val uzf = ub[2].toFloat()
            val wxf = wb[0].toFloat(); val wyf = wb[1].toFloat(); val wzf = wb[2].toFloat()
            val dxf = dx.toFloat(); val dyf = dy.toFloat(); val dzf = dz.toFloat()

            val size = min(w, h) * 0.28f
            val cx = w / 2f
            val cy = h * 0.46f
            val headColor = Color.rgb(255, 96, 48)
            val shaftColor = Color.rgb(255, 196, 80)

            var vis = 0
            for (i in mesh.indices) {
                val t = mesh[i]
                for (k in 0 until 3) {
                    val lx = t.v[3 * k]
                    val ly = t.v[3 * k + 1]
                    val lz = t.v[3 * k + 2]
                    tv[3 * k] = lx * uxf + ly * wxf + lz * dxf
                    tv[3 * k + 1] = lx * uyf + ly * wyf + lz * dyf
                    tv[3 * k + 2] = lx * uzf + ly * wzf + lz * dzf
                }
                val nx = t.n[0] * uxf + t.n[1] * wxf + t.n[2] * dxf
                val ny = t.n[0] * uyf + t.n[1] * wyf + t.n[2] * dyf
                val nz = t.n[0] * uzf + t.n[1] * wzf + t.n[2] * dzf

                val mx = (tv[0] + tv[3] + tv[6]) / 3f
                val my = (tv[1] + tv[4] + tv[7]) / 3f
                val mz = (tv[2] + tv[5] + tv[8]) / 3f

                var vx = -mx
                var vy = -my
                var vz = CAM - mz
                val vl = sqrt(vx * vx + vy * vy + vz * vz)
                vx /= vl; vy /= vl; vz /= vl

                // Rückseiten weglassen
                if (nx * vx + ny * vy + nz * vz <= 0.02f) continue

                val diff = max(0f, nx * LX + ny * LY + nz * LZ)
                val hx = LX + vx
                val hy = LY + vy
                val hz = LZ + vz
                val hl = sqrt(hx * hx + hy * hy + hz * hz)
                val spec = max(0f, (nx * hx + ny * hy + nz * hz) / hl).pow(28f) * 0.55f
                val inten = 0.30f + 0.70f * diff

                for (k in 0 until 3) {
                    val zz = tv[3 * k + 2]
                    val p = CAM / (CAM - zz)
                    pr[vis * 6 + 2 * k] = cx + tv[3 * k] * size * p
                    pr[vis * 6 + 2 * k + 1] = cy - tv[3 * k + 1] * size * p
                }
                depth[vis] = mz
                col[vis] = shade(if (t.head) headColor else shaftColor, inten, spec)
                order[vis] = vis
                vis++
            }

            // Painter's Algorithm: hinten zuerst (Insertion Sort)
            for (a in 1 until vis) {
                val key = order[a]
                var b = a - 1
                while (b >= 0 && depth[order[b]] > depth[key]) {
                    order[b + 1] = order[b]
                    b--
                }
                order[b + 1] = key
            }

            for (j in 0 until vis) {
                val idx = order[j]
                path.reset()
                path.moveTo(pr[idx * 6], pr[idx * 6 + 1])
                path.lineTo(pr[idx * 6 + 2], pr[idx * 6 + 3])
                path.lineTo(pr[idx * 6 + 4], pr[idx * 6 + 5])
                path.close()
                facePaint.color = col[idx]
                canvas.drawPath(path, facePaint)
            }
        }

        private fun drawHint(canvas: Canvas, w: Float, h: Float, now: Long) {
            // nur bei niedriger oder unzuverlässiger Sensor-Genauigkeit
            if (sensorAccuracy < 0 || sensorAccuracy >= 2) return

            val alpha = (150 + 60 * sin(now / 450.0)).toInt().coerceIn(0, 255)
            val cx = w / 2f
            val cy = h * 0.80f
            val rx = 34f * density
            val ry = 17f * density

            // Acht (liegende Lemniskate)
            hintPath.reset()
            val steps = 60
            for (i in 0..steps) {
                val t = 2.0 * PI * i / steps
                val x = cx + rx * sin(t).toFloat()
                val y = cy + ry * sin(2.0 * t).toFloat()
                if (i == 0) hintPath.moveTo(x, y) else hintPath.lineTo(x, y)
            }
            hintLinePaint.color = Color.argb(alpha / 2, 200, 212, 255)
            canvas.drawPath(hintPath, hintLinePaint)

            // wandernder Punkt entlang der Acht
            val tp = now / 700.0
            val px = cx + rx * sin(tp).toFloat()
            val py = cy + ry * sin(2.0 * tp).toFloat()
            hintDotPaint.color = Color.argb(alpha, 255, 214, 140)
            canvas.drawCircle(px, py, 4.5f * density, hintDotPaint)

            textPaint.textSize = 12f * density
            canvas.drawText(
                "Kompass ungenau: Handy in einer Acht bewegen",
                cx, cy + ry + 26f * density, textPaint
            )
        }

        private fun drawLabel(canvas: Canvas, w: Float, h: Float) {
            textPaint.textSize = 11f * density
            val y0 = h * 0.66f
            val lh = 15f * density

            val line1 = String.format(
                Locale.GERMAN, "gal. Zentrum: Höhe %.0f°, Azimut %.0f°", altDeg, azDeg
            )

            // Kompasswert laut Sensor: bei flachem Handy die Oberkante, sonst die Rückseite
            val r = rotMat
            val flat = abs(r[8]) > 0.7f
            val hx = if (flat) r[1] else -r[2]
            val hy = if (flat) r[4] else -r[5]
            val headMag = Math.toDegrees(atan2(hx.toDouble(), hy.toDouble()))
            val head = (headMag + declDeg + 360.0) % 360.0
            val genau = when (sensorAccuracy) {
                3 -> "hoch"
                2 -> "mittel"
                1 -> "niedrig"
                0 -> "unzuverlässig"
                else -> "?"
            }
            val line2 = String.format(
                Locale.GERMAN, "Kompass: %.0f° (%s) · Genauigkeit: %s",
                head, if (flat) "Oberkante" else "Rückseite", genau
            )

            val quelle = if (hasRealLocation) "Standort" else "Standort geschätzt"
            val line3 = String.format(
                Locale.GERMAN, "%s: %.2f° Breite, %.2f° Länge", quelle, latDeg, lonDeg
            )

            canvas.drawText(line1, w / 2f, y0, textPaint)
            canvas.drawText(line2, w / 2f, y0 + lh, textPaint)
            canvas.drawText(line3, w / 2f, y0 + 2 * lh, textPaint)
        }
    }
}