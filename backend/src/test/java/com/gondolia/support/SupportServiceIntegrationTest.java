package com.gondolia.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gondolia.common.PageResponse;
import com.gondolia.common.error.BadRequestException;
import com.gondolia.common.error.ConflictException;
import com.gondolia.common.error.NotFoundException;
import com.gondolia.domain.support.MessageSenderType;
import com.gondolia.domain.support.TicketCategory;
import com.gondolia.domain.support.TicketChannel;
import com.gondolia.domain.support.TicketPriority;
import com.gondolia.domain.support.TicketStatus;
import com.gondolia.domain.user.Role;
import com.gondolia.it.PostgresIntegrationTest;
import com.gondolia.it.TestData;
import com.gondolia.security.AuthUser;
import com.gondolia.support.SupportQueryRepository.AgentFilter;
import com.gondolia.support.dto.CreateTicketRequest;
import com.gondolia.support.dto.MessageDto;
import com.gondolia.support.dto.RateTicketRequest;
import com.gondolia.support.dto.SupportStatsDto;
import com.gondolia.support.dto.TicketDetail;
import com.gondolia.support.dto.TicketSummary;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link SupportService} contra PostgreSQL: ciclo de vida del ticket, no leídos según quién mira, primera respuesta,
 * adjuntos, calificación, filtros de la bandeja y aislamiento entre comercios. Cada prueba se revierte.
 */
@Transactional
class SupportServiceIntegrationTest extends PostgresIntegrationTest {

    /** PNG mínimo válido para {@code AttachmentStorageService} (se valida por la firma del archivo). */
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13,
            'I', 'H', 'D', 'R'};

    @Autowired
    private SupportService supportService;
    @Autowired
    private SupportQueryRepository queries;
    @Autowired
    private JdbcTemplate jdbc;

    private TestData data;
    private long tenant;
    private long otherTenant;
    private AuthUser admin;
    private AuthUser employee;
    private AuthUser otherAdmin;
    private AuthUser agent;
    private AuthUser otherAgent;

    @BeforeEach
    void setUp() {
        data = new TestData(jdbc);
        tenant = data.tenant("Soporte", "ALMACEN", "BASICO", "ACTIVE", "FIFO");
        otherTenant = data.tenant("Otro Soporte", "KIOSCO", "FREEMIUM", "ACTIVE", "FIFO");
        long branch = data.branch(tenant, "Centro", true);
        admin = data.user(tenant, Role.TENANT_ADMIN, true);
        employee = data.user(tenant, Role.TENANT_EMPLOYEE, true, branch);
        otherAdmin = data.user(otherTenant, Role.TENANT_ADMIN, true);
        agent = data.user(null, Role.SUPPORT_AGENT, true);
        otherAgent = data.user(null, Role.SUPPORT_AGENT, true);
    }

    // ------------------------------------------------------------------ alta y conversación

    @Test
    void createsTicketWithFirstMessageAndLeavesItUnreadForSupport() {
        TicketDetail detail = createTicket(admin, "No puedo cargar un lote");

        assertThat(detail.status()).isEqualTo(TicketStatus.OPEN);
        assertThat(detail.tenantId()).isEqualTo(tenant);
        assertThat(detail.createdByRole()).isEqualTo(Role.TENANT_ADMIN);
        assertThat(detail.channel()).isEqualTo(TicketChannel.CHAT);
        assertThat(detail.category()).isEqualTo(TicketCategory.TECNICO);
        assertThat(detail.messages()).hasSize(1);
        assertThat(detail.messages().get(0).senderType()).isEqualTo(MessageSenderType.CUSTOMER);
        assertThat(detail.lastMessagePreview()).isEqualTo("Hola, no me anda la carga");
        // Quien escribe ya leyó lo suyo; el agente todavía no.
        assertThat(detail.unreadCount()).isZero();
        assertThat(agentView(detail.id()).unreadCount()).isEqualTo(1);
        assertThat(detail.firstResponseAt()).isNull();
    }

    @Test
    void rejectsTicketWithoutMessage() {
        assertThatThrownBy(() -> supportService.createTicket(admin,
                new CreateTicketRequest("Asunto", TicketCategory.USO, TicketPriority.MEDIA, TicketChannel.TICKET, "   ")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Escribí un mensaje");
    }

    @Test
    void agentReplySetsFirstResponseAndMovesTicketToInProgress() {
        long ticketId = createTicket(admin, "Consulta de facturación").id();

        supportService.agentMessage(agent, ticketId, "Hola, ya lo miro", null);

        TicketDetail detail = supportService.agentDetail(ticketId);
        assertThat(detail.status()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(detail.firstResponseAt()).isNotNull();
        assertThat(detail.assignedToId()).isEqualTo(agent.id());
        assertThat(detail.messages()).hasSize(2);
        // El agente leyó al escribir; el comercio tiene la respuesta sin leer.
        assertThat(detail.unreadCount()).isZero();
        assertThat(customerView(ticketId).unreadCount()).isEqualTo(1);

        supportService.tenantRead(tenant, ticketId);
        assertThat(customerView(ticketId).unreadCount()).isZero();
    }

    @Test
    void customerReplyOnWaitingCustomerReopensTheConversation() {
        long ticketId = createTicket(admin, "Pregunta").id();
        supportService.agentMessage(agent, ticketId, "¿Qué versión usás?", null);
        supportService.changeStatus(agent, ticketId, TicketStatus.WAITING_CUSTOMER);

        supportService.tenantMessage(employee, ticketId, "La última", null);

        assertThat(agentView(ticketId).status()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(agentView(ticketId).unreadCount()).isEqualTo(1);
    }

    @Test
    void storesImageAttachmentAndUsesItAsPreview() {
        long ticketId = createTicket(admin, "Error en pantalla").id();
        MockMultipartFile file = new MockMultipartFile("file", "captura.png", "image/png", PNG);

        MessageDto message = supportService.tenantMessage(employee, ticketId, "   ", file);

        assertThat(message.body()).isNull();
        assertThat(message.attachment()).isNotNull();
        assertThat(message.attachment().contentType()).isEqualTo("image/png");
        assertThat(message.attachment().url()).isEqualTo("/api/attachments/" + message.attachment().id());
        assertThat(agentView(ticketId).lastMessagePreview()).isEqualTo("Imagen adjunta");
    }

    @Test
    void rejectsMessageWithoutTextAndWithoutImage() {
        long ticketId = createTicket(admin, "Vacío").id();

        assertThatThrownBy(() -> supportService.tenantMessage(admin, ticketId, "  ", null))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> supportService.agentMessage(agent, ticketId, null, null))
                .isInstanceOf(BadRequestException.class);
    }

    // ------------------------------------------------------------------ cierre y calificación

    @Test
    void closingLeavesASystemNoteAndBlocksNewMessages() {
        long ticketId = createTicket(admin, "Ya lo resolví").id();

        TicketDetail closed = supportService.tenantClose(admin, ticketId);

        assertThat(closed.status()).isEqualTo(TicketStatus.CLOSED);
        assertThat(closed.resolvedAt()).isNotNull();
        assertThat(closed.messages()).last()
                .satisfies(message -> assertThat(message.senderType()).isEqualTo(MessageSenderType.SYSTEM));
        assertThatThrownBy(() -> supportService.tenantMessage(admin, ticketId, "Una más", null))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("cerrado");
        assertThatThrownBy(() -> supportService.agentMessage(agent, ticketId, "Una más", null))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void ratingNeedsTheTicketResolvedOrClosed() {
        long ticketId = createTicket(admin, "Calificar").id();
        RateTicketRequest request = new RateTicketRequest(5, "Muy rápidos");

        assertThatThrownBy(() -> supportService.tenantRate(admin, ticketId, request))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("calificar");

        supportService.changeStatus(agent, ticketId, TicketStatus.RESOLVED);
        TicketDetail rated = supportService.tenantRate(admin, ticketId, request);

        assertThat(rated.rating()).isEqualTo(5);
        assertThat(rated.ratingComment()).isEqualTo("Muy rápidos");
    }

    // ------------------------------------------------------------------ bandeja del agente

    @Test
    void assignsOnlyToActiveSupportAgents() {
        long ticketId = createTicket(admin, "Asignación").id();

        TicketSummary assigned = supportService.assign(agent, ticketId, otherAgent.id());
        assertThat(assigned.assignedToId()).isEqualTo(otherAgent.id());

        assertThatThrownBy(() -> supportService.assign(agent, ticketId, admin.id()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("agente");
    }

    @Test
    void filtersTheQueueByAssignmentStatusAndText() {
        long mine = createTicket(admin, "Impresora de tickets").id();
        long unassigned = createTicket(employee, "Vencimientos raros").id();
        supportService.assign(agent, mine, agent.id());

        PageResponse<TicketSummary> onlyMine =
                supportService.listForAgent(new AgentFilter(null, AssignedFilter.ME, null), agent.id(), 0, 20);
        assertThat(onlyMine.content()).extracting(TicketSummary::id).contains(mine).doesNotContain(unassigned);

        PageResponse<TicketSummary> free =
                supportService.listForAgent(new AgentFilter(null, AssignedFilter.UNASSIGNED, null), agent.id(), 0, 20);
        assertThat(free.content()).extracting(TicketSummary::id).contains(unassigned).doesNotContain(mine);

        PageResponse<TicketSummary> search = supportService.listForAgent(
                new AgentFilter(TicketStatusFilter.ACTIVE, AssignedFilter.ALL, "impresora"), agent.id(), 0, 20);
        assertThat(search.content()).extracting(TicketSummary::id).containsExactly(mine);

        supportService.changeStatus(agent, mine, TicketStatus.CLOSED);
        PageResponse<TicketSummary> active = supportService.listForAgent(
                new AgentFilter(TicketStatusFilter.ACTIVE, AssignedFilter.ALL, "impresora"), agent.id(), 0, 20);
        assertThat(active.content()).isEmpty();
    }

    @Test
    void statsCountPendingWorkAndFirstResponseTime() {
        long open = createTicket(admin, "Sin atender").id();
        long answered = createTicket(admin, "Atendido").id();
        supportService.assign(agent, answered, agent.id());
        supportService.agentMessage(agent, answered, "Ya lo veo", null);
        supportService.changeStatus(agent, answered, TicketStatus.RESOLVED);

        SupportStatsDto stats = supportService.stats(agent.id());

        assertThat(stats.open()).isGreaterThanOrEqualTo(1);
        assertThat(stats.unassigned()).isGreaterThanOrEqualTo(1);
        assertThat(stats.resolvedToday()).isGreaterThanOrEqualTo(1);
        assertThat(stats.avgFirstResponseMinutes()).isNotNull().isGreaterThanOrEqualTo(0.0);
        assertThat(queries.findSummary(open, SupportQueryRepository.Viewer.AGENT))
                .get().extracting(TicketSummary::assignedToId).isNull();
    }

    @Test
    void agentListIncludesPresenceForActiveAgentsOnly() {
        jdbc.update("update users set active = false where id = ?", otherAgent.id());

        assertThat(supportService.agents()).extracting(a -> a.id())
                .contains(agent.id())
                .doesNotContain(otherAgent.id());
    }

    // ------------------------------------------------------------------ aislamiento

    @Test
    void ticketsOfAnotherTenantAreNotFound() {
        long ticketId = createTicket(admin, "Privado").id();

        assertThatThrownBy(() -> supportService.tenantDetail(otherTenant, ticketId))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> supportService.tenantMessage(otherAdmin, ticketId, "Hola", null))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> supportService.tenantClose(otherAdmin, ticketId))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> supportService.tenantRead(otherTenant, ticketId))
                .isInstanceOf(NotFoundException.class);

        List<TicketSummary> theirs = supportService.listForTenant(otherTenant, TicketStatusFilter.ALL);
        assertThat(theirs).extracting(TicketSummary::id).doesNotContain(ticketId);
        assertThat(supportService.listForTenant(tenant, TicketStatusFilter.ALL))
                .extracting(TicketSummary::id).contains(ticketId);
    }

    @Test
    void previewCollapsesWhitespaceAndTrimsLongMessages() {
        String longBody = "x".repeat(SupportQueryRepository.PREVIEW_LENGTH + 50);

        assertThat(SupportQueryRepository.preview("hola\n  mundo", false)).isEqualTo("hola mundo");
        assertThat(SupportQueryRepository.preview(longBody, false))
                .hasSize(SupportQueryRepository.PREVIEW_LENGTH).endsWith("…");
        assertThat(SupportQueryRepository.preview("  ", true)).isEqualTo("Imagen adjunta");
        assertThat(SupportQueryRepository.preview(null, false)).isNull();
    }

    @Test
    void searchEscapesWildcards() {
        assertThat(SupportQueryRepository.normalizeSearch("  ")).isNull();
        assertThat(SupportQueryRepository.normalizeSearch("100%")).isEqualTo("%100\\%%");
        assertThat(new String("ñ".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8)).isEqualTo("ñ");
    }

    // ------------------------------------------------------------------ helpers

    private TicketDetail createTicket(AuthUser user, String subject) {
        return supportService.createTicket(user, new CreateTicketRequest(subject, TicketCategory.TECNICO,
                TicketPriority.ALTA, TicketChannel.CHAT, "Hola, no me anda la carga"));
    }

    private TicketSummary agentView(Long ticketId) {
        return queries.findSummary(ticketId, SupportQueryRepository.Viewer.AGENT).orElseThrow();
    }

    private TicketSummary customerView(Long ticketId) {
        return queries.findSummary(ticketId, SupportQueryRepository.Viewer.CUSTOMER).orElseThrow();
    }
}
