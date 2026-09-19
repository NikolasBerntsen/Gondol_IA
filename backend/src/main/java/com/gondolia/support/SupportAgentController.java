package com.gondolia.support;

import com.gondolia.common.PageResponse;
import com.gondolia.security.CurrentUser;
import com.gondolia.security.Roles;
import com.gondolia.support.SupportQueryRepository.AgentFilter;
import com.gondolia.support.dto.AgentDto;
import com.gondolia.support.dto.AssignRequest;
import com.gondolia.support.dto.MessageDto;
import com.gondolia.support.dto.PatchTicketRequest;
import com.gondolia.support.dto.StatusRequest;
import com.gondolia.support.dto.SupportStatsDto;
import com.gondolia.support.dto.TicketDetail;
import com.gondolia.support.dto.TicketSummary;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Bandeja del equipo de soporte (SPEC §6.8). Solo {@code SUPPORT_AGENT}.
 * <p>
 * Los agentes ven los tickets de todos los comercios, pero únicamente lo que el cliente envía en la conversación más
 * los datos administrativos del comercio (nombre, plan, rubro) y de la persona: nunca su stock, sus ventas ni sus
 * alertas (SPEC §3.4).
 */
@Tag(name = "Soporte (agentes)")
@RestController
@RequestMapping("/api/support")
@PreAuthorize(Roles.SUPPORT)
@RequiredArgsConstructor
public class SupportAgentController {

    private final SupportService supportService;

    @Operation(summary = "Bandeja de tickets",
            description = "Filtra por estado (`ALL`, `ACTIVE` o un estado puntual), por asignación "
                    + "(`all`, `me`, `unassigned`) y por texto libre en el asunto, el comercio o la persona.")
    @GetMapping("/tickets")
    public PageResponse<TicketSummary> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String assigned,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "no puede ser negativa") int page,
            @RequestParam(defaultValue = "20") @Min(value = 1, message = "tiene que ser al menos 1")
            @Max(value = 100, message = "no puede superar 100") int size) {
        AgentFilter filter = new AgentFilter(TicketStatusFilter.from(status), AssignedFilter.from(assigned), q);
        return supportService.listForAgent(filter, CurrentUser.id(), page, size);
    }

    @Operation(summary = "Ver un ticket con toda la conversación")
    @GetMapping("/tickets/{id}")
    public TicketDetail detail(@PathVariable Long id) {
        return supportService.agentDetail(id);
    }

    @Operation(summary = "Asignar el ticket", description = "Sin `agentId` se lo asigna quien lo pide.")
    @PostMapping("/tickets/{id}/assign")
    public TicketSummary assign(@PathVariable Long id, @RequestBody(required = false) AssignRequest request) {
        return supportService.assign(CurrentUser.get(), id, request == null ? null : request.agentId());
    }

    @Operation(summary = "Cambiar el estado del ticket")
    @PostMapping("/tickets/{id}/status")
    public TicketSummary changeStatus(@PathVariable Long id, @Valid @RequestBody StatusRequest request) {
        return supportService.changeStatus(CurrentUser.get(), id, request.status());
    }

    @Operation(summary = "Reclasificar el ticket", description = "Prioridad y/o categoría.")
    @PatchMapping("/tickets/{id}")
    public TicketSummary patch(@PathVariable Long id, @RequestBody PatchTicketRequest request) {
        return supportService.patch(id, request);
    }

    @Operation(summary = "Responder",
            description = "Multipart: `body` (texto, opcional) y `file` (imagen, opcional); al menos uno. "
                    + "La primera respuesta fija la primera respuesta del SLA y pasa el ticket a En curso.")
    @PostMapping(path = "/tickets/{id}/messages", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public MessageDto sendMessage(@PathVariable Long id,
                                  @RequestParam(required = false) String body,
                                  @RequestParam(required = false) MultipartFile file) {
        return supportService.agentMessage(CurrentUser.get(), id, body, file);
    }

    @Operation(summary = "Marcar la conversación como leída")
    @PostMapping("/tickets/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markRead(@PathVariable Long id) {
        supportService.agentRead(CurrentUser.get(), id);
    }

    @Operation(summary = "Tablero de la bandeja",
            description = "Pendientes por estado, sin asignar, míos, resueltos hoy y primera respuesta promedio "
                    + "de los últimos 30 días.")
    @GetMapping("/stats")
    public SupportStatsDto stats() {
        return supportService.stats(CurrentUser.id());
    }

    @Operation(summary = "Equipo de soporte", description = "Agentes activos y quién está conectado ahora.")
    @GetMapping("/agents")
    public List<AgentDto> agents() {
        return supportService.agents();
    }
}
