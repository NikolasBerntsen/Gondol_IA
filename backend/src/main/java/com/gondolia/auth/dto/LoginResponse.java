package com.gondolia.auth.dto;

import java.time.Instant;

public record LoginResponse(String token, Instant expiresAt, MeDto user) {
}
