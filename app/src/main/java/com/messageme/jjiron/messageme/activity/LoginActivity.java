package com.messageme.jjiron.messageme.activity;

import android.content.Intent;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import com.facebook.AccessToken;
import com.facebook.CallbackManager;
import com.facebook.FacebookCallback;
import com.facebook.FacebookException;
import com.facebook.login.LoginManager;
import com.facebook.login.LoginResult;
import com.facebook.login.widget.LoginButton;
import com.messageme.jjiron.messageme.R;


public class LoginActivity extends AppCompatActivity {
	private static final String TAG = "LoginActivity";

	private CallbackManager callbackManager;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_login);

		LoginButton loginButton = findViewById(R.id.fb_login_button);
		loginButton.setReadPermissions("email", "public_profile");


		if (AccessToken.getCurrentAccessToken() != null) {
			onLoggedIn();
		} else {
			callbackManager = CallbackManager.Factory.create();
			LoginManager.getInstance().registerCallback(callbackManager, loginResultCallback);
		}
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		Log.d(TAG, "onDestroy!");
		if (callbackManager != null) {
			LoginManager.getInstance().unregisterCallback(callbackManager);
		}
	}


	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		super.onActivityResult(requestCode, resultCode, data);
		callbackManager.onActivityResult(requestCode, resultCode, data);
	}

	@Override
	protected void onStart() {
		super.onStart();
	}


	private void onLoggedIn() {
		Intent intent = new Intent(this, MainActivity.class);
		startActivity(intent);
		finish();
	}

	private final FacebookCallback<LoginResult> loginResultCallback = new FacebookCallback<LoginResult>() {
		@Override
		public void onSuccess(LoginResult loginResult) {
			onLoggedIn();
		}

		@Override
		public void onCancel() {
			Log.d(TAG, "loginResultCallback: cancel logging in");
			Toast.makeText(LoginActivity.this, "You must log in to continue", Toast.LENGTH_LONG);
		}

		@Override
		public void onError(FacebookException error) {
			Log.d(TAG, "loginResultCallback: error logging in " + error);
			Toast.makeText(LoginActivity.this, "Error logging in", Toast.LENGTH_SHORT);
		}
	};
}