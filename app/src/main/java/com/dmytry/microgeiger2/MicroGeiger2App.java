/*
    Copyright 2013 Dmytry Lavrov

    This file is part of MicroGeiger for Android.

    MicroGeiger is free software: you can redistribute it and/or modify it under the terms of the 
    GNU General Public License as published by the Free Software Foundation, either version 2 
    of the License, or (at your option) any later version.

    MicroGeiger is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; 
    without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. 
    See the GNU General Public License for more details.

    You should have received a copy of the GNU General Public License along with MicroGeiger. 
    If not, see http://www.gnu.org/licenses/.
*/

package com.dmytry.microgeiger2;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder.AudioSource;
import android.media.MicrophoneInfo;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.util.Log;

import java.io.IOException;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;

public class MicroGeiger2App extends Application {
	private static final String TAG = "MicroGeiger";
	static final float running_avg_const=0.25f;

	public volatile int total_count=0;
	public final int min_clicks_in_queue=100;
	public final float min_smoothing_duration_sec=0.5f;

	public final int sample_rate=44100;
	public final int counters_update_rate=2;/// sample rate must be divisible by counters update rate

	public volatile long total_sample_count=0;
	
	public final int log_interval=6*sample_rate;/// logging interval in samples
	public java.util.Vector<Integer> counts_log=new java.util.Vector<Integer>();
	public int log_countdown=0, log_interval_click_count=0;
	
	public final int samples_per_update=sample_rate/counters_update_rate;

	//public volatile boolean changed=false;
	public volatile int change_count=0;

	boolean started=false;
	boolean connected=false;

	static public class IIRFilter{
		float running_avg=0;
		float get_value(float normalized_in){
			float v=normalized_in-running_avg;
			running_avg=running_avg*(1.0f-running_avg_const)+normalized_in*running_avg_const;
			return v;
		}
	}

	public class Counter{
		public int counts[];
		public int pos=0;		
		volatile public int count=0;
		public double scale=1.0;
		public String name;
		Counter(int n_counts, double scale_, String name_){
			counts=new int[n_counts];
			scale=scale_;
			name=name_;
		}
		public void push(int n){			
			pos=pos+1;
			if(pos>=counts.length)pos=0;
			int old_count=count;
			count-=counts[pos];
			counts[pos]=n;
			count+=n;
			if(count!=old_count)change_count++;
		}
		double getValue(){
			return scale*count;
		}
	}
	public volatile Counter counters[];

	static public class Click{
		public long time_in_samples=0;
	}

	public volatile Deque<Click> last_n_clicks=new LinkedList<Click>();

	void AppendClick(long time_in_samples){
		Click c=new Click();
		c.time_in_samples=time_in_samples;
		synchronized(last_n_clicks) {
			last_n_clicks.addLast(c);
		}
	}
	void TrimQueue(long time_in_samples) {
		long cutoff=time_in_samples-(long)(min_smoothing_duration_sec*sample_rate);
		synchronized(last_n_clicks){
			while(last_n_clicks.size() > min_clicks_in_queue && last_n_clicks.getFirst().time_in_samples<cutoff) {
				last_n_clicks.removeFirst();
			}
		}
	}

	float GetQueueCPM(){
		synchronized(last_n_clicks){
			if(last_n_clicks.size()==0)return 0;
			long sc=total_sample_count;
			long duration_in_samples=sc-last_n_clicks.getFirst().time_in_samples;
			if(duration_in_samples<=0)return 0;
			long count=last_n_clicks.size();
			return count*sample_rate*60.0f/duration_in_samples;
		}
	}

  
    public class Listener implements Runnable{
    	public volatile boolean do_stop=false;

		IIRFilter filter=new IIRFilter();

    	int current_offset=0;
		public short input_buffer[];
		short playback_buffer[];

		AudioRecord recorder;

		AudioTrack player;

		int dead_time=sample_rate/2000;
		double threshold=0.1;
		// High pass filter , peak is reached in 3 samples
		double rms_avg=0.0;
		double peak_meter=0.1;
		double peak_meter_decay=100.0/sample_rate;

		float click_volume=1.0f;
		int dead_countdown=0;
		int click_countdown=0;

		int click_duration=40;
		int click_beep_divisor=20;

		int sample_update_counter=0;
		int sample_count=0;

		final short getFromBufferAt(int i){
			i%=input_buffer.length;
			if(i<0)i+=input_buffer.length;
			return input_buffer[i];
		}

		// Circular buffer reading
		// Returns how many bytes were read
		int readIntoBuffer(AudioRecord recorder, int wanted_read, boolean blocking) { // int read_size = recorder.read(input_buffer,0,data_size, AudioRecord.READ_NON_BLOCKING);
			// sanitize
			if(wanted_read>input_buffer.length)wanted_read=input_buffer.length;

			int max_read_size=wanted_read;
			// Are we wrapping around the buffer?
			Boolean partial=max_read_size > input_buffer.length-current_offset;
			if(partial) {
				max_read_size=input_buffer.length-current_offset;
			}
			int bytes_read=recorder.read(input_buffer, current_offset, max_read_size,  blocking ? AudioRecord.READ_BLOCKING : AudioRecord.READ_NON_BLOCKING);

			int new_offset=current_offset+bytes_read;
			// Wraparound
			if(new_offset>=input_buffer.length){
				new_offset=0;
				if(partial){// Wraparound and read was partial, need another read
					bytes_read+=recorder.read(input_buffer, new_offset, wanted_read-bytes_read, blocking ? AudioRecord.READ_BLOCKING : AudioRecord.READ_NON_BLOCKING);
				}
			}
			return bytes_read;
		}

		// Blocking read for min_read plus queue emptying up to max_read
		int readAtLeast(AudioRecord recorder, int min_read, int max_read){
			int result=readIntoBuffer(recorder, min_read, true);
			if(result<max_read) {
				result+=readIntoBuffer(recorder, max_read-result, false);
			}
			return result;
		}


		@Override
		public void run() {	

			int record_min_buffer_size=AudioRecord.getMinBufferSize(sample_rate, AudioFormat.CHANNEL_OUT_FRONT_LEFT | AudioFormat.CHANNEL_OUT_FRONT_RIGHT, AudioFormat.ENCODING_PCM_16BIT);
			int play_min_buffer_size=AudioTrack.getMinBufferSize(sample_rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
			int min_buffer_size=Math.max(record_min_buffer_size, play_min_buffer_size);
			
			int data_size=Math.max(sample_rate/4, min_buffer_size);
			// Store about 160 seconds in circular buffer for the waveform viewer, power of 2 so that wraparound of sample numbers isn't a problem
			input_buffer=new short[Math.max(data_size,1024*1024)];
			current_offset=0;
			playback_buffer =new short[data_size*2];

			int recorder_buffer_size_bytes=4*Math.max(sample_rate/10, min_buffer_size);
			try {
				recorder = new AudioRecord(AudioSource.DEFAULT, sample_rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, recorder_buffer_size_bytes);
			}catch(SecurityException ex) {
				Log.d(TAG, "No audio permission");
				return;
			}

			player = new AudioTrack(AudioManager.STREAM_RING,
					sample_rate, /* AudioFormat.CHANNEL_OUT_MONO */ AudioFormat.CHANNEL_OUT_FRONT_LEFT | AudioFormat.CHANNEL_OUT_FRONT_RIGHT,
	                AudioFormat.ENCODING_PCM_16BIT, 4*data_size,
	                AudioTrack.MODE_STREAM);

			Log.d(TAG, "Output channels: "+player.getChannelCount());
	        player.play();

			try{
			    while(!do_stop){
					getParametersFromConfig();
					if (recorder.getState()== AudioRecord.STATE_INITIALIZED){ // check to see if the recorder has initialized yet.
			            if (recorder.getRecordingState()== AudioRecord.RECORDSTATE_STOPPED){
			                 recorder.startRecording();
			            }else{
			            	// This is slow for some reason, todo: use events instead
							boolean is_peripheral = checkIfPeripheralIsConnected();

							if( is_peripheral ){
			            		if(!connected)change_count++;
			            		connected=true;
								long start_t_ = SystemClock.elapsedRealtime();
				            	//int read_size = recorder.read(input_buffer,0,data_size, AudioRecord.READ_NON_BLOCKING);

								// read at least 0.1 seconds of audio
								int read_size = readAtLeast(recorder, 4410, input_buffer.length);//readIntoBuffer(recorder, input_buffer.length);

								long end_t_ = SystemClock.elapsedRealtime();
								Log.d(TAG, "Read: "+read_size+" duration="+(end_t_-start_t_)+" t="+end_t_);
				            	int old_total_count=total_count;
				            	processInputDataAndGenerateClicks(read_size, playback_buffer);
								TrimQueue(total_sample_count);
				            	if(old_total_count!=total_count){
									change_count++;
				            	}
								playOutputSounds(read_size);
							}else{/// wired headset is not on
			            		if(connected)change_count++;
			            		connected=false;
			            		if(recorder!=null) {
									recorder.stop();
									recorder.release();
								}
								Thread.sleep(500);
								try {
									recorder = new AudioRecord(AudioSource.DEFAULT, sample_rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, recorder_buffer_size_bytes);
								}catch(SecurityException ex) {
									Log.d(TAG, "No audio permission");
									return;
								}
			            		Thread.sleep(500);
			            	}
				    	}
			    	}else{
				    	Log.d(TAG, "failed to initialize audio");
				    	Thread.sleep(5000);
				    }
			    }
			} catch (InterruptedException e){
			}
		    finally{
		    	recorder.stop();
		    	recorder.release();
		    	player.stop();
		    	player.release();
		    }
		}

		private void playOutputSounds(int read_size) {
			if(click_volume>0.001) {
				long start_t= SystemClock.elapsedRealtime();
				int how_much_to_write= read_size *2;
				// hack to reduce amount of buffering
				// read was more than 1/10th of a second, skip some writing
				if(read_size >4410) {
					how_much_to_write-=16;
				}
				how_much_to_write-=2;
				int wrote_size=0;
				if(how_much_to_write>0) {
					wrote_size = player.write(playback_buffer, 0, how_much_to_write);// , AudioTrack.WRITE_NON_BLOCKING
				}
				long end_t= SystemClock.elapsedRealtime();
				Log.d(TAG, "Time to write: "+(end_t-start_t));
			}
		}

		private void processInputDataAndGenerateClicks(int read_size, short[] playback_data) {
			for(int i = 0; i < read_size; ++i, ++current_offset){
				if(current_offset>=input_buffer.length)current_offset=0;
				total_sample_count++;
				if(dead_countdown>0){
					dead_countdown--;
				}
				if(i*2+1<playback_data.length) {
					if (click_countdown > 0) {
						click_countdown--;
						playback_data[i * 2] = (short) (Math.exp((click_volume - 1.0) * Math.log(10000)) * ((click_countdown / click_beep_divisor) % 2 == 1 ? 32767 : -32767));
						playback_data[i * 2 + 1] = playback_data[i * 2];
					} else {
						//playback_data[i]=0;
						// test beep
						//short beep=(short)((total_sample_count/40)%2 == 1 ? 1000:-1000);
						short beep = 0;
						playback_data[i * 2] = beep;
						playback_data[i * 2 + 1] = beep;
					}
				}
				sample_update_counter++;
				if(sample_update_counter>=samples_per_update){
					for(int j=0;j<counters.length;++j){
						counters[j].push(sample_count);
					}
					sample_update_counter=0;
					sample_count=0;
					//Log.d(TAG, "got a sample");
				}
				float raw_v=input_buffer[current_offset]*(1.0f/32768.0f);
				float v=filter.get_value(raw_v);

				if(/* v>threshold || */ v<-threshold){
					if(dead_countdown<=0){
						total_count++;
						sample_count++;
						log_interval_click_count++;
						dead_countdown=dead_time;
						if(click_countdown<=0)click_countdown=click_duration;
						AppendClick(total_sample_count);
						TrimQueue(total_sample_count);
					}
				}
				if(log_countdown<=0){
					log_countdown=log_interval;
					counts_log.add(log_interval_click_count);
					log_interval_click_count=0;
				}
				log_countdown--;
			}
		}

		private boolean checkIfPeripheralIsConnected() {
			boolean is_peripheral=false;
			boolean checked_peripheral=false;
			try{
				if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
					List<MicrophoneInfo> microphones=recorder.getActiveMicrophones();
					for(MicrophoneInfo m: microphones) {
						int l=m.getLocation();
						if(l==MicrophoneInfo.LOCATION_UNKNOWN || l==MicrophoneInfo.LOCATION_PERIPHERAL) {
							is_peripheral=true;
						}
					}
					checked_peripheral=true;
				}
			}catch(IOException e) {
				Log.d(TAG, "Failed to query connected microphones");
			}
			// if we were unable to determine if microphone is peripheral, fallback to wired headset check
			if(!checked_peripheral) {
				is_peripheral=((AudioManager) getApplicationContext().getSystemService(Context.AUDIO_SERVICE)).isWiredHeadsetOn();
			}
			return is_peripheral;
		}

		private void getParametersFromConfig() {
			SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
			try{
				threshold=Double.parseDouble(prefs.getString("threshold", ""));
			}catch(NumberFormatException e){
			}
			try{
				dead_time=(int)(0.001*sample_rate*Double.parseDouble(prefs.getString("dead_time", "")));
			}catch(NumberFormatException e){
			}
			try{
				click_volume=Float.parseFloat(prefs.getString("click_volume", "1.0"));
			}catch(NumberFormatException e){
			}
		}
	}
    
    Listener listener;
    Thread listener_thread;
    
    void init_counters(){
    	counters=new Counter[4];
    	counters[0]=new Counter(counters_update_rate*5, 12.0, " CPM in last 5 sec");
    	counters[1]=new Counter(counters_update_rate*30, 2.0, " CPM in last 30 sec");
    	counters[2]=new Counter(counters_update_rate*120, 0.5, " CPM in last 2 min");
    	counters[3]=new Counter(counters_update_rate*600, 0.1, " CPM in last 10 min");
    }
    
    void start(){
    	if(!started){	    	
	    	init_counters();
	    	if(listener_thread==null){
		        listener=new Listener();
		        listener_thread=new Thread(listener);
		        listener_thread.start();
	        }
	    	started=true;
    	}
    }
    void reset(){
    	total_count=0;
    	init_counters();
		change_count++;
    }
    void stop(){
    	if(listener!=null){
    		listener.do_stop=true;
    		listener_thread.interrupt();
    		try {
				listener_thread.join();
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
    		listener_thread=null;
    	}  
    	started=false;
    }
    @Override
	public void onCreate() {    	
        super.onCreate();   
        start();
    }
    @Override
    public void onTerminate() {    	
        super.onTerminate();
        stop();
    }
}
