package dev.localization;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TemplateAnalyzerTest {

    private final TemplateAnalyzer analyzer = new TemplateAnalyzer(10);

    @Test
    void extractsNamedSelectPluralAndNestedParams() {
        String t = "{gender,select,female {{count,plural,offset:1 =1{one of her #} other{her #}}} other{{theirs}}}";
        TemplateInfo info = analyzer.analyze(t);
        assertEquals(ParamKind.SELECT, info.params().get("gender"));
        assertEquals(ParamKind.PLURAL, info.params().get("count"));
    }

    @Test
    void rendersQuotedHashAndOffsetAndExactBranches() {
        Renderer renderer = new Renderer(10);
        String t = "{n,plural,offset:1 =0{none} =1{one} other{# '#' items}}";
        assertEquals("none", renderer.render(t, "en", java.util.Map.of("n", 0)));
        assertEquals("one", renderer.render(t, "en", java.util.Map.of("n", 1L)));
        assertEquals("4 # items", renderer.render(t, "en", java.util.Map.of("n", 5L)));
    }

    @Test
    void rejectsNumberDateChoiceAndSelectordinal() {
        assertThrows(TemplateException.class, () -> analyzer.analyze("{count,number}"));
        assertThrows(TemplateException.class, () -> analyzer.analyze("{when,date}"));
        assertThrows(TemplateException.class, () -> analyzer.analyze("{x,choice,0#none|1#one}"));
        assertThrows(TemplateException.class,
                () -> analyzer.analyze("{n,selectordinal,one{#st} two{#nd} other{#th}}"));
    }

    @Test
    void rejectsPositionalAndMixedRolesAndDepth() {
        assertThrows(TemplateException.class, () -> analyzer.analyze("{0}"));
        assertThrows(TemplateException.class, () ->
                analyzer.analyze("{x,plural,one{{x,select,a{} other{}}} other{}}"));
        TemplateAnalyzer shallow = new TemplateAnalyzer(1);
        assertThrows(TemplateException.class, () -> shallow.analyze(
                "{g,select,a{{n,plural,one{x} other{y}}} other{z}}"));
    }

    @Test
    void emptyStringIsValidTemplateWithoutParams() {
        assertTrue(analyzer.analyze("").params().isEmpty());
    }
}
