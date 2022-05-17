package com.example.microgeiger2;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.Handler;
import android.view.Menu;
import android.view.MenuItem;
import android.view.Window;
import android.view.WindowManager;

import java.text.DecimalFormat;

public class MainActivity extends AppCompatActivity {


    Handler handler;
    boolean stop_handler=false;
    int old_count=-1;
    Panel panel;
    MicroGeiger2App app;
    private static final int RESULT_SETTINGS = 1;

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle item selection
        switch (item.getItemId()) {
            case R.id.action_quit:
                app.stop();
                finish();
                return true;
            case R.id.action_reset:
                app.reset();
                return true;
            case R.id.action_settings:
                Intent i = new Intent(this, SettingsActivity.class);
                startActivityForResult(i, RESULT_SETTINGS);
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        switch (requestCode) {
            case RESULT_SETTINGS:
                //showUserSettings();
                break;

        }

    }

    private ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
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
            });


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        panel=new Panel(this);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        //setContentView(R.layout.activity_main);
        setContentView(panel);
        handler=new Handler();



        if(ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(
                    android.Manifest.permission.RECORD_AUDIO
            );
        }

        app=(MicroGeiger2App) getApplication();
        app.start();

        Runnable runnable = new Runnable() {
            @Override
            public void run() {
                if (!stop_handler) {
                    if(app.changed){
                        panel.invalidate();
                        app.changed=false;
                    }
                    handler.postDelayed(this, 100);
                }
            }
        };
        // start it with:
        handler.post(runnable);

    }
    @Override
    protected void onDestroy() {
        stop_handler=true;
        super.onDestroy();
    }

    class Panel extends android.view.View {
        DecimalFormat decim = new DecimalFormat("0000.0");
        public Panel(Context context) {
            super(context);
        }

        public float CountToY(float count){
            if(count<10) {
                return count/50.0f;
            }
            float y = (float) Math.log10(count)/5.0f;
            return y;
        }

        public void RedrawControl(android.graphics.Canvas canvas){
            if(app==null)return;
            Paint p=new Paint();
            p.setColor(Color.WHITE);
            p.setTextSize(30);
            p.setStyle(Paint.Style.FILL);

            Paint p_grid=new Paint();
            p_grid.setColor(Color.rgb(255,255,0));
            p_grid.setTextSize(20);

            Paint p_grid_m=new Paint();
            p_grid_m.setColor(Color.rgb(100,100,0));


            int ypos=50;
            int ydelta=60;
            int w=canvas.getWidth();
            int h=canvas.getHeight();
            int px_per_tick=5;

            int x_steps=(int)(w*0.9f)/px_per_tick;

            float x_scale=px_per_tick;
            float x_offset=(w-x_steps*x_scale)*0.75f;

            float y_scale=-h*0.9f;
            float y_offset=h*0.95f;

            canvas.drawLine(x_offset, y_offset, x_offset, y_offset+y_scale, p_grid);
            canvas.drawLine(x_offset+x_scale*x_steps, y_offset, x_offset+x_scale*x_steps, y_offset+y_scale, p_grid);

            Boolean bars=true;

            canvas.drawLine(0, y_offset, x_offset+x_scale*x_steps, y_offset, p_grid);

            for(int j=0; j<10; ++j) {
                float y=y_offset+CountToY(j)*y_scale;
                canvas.drawLine(x_offset, y, x_offset+x_scale*x_steps, y, p_grid_m);
            }

            canvas.drawLine(0, y_offset, x_offset+x_scale*x_steps, y_offset, p_grid);
            canvas.drawText("0", 10, y_offset-3, p_grid);

            for(int i=1; i<=5; ++i) {
                double base_num=Math.pow(10f, i);
                float y=y_offset+(i/5.0f)*y_scale;
                canvas.drawLine(0, y, x_offset+x_scale*x_steps, y, p_grid);

                canvas.drawText(Integer.toString((int)base_num), 10, y-3, p_grid);
                for(int j=2; j<10; ++j) {
                    y=y_offset+CountToY(j*(float)base_num)*y_scale;
                    canvas.drawLine(x_offset, y, x_offset+x_scale*x_steps, y, p_grid_m);
                }
            }
            if(app.connected){
                p.setTextAlign(Paint.Align.LEFT);
                canvas.drawText(decim.format(app.GetQueueCPM())+" CPM", w/10, ypos, p);
                p.setTextAlign(Paint.Align.RIGHT);
                canvas.drawText(Integer.toString(app.total_count)+" total", w-w/10, ypos, p);

                /*
                ypos+=ydelta;
                for(int i=0;i<app.counters.length;++i){
                    canvas.drawText(decim.format(app.counters[i].getValue())+" "+app.counters[i].name, 10, ypos, p);
                    ypos+=ydelta;
                }*/
                int log_total_samples=app.counts_log.size();

                if(log_total_samples>0){
                    int graph_width=x_steps;
                    int log_first_sample=log_total_samples-graph_width;
                    if(log_first_sample<0){
                        log_first_sample=0;
                        graph_width=log_total_samples;
                    }
                    float prev_y=CountToY(app.counts_log.get(log_first_sample));
                    for(int i=1; i<graph_width;++i){
                        float count=app.counts_log.get(i+log_first_sample);
                        float y = CountToY(count);
                        if(bars) {
                            if(count>0) {
                                canvas.drawRect((i - 1) * x_scale + x_offset,
                                        y * y_scale + y_offset,
                                        i * x_scale + x_offset,
                                        y_offset,
                                        p
                                );
                            }
                        }else {
                            canvas.drawLine((i - 1) * x_scale + x_offset, prev_y * y_scale + y_offset, i * x_scale + x_offset, y * y_scale + y_offset, p);
                        }
                        prev_y=y;
                    }
                }

            }else{
                canvas.drawText("MicroGeiger not connected.", 10, ypos, p);
            }
        }

        @Override
        public void onDraw(android.graphics.Canvas canvas){
            canvas.drawColor(Color.BLACK);
            RedrawControl(canvas);
        }
    }

    /*
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
    }*/
}