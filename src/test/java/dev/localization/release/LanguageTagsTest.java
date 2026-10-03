package dev.localization.release;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LanguageTagsTest {
    @Test
    void acceptsWellFormed() {
        assertEquals("en", LanguageTags.requireValid("en"));
        assertEquals("zh-Hant-TW", LanguageTags.requireValid("zh-hant-tw"));
        assertEquals("pt-BR", LanguageTags.requireValid("PT-br"));
        assertEquals("sr-Latn-RS", LanguageTags.requireValid("sr-Latn-RS"));
        // grandfathered tag is accepted (ICU may canonicalize it to tlh)
        assertEquals("tlh", LanguageTags.requireValid("i-klingon"));
    }

    @Test
    void rejectsMalformed() {
        assertThrows(IllegalArgumentException.class, () -> LanguageTags.requireValid(""));
        assertThrows(IllegalArgumentException.class, () -> LanguageTags.requireValid("en_"));
        assertThrows(IllegalArgumentException.class, () -> LanguageTags.requireValid("12-en"));
        assertThrows(IllegalArgumentException.class, () -> LanguageTags.requireValid("en--US"));
    }
}
