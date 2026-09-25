package com.asif.vmreader

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream

/**
 * The only screen of the app.
 *  - "Load model": pick ggml-bn-small-q5_0.bin once (copied into the app)
 *  - WhatsApp → Share → VM Reader: the voice message is turned into Bangla text
 *  - "Open audio": test with any audio file
 */
class MainActivity : Activity() {

    companion object {
        private const val PICK_MODEL = 1
        private const val PICK_AUDIO = 2
        @Volatile private var ctxPtr = 0L      // loaded model (kept while the app is alive)
        @Volatile private var busy = false
    }

    private lateinit var status: TextView
    private lateinit var output: TextView
    private val modelFile by lazy { File(filesDir, "model.bin") }

    // ------------------------------------------------------------------
    // Start
    // ------------------------------------------------------------------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        refreshStatus()
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {             // shared again while app is open
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    // ------------------------------------------------------------------
    // Screen (built in code, no XML needed)
    // ------------------------------------------------------------------
    private fun buildUi() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val title = TextView(this).apply {
            text = "VM Reader"; textSize = 22f; setTypeface(null, Typeface.BOLD)
        }
        status = TextView(this).apply { textSize = 14f; setPadding(0, pad / 2, 0, pad / 2) }

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(this).apply { text = "Load model"; setOnClickListener { pick(PICK_MODEL) } })
        row.addView(Button(this).apply { text = "Open audio"; setOnClickListener { pick(PICK_AUDIO) } })
        row.addView(Button(this).apply { text = "Copy"; setOnClickListener { copyText() } })

        output = TextView(this).apply {
            textSize = 18f; setTextIsSelectable(true); setLineSpacing(0f, 1.25f)
            setPadding(0, pad, 0, pad)
        }
        val scroll = ScrollView(this).apply { addView(output) }

        root.addView(title); root.addView(status); root.addView(row)
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun refreshStatus() {
        setStatus(
            if (modelFile.exists())
                "✅ Model ready. In WhatsApp: long-press a voice message → Share → VM Reader."
            else
                "Step 1: tap \"Load model\" and choose ggml-bn-small-q5_0.bin"
        )
    }

    private fun setStatus(s: String) = runOnUiThread { status.text = s }
    private fun toast(s: String) = runOnUiThread { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }

    // ------------------------------------------------------------------
    // Receive a shared / opened audio file
    // ------------------------------------------------------------------
    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_SEND ->
                if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }
        if (uri != null) process(uri)
    }

    // ------------------------------------------------------------------
    // File pickers
    // ------------------------------------------------------------------
    private fun pick(code: Int) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = if (code == PICK_MODEL) "*/*" else "audio/*"
        }
        @Suppress("DEPRECATION")
        startActivityForResult(i, code)
    }

    @Deprecated("simple API is fine here")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            PICK_MODEL -> copyModel(uri)
            PICK_AUDIO -> process(uri)
        }
    }

    // ------------------------------------------------------------------
    // Copy the model file into the app (only once)
    // ------------------------------------------------------------------
    private fun copyModel(uri: Uri) {
        if (busy) { toast("Please wait…"); return }
        busy = true
        Thread {
            try {
                val tmp = File(filesDir, "model.tmp")
                contentResolver.openInputStream(uri)!!.use { input ->
                    FileOutputStream(tmp).use { out ->
                        val buf = ByteArray(1 shl 20)
                        var total = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            total += n
                            if (total % (20L shl 20) < (1 shl 20)) setStatus("Copying model… ${total shr 20} MB")
                        }
                    }
                }
                if (tmp.length() < 50L * 1024 * 1024) {
                    tmp.delete(); throw IllegalStateException("This file is too small to be the model")
                }
                if (ctxPtr != 0L) { WhisperLib.freeModel(ctxPtr); ctxPtr = 0L }
                modelFile.delete(); tmp.renameTo(modelFile)
                refreshStatus()
            } catch (e: Throwable) {
                setStatus("❌ Model copy failed: ${e.message}")
            } finally { busy = false }
        }.start()
    }

    // ------------------------------------------------------------------
    // Voice message → text (runs in the background)
    // ------------------------------------------------------------------
    private fun process(uri: Uri) {
        if (busy) { toast("Please wait, still working…"); return }
        if (!modelFile.exists()) {
            setStatus("⚠️ First tap \"Load model\" and choose ggml-bn-small-q5_0.bin, then share again.")
            return
        }
        busy = true
        output.text = ""

        // Copy the audio now: the permission from WhatsApp is only temporary
        val input = File(cacheDir, "input_audio")
        try {
            contentResolver.openInputStream(uri)!!.use { i -> input.outputStream().use { i.copyTo(it) } }
        } catch (e: Exception) {
            busy = false; setStatus("❌ Cannot read this audio: ${e.message}"); return
        }

        Thread {
            val t0 = System.currentTimeMillis()
            try {
                // 1) Load model (only the first time)
                if (ctxPtr == 0L) {
                    setStatus("Loading model… (first time only)")
                    ctxPtr = WhisperLib.initModel(modelFile.absolutePath)
                    if (ctxPtr == 0L) throw IllegalStateException("Model file is not valid")
                }
                // 2) Decode audio → 16 kHz mono
                setStatus("Reading audio…")
                val samples = AudioDecoder.decode(input.absolutePath)
                val secs = samples.size / AudioDecoder.TARGET_SR
                // 3) Cut into ≤14 s parts and transcribe one by one (text appears as it goes)
                val parts = Splitter.split(samples)
                val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
                val sb = StringBuilder()
                parts.forEachIndexed { idx, part ->
                    setStatus("Writing… part ${idx + 1} of ${parts.size}  (audio ${secs}s)")
                    val text = String(WhisperLib.transcribe(ctxPtr, part, threads), Charsets.UTF_8).trim()
                    if (text.isNotEmpty()) { if (sb.isNotEmpty()) sb.append(' '); sb.append(text) }
                    val now = sb.toString()
                    runOnUiThread { output.text = now }
                }
                val took = (System.currentTimeMillis() - t0) / 1000
                setStatus("✅ Done in ${took}s  (audio ${secs}s)")
            } catch (e: Throwable) {
                setStatus("❌ Error: ${e.message}")
            } finally {
                busy = false
            }
        }.start()
    }

    // ------------------------------------------------------------------
    // Copy result text
    // ------------------------------------------------------------------
    private fun copyText() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("VM text", output.text))
        toast("Copied")
    }
}
