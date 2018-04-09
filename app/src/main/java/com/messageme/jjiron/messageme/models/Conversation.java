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
    public String address; // phone number of other person, or list of addresses separate by a "," for group convos
    public String type; // sms or mms
    private int messageStatus; // Message.Status

    public Conversation() {
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
                .append(messageStatus == 1 ? "received" : "sent    ")
                .append(" body: ")
                .append(body)
                .toString();
    }

    public static List<Conversation> getAll() {
        Cursor cursor = App.get().getContentResolver().query(Telephony.MmsSms.CONTENT_CONVERSATIONS_URI,
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
            conversation.date = cursor.getLong(cursor.getColumnIndex(Telephony.Sms.DATE));
            conversation.body = cursor.getString(cursor.getColumnIndex(Telephony.Sms.BODY));
            conversation.messageStatus = cursor.getInt(cursor.getColumnIndex(Telephony.Sms.TYPE));

            if ("application/vnd.wap.multipart.related".equals(contentType) || "application/vnd.wap.multipart.mixed".equals(contentType)) {
                conversation.date = conversation.date * 1000; // dates are different for mms
                conversation.address = getAllMmsAddresses(cursor.getString(cursor.getColumnIndex(Telephony.Mms._ID)));
                conversation.type = "mms";
            } else {
                conversation.address = Contact.normalizeAddress(cursor.getString(cursor.getColumnIndex(Telephony.Sms.ADDRESS)));
                conversation.type = "sms";
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
        Cursor cursor = App.get().getContentResolver().query(
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
