package com.dmytry.microgeiger2;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;

import com.google.android.material.snackbar.Snackbar;

import androidx.appcompat.app.AppCompatActivity;

import android.os.Handler;
import android.view.Menu;
import android.view.View;

import androidx.navigation.NavController;
import androidx.navigation.Navigation;
import androidx.navigation.ui.AppBarConfiguration;
import androidx.navigation.ui.NavigationUI;

import com.dmytry.microgeiger2.databinding.ActivityWaveformViewBinding;

import java.text.DecimalFormat;
import java.util.Iterator;

public class WaveformView extends AppCompatActivity {

    private AppBarConfiguration appBarConfiguration;
    private ActivityWaveformViewBinding binding;

    Handler handler;
    boolean stop_handler=false;

    Panel panel;
    MicroGeiger2App app;

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    int change_count=0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        app=(MicroGeiger2App) getApplication();
        app.start();
        panel=new Panel(this);
        setContentView(panel);

        //setSupportActionBar(binding.toolbar);
        Runnable runnable = new Runnable() {
            @Override
            public void run() {
                if (!stop_handler) {
                    if(app.change_count!=change_count){
                        panel.invalidate();
                        change_count=app.change_count;
                    }
                    handler.postDelayed(this, 100);
                }
            }
        };
        handler=new Handler();
        handler.post(runnable);
    }
    @Override
    protected void onDestroy() {
        stop_handler=true;
        super.onDestroy();
    }

    @Override
    public boolean onSupportNavigateUp() {
        NavController navController = Navigation.findNavController(this, R.id.nav_host_fragment_content_waveform_view);
        return NavigationUI.navigateUp(navController, appBarConfiguration)
                || super.onSupportNavigateUp();
    }


    class Panel extends android.view.View {
        DecimalFormat decim = new DecimalFormat("0000.0");
        public Panel(Context context) {
            super(context);
        }


        String LabelFormat(int n){
            if(n>=1000000)return Integer.toString(n/1000000)+"M";
            if(n>=1000)return Integer.toString(n/1000)+"K";
            return Integer.toString(n);
        }

        public void RedrawControl(android.graphics.Canvas canvas){

            if(app==null)return;
            if(app.listener==null)return;
            Paint p=new Paint();
            p.setColor(Color.WHITE);

            Paint p_uf=new Paint();
            p_uf.setColor(Color.rgb(0,100,100));

            int w=canvas.getWidth();
            int h=canvas.getHeight();
            float text_size=h*0.05f;
            p.setTextSize(h*0.05f);

            p.setStyle(Paint.Style.FILL);

            Paint p_grid=new Paint();
            p_grid.setColor(Color.rgb(255,180,0));
            p_grid.setTextSize(20);
            p_grid.setTextAlign(Paint.Align.RIGHT);
            p_grid.setStrokeWidth(2);

            Paint p_grid_half=new Paint();
            p_grid_half.setColor(Color.rgb(255,180,0));

            Paint p_grid_m=new Paint();
            p_grid_m.setColor(Color.rgb(100,60,0));

            int length_to_display=100;

            int x_steps=length_to_display;

            float x_scale=(w*0.9f/length_to_display);
            float x_offset=(w-x_steps*x_scale)*0.75f;

            float y_scale=-h*0.9f;
            float y_offset=h*0.97f;

            int count_to_cpm_scale=10;

            // Outer box for graph
            canvas.drawLine(x_offset, y_offset, x_offset, y_offset+y_scale, p_grid);
            canvas.drawLine(x_offset+x_scale*x_steps, y_offset, x_offset+x_scale*x_steps, y_offset+y_scale, p_grid);
            canvas.drawLine(x_offset, y_offset, x_offset+x_scale*x_steps, y_offset, p_grid);
            canvas.drawLine(x_offset, y_offset+y_scale, x_offset+x_scale*x_steps, y_offset+y_scale, p_grid);

            // zero time
            canvas.drawLine(x_offset+x_scale*(length_to_display/2), y_offset, x_offset+x_scale*(length_to_display/2), y_offset+y_scale, p_grid);

            float threshold=(float)app.listener.threshold;
            float y=y_offset+y_scale*(0.5f-0.5f*threshold);
            // Thresholds
            p_grid.setColor(Color.rgb(0,255,0));
            canvas.drawLine(x_offset, y, x_offset+x_scale*x_steps, y, p_grid);
            y=y_offset+y_scale*(0.5f+0.5f*threshold);
            canvas.drawLine(x_offset, y, x_offset+x_scale*x_steps, y, p_grid);

            int dead_time=app.listener.dead_time;
            canvas.drawLine(x_offset+x_scale*(length_to_display/2+dead_time), y_offset, x_offset+x_scale*(length_to_display/2+dead_time), y_offset+y_scale, p_grid);

            Boolean bars=true;

            float ypos=-p.ascent();

            if(app.connected){
                p.setTextAlign(Paint.Align.LEFT);
                canvas.drawText(decim.format(app.GetQueueCPM())+" CPM", w/20, ypos, p);
                p.setTextAlign(Paint.Align.RIGHT);
                canvas.drawText(Integer.toString(app.total_count), w-w/20, ypos, p);
            }else{
                p.setTextAlign(Paint.Align.CENTER);
                canvas.drawText("DISCONNECTED", w/2, ypos, p);
            }
            /*
            ypos+=ydelta;
            for(int i=0;i<app.counters.length;++i){
                canvas.drawText(decim.format(app.counters[i].getValue())+" "+app.counters[i].name, 10, ypos, p);
                ypos+=ydelta;
            }*/



            if(app.last_n_clicks!=null) {
                synchronized (app.last_n_clicks) {
                    if (app.last_n_clicks.size() > 0) {
                        Iterator<MicroGeiger2App.Click> it = app.last_n_clicks.descendingIterator();
                        for(int j=0; j<5; ++j) {
                            MicroGeiger2App.IIRFilter filter=new MicroGeiger2App.IIRFilter();
                            if(!it.hasNext())break;
                            long t = it.next().time_in_samples;
                            float prev_y = 0;
                            float prev_unfiltered_y=0;
                            for (int i = 0; i <= length_to_display; ++i) {
                                int raw = app.listener.getFromBufferAt((int) (i + t - length_to_display / 2));
                                float normalized = raw / 32767.0f;
                                float v = filter.get_value(normalized);
                                float unfiltered_y=normalized * 0.5f + 0.5f;
                                y = v * 0.5f + 0.5f;
                                canvas.drawLine((i - 1) * x_scale + x_offset, prev_unfiltered_y * y_scale + y_offset,
                                        i * x_scale + x_offset, unfiltered_y * y_scale + y_offset, p_uf);
                                canvas.drawLine((i - 1) * x_scale + x_offset, prev_y * y_scale + y_offset,
                                        i * x_scale + x_offset, y * y_scale + y_offset, p);

                                prev_y = y;
                                prev_unfiltered_y=unfiltered_y;
                            }
                        }
                    }
                }
            }
        }

        @Override
        public void onDraw(android.graphics.Canvas canvas){
            canvas.drawColor(Color.BLACK);
            RedrawControl(canvas);
        }
    }

}