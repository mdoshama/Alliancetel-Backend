package com.example.schedulingsoftware.notify;

public enum NotificationStatus {
    /** Waiting for the sender (normally a few seconds). */
    QUEUED,
    /** Accepted by WhatsApp. */
    SENT,
    /** WhatsApp refused it after several tries; see the error. */
    FAILED,
    /** Not sent: no phone number, the person hasn't agreed to WhatsApp messages, or WhatsApp isn't connected. */
    NOT_SENT
}
