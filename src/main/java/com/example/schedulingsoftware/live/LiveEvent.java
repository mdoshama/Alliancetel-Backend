package com.example.schedulingsoftware.live;

import java.time.Instant;

/**
 * "Something changed": sent to every other open screen so it can reload what it shows.
 *
 * @param topic    what changed: {@code schedule}, {@code dayoff}, {@code people} or {@code timeclock}
 * @param byName   who made the change (first name; only sent to managers and admins)
 * @param clientId the browser tab that made it, so that tab doesn't reload itself
 * @param at       when
 */
public record LiveEvent(String topic, String byName, String clientId, Instant at) {
}
