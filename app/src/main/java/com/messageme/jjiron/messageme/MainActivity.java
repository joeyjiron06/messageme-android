package com.messageme.jjiron.messageme;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.provider.Telephony;
import android.support.annotation.NonNull;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.telephony.SmsManager;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.facebook.AccessToken;
import com.facebook.login.LoginManager;
import com.google.firebase.auth.FacebookAuthProvider;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import com.messageme.jjiron.messageme.models.Contact;
import com.messageme.jjiron.messageme.models.Conversation;
import com.messageme.jjiron.messageme.models.Message;
import com.messageme.jjiron.messageme.models.OutboxMessage;

import java.util.HashMap;
import java.util.List;
import java.util.Map;


public class MainActivity extends AppCompatActivity {
	private static final String TAG = "MainActivity";
    private static final int ALL_PERMISSIONS_REQUEST_ID = 4;

    private static final String SENT_BROADCAST = "SMS_BROADCAST";

    private TextView permissionsText;
    private Thread syncDbThread;
    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private DatabaseReference outboxDB;
    private DatabaseReference sentDB;
    private DatabaseReference contactsDB;
    private DatabaseReference messagesDB;
    private DatabaseReference desktopDB;

    private ContentObserver smsContentListener;
    private String threadId;

    private final Map<String, OutboxMessage> pendingMessages = new HashMap<>();

    @Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);

        permissionsText = findViewById(R.id.permissions_text);
        permissionsText.setOnClickListener(requestPermissionsListener);

        checkAllPermissions();

        smsContentListener = new SmsContentListener();
    }


	private final View.OnClickListener logoutButtonListener = new View.OnClickListener() {
		@Override
		public void onClick(View view) {
			LoginManager.getInstance().logOut();
			// TODO also logout of firebase
		}
	};

    private void checkAllPermissions() {
        requestPermissions(new String[]{
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.RECEIVE_SMS,
        }, ALL_PERMISSIONS_REQUEST_ID);
    }

    @SuppressLint("MissingPermission")
    private void onAllPermissionsGranted() {
        Log.d(TAG, "onAllPermissionsGranted");
        Contact.myNumber = Contact.normalizeAddress(((TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE)).getLine1Number());

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();

        if (user == null) {
            FirebaseAuth.getInstance()
                    .signInWithCredential(FacebookAuthProvider.getCredential(AccessToken.getCurrentAccessToken().getToken()))
                    .addOnCompleteListener(task -> {
                        startSync();
                    });
        } else {
            startSync();
        }
    }

    private void startSync() {
        if (syncDbThread != null) {
            return;
        }

        String uid = FirebaseAuth.getInstance().getCurrentUser().getUid();
        DatabaseReference db = FirebaseDatabase.getInstance().getReference();

        outboxDB = db.child("outbox").child(uid);
        sentDB = db.child("sent").child(uid);
        contactsDB = db.child("contacts").child(uid);
        messagesDB = db.child("messages").child(uid);
        desktopDB = db.child("desktop").child(uid);

        outboxDB.addChildEventListener(outboxEventListener);
        desktopDB.child("conversation").addValueEventListener(desktopConversationListener);

        getContentResolver().registerContentObserver(Telephony.Sms.CONTENT_URI, true, smsContentListener);


        syncDbThread = new Thread(() -> {

            Log.d(TAG, "starting sync");

            Log.d(TAG, "starting contacts sync...");
            for (Contact contact : Contact.getAllMobile()) {
                contactsDB.child(contact.number).setValue(contact.displayName);
            }

            Log.d(TAG, "starting conversations sync...");
            DatabaseReference conversationsDB = FirebaseDatabase.getInstance().getReference().child("conversations").child(uid);
            conversationsDB.setValue(Conversation.getAll());

            mainHandler.post(() -> {
                Log.d(TAG, "done sync!");
                syncDbThread = null;
            });
        });
        syncDbThread.start();
    }

	@Override
	public void onRequestPermissionsResult(int requestCode, @NonNull String permissions[], @NonNull int[] grantResults) {
		// Make sure it's our original READ_CONTACTS request
		if (requestCode == ALL_PERMISSIONS_REQUEST_ID) {
		    if (allSame(grantResults, PackageManager.PERMISSION_GRANTED)) {
                onAllPermissionsGranted();
            } else {
		        Log.d(TAG, "permissions not granted " + grantResults.length);
            }
        } else {
			super.onRequestPermissionsResult(requestCode, permissions, grantResults);
		}
	}

    private static boolean allSame(int[] arr, int val) {
        for (int i : arr) {
            if (val != i) {
                return false;
            }
        }
        return true;
    }

    private final ChildEventListener outboxEventListener = new ChildEventListener() {
        @Override
        public void onChildAdded(DataSnapshot dataSnapshot, String s) {
            mainThreadCheck("outboxEventListener");


            runOnUiThread(() -> {

            OutboxMessage outboxMessage = dataSnapshot.getValue(OutboxMessage.class);

            if (outboxMessage == null) {
                Log.d(TAG, "outbox added but its null " + dataSnapshot.getValue());
                return;
            }

            if (outboxMessage.address == null) {
                Log.d(TAG, "outbox address is null " + dataSnapshot.getValue().toString());
                return;
            }

            if (pendingMessages.containsKey(outboxMessage.id)) {
                Log.d(TAG, "outbox message is not pending. not sending " + outboxMessage.toString());
                return;
            }

            Log.d(TAG, "sending message " + outboxMessage);

            pendingMessages.put(outboxMessage.id, outboxMessage);

            registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    switch (getResultCode()) {
                        case Activity.RESULT_OK:
                            mainThreadCheck("broadcast receiver");

                            pendingMessages.remove(outboxMessage.id, outboxMessage);

                            // remove from outbox
                            outboxDB.child(dataSnapshot.getKey()).removeValue();

                            // put message id in sent db
                            Message message = Message.getLastSent(outboxMessage.threadId);

                            if (message != null) {
                                Log.d(TAG, "message sent!" + outboxMessage.toString());
                                sentDB.child(message.id).setValue(outboxMessage.id);
                                resync(message.threadId);
                            } else {
                                Log.d(TAG, "could not find last sent message " + outboxMessage.toString());
                            }

                            break;

                        // TODO figure out how to handle error
                        case SmsManager.RESULT_ERROR_GENERIC_FAILURE:
                            Toast.makeText(getBaseContext(), "RESULT_ERROR_GENERIC_FAILURE", Toast.LENGTH_SHORT).show();
                            break;
                        case SmsManager.RESULT_ERROR_NO_SERVICE:
                            Toast.makeText(getBaseContext(), "RESULT_ERROR_NO_SERVICE", Toast.LENGTH_SHORT).show();
                            break;
                        case SmsManager.RESULT_ERROR_NULL_PDU:
                            Toast.makeText(getBaseContext(), "RESULT_ERROR_NULL_PDU", Toast.LENGTH_SHORT).show();
                            break;
                        case SmsManager.RESULT_ERROR_RADIO_OFF:
                            Toast.makeText(getBaseContext(), "RESULT_ERROR_RADIO_OFF", Toast.LENGTH_SHORT).show();
                            break;
                    }

                    unregisterReceiver(this);
                }
            }, new IntentFilter(SENT_BROADCAST));

            SmsManager.getDefault().sendTextMessage(
                    outboxMessage.address,
                    null,
                    outboxMessage.body,
                    PendingIntent.getBroadcast(MainActivity.this, 0 ,new Intent(SENT_BROADCAST),0),
                    null
            );
            });
        }

        @Override
        public void onChildChanged(DataSnapshot dataSnapshot, String s) {

        }

        @Override
        public void onChildRemoved(DataSnapshot dataSnapshot) {

        }

        @Override
        public void onChildMoved(DataSnapshot dataSnapshot, String s) {

        }

        @Override
        public void onCancelled(DatabaseError databaseError) {

        }
    };

    private final ValueEventListener desktopConversationListener = new ValueEventListener() {
        @Override
        public void onDataChange(DataSnapshot dataSnapshot) {
            mainThreadCheck("smsContentListener");


            threadId = dataSnapshot.getValue(String.class);
            if (threadId != null) {
                Log.d(TAG, "conversation id changed and is " + threadId);
                resync(threadId);
            } else {
                Log.d(TAG, "conversation id changed but is null");
            }
        }

        @Override
        public void onCancelled(DatabaseError databaseError) {

        }
    };


    private final View.OnClickListener requestPermissionsListener = new View.OnClickListener() {
        @Override
        public void onClick(View view) {
        }
    };


    private void mainThreadCheck(String message) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.d(TAG, message + " on main thread");
        } else {
            Log.d(TAG, message + " NOT on main thread");
        }
    }

    private class SmsContentListener extends ContentObserver {
        public SmsContentListener() {
            super(new Handler());
        }

        @Override
        public boolean deliverSelfNotifications() {
            return true;
        }

        @Override
        public void onChange(boolean selfChange) {
            super.onChange(selfChange);

            mainThreadCheck("smsContentListener");

            if (threadId != null) {
                Log.d(TAG, "sms changed, attempting resync");
                resync(threadId);
            } else {
                Log.d(TAG, "sms changed, but no threadId");
            }
        }
    }

    private void resync(String threadId) {
        mainThreadCheck("resync");

        List<Message> messages = Message.getAll(threadId);

        if (messages != null) {
            Log.d(TAG, "syncing "+ threadId);
            messagesDB.child(threadId).setValue(messages);
        } else {
            Log.d(TAG, "no messages found for thread id not syncing"+ threadId);
        }
    }
}