package com.messageme.jjiron.messageme.models;

import com.google.firebase.database.IgnoreExtraProperties;

@IgnoreExtraProperties
public class OutboxMessage {
    public String id;
    public String threadId;
    public String address;
    public String body;
    public long date;

    public OutboxMessage() {
    }

    public OutboxMessage(String id, String address, String body, long date, String threadId) {
        this.id = id;
        this.address = address;
        this.body = body;
        this.date = date;
        this.threadId = threadId;
    }

    @Override
    public String toString() {
        return new StringBuilder()
                .append("OutboxMessage -- ")
                .append(" address: ")
                .append(address)
                .append(" body: ")
                .append(body)
                .append(" date: ")
                .append(date)
                .append(" threadId: ")
                .append(threadId)
                .toString();
    }
}
