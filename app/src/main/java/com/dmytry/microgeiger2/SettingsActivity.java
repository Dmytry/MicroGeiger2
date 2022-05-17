package com.dmytry.microgeiger2;

import android.os.Bundle;
import android.preference.PreferenceActivity;

import com.dmytry.microgeiger2.R;

public class SettingsActivity extends PreferenceActivity {
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		//SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
		addPreferencesFromResource(R.xml.settings);
	}
}
