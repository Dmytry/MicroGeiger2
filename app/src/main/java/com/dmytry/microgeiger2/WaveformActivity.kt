package com.dmytry.microgeiger2

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.Handler
import android.view.Menu
import android.view.MenuItem
import android.view.View
import com.dmytry.microgeiger2.MicroGeiger2App.IIRFilter
import java.text.DecimalFormat

class WaveformActivity : AppCompatActivity() {
    var handler: Handler? = null
    var stopHandler = false
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
                app!!.stop()
                finish()
                true
            }
            R.id.action_reset -> {
                app!!.reset()
                true
            }
            R.id.action_settings -> {
                val i = Intent(this, SettingsActivity::class.java)
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

    var changeCount = 0
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as MicroGeiger2App
        app!!.start()
        panel = Panel(this)
        setContentView(panel)

        //setSupportActionBar(binding.toolbar);
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
        handler = Handler()
        handler!!.post(runnable)
    }

    override fun onDestroy() {
        stopHandler = true
        super.onDestroy()
    }

    inner class Panel(context: Context?) : View(context) {
        var decim = DecimalFormat("0000.0")

        private fun redrawControl(canvas: Canvas) {
            if (app == null) return
            if (app!!.listener == null) return
            val p = Paint()
            p.color = Color.WHITE
            val p_uf = Paint()
            p_uf.color = Color.rgb(0, 100, 100)
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
            val length_to_display = 100
            val x_scale = w * 0.9f / length_to_display
            val x_offset = (w - length_to_display * x_scale) * 0.75f
            val y_scale = -h * 0.9f
            val y_offset = h * 0.97f
            val count_to_cpm_scale = 10

            // Outer box for graph
            canvas.drawLine(x_offset, y_offset, x_offset, y_offset + y_scale, p_grid)
            canvas.drawLine(x_offset + x_scale * length_to_display, y_offset, x_offset + x_scale * length_to_display, y_offset + y_scale, p_grid)
            canvas.drawLine(x_offset, y_offset, x_offset + x_scale * length_to_display, y_offset, p_grid)
            canvas.drawLine(x_offset, y_offset + y_scale, x_offset + x_scale * length_to_display, y_offset + y_scale, p_grid)

            // zero time
            canvas.drawLine(x_offset + x_scale * (length_to_display / 2), y_offset, x_offset + x_scale * (length_to_display / 2), y_offset + y_scale, p_grid)
            val threshold = app!!.listener!!.threshold.toFloat()
            var y = y_offset + y_scale * (0.5f - 0.5f * threshold)
            // Thresholds
            p_grid.color = Color.rgb(0, 255, 0)
            canvas.drawLine(x_offset, y, x_offset + x_scale * length_to_display, y, p_grid)
            y = y_offset + y_scale * (0.5f + 0.5f * threshold)
            canvas.drawLine(x_offset, y, x_offset + x_scale * length_to_display, y, p_grid)
            val dead_time = app!!.listener!!.deadTime
            canvas.drawLine(x_offset + x_scale * (length_to_display / 2 + dead_time), y_offset, x_offset + x_scale * (length_to_display / 2 + dead_time), y_offset + y_scale, p_grid)
            val bars = true
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
            if (app?.lastNClicks != null) {
                synchronized(app!!.lastNClicks) {
                    if (app!!.lastNClicks.size > 0) {
                        val it = app!!.lastNClicks.descendingIterator()
                        for (j in 0..4) {
                            val filter = IIRFilter()
                            if (!it.hasNext()) break
                            val t = it.next().timeInSamples
                            var prev_y = 0f
                            var prev_unfiltered_y = 0f
                            for (i in 0..length_to_display) {
                                val raw = app!!.listener!!.getFromBufferAt((i + t - length_to_display / 2).toInt()).toInt()
                                val normalized = raw / 32767.0f
                                val v = filter.getValue(normalized)
                                val unfiltered_y = normalized * 0.5f + 0.5f
                                y = v * 0.5f + 0.5f
                                canvas.drawLine((i - 1) * x_scale + x_offset, prev_unfiltered_y * y_scale + y_offset,
                                        i * x_scale + x_offset, unfiltered_y * y_scale + y_offset, p_uf)
                                canvas.drawLine((i - 1) * x_scale + x_offset, prev_y * y_scale + y_offset,
                                        i * x_scale + x_offset, y * y_scale + y_offset, p)
                                prev_y = y
                                prev_unfiltered_y = unfiltered_y
                            }
                        }
                    }
                }
            }
        }

        public override fun onDraw(canvas: Canvas) {
            canvas.drawColor(Color.BLACK)
            redrawControl(canvas)
        }
    }
}