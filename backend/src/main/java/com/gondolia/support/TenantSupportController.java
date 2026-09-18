package com.gondolia.support;

import com.gondolia.security.CurrentUser;
import com.gondolia.security.Roles;
import com.gondolia.support.dto.CreateTicketRequest;
import com.gondolia.support.dto.MessageDto;
import com.gondolia.support.dto.RateTicketRequest;
import com.gondolia.support.dto.TicketDetail;
import com.gondolia.support.dto.TicketSummary;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Soporte del lado del comercio (SPEC §6.8): cualquier rol de tenant puede abrir tickets y chatear.
 * <p>
 * Solo se ven los tickets del comercio del usuario autenticado: el {@code tenantId} sale de {@link CurrentUser} y
 * nunca del request, así que el id de un ticket de otro comercio responde 404. Soporte no es un módulo opcional
 * (SPEC §14.1): está siempre disponible.
 */
@Tag(name = "Soporte (comercio)")
@RestController
@RequestMapping("/api/tenant/support/tickets")
@PreAuthorize(Roles.TENANT_ANY)
@RequiredArgsConstructor
public class TenantSupportController {

    private final SupportService supportService;

    @Operation(summary = "Mis conversaciones con soporte",
            description = "Tickets del comercio, del más reciente al más viejo. `status` acepta un estado, `ACTIVE` "
                    + "(abierto, en curso o esperando respuesta) o `ALL` (por defecto).")
    @GetMapping
    public List<TicketSummary> list(@RequestParam(required = false) String status) {
        return supportService.listForTenant(CurrentUser.tenantId(), TicketStatusFilter.from(status));
    }

    @Operation(summary = "Abrir una consulta",
            description = "Crea el ticket con su primer mensaje y avisa al equipo de soporte.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TicketDetail create(@Valid @RequestBody CreateTicketRequest request) {
        return supportService.createTicket(CurrentUser.get(), request);
    }

    @Operation(summary = "Ver una consulta con toda la conversación")
    @GetMapping("/{id}")
    public TicketDetail detail(@PathVariable Long id) {
        return supportService.tenantDetail(CurrentUser.tenantId(), id);
    }

    @Operation(summary = "Enviar un mensaje",
            description = "Multipart: `body` (texto, opcional) y `file` (imagen PNG/JPG/WEBP/GIF de hasta 10 MB, "
                    + "opcional). Tiene que venir al menos uno de los dos.")
    @PostMapping(path = "/{id}/messages", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public MessageDto sendMessage(@PathVariable Long id,
                                  @RequestParam(required = false) String body,
                                  @RequestParam(required = false) MultipartFile file) {
        return supportService.tenantMessage(CurrentUser.get(), id, body, file);
    }

    @Operation(summary = "Marcar la conversación como leída")
    @PostMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markRead(@PathVariable Long id) {
        supportService.tenantRead(CurrentUser.tenantId(), id);
    }

    @Operation(summary = "Cerrar la conversación")
    @PostMapping("/{id}/close")
    public TicketDetail close(@PathVariable Long id) {
        return supportService.tenantClose(CurrentUser.get(), id);
    }

    @Operation(summary = "Calificar la atención",
            description = "De 1 a 5 estrellas con un comentario opcional; solo con la consulta resuelta o cerrada.")
    @PostMapping("/{id}/rate")
    public TicketDetail rate(@PathVariable Long id, @Valid @RequestBody RateTicketRequest request) {
        return supportService.tenantRate(CurrentUser.get(), id, request);
    }
}
