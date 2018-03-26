package com.messageme.jjiron.messageme.models;

import android.database.Cursor;
import android.net.Uri;
import android.provider.Telephony;

import com.google.firebase.database.IgnoreExtraProperties;
import com.messageme.jjiron.messageme.App;
import com.messageme.jjiron.messageme.Cursors;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@IgnoreExtraProperties
public class Conversation {
    public String id;
    public String body;
    public long date;
    public String address;
    public int type; // 1 received, 2 sent
    public String messageType; // Message.Type

    public Conversation() {
    }

    public Conversation(String id, String body, long date, String address, int type, String messageType) {
        this.id = id;
        this.type = type;
        this.date = date;
        this.address = address;
        this.body = body;
        this.messageType = messageType;
    }

    @Override
    public String toString() {
        return new StringBuilder()
                .append("Conversation -- ")
                .append("id: ")
                .append(id)
                .append(" address: ")
                .append(address)
                .append(" date: ")
                .append(date)
                .append(" status: ")
                .append(type == 1 ? "received" : "sent    ")
                .append(" messageType: ")
                .append(messageType)
                .append(" body: ")
                .append(body)
                .toString();
    }

    public static String snippet(String message) {
        if (message == null) {
            return message;
        }

        if (message.length() > 32) {
            return message.substring(0, 31);
        }

        return message;
    }


    public static List<Conversation> getAll() {
        Cursor cursor = App.contentResolver.query(Telephony.MmsSms.CONTENT_CONVERSATIONS_URI,
                new String[]{
                    Telephony.Mms._ID,
                    Telephony.Mms.THREAD_ID,
                    Telephony.Mms.CONTENT_TYPE,
                    Telephony.Sms.TYPE,
                    Telephony.Sms.DATE,
                    Telephony.Sms.ADDRESS,
                    Telephony.Sms.BODY
                },
                null,
                null,
                null);

        List<Conversation> result = new ArrayList<>();

        Cursors.iterate(cursor, () -> {
            String contentType = cursor.getString(cursor.getColumnIndex(Telephony.Mms.CONTENT_TYPE));

            Conversation conversation = new Conversation();

            conversation.id = cursor.getString(cursor.getColumnIndex(Telephony.Mms.THREAD_ID));
            conversation.type = cursor.getInt(cursor.getColumnIndex(Telephony.Sms.TYPE));
            conversation.date = cursor.getLong(cursor.getColumnIndex(Telephony.Sms.DATE));
            conversation.body = Conversation.snippet(cursor.getString(cursor.getColumnIndex(Telephony.Sms.BODY)));

            if ("application/vnd.wap.multipart.related".equals(contentType) || "application/vnd.wap.multipart.mixed".equals(contentType)) {
                conversation.messageType = Message.Type.MMS;
                conversation.date = conversation.date * 1000; // dates are different for mms
                conversation.address = getAllMmsAddresses(cursor.getString(cursor.getColumnIndex(Telephony.Mms._ID)));
            } else {
                conversation.messageType = Message.Type.SMS;
                conversation.address = Contact.normalizeAddress(cursor.getString(cursor.getColumnIndex(Telephony.Sms.ADDRESS)));
            }

            result.add(conversation);
        });

        result.sort((c1, c2) -> {
            if (c1.date < c2.date) {
                return 1;
            } else if (c1.date > c2.date) {
                return -1;
            }

            return 0;
        });

        return result;
    }


    private static String getAllMmsAddresses(String id) {
        Cursor cursor = App.contentResolver.query(
                Uri.parse("content://mms/" + id + "/addr"),
                new String[]{Telephony.Sms.ADDRESS},
                "msg_id=" + id,
                null,
                null);
        List<String> addresses = new ArrayList<>();

        Cursors.iterate(cursor, () -> {
            String address = cursor.getString(cursor.getColumnIndex(Telephony.Sms.ADDRESS));
            address = Contact.normalizeAddress(address);

            if (!address.equals(Contact.myNumber)) {
                addresses.add(address);
            }
        });

        return addresses.toString()
                .replace("[", "")
                .replace("]", "")
                .replace(" ", "");
    }

}
