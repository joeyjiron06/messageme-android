package com.messageme.jjiron.messageme;

import android.app.Application;
import android.content.ContentResolver;


public class App extends Application {
	public static ContentResolver contentResolver;
	@Override
	public void onCreate() {
		super.onCreate();
		contentResolver = getContentResolver();
	}

	@Override
	public void onTerminate() {
		super.onTerminate();
	}
}
