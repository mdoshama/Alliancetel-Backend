package com.example.schedulingsoftware.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class PhoneNumbersTest {

    @Test
    void usNumbersInAnyCommonFormat() {
        assertEquals("+16195550142", PhoneNumbers.normalize("(619) 555-0142"));
        assertEquals("+16195550142", PhoneNumbers.normalize("619-555-0142"));
        assertEquals("+16195550142", PhoneNumbers.normalize("619.555.0142"));
        assertEquals("+16195550142", PhoneNumbers.normalize("1 619 555 0142"));
        assertEquals("+16195550142", PhoneNumbers.normalize("+1 (619) 555-0142"));
        assertEquals("+16195550142", PhoneNumbers.normalize("  6195550142 "));
    }

    @Test
    void internationalNumbersNeedTheirCountryCode() {
        assertEquals("+9779812345678", PhoneNumbers.normalize("+977 981-234-5678"));
        assertEquals("+919876543210", PhoneNumbers.normalize("0091 98765 43210"));
        assertEquals("+447911123456", PhoneNumbers.normalize("+44 7911 123456"));
    }

    @Test
    void blankIsNoNumber() {
        assertNull(PhoneNumbers.normalize(null));
        assertNull(PhoneNumbers.normalize("   "));
    }

    @Test
    void rejectsWhatCanNotBeAPhoneNumber() {
        for (String bad : new String[] {"abc", "555-0142", "12345", "619-555-014", "(019) 555-0142", "619-155-0142",
                "+1 619 555 01422", "+0 123 456 789", "+12345678901234567", "61955501 42x", "6+19 555 0142",
                "+977", "98123456789"}) {
            assertThrows(BusinessRuleException.class, () -> PhoneNumbers.normalize(bad), bad);
        }
    }
}
