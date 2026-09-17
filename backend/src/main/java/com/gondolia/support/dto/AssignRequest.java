package com.gondolia.support.dto;

/** Asignación de un ticket; {@code agentId} nulo = asignármelo a mí. */
public record AssignRequest(Long agentId) {
}
