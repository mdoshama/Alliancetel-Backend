package com.example.schedulingsoftware.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.Test;

class WhatsAppFormatTest {

    @Test
    void timesAndDays() {
        assertEquals("10am", ScheduleNotifier.time(LocalTime.of(10, 0)));
        assertEquals("8pm", ScheduleNotifier.time(LocalTime.of(20, 0)));
        assertEquals("12pm", ScheduleNotifier.time(LocalTime.of(12, 0)));
        assertEquals("9:30am", ScheduleNotifier.time(LocalTime.of(9, 30)));
        assertEquals("Wed 11/4", ScheduleNotifier.day(LocalDate.of(2026, 11, 4), false));
        assertEquals("Wednesday, Nov 4", ScheduleNotifier.day(LocalDate.of(2026, 11, 4), true));
    }

    @Test
    void templateValuesFollowWhatsAppsRules() {
        assertEquals("a b", ScheduleNotifier.tidy("a\n\tb"));
        assertEquals("a b", ScheduleNotifier.tidy("a      b"));
        assertEquals("-", ScheduleNotifier.tidy("  "));
        assertEquals(900, ScheduleNotifier.tidy("x".repeat(2000)).length());
        assertEquals("Hi Ana, 2", ScheduleNotifier.fill("Hi {{1}}, {{2}}", List.of("Ana", "2")));
    }

    @Test
    void metaRequestsAndErrors() {
        assertEquals("\"a\\\"b\\\\c\\nd\"", MetaWhatsAppClient.json("a\"b\\c\nd"));
        String err = "{\"error\":{\"message\":\"(#132001) Template name does not exist in the translation\",\"type\":\"OAuthException\","
                + "\"code\":132001,\"error_data\":{\"details\":\"template name (schedule_published) does not exist in en_US\"}}}";
        assertEquals("(#132001) Template name does not exist in the translation – template name (schedule_published) "
                + "does not exist in en_US", MetaWhatsAppClient.errorText(err, 404));
        assertEquals("HTTP 500", MetaWhatsAppClient.errorText("oops", 500));
    }
}
