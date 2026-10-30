package com.example.schedulingsoftware.notify;

/**
 * What happened when a message was handed to WhatsApp or the email service.
 *
 * @param retry true when trying again later may work (network trouble, rate limits)
 */
public record SendResult(boolean ok, String messageId, String error, boolean retry) {

    static SendResult sent(String id) {
        return new SendResult(true, id, null, false);
    }

    static SendResult failed(String error, boolean retry) {
        return new SendResult(false, null, error, retry);
    }
}
