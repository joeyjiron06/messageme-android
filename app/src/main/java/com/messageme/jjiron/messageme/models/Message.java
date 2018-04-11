package com.messageme.jjiron.messageme.models;

import android.content.ContentResolver;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.provider.Telephony;
import android.util.Log;

import com.google.firebase.database.IgnoreExtraProperties;
import com.messageme.jjiron.messageme.App;
import com.messageme.jjiron.messageme.Cursors;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// https://stackoverflow.com/questions/3012287/how-to-read-mms-data-in-android

@IgnoreExtraProperties
public class Message {
    private static final String TAG = "Message";
    private static final String ORDER_BY = "date desc limit 40";
    private static final Set<String> IMAGE_TYPES = new HashSet<>(Arrays.asList(
        "image/jpeg",
        "image/bmp",
        "image/gif",
        "image/jpg",
        "image/png"
    ));

    public String id; // id of the message
    public String conversationId; // id of the conversation
    public String body; // text content of the message
    public long date; // when the text was sent/receive
    public String address; // phone number of the person who sent the message
    public int status; // Message.Status
    public boolean sentFromDesktop; // did we send this from the desktop or not
    public List<Message.Part> parts;


    public Message() {}

    private void addPart(Part part) {
        if (parts == null) {
            parts = new ArrayList<>();
        }

        parts.add(part);
    }

    @Override
    public String toString() {
        return new StringBuilder()
                .append("Message --")
                .append(" id: ").append(id)
                .append(" conversationId: ").append(conversationId)
                .append(" address: ").append(address)
                .append(" date: ").append(date)
                .append(" status: ").append(Status.toString(status))
                .append(" body: ").append(body)
                .toString();
    }

    public static class Status {
        public static final int RECEIVED = 1;
        public static final int SENT = 2;

        static String toString(int val) {
            switch (val) {
                case RECEIVED: return "RECEIVED";
                case SENT: return "SENT";
                default: return "UNKNOWN " + val;
            }
        }
    }

    public static class Part {
        public String id; // id of the part in the mms message
        public String type; // Part.Type - the type of content
        public String url; // url of the uploaded mms content (image, video, etc.)

        public Part() {
        }

        public static class Type {
            public static final String IMAGE = "IMAGE";
            public static final String VIDEO = "VIDEO";
        }
    }

    public static List<Message> getAll(String conversationId) {
        List<Message> messages = new ArrayList<>();

        messages.addAll(getSmsMessages(conversationId));
        messages.addAll(getMmsMessages(conversationId));

        messages.sort((m1, m2) -> {
            if (m1.date < m2.date) {
                return -1;
            } else if (m1.date > m2.date) {
                return 1;
            }
            return 0;
        });

        return messages;
    }

    public static List<Message> getSmsMessages(String conversationId) {
        List<Message> messages = new ArrayList<>();

        Cursor smsCursor = getContentResolver().query(
                Telephony.Sms.CONTENT_URI,
                new String[]{
                    Telephony.Sms._ID,
                    Telephony.Sms.THREAD_ID,
                    Telephony.Sms.TYPE,
                    Telephony.Sms.DATE,
                    Telephony.Sms.ADDRESS,
                    Telephony.Sms.BODY
                },
                "thread_id=" + conversationId,
                null,
                ORDER_BY);

        Cursors.iterate(smsCursor, () -> {
            Message message = new Message();

            message.conversationId = conversationId;
            message.id = smsCursor.getString(smsCursor.getColumnIndex(Telephony.Sms._ID));
            message.status = smsCursor.getInt(smsCursor.getColumnIndex(Telephony.Sms.TYPE));
            message.date = smsCursor.getLong(smsCursor.getColumnIndex(Telephony.Sms.DATE));
            message.address = Contact.normalizeAddress(smsCursor.getString(smsCursor.getColumnIndex(Telephony.Sms.ADDRESS)));
            message.body = smsCursor.getString(smsCursor.getColumnIndex(Telephony.Sms.BODY));

            messages.add(message);
        });

        return messages;
    }

    public static List<Message> getMmsMessages(String conversationId) {
        List<Message> messages = new ArrayList<>();

        Cursor mmsCursor = getContentResolver().query(
                Telephony.Mms.CONTENT_URI,
                null,
                "thread_id=" + conversationId,
                null,
                ORDER_BY);

        Cursors.iterate(mmsCursor, () -> {
            Message message = new Message();

            message.conversationId = conversationId;
            message.id = mmsCursor.getString(mmsCursor.getColumnIndex(Telephony.Mms._ID));
            message.date = mmsCursor.getLong(mmsCursor.getColumnIndex(Telephony.Mms.DATE)) * 1000;
            message.status = mmsCursor.getInt(mmsCursor.getColumnIndex(Telephony.Mms.MESSAGE_BOX));
            setMmsParts(message);


            // optimization - we know that if the status is sent, then we sent it, so no need to get address because it's ours
            if (message.status != Message.Status.SENT) {
                message.address = getMmsAddress(message.id);
            }

            messages.add(message);
        });

        return messages;
    }

    public static Bitmap getImage(String partId) {
        InputStream inputStream = null;
        Bitmap bitmap = null;

        try {
            inputStream = getContentResolver().openInputStream(Uri.parse("content://mms/part/" + partId));
            bitmap = BitmapFactory.decodeStream(inputStream);
        } catch (IOException e) {
            Log.e(TAG, "error getting image " + e);
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException e) {
                    Log.e(TAG, "error closing inputStream image " + e);
                }
            }
        }

        return bitmap;
    }

    private static void setMmsParts(Message message) {
        Cursor cursor = getContentResolver().query(
                Uri.parse("content://mms/part"),
                null,
                "mid=" + message.id,
                null,
                null);

        Cursors.iterate(cursor,  () -> {
            String type = cursor.getString(cursor.getColumnIndex(Telephony.Mms.Part.CONTENT_TYPE));
            if ("text/plain".equals(type)) {
                message.body = cursor.getString(cursor.getColumnIndex(Telephony.Mms.Part.TEXT));
            } else if (IMAGE_TYPES.contains(type)) {
                Part part = new Part();
                part.id = cursor.getString(cursor.getColumnIndex(Telephony.Mms.Part._ID));
                part.type = Part.Type.IMAGE;
                message.addPart(part);
            }
            // TODO videos as well
        });
    }

    private static String getMmsAddress(String id) {
        Cursor cursor = getContentResolver().query(
                Uri.parse("content://mms/" + id + "/addr"),
                new String[]{Telephony.Sms.ADDRESS},
                "msg_id=" + id,
                null,
                null);

        if (cursor != null && cursor.moveToFirst()) {
            String address = Contact.normalizeAddress(cursor.getString(cursor.getColumnIndex(Telephony.Sms.ADDRESS)));
            cursor.close();
            return address;
        }

        return null;
    }


    private static ContentResolver getContentResolver() {
        return App.get().getContentResolver();
    }
}
