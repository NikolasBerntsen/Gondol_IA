package com.gondolia.modules;

/**
 * Aviso por {@code /user/queue/session} cuando cambian los módulos habilitados del comercio (SPEC §14.1):
 * {@code {"type":"MODULES_CHANGED"}}. El frontend recarga {@code /api/auth/me} y muestra un toast.
 */
public record ModulesChangedMessage(String type) {

    public static final String TYPE = "MODULES_CHANGED";

    public ModulesChangedMessage() {
        this(TYPE);
    }
}
