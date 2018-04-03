package com.messageme.jjiron.messageme;

import android.app.Application;
import android.content.ContentResolver;
import android.content.Context;


public class App extends Application {

	private static App instance;

	public static App get() {
		return instance;
	}

	@Override
	public void onCreate() {
		super.onCreate();
		instance = this;
	}

	@Override
	public void onTerminate() {
		super.onTerminate();
	}
}
