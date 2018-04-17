package com.messageme.jjiron.messageme.firebase;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.graphics.Bitmap;
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
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;
import com.messageme.jjiron.messageme.models.Contact;
import com.messageme.jjiron.messageme.models.Conversation;
import com.messageme.jjiron.messageme.models.Message;
import com.messageme.jjiron.messageme.util.Cursors;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class FirebaseManager {
    private static final String TAG = "FirebaseManager";

    private static final String SENT_BROADCAST = "SMS_BROADCAST";

    private SmsMmsContentListener smsMmsContentListener;

    private static FirebaseManager instance;
    private String conversationId;

    // syncing tasks
    private AsyncTask<Void, Integer, List<Contact>> contactsSync;
    private AsyncTask<Void, Integer, List<Conversation>> conversationSync;
    private AsyncTask<String, Integer, List<Message>> messageSync;
    private boolean hasInitialized;

    // firebase references
    private DatabaseReference db;
    private StorageReference storage;

    // cached data
    private final List<Message> outboxMessages;
    private final Set<String> sentIds;
    private final Map<String, String> partUrls;
    // todo cache messages

    public static FirebaseManager getInstance() {
        if (instance == null) {
            instance = new FirebaseManager();
        }
        return instance;
    }

    private FirebaseManager() {
        outboxMessages = new ArrayList<>();
        sentIds = new HashSet<>();
        partUrls = new HashMap<>();
    }



    // METHODS

    public void initialize(Context context) {
        if (hasInitialized) {
            return;
        }

        hasInitialized = true;
        Cursors.context = context;

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
        db = FirebaseDatabase.getInstance().getReference().child(user.getUid());
        storage = FirebaseStorage.getInstance().getReference().child(user.getUid());

        // add firebase listeners
        db.child("outbox").addChildEventListener(new OutboxListener());
        db.child("sent").addValueEventListener(new SentListener());
        db.child("desktop").child("conversation").addValueEventListener(new ConversationListener());
        db.child("desktop").child("requests").child("mmsUpload").addChildEventListener(new MmsUploadRequestListener());
        db.child("mmsUploadUrls").addValueEventListener(new MmsUploadUrlsValueListener());
        FirebaseDatabase.getInstance().getReference().child(".info/connected").addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(final DataSnapshot dataSnapshot) {
                if (dataSnapshot.getValue(Boolean.class)){
                    DatabaseReference onlineRef = db.child("phone").child("online");
                    onlineRef.onDisconnect().setValue(false);
                    onlineRef.setValue(true);
                }
            }

            @Override
            public void onCancelled(final DatabaseError databaseError) {
                Log.d(TAG, "DatabaseError:" + databaseError);
            }
        });


        // add listeners for message changes
        ContentResolver contentResolver = getContentResolver();
        smsMmsContentListener = new SmsMmsContentListener();
        contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, smsMmsContentListener);
        contentResolver.registerContentObserver(Telephony.Mms.CONTENT_URI, true, smsMmsContentListener);
        contentResolver.registerContentObserver(Telephony.MmsSms.CONTENT_CONVERSATIONS_URI, true, new ConversationContentObserver());

        Contact.myNumber = Contact.normalizeAddress(((TelephonyManager) getContext().getSystemService(Context.TELEPHONY_SERVICE)).getLine1Number());

        // sync firebase
        syncContacts();
        syncAllConversations();
    }

    private Context getContext() {
        return Cursors.context;
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
                    db.child("contacts").child(contact.number).setValue(contact.displayName);
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
                db.child("conversations").setValue(conversations);
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
            Log.d(TAG, "message sync in progress. cancelling ");
            messageSync.cancel(true);
        }

        // copy these values so that we don't have problems iterating on a background thread
        final List<Message> outbox = new ArrayList<>(outboxMessages);
        final Set<String> sentMessageIds = new HashSet<>(sentIds);
        final Map<String, String> partUrlsMap = new HashMap<>(partUrls);
        final Set<String> sentOutboxIds = new HashSet<>();

        messageSync = new AsyncTask<String, Integer, List<Message>>() {
            @Override
            protected List<Message> doInBackground(String... strings) {
                Log.d(TAG, "message sync running in background ");

                if (isCancelled()) {
                    return null;
                }

                // all the sms/mms messages for the conversation
                // TODO improve this with caching
                List<Message> messages = Message.getAll(conversationId);

                if (isCancelled()) {
                    return null;
                }

                // merge outbox messages, but also just add items that have NOT been sent to avoid
                // duplication
                if (outbox.size() > 0) {
                    for (Message outboxMessage : outbox) {
                        // part of different conversation - dont add to the list of messages
                        if (!conversationId.equals(outboxMessage.conversationId)) {
                            continue;
                        }


                        Message sentMessage = findMatch(messages, outboxMessage);

                        if (sentMessage != null) {
                            // it was sent. dont add it to the messages list because its already there
                            sentMessageIds.add(sentMessage.id);
                            sentOutboxIds.add(outboxMessage.id);
                        } else {
                            Log.d(TAG, "no match found for outbox message ");
                            Log.d(TAG, outboxMessage.toString());

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

                for (Message message : messages) {
                    message.sentFromDesktop = sentMessageIds.contains(message.id);
                    if (message.parts != null) {
                        for (Message.Part part : message.parts) {
                            part.url = partUrlsMap.get(part.id);// can and will be null sometimes
                        }
                    }
                }

                return messages;
            }

            @Override
            protected void onPostExecute(List<Message> messages) {
                if (isCancelled()) {
                    return;
                }


                Log.d(TAG, "message sync post execute ");

                // remove any sent outbox messages from the outbox
                for (String outboxId : sentOutboxIds) {
                    removeOutboxMessage(outboxId);
                    db.child("outbox").child(outboxId).removeValue();
                }

                // update the sent message ids
                for (String messageId : sentMessageIds) {
                    db.child("sent").child(messageId).setValue(true);
                }

                // update the message for the specific conversation
                db.child("messages").child(conversationId).setValue(messages);

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
                Log.d(TAG, "message is older");
                Log.d(TAG, "message " + message);
                Log.d(TAG, "outbox message " + outboxMessage);
                break;
            }

            // TODO account for mms messages. body might be null
            if (message.status == Message.Status.SENT &&
                message.address.equals(outboxMessage.address) &&
                message.body != null &&
                message.body.equals(outboxMessage.body) ) {
                return message;
            } else {
                Log.d(TAG, "messages are different ");
                Log.d(TAG, "message " + message);
                Log.d(TAG, "outbox message " + outboxMessage);
            }
        }

        return null;
    }

    private void removeOutboxMessage(String messageId) {
        Log.d(TAG, "removing message with id " + messageId);

        // remove the right message from the list
        for (int i=0; i < outboxMessages.size(); ++i) {
            Message message = outboxMessages.get(i);
            if (message.id.equals(messageId)) {
                Log.d(TAG, "removing message " + message);
                outboxMessages.remove(i);
                break;
            }
        }
    }

    private boolean hasOutboxMessage(String messageId) {
        for (Message message: outboxMessages) {
            if (message.id.equals(messageId)) {
                return true;
            }
        }

        return false;
    }

    // INTERNAL LISTENERS
    private class SentListener implements ValueEventListener {
        @Override
        public void onDataChange(DataSnapshot dataSnapshot) {
            // clear any previous data
            sentIds.clear();

            for (DataSnapshot snapshot : dataSnapshot.getChildren()) {
                String messageId = snapshot.getKey();
                sentIds.add(messageId);
            }
        }

        @Override
        public void onCancelled(DatabaseError databaseError) { }
    }

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

            if (outboxMessage.status != Message.Status.REQUESTING) {
                Log.d(TAG, "outbox message is not in the requesting state. not sending the message " + outboxMessage);
                return;
            }

            if (hasOutboxMessage(outboxMessage.id)) {
                Log.d(TAG, "already sending message but received a new outbox message. " + outboxMessage);
                return;
            }

            // the date received from the client might be ahead of our time so we need to adjust for
            // that and mark the date a now. it causes issues when trying to find message that was sent
            outboxMessage.date = System.currentTimeMillis();

            Log.d(TAG, "outbox message added. sending... " + outboxMessage);

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

            Log.d(TAG, "outbox message removed " + outboxMessage);
            removeOutboxMessage(outboxMessage.id);
        }

        private class OutboxMessageErrorReceiver extends BroadcastReceiver {

            private final Message outboxMessage;

            public OutboxMessageErrorReceiver(Message outboxMessage) {
                this.outboxMessage = outboxMessage;
            }

            @Override
            public void onReceive(Context context, Intent intent) {
                getContext().unregisterReceiver(this);
                int result = getResultCode();

                switch (result) {
                    case Activity.RESULT_OK:
                        Log.d(TAG, "message sent!" + outboxMessage.toString());
                        break;
                    default:
                        outboxMessage.status = Message.Status.FAILED;
                        db.child("outbox").child(outboxMessage.id).setValue(outboxMessage);

                        // TODO handle error scenario
                        Toast.makeText(getContext(), "error sending message", Toast.LENGTH_SHORT).show();
                        Log.d(TAG, "error sending message " + (result));
                        break;
                }
            }
        }
    }

    private class ConversationContentObserver extends ContentObserver {
        public ConversationContentObserver() {
            super(new Handler());
        }

        @Override
        public void onChange(boolean selfChange) {
            super.onChange(selfChange);
            syncAllConversations();
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

    private class MmsUploadRequestListener extends FirebaseChildListenerAdapter {
        @Override
        public void onChildAdded(DataSnapshot dataSnapshot, String s) {
            String partId = dataSnapshot.getKey();
            Log.d(TAG, "mmsUpload child added " + partId);
            attemptUploadMMSContent(partId);
        }

        @SuppressLint("StaticFieldLeak")
        private void attemptUploadMMSContent(String partId) {
            new AsyncTask<Void, Integer, byte[]>() {

                @Override
                protected byte[] doInBackground(Void... voids) {
                    // there may be multiple images in one mms message.
                    Bitmap bitmap = Message.getImage(partId);
                    byte[] imageBytes = null;

                    if (bitmap != null) {
                        Log.d(TAG, "got a bitmap!");

                        ByteArrayOutputStream imageByteStream = new ByteArrayOutputStream();
                        int quality = 70;
                        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, imageByteStream);
                        imageBytes = imageByteStream.toByteArray();

                    } else {
                        Log.d(TAG, "no bitmap for partId " + partId);
                    }

                    return imageBytes;
                }

                @Override
                protected void onPostExecute(byte[] imageBytes) {
                    if (imageBytes == null) {
                        Log.d(TAG, "no image found for mmsId " + partId);
                        return;
                    }

                    storage.child("images")
                        .child(partId + ".jpg")
                        .putBytes(imageBytes)
                        .addOnSuccessListener(taskSnapshot -> {
                            Log.d(TAG, "image upload success ");

                            String url = taskSnapshot.getDownloadUrl().toString();

                            // remove from requests since the request is now fullfilled
                            db.child("desktop").child("requests").child("mmsUpload").child(partId).removeValue();

                            db.child("mmsUploadUrls").child(partId).setValue(url);
                        })
                        .addOnFailureListener(e -> {
                            Log.e(TAG, "image upload error " + e);
                        });
                }
            }.execute();
        }
    }

    private class MmsUploadUrlsValueListener implements ValueEventListener {
        @Override
        public void onDataChange(DataSnapshot dataSnapshot) {
            // cache the urls for mms parts
            for (DataSnapshot partSnapshot : dataSnapshot.getChildren()) {
                partUrls.put(partSnapshot.getKey(), partSnapshot.getValue(String.class));
            }
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
            Log.d(TAG, "sms/mms content changed");
            syncMessages(conversationId);
        }
    }
}
