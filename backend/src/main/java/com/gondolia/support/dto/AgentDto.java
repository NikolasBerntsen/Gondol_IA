package com.gondolia.support.dto;

/** Agente de soporte activo y si tiene alguna sesión abierta ({@code PresenceTracker}). */
public record AgentDto(Long id, String fullName, boolean online) {
}
