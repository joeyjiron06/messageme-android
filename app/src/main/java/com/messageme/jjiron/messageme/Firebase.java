package com.messageme.jjiron.messageme;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.os.AsyncTask;
import android.os.Handler;
import android.provider.Telephony;
import android.telephony.SmsManager;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.widget.Toast;

import com.facebook.AccessToken;
import com.google.firebase.auth.FacebookAuthProvider;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import com.messageme.jjiron.messageme.models.Contact;
import com.messageme.jjiron.messageme.models.Conversation;
import com.messageme.jjiron.messageme.models.Message;

import java.util.ArrayList;
import java.util.List;

public class Firebase {
    private static final String TAG = "Firebase";

    private static final String SENT_BROADCAST = "SMS_BROADCAST";

    private DatabaseReference outboxDB;
    private DatabaseReference sentDB;
    private DatabaseReference contactsDB;
    private DatabaseReference messagesDB;
    private DatabaseReference desktopDB;
    private DatabaseReference conversationsDB;
    private SmsMmsContentListener smsMmsContentListener;

    private static Firebase instance;
    private String conversationId;

    // syncing tasks
    private AsyncTask<Void, Integer, List<Contact>> contactsSync;
    private AsyncTask<Void, Integer, List<Conversation>> conversationSync;
    private AsyncTask<String, Integer, List<Message>> messageSync;
    private boolean hasLoggedIn;
    private final List<Message> outboxMessages;

    public static Firebase getInstance() {
        if (instance == null) {
            instance = new Firebase();
        }
        return instance;
    }

    private Firebase() {
        outboxMessages = new ArrayList<>();
    }



    // METHODS

    public void login() {
        if (hasLoggedIn) {
            return;
        }

        hasLoggedIn = true;

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();

        if (user == null) {
            FirebaseAuth.getInstance()
                    .signInWithCredential(FacebookAuthProvider.getCredential(AccessToken.getCurrentAccessToken().getToken()))
                    .addOnCompleteListener(task -> {
                        onLoggedIn(FirebaseAuth.getInstance().getCurrentUser());
                    });
        } else {
            onLoggedIn(user);
        }
    }

    @SuppressLint("MissingPermission")
    private void onLoggedIn(FirebaseUser user) {
        DatabaseReference db = FirebaseDatabase.getInstance().getReference();
        String uid = user.getUid();

        // setup firebase databases
        outboxDB = db.child("outbox").child(uid);
        sentDB = db.child("sent").child(uid);
        contactsDB = db.child("contacts").child(uid);
        messagesDB = db.child("messages").child(uid);
        desktopDB = db.child("desktop").child(uid);
        conversationsDB = db.child("conversations").child(uid);

        // add firebase listeners
        outboxDB.addChildEventListener(new OutboxListener());
        desktopDB.child("conversation").addValueEventListener(new ConversationListener());


        // add listeners for message changes
        ContentResolver contentResolver = getContentResolver();
        smsMmsContentListener = new SmsMmsContentListener();
        contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, smsMmsContentListener);
        contentResolver.registerContentObserver(Telephony.Mms.CONTENT_URI, true, smsMmsContentListener);

        Contact.myNumber = Contact.normalizeAddress(((TelephonyManager) getContext().getSystemService(Context.TELEPHONY_SERVICE)).getLine1Number());

        // sync firebase
        syncContacts();
        syncAllConversations();
    }

    private Context getContext() {
        return App.get();
    }
    private ContentResolver getContentResolver() {
        return getContext().getContentResolver();
    }

    @SuppressLint("StaticFieldLeak")
    private void syncContacts() {
        if (contactsSync != null) {
            contactsSync.cancel(true);
        }

        Log.d(TAG, "syncContacts...");

        contactsSync = new AsyncTask<Void, Integer, List<Contact>>() {
            @Override
            protected List<Contact> doInBackground(Void... voids) {
                return Contact.getAllMobile();
            }

            @Override
            protected void onPostExecute(List<Contact> contacts) {
                for (Contact contact : contacts) {
                    contactsDB.child(contact.number).setValue(contact.displayName);
                }

                contactsSync = null;

                Log.d(TAG, "done syncContacts");
            }
        };

        contactsSync.execute();
    }

    @SuppressLint("StaticFieldLeak")
    private void syncAllConversations() {
        Log.d(TAG, "syncAllConversations....");

        if (conversationSync != null) {
            conversationSync.cancel(true);
        }

        conversationSync = new AsyncTask<Void, Integer, List<Conversation>>() {
            @Override
            protected List<Conversation> doInBackground(Void... voids) {
                return Conversation.getAll();
            }

            @Override
            protected void onPostExecute(List<Conversation> conversations) {
                conversationsDB.setValue(conversations);
                conversationSync = null;
                Log.d(TAG, "done syncAllConversations");
            }
        };

        conversationSync.execute();
    }

    @SuppressLint("StaticFieldLeak")
    private void syncMessages(final String conversationId) {
        if (conversationId == null) {
            Log.d(TAG, "trying to syncing conversation, but no conversationId");
            return;
        }

        Log.d(TAG, "syncMessages " + conversationId);

        if (messageSync != null) {
            messageSync.cancel(true);
        }

        // copy the outbox on the ui thread so we dont have problems iterating on a separate thread
        final List<Message> outbox = new ArrayList<>(outboxMessages);
        final List<Message> newlySent = new ArrayList<>();

        messageSync = new AsyncTask<String, Integer, List<Message>>() {
            @Override
            protected List<Message> doInBackground(String... strings) {
                List<Message> messages = Message.getAll(conversationId);

                // merge outbox messages, but also just add items that have NOT been sent to avoid
                // duplication
                if (outbox.size() > 0) {
                    for (Message outboxMessage : outbox) {
                        Message sentMessage = findMatch(messages, outboxMessage);

                        if (sentMessage != null) {
                            // save the key!
                            sentMessage.outboxKey = outboxMessage.outboxKey;

                            // it was sent!
                            newlySent.add(sentMessage);
                        } else {
                            // hasn't been sent. add it to the list so it shows up in the UI
                            messages.add(outboxMessage);
                        }
                    }


                    // only sort when we have outbox messages. by default Message.getAll returns
                    // sorted items
                    messages.sort((m1, m2) -> {
                        if (m1.date < m2.date) {
                            return -1;
                        }

                        if (m1.date > m2.date) {
                            return 1;
                        }

                        return 0;
                    });
                } else {
                    Log.d(TAG, "no outbox");
                }

                return messages;
            }

            @Override
            protected void onPostExecute(List<Message> messages) {
                // update the sent/outbox db
                for (Message sentMessage: newlySent) {
                    sentDB.child(sentMessage.id).setValue(true); // mark this message
                    outboxDB.child(sentMessage.outboxKey).removeValue();
                }

                messagesDB.child(conversationId).setValue(messages);

                messageSync = null;

                Log.d(TAG, "done syncMessages " + conversationId);
            }
        };

        messageSync.execute(conversationId);
    }

    private Message findMatch(List<Message> messages, Message outboxMessage) {
        for (int i = messages.size()-1; i >= 0; i--) {
            Message message = messages.get(i);

            // the message list is sorted so we can say that we've gone to far back in time.
            if (message.date < outboxMessage.date) {
                break;
            }

            // TODO account for mms messages. body might be null
            if (message.status == Message.Status.SENT &&
                message.address.equals(outboxMessage.address) &&
                message.body != null &&
                message.body.equals(outboxMessage.body) ) {
                return message;
            }
        }

        return null;
    }

    // INTERNAL LISTENERS

    private class OutboxListener extends FirebaseChildListenerAdapter {
        @Override
        public void onChildAdded(DataSnapshot dataSnapshot, String s) {
            Message outboxMessage = dataSnapshot.getValue(Message.class);
            if (outboxMessage == null) {
                Log.d(TAG, "outbox tried to process but its null " + dataSnapshot.getValue());
                return;
            }

            if (outboxMessage.address == null) {
                Log.d(TAG, "outbox tried to process but address is null " + dataSnapshot.getValue().toString());
                return;
            }

            outboxMessage.outboxKey = dataSnapshot.getKey();
            outboxMessages.add(outboxMessage);

            // add error handler
            getContext().registerReceiver(new OutboxMessageErrorReceiver(outboxMessage), new IntentFilter(SENT_BROADCAST));

            // send the message
            SmsManager.getDefault().sendTextMessage(
                    outboxMessage.address,
                    null,
                    outboxMessage.body,
                    PendingIntent.getBroadcast(getContext(), 0 ,new Intent(SENT_BROADCAST),0),
                    null
            );

            // now we wait for the messageSync to happen and look for the sent message
            // @see syncMessages
        }

        @Override
        public void onChildRemoved(DataSnapshot dataSnapshot) {
            Message outboxMessage = dataSnapshot.getValue(Message.class);
            if (outboxMessage == null) {
                return;
            }

            // remove the right message from the list
            for (int i=0; i < outboxMessages.size(); ++i) {
                Message message = outboxMessages.get(i);
                if (message.id.equals(outboxMessage.id)) {
                    outboxMessages.remove(i);
                    break;
                }
            }
        }

        private class OutboxMessageErrorReceiver extends BroadcastReceiver {

            private final Message outboxMessage;

            public OutboxMessageErrorReceiver(Message outboxMessage) {
                this.outboxMessage = outboxMessage;
            }

            @Override
            public void onReceive(Context context, Intent intent) {
                getContext().unregisterReceiver(this);

                switch (getResultCode()) {
                    case Activity.RESULT_OK:
                        Log.d(TAG, "message sent!" + outboxMessage.toString());
                        break;
                    default:
                        // TODO handle error scenario
                        Toast.makeText(getContext(), "error sending message", Toast.LENGTH_SHORT).show();
                        Log.d(TAG, "error sending message");
                        break;
                }
            }
        }
    }

    private class ConversationListener implements ValueEventListener {
        @Override
        public void onDataChange(DataSnapshot dataSnapshot) {
            // save it as the "current" state
            conversationId = dataSnapshot.getValue(String.class);
            Log.d(TAG, "conversation id changed and is " + conversationId);
            syncMessages(conversationId);
        }

        @Override
        public void onCancelled(DatabaseError databaseError) {

        }
    }

    private class SmsMmsContentListener extends ContentObserver {
        public SmsMmsContentListener() {
            super(new Handler());
        }

        @Override
        public boolean deliverSelfNotifications() {
            return true;
        }

        @Override
        public void onChange(boolean selfChange) {
            super.onChange(selfChange);
            syncMessages(conversationId);
        }
    }
}
