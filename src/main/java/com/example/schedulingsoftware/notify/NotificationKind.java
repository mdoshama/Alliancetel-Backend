package com.example.schedulingsoftware.notify;

/** What a WhatsApp message is about; each kind uses its own approved template. */
public enum NotificationKind {
    /** A week was published: the person's whole week. */
    WEEK_PUBLISHED,
    /** A published week changed: only the person's changed days. */
    WEEK_CHANGED,
    /** Their day-off request was approved or rejected. */
    DAY_OFF_DECISION,
    /** A message from a manager or admin to everyone in an area. */
    ANNOUNCEMENT,
    /** Text only: the one-time confirmation sent when someone agrees to text messages. */
    SMS_OPT_IN,
    /** Email to admins and managers: team birthdays coming up (and today). */
    BIRTHDAY_REMINDER,
    /** A one-time link to choose a password: new account or password reset. The link is never shown on the Messages page. */
    ACCOUNT_SETUP
}
