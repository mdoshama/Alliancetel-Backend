package com.example.schedulingsoftware.dayoff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Generating a week failed with "This change conflicts with existing data" when a waiting request was turned down
 * with a long "Not given: …" explanation (over the 255 characters the column holds).
 */
class LongNotesFitTest {

    @Test
    void longTextIsShortenedToFitTheColumn() {
        DayOffRequest r = new DayOffRequest();
        String longNote = "Not given: " + "Ana, Ben, Cara, Dee and Eli are already off at North, South and Bay. ".repeat(10);
        r.setReviewNote(longNote);
        r.setDecisionChangeReason(longNote);
        r.setReason(longNote);
        assertEquals(DayOffRequest.TEXT_LIMIT, r.getReviewNote().length());
        assertTrue(r.getReviewNote().startsWith("Not given: Ana"));
        assertTrue(r.getReviewNote().endsWith("…"));
        assertTrue(r.getDecisionChangeReason().length() <= DayOffRequest.TEXT_LIMIT);
        assertTrue(r.getReason().length() <= DayOffRequest.TEXT_LIMIT);
    }

    @Test
    void shortTextAndNothingStayAsTheyAre() {
        DayOffRequest r = new DayOffRequest();
        r.setReviewNote("Given automatically: the stores stay covered.");
        assertEquals("Given automatically: the stores stay covered.", r.getReviewNote());
        r.setReviewNote(null);
        assertNull(r.getReviewNote());
    }
}
