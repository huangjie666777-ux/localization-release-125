package dev.localization;

/** Structural role of a named ICU argument inside a message template. */
public enum ParamKind {
    /** Plain substitution, e.g. <code>{name}</code>. */
    SIMPLE,
    /** <code>{n,plural,...}</code>, driven by a finite numeric value. */
    PLURAL,
    /** <code>{g,select,...}</code>, driven by a string value. */
    SELECT
}
