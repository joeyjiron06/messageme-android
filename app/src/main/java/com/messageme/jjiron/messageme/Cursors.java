package com.messageme.jjiron.messageme;

import android.database.Cursor;
import android.util.Log;

public class Cursors {
    public static void iterate(Cursor cursor, Runnable runnable) {
        if (cursor != null && cursor.moveToFirst()) {
            do {
                runnable.run();
            } while (cursor.moveToNext());
            cursor.close();
        }
    }

    public static void debug(Cursor cursor) {
        Cursors.iterate(cursor, () -> {
            StringBuilder msgData = new StringBuilder();

            for (int idx = 0; idx < cursor.getColumnCount(); idx++){
                msgData.append(" " + cursor.getColumnName(idx) + ":" + cursor.getString(idx));
            }

            Log.d("Cursor", msgData.toString());
        });
    }
}
