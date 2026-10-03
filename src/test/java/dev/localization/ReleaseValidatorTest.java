package dev.localization;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReleaseValidatorTest {

    private final Limits limits = new Limits(64 * 1024, 100, 20, 10);
    private final ReleaseValidator validator = new ReleaseValidator(limits);

    @Test
    void acceptsValidBatchWithDifferentPluralBranches() {
        String json = """
        {
          "baseLanguage":"en",
          "fallbacks":{"zh-CN":"en"},
          "languages":{
            "en":{"items":"{n,plural,offset:1 =1{one} other{# items}}","g":"{who}"},
            "zh-CN":{"items":"{n,plural,other{# 个}}"}
          }
        }
        """;
        ValidatedBatch batch = validator.validate(json, limits);
        assertEquals("en", batch.baseLanguage());
        assertEquals(java.util.List.of("en"), batch.chains().get("zh-CN"));
    }

    @Test
    void rejectsDuplicateKeys() {
        String json = "{\"baseLanguage\":\"en\",\"languages\":{\"en\":{\"k\":\"a\",\"k\":\"b\"}}}";
        ValidationException ex = assertThrows(ValidationException.class,
                () -> validator.validate(json, limits));
        assertTrue(ex.getMessage().toLowerCase().contains("duplicate"));
    }

    @Test
    void rejectsNonStringValueIllegalLangAndExtraKeyAndParamMismatch() {
        String json = """
        {
          "baseLanguage":"english",
          "languages":{
            "english":{"k":"{x,plural,one{1} other{n}}"},
            "fr":{"k":"{y}","new":"v","num":123}
          }
        }
        """;
        ValidationException ex = assertThrows(ValidationException.class,
                () -> validator.validate(json, limits));
        assertTrue(ex.errors().size() >= 4, () -> ex.errors().toString());
    }
}
