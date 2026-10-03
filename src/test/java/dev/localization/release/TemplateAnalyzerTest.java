package dev.localization.release;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TemplateAnalyzerTest {

    private TemplateAnalyzer.Analysis a(String t) {
        return TemplateAnalyzer.analyze(t);
    }

    @Test
    void extractsNamedArgsAndLiterals() {
        TemplateAnalyzer.Analysis a = a("Hello {name}, you have ''{count}'' raw and a brace '{}'");
        assertEquals(2, a.argumentNames().size());
        assertEquals(TemplateAnalyzer.Kind.SIMPLE, a.kindOf("name"));
        assertEquals(TemplateAnalyzer.Kind.SIMPLE, a.kindOf("count"));
    }

    @Test
    void extractsSelectPluralNestedAndHash() {
        String t = "{gender, select, "
                + "female {{count, plural, offset:1 =0 {no photos} =1 {one photo for #} other {# photos}}}"
                + " other {{count, plural, =1 {one photo} other {# photos}}}}";
        TemplateAnalyzer.Analysis a = a(t);
        assertEquals(TemplateAnalyzer.Kind.SELECT, a.kindOf("gender"));
        assertEquals(TemplateAnalyzer.Kind.PLURAL, a.kindOf("count"));
    }

    @Test
    void exactValueAndOffsetBranchesAccepted() {
        TemplateAnalyzer.Analysis a = a("{n, plural, offset:2 =2 {two} =3 {three} other {many #}}");
        assertEquals(TemplateAnalyzer.Kind.PLURAL, a.kindOf("n"));
    }

    @Test
    void rejectsOtherFormatTypes() {
        assertThrows(IllegalArgumentException.class, () -> a("{when, date, short}"));
        assertThrows(IllegalArgumentException.class, () -> a("{n, number, integer}"));
        assertThrows(IllegalArgumentException.class, () -> a("{n, choice, 0#none|1#one}"));
    }

    @Test
    void rejectsMixedPluralSelectSameName() {
        assertThrows(IllegalArgumentException.class, () ->
                a("{x, plural, other {{x, select, a {A} other {B}}}}"));
    }

    @Test
    void rejectsNumberedArguments() {
        assertThrows(IllegalArgumentException.class, () -> a("Hello {0}"));
    }

    @Test
    void hashOutsidePluralIsLiteral() {
        // Per ICU semantics '#' is literal text outside plural messages.
        assertTrue(a("cost is #").argumentNames().isEmpty());
    }

    @Test
    void emptyStringIsValidTemplate() {
        assertTrue(a("").argumentNames().isEmpty());
    }

    @Test
    void selectordinalAllowedAsPlural() {
        assertEquals(TemplateAnalyzer.Kind.PLURAL,
                a("{n, selectordinal, one {#st} two {#nd} few {#rd} other {#th}}").kindOf("n"));
    }

    @Test
    void invalidQuotesRejected() {
        assertThrows(IllegalArgumentException.class, () -> a("unterminated {name, select, a {x}"));
    }
}
