package dev.localization;

/** Hard limits applied to every submit request. */
public record Limits(long maxBodyBytes, int maxKeys, int maxLanguageEntries, int maxTemplateDepth) {
    public static Limits fromEnv() {
        return new Limits(
                parseLong("LOC_MAX_BODY_BYTES", 1_048_576L),
                parseInt("LOC_MAX_KEYS", 2_000),
                parseInt("LOC_MAX_LANGUAGES", 100),
                parseInt("LOC_MAX_TEMPLATE_DEPTH", 10));
    }

    private static int parseInt(String name, int def) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            v = System.getProperty(name.toLowerCase().replace('_', '.'));
        }
        try {
            return v == null || v.isBlank() ? def : Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static long parseLong(String name, long def) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            v = System.getProperty(name.toLowerCase().replace('_', '.'));
        }
        try {
            return v == null || v.isBlank() ? def : Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
