package com.gondolia.announcements;

import com.gondolia.announcements.dto.TenantAnnouncementDto;
import com.gondolia.announcements.dto.UnreadCountDto;
import com.gondolia.common.PageResponse;
import com.gondolia.security.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Bandeja de avisos del comercio (SPEC §6.7). La ven todos los roles de comercio, incluido el cajero.
 */
@Tag(name = "Avisos (comercio)")
@RestController
@RequestMapping("/api/tenant/announcements")
@PreAuthorize(Roles.TENANT_ANY)
@RequiredArgsConstructor
public class TenantAnnouncementController {

    private final TenantAnnouncementService tenantAnnouncementService;

    @Operation(summary = "Mis avisos",
            description = "Avisos publicados que le corresponden al comercio, con estado de lectura y si lo afecta.")
    @GetMapping
    public PageResponse<TenantAnnouncementDto> list(
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "no puede ser negativa") int page,
            @RequestParam(defaultValue = "20") @Min(value = 1, message = "tiene que ser al menos 1")
            @Max(value = 100, message = "no puede superar 100") int size) {
        return tenantAnnouncementService.list(page, size);
    }

    @Operation(summary = "Cantidad de avisos sin leer")
    @GetMapping("/unread-count")
    public UnreadCountDto unreadCount() {
        return new UnreadCountDto(tenantAnnouncementService.unreadCount());
    }

    @Operation(summary = "Marcar un aviso como leído")
    @PostMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markRead(@PathVariable Long id) {
        tenantAnnouncementService.markRead(id);
    }
}
