package com.gondolia.announcements;

import com.gondolia.announcements.dto.AnnouncementDetailDto;
import com.gondolia.announcements.dto.AnnouncementListItem;
import com.gondolia.announcements.dto.CreateAnnouncementRequest;
import com.gondolia.announcements.dto.RecallPreviewRequest;
import com.gondolia.announcements.dto.RecallPreviewResponse;
import com.gondolia.common.PageResponse;
import com.gondolia.domain.announcement.AnnouncementKind;
import com.gondolia.domain.announcement.AnnouncementStatus;
import com.gondolia.security.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Avisos y recalls de la consola de dueños (SPEC §6.7). Solo datos agregados: nunca se expone qué comercios
 * coinciden con un recall (SPEC §3.4.3).
 */
@Tag(name = "Avisos y recalls (dueños)")
@RestController
@RequestMapping("/api/platform/announcements")
@PreAuthorize(Roles.OWNER)
@RequiredArgsConstructor
public class PlatformAnnouncementController {

    private final AnnouncementService announcementService;

    @Operation(summary = "Listar avisos", description = "Más nuevos primero. Se puede filtrar por tipo y estado.")
    @GetMapping
    public PageResponse<AnnouncementListItem> list(
            @RequestParam(required = false) AnnouncementKind kind,
            @RequestParam(required = false) AnnouncementStatus status,
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "no puede ser negativa") int page,
            @RequestParam(defaultValue = "20") @Min(value = 1, message = "tiene que ser al menos 1")
            @Max(value = 100, message = "no puede superar 100") int size) {
        return announcementService.list(kind, status, page, size);
    }

    @Operation(summary = "Vista previa del alcance de un recall",
            description = "Cuántos comercios y lotes con remanente coincidirían. No crea nada.")
    @PostMapping("/recall-preview")
    public RecallPreviewResponse preview(@Valid @RequestBody RecallPreviewRequest request) {
        return announcementService.preview(request);
    }

    @Operation(summary = "Detalle del aviso con estadísticas agregadas")
    @GetMapping("/{id}")
    public AnnouncementDetailDto detail(@PathVariable Long id) {
        return announcementService.detail(id);
    }

    @Operation(summary = "Publicar un aviso o un recall",
            description = "Se publica en el acto: notifica a todos los usuarios de comercios activos y, si es un "
                    + "recall, pone en cuarentena los lotes alcanzados.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AnnouncementDetailDto create(@Valid @RequestBody CreateAnnouncementRequest request) {
        return announcementService.create(request);
    }

    @Operation(summary = "Archivar un aviso", description = "Deja de mostrarse en los comercios.")
    @PostMapping("/{id}/archive")
    public AnnouncementDetailDto archive(@PathVariable Long id) {
        return announcementService.archive(id);
    }
}
