package com.example.schedulingsoftware.notify;

/** Sends one email. */
public interface EmailClient {

    /**
     * @param idempotencyKey the same key never sends twice (protects against duplicate sends when retrying)
     */
    SendResult send(String to, String subject, String text, String html, String idempotencyKey);
}
