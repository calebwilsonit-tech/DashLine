package app.seb3thehacker.gearslip

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import app.seb3thehacker.gearslip.audio.AudioCaptureService
import app.seb3thehacker.gearslip.audio.CarAudio
import app.seb3thehacker.gearslip.car.CarEnvironment
import app.seb3thehacker.gearslip.car.CarSettings
import app.seb3thehacker.gearslip.car.CarUi
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

/**
 * Debug-only harness: runs the real car UI on a virtual display (the same path a head unit
 * gets), taps where told, and saves what it rendered as PNGs in the external files dir.
 *
 *   adb shell am start -n app.seb3thehacker.gearslip/.CarPreviewActivity \
 *       --ei w 800 --ei h 480 --ei dpi 160 --es taps "400,440;90,440"
 *
 * Writes preview-0.png (before any tap), then one more per tap.
 */
class CarPreviewActivity : ComponentActivity() {

    private val main = Handler(Looper.getMainLooper())
    private var projector: ScreenProjector? = null
    private var reader: ImageReader? = null
    @Volatile private var latest: Bitmap? = null

    private val audioPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) askAudioCapture() else CarAudio.denied()
    }

    private val audioConsent = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            AudioCaptureService.start(this, result.resultCode, data)
        } else {
            GearslipLog.w("preview: audio capture consent declined")
            CarAudio.denied()
        }
    }

    private fun askAudioCapture() {
        val manager = getSystemService(android.media.projection.MediaProjectionManager::class.java)
        runCatching { audioConsent.launch(manager.createScreenCaptureIntent()) }
            .onFailure { GearslipLog.e("preview: could not ask for audio capture", it); CarAudio.denied() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GearslipLog.init(this)
        CarSettings.init(this)
        CarEnvironment.start(this)

        val w = intent.getIntExtra("w", 800)
        val h = intent.getIntExtra("h", 480)
        val dpi = intent.getIntExtra("dpi", 160)
        val taps = intent.getStringExtra("taps").orEmpty().split(";").filter { it.isNotBlank() }
            .map { it.split(",").let { p -> p[0].toFloat() to p[1].toFloat() } }

        val imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        reader = imageReader
        imageReader.setOnImageAvailableListener({ r ->
            r.acquireLatestImage()?.use { image ->
                val plane = image.planes[0]
                val padded = plane.rowStride / plane.pixelStride
                val bmp = Bitmap.createBitmap(padded, h, Bitmap.Config.ARGB_8888)
                bmp.copyPixelsFromBuffer(plane.buffer)
                latest = Bitmap.createBitmap(bmp, 0, 0, w, h)
            }
        }, main)

        val p = ScreenProjector(this).also { projector = it }
        p.start(imageReader.surface, w, h, dpi) { app.seb3thehacker.gearslip.dash.DashUi() }

        // Stand in for the car's media sink, so the phone half of the audio path runs for real.
        if (intent.getBooleanExtra("audio", false)) {
            CarAudio.onConsentRequested = {
                runOnUiThread {
                    if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                    ) {
                        askAudioCapture()
                    } else {
                        audioPermission.launch(android.Manifest.permission.RECORD_AUDIO)
                    }
                }
            }
            CarAudio.attach(BenchAudioSink())
        }

        GearslipLog.i("preview: displayId=${p.displayId}")

        // Dashline keyframe check: --ez enctest true encodes 60 test-card frames per VideoTuning
        // mode on this phone's real encoder and logs how many came out as keyframes.
        if (intent.getBooleanExtra("enctest", false)) Thread {
            val before = app.seb3thehacker.gearslip.dash.VideoTuning.mode.value
            for (m in app.seb3thehacker.gearslip.dash.VideoTuning.Mode.entries) {
                app.seb3thehacker.gearslip.dash.VideoTuning.set(m)
                var frames = 0
                var keys = 0
                var bytes = 0L
                val done = java.util.concurrent.CountDownLatch(1)
                val src = VideoSource(800, 480, 30, onCodecConfig = {}, onFrame = { d, _, k ->
                    frames++; bytes += d.size; if (k) keys++
                    if (frames >= 60) done.countDown()
                })
                runCatching { src.start(); done.await(10, java.util.concurrent.TimeUnit.SECONDS) }
                src.stop()
                GearslipLog.i("enctest: $m -> $keys of $frames frames were keyframes, avg ${if (frames > 0) bytes / frames else 0} bytes")
            }
            app.seb3thehacker.gearslip.dash.VideoTuning.set(before)
        }.start()

        // Dashline maps without speaking: --es navsearch "costco" searches, then previews the top result.
        intent.getStringExtra("navsearch")?.let { q ->
            kotlinx.coroutines.MainScope().launch {
                kotlinx.coroutines.delay(3000)
                app.seb3thehacker.gearslip.dash.DashNav.init(this@CarPreviewActivity)
                app.seb3thehacker.gearslip.dash.DashNav.search(q)
                val s = kotlinx.coroutines.withTimeoutOrNull(20_000) {
                    app.seb3thehacker.gearslip.dash.DashNav.state.first { it.phase != app.seb3thehacker.gearslip.dash.DashNav.Phase.SEARCHING }
                }
                GearslipLog.i("navsearch: ${s?.phase} ${s?.results?.size ?: 0} results ${s?.error.orEmpty()}")
                s?.results?.firstOrNull()?.let { app.seb3thehacker.gearslip.dash.DashNav.preview(it) }
                val r = kotlinx.coroutines.withTimeoutOrNull(25_000) {
                    app.seb3thehacker.gearslip.dash.DashNav.state.first { it.phase != app.seb3thehacker.gearslip.dash.DashNav.Phase.ROUTING }
                }
                GearslipLog.i("navsearch: route ${r?.phase} ${r?.routes?.firstOrNull()?.let { "${(it.distanceMeters / 1609).toInt()} mi, ${(it.durationSeconds / 60).toInt()} min, ${it.maneuvers.size} steps" }} ${r?.error.orEmpty()}")
            }
        }

        // Dashline assistant without speaking: --es ask "call sam" logs what it would answer.
        intent.getStringExtra("ask")?.let { q ->
            kotlinx.coroutines.MainScope().launch {
                kotlinx.coroutines.delay(2500)
                val said = app.seb3thehacker.gearslip.dash.DashAssistant.handle(this@CarPreviewActivity, q)
                GearslipLog.i("ask: \"$q\" -> \"$said\"")
                app.seb3thehacker.gearslip.car.CarAssistant.reply(said)
            }
        }

        // Dashline garage, without holding a button: --es garage dry|press (fires after 3 s).
        intent.getStringExtra("garage")?.let { mode ->
            main.postDelayed({ app.seb3thehacker.gearslip.dash.Garage.press(this, dryRun = mode != "press") }, 3000)
        }
        if (intent.getBooleanExtra("hide", false)) p.setOverlayVisible(false)

        intent.getStringExtra("launch")?.let { pkg ->
            main.postDelayed({
                packageManager.getLaunchIntentForPackage(pkg)?.let { p.launchApp(it) }
                    ?: GearslipLog.e("preview: no launch intent for $pkg")
            }, 1500)
        }

        var step = 0
        fun snap() {
            val bmp = latest ?: return GearslipLog.e("preview: no frame yet for step $step")
            val out = File(getExternalFilesDir(null), "preview-$step.png")
            FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            GearslipLog.i("preview: wrote ${out.name}")
            step++
        }
        // A two-finger pinch: "cx,cy,startGap,endGap" in projected pixels, after "pinchAfter" ms.
        intent.getStringExtra("pinch")?.let { spec ->
            val (cx, cy, gap0, gap1) = spec.split(",").map { it.toFloat() }
            val pair = { gap: Float -> listOf(TouchPoint(3, cx - gap / 2, cy), TouchPoint(7, cx + gap / 2, cy)) }
            val after = intent.getIntExtra("pinchAfter", 9000).toLong()
            main.postDelayed({
                snap()
                p.dispatchTouch(MotionEvent.ACTION_DOWN, 0, listOf(pair(gap0)[0]))
                p.dispatchTouch(MotionEvent.ACTION_POINTER_DOWN, 1, pair(gap0))
                val steps = 14
                for (i in 1..steps) main.postDelayed({
                    p.dispatchTouch(MotionEvent.ACTION_MOVE, 0, pair(gap0 + (gap1 - gap0) * i / steps))
                }, 40L * i)
                main.postDelayed({
                    p.dispatchTouch(MotionEvent.ACTION_POINTER_UP, 1, pair(gap1))
                    p.dispatchTouch(MotionEvent.ACTION_UP, 0, listOf(pair(gap1)[0]))
                }, 40L * (steps + 1))
                main.postDelayed({ snap() }, 40L * (steps + 1) + 2000)
            }, after)
        }

        // Free-running mode: keep snapshotting on a timer so the display can be driven from
        // outside (adb `am start --display` / `input -d`) while the harness records it.
        val snaps = intent.getIntExtra("snaps", 0)
        if (snaps > 0) {
            val every = intent.getIntExtra("every", 3000).toLong()
            repeat(snaps) { i -> main.postDelayed({ snap() }, 2000L + every * i) }
            return
        }

        main.postDelayed({
            snap()
            taps.forEachIndexed { i, (x, y) ->
                main.postDelayed({
                    val point = listOf(TouchPoint(0, x, y))
                    p.dispatchTouch(MotionEvent.ACTION_DOWN, 0, point)
                    main.postDelayed({ p.dispatchTouch(MotionEvent.ACTION_UP, 0, point) }, 80)
                }, 2500L * (i + 1))
                main.postDelayed({ snap() }, 2500L * (i + 1) + 1500)
            }
        }, 2000)
    }

    override fun onDestroy() {
        super.onDestroy()
        projector?.stop()
        reader?.close()
        CarAudio.onConsentRequested = null
        CarAudio.release()
        CarEnvironment.stop()
    }
}
