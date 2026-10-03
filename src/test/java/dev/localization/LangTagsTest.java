package dev.localization;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LangTagsTest {
    @Test
    void acceptsCommonTags() {
        assertTrue(LangTags.isValid("en"));
        assertTrue(LangTags.isValid("zh-Hans-CN"));
        assertTrue(LangTags.isValid("pt-BR"));
        assertTrue(LangTags.isValid("zh-Hant"));
        assertTrue(LangTags.isValid("es-419"));
    }

    @Test
    void rejectsGarbage() {
        assertFalse(LangTags.isValid(""));
        assertFalse(LangTags.isValid("english"));
        assertFalse(LangTags.isValid("12"));
        assertFalse(LangTags.isValid("en-US-"));
        assertFalse(LangTags.isValid("-en"));
        assertFalse(LangTags.isValid("x-"));
    }
}
