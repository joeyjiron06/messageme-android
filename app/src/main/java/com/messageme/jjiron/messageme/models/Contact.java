package com.messageme.jjiron.messageme.models;

import android.database.Cursor;
import android.provider.ContactsContract;

import com.google.firebase.database.IgnoreExtraProperties;
import com.messageme.jjiron.messageme.App;
import com.messageme.jjiron.messageme.Cursors;

import java.util.ArrayList;
import java.util.List;

@IgnoreExtraProperties
public class Contact {
    private static final String TAG = "Contact";
    public static String myNumber;

    public String number;
    public String displayName;

    public Contact() {
    }

    public Contact(String number, String displayName) {
        this.number = number;
        this.displayName = displayName;
    }

    @Override
    public String toString() {
        return new StringBuilder()
        .append("Conact -- ")
        .append(" number: ")
        .append(number)
        .append(" displayName: ")
        .append(displayName)
        .toString();
    }

    public static List<Contact> getAllMobile() {
        Cursor cursor = App.get().getContentResolver().query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            new String[] {
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.TYPE
            },
            ContactsContract.CommonDataKinds.Phone.TYPE + "=" + ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE,
            null,
            null);

        List<Contact> contacts = new ArrayList<>();

        Cursors.iterate(cursor, () -> {
            Contact contact = new Contact();
            contact.displayName = cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME));
            contact.number = normalizeAddress(cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)));
            contacts.add(contact);
        });

        return contacts;
    }

    public static String normalizeAddress(String address) {
        address = address
                .replace("+", "")
                .replace("-", "")
                .replace(" ", "")
                .replace("(", "")
                .replace(")", "");

        if (address.length() > 10 && address.startsWith("1")) {
            address = address.substring(1);
        }

        return address;
    }
}
