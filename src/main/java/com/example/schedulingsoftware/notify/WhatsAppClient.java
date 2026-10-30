package com.example.schedulingsoftware.notify;

import java.util.List;

/** Sends one approved template message. */
public interface WhatsAppClient {

    /**
     * @param to       international number, e.g. +16195550142
     * @param template approved template name
     * @param params   values for {{1}}, {{2}}, …
     */
    SendResult send(String to, String template, String language, List<String> params);

}
