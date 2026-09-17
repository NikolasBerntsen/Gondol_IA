package com.gondolia.security;

/**
 * API key recién generada. {@code rawKey} se muestra una sola vez; se persisten solo {@code prefix} y {@code hash}.
 */
public record GeneratedApiKey(String rawKey, String prefix, String hash) {

    @Override
    public String toString() {
        return "GeneratedApiKey[prefix=" + prefix + "]";
    }
}
