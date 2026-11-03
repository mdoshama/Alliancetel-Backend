package com.example.schedulingsoftware.notify;

/** Sends one text message. */
public interface SmsClient {

    /**
     * @param to   international number, e.g. +16195550142
     * @param text the message (kept short: long texts are split into several and cost more)
     */
    SendResult send(String to, String text);
}
