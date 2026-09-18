package com.gondolia.announcements.dto;

/**
 * Alcance estimado de un recall: cuántos comercios y cuántos lotes con remanente coinciden. Nunca se expone
 * <b>qué</b> comercios (SPEC §3.4.3).
 */
public record RecallPreviewResponse(int affectedTenantsCount, int affectedLotsCount, int affectedUnits) {
}
