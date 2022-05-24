package com.dmytry.microgeiger2

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.Handler
import android.view.*
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.DecimalFormat

class MainActivity : AppCompatActivity() {
    var handler: Handler? = null
    var stopHandler = false
    var oldCount = -1
    var panel: Panel? = null
    var app: MicroGeiger2App? = null
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu; this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Handle item selection
        return when (item.itemId) {
            R.id.action_quit -> {
                //app?.stop()
                //finish()
                finishAndRemoveTask();
                app?.stop()
                true
            }
            R.id.action_reset -> {
                app?.reset()
                true
            }
            R.id.action_settings -> {
                val i = Intent(this, SettingsActivity::class.java)
                //startActivityForResult(i, RESULT_SETTINGS);
                startActivity(i)
                true
            }
            R.id.action_waveforms -> {
                val i = Intent(this, WaveformActivity::class.java)
                startActivity(i)
                //startActivityForResult(i, RESULT_SETTINGS);
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            RESULT_SETTINGS -> {
            }
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(RequestPermission()) { isGranted: Boolean ->
        if (isGranted) {
            // Permission is granted. Continue the action or workflow in your
            // app.
        } else {
            // Explain to the user that the feature is unavailable because the
            // features requires a permission that the user has denied. At the
            // same time, respect the user's decision. Don't link to system
            // settings in an effort to convince the user to change their
            // decision.
        }
    }
    var changeCount = 0
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        panel = Panel(this)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        //setContentView(R.layout.activity_main);
        setContentView(panel)
        handler = Handler()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(
                    Manifest.permission.RECORD_AUDIO
            )
        }
        app = application as MicroGeiger2App
        app!!.start()
        val runnable: Runnable = object : Runnable {
            override fun run() {
                if (!stopHandler) {
                    if (app!!.changeCount != changeCount) {
                        panel!!.invalidate()
                        changeCount = app!!.changeCount
                    }
                    handler!!.postDelayed(this, 100)
                }
            }
        }
        // start it with:
        handler!!.post(runnable)
    }

    override fun onDestroy() {
        stopHandler = true
        super.onDestroy()
    }

    inner class Panel(context: Context?) : View(context) {
        var decim = DecimalFormat("0000.0")
        fun CountToY(count: Float): Float {
            return if (count < 10) {
                count / 50.0f
            } else Math.log10(count.toDouble()).toFloat() / 5.0f
        }

        fun labelFormat(n: Int): String {
            if (n >= 1000000) return Integer.toString(n / 1000000) + "M"
            return if (n >= 1000) Integer.toString(n / 1000) + "K" else Integer.toString(n)
        }

        fun redrawControl(canvas: Canvas) {
            if (app == null) return
            val p = Paint()
            p.color = Color.WHITE
            val w = canvas.width
            val h = canvas.height
            val text_size = h * 0.05f
            p.textSize = h * 0.05f
            p.style = Paint.Style.FILL
            val p_grid = Paint()
            p_grid.color = Color.rgb(255, 180, 0)
            p_grid.textSize = 20f
            p_grid.textAlign = Paint.Align.RIGHT
            p_grid.strokeWidth = 2f
            val p_grid_half = Paint()
            p_grid_half.color = Color.rgb(255, 180, 0)
            val p_grid_m = Paint()
            p_grid_m.color = Color.rgb(100, 60, 0)
            val px_per_tick = 5
            val x_steps = (w * 0.9f).toInt() / px_per_tick
            val x_scale = px_per_tick.toFloat()
            val x_offset = (w - x_steps * x_scale) * 0.75f
            val y_scale = -h * 0.9f
            val y_offset = h * 0.97f
            val count_to_cpm_scale = 10

            // Outer box for graph
            canvas.drawLine(x_offset, y_offset, x_offset, y_offset + y_scale, p_grid)
            canvas.drawLine(x_offset + x_scale * x_steps, y_offset, x_offset + x_scale * x_steps, y_offset + y_scale, p_grid)
            canvas.drawLine(x_offset, y_offset, x_offset + x_scale * x_steps, y_offset, p_grid)
            canvas.drawLine(x_offset, y_offset + y_scale, x_offset + x_scale * x_steps, y_offset + y_scale, p_grid)
            val bars = true

            // Linear 1..9 scale
            for (j in 1..9) {
                val y = y_offset + CountToY(j.toFloat()) * y_scale
                canvas.drawLine(x_offset, y, x_offset + x_scale * x_steps, y, if (j == 5) p_grid_half else p_grid_m)
            }
            val text_y_offset = 0.5f * (p_grid.descent() + p_grid.ascent())
            canvas.drawText("0", x_offset - 5, y_offset - text_y_offset, p_grid)
            canvas.drawText("50", x_offset - 5, y_offset + CountToY(5f) * y_scale - text_y_offset, p_grid)


            // Main ticks for log scale, starting at 10
            for (i in 1..5) {
                val base_num = Math.pow(10.0, i.toDouble()).toFloat()
                var y = y_offset + i / 5.0f * y_scale
                canvas.drawLine(x_offset, y, x_offset + x_scale * x_steps, y, p_grid)
                canvas.drawText(labelFormat(base_num.toInt() * count_to_cpm_scale), x_offset - 5, y - text_y_offset, p_grid)
                if (i < 5) {
                    for (j in 2..9) {
                        y = y_offset + CountToY(j * base_num) * y_scale
                        canvas.drawLine(x_offset, y, x_offset + x_scale * x_steps, y, if (j == 5) p_grid_half else p_grid_m)
                    }

                    // Labels for a few ticks
                    for (j in 2..5) {
                        y = y_offset + CountToY(j * base_num) * y_scale
                        canvas.drawText(labelFormat((j * base_num * count_to_cpm_scale).toInt()), x_offset - 5, y - text_y_offset, p_grid)
                    }
                }
            }
            val ypos = -p.ascent()
            if (app!!.connected) {
                p.textAlign = Paint.Align.LEFT
                canvas.drawText(decim.format(app!!.getQueueCPM().toDouble()) + " CPM", (w / 20).toFloat(), ypos, p)
                p.textAlign = Paint.Align.RIGHT
                canvas.drawText(Integer.toString(app!!.totalCount), (w - w / 20).toFloat(), ypos, p)
            } else {
                p.textAlign = Paint.Align.CENTER
                canvas.drawText("DISCONNECTED", (w / 2).toFloat(), ypos, p)
            }
            /*
            ypos+=ydelta;
            for(int i=0;i<app.counters.length;++i){
                canvas.drawText(decim.format(app.counters[i].getValue())+" "+app.counters[i].name, 10, ypos, p);
                ypos+=ydelta;
            }*/
            val log_total_samples = app!!.countsLog.size
            if (log_total_samples > 0) {
                var graph_width = x_steps
                var log_first_sample = log_total_samples - graph_width
                if (log_first_sample < 0) {
                    log_first_sample = 0
                    graph_width = log_total_samples
                }
                var prev_y = CountToY(app!!.countsLog[log_first_sample].toFloat())
                for (i in 1 until graph_width) {
                    val count = app!!.countsLog[i + log_first_sample].toFloat()
                    val y = CountToY(count)
                    if (bars) {
                        if (count > 0) {
                            canvas.drawRect((i - 1) * x_scale + x_offset,
                                    y * y_scale + y_offset,
                                    i * x_scale + x_offset,
                                    y_offset,
                                    p
                            )
                        }
                    } else {
                        canvas.drawLine((i - 1) * x_scale + x_offset, prev_y * y_scale + y_offset, i * x_scale + x_offset, y * y_scale + y_offset, p)
                    }
                    prev_y = y
                }
            }
        }

        public override fun onDraw(canvas: Canvas) {
            canvas.drawColor(Color.BLACK)
            redrawControl(canvas)
        }
    } /*
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
    }*/

    companion object {
        private const val RESULT_SETTINGS = 1
    }
}