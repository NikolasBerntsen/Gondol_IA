package com.gondolia.support;

import com.gondolia.common.PageResponse;
import com.gondolia.common.error.BadRequestException;
import com.gondolia.common.error.ConflictException;
import com.gondolia.common.error.ErrorCodes;
import com.gondolia.common.error.NotFoundException;
import com.gondolia.domain.common.Timestamps;
import com.gondolia.domain.support.Attachment;
import com.gondolia.domain.support.AttachmentPurpose;
import com.gondolia.domain.support.MessageSenderType;
import com.gondolia.domain.support.SupportMessage;
import com.gondolia.domain.support.SupportMessageRepository;
import com.gondolia.domain.support.SupportTicket;
import com.gondolia.domain.support.SupportTicketRepository;
import com.gondolia.domain.support.TicketStatus;
import com.gondolia.domain.tenant.Tenant;
import com.gondolia.domain.tenant.TenantRepository;
import com.gondolia.domain.user.Role;
import com.gondolia.domain.user.User;
import com.gondolia.domain.user.UserRepository;
import com.gondolia.realtime.PresenceTracker;
import com.gondolia.security.AuthUser;
import com.gondolia.storage.AttachmentStorageService;
import com.gondolia.support.SupportQueryRepository.AgentFilter;
import com.gondolia.support.SupportQueryRepository.Viewer;
import com.gondolia.support.dto.AgentDto;
import com.gondolia.support.dto.AttachmentDto;
import com.gondolia.support.dto.CreateTicketRequest;
import com.gondolia.support.dto.MessageDto;
import com.gondolia.support.dto.PatchTicketRequest;
import com.gondolia.support.dto.RateTicketRequest;
import com.gondolia.support.dto.SupportStatsDto;
import com.gondolia.support.dto.TicketDetail;
import com.gondolia.support.dto.TicketSummary;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Tickets y chat en vivo de soporte (SPEC §6.8, §7).
 * <p>
 * Dos lados de la misma conversación: el comercio ({@code /api/tenant/support/**}, cualquier rol de tenant, solo sus
 * propios tickets) y el equipo de soporte ({@code /api/support/**}, {@code SUPPORT_AGENT}, todos los tickets). El
 * {@code tenantId} sale siempre del usuario autenticado y los tickets se buscan con
 * {@code findByIdAndTenantId} (404 si son de otro comercio).
 * <p>
 * Cada escritura deja la conversación consistente y avisa por los dos caminos: notificación in-app al otro lado y
 * push STOMP a {@code /topic/tickets/{id}} (y a {@code /topic/support/queue} para la bandeja).
 */
@Service
@RequiredArgsConstructor
public class SupportService {

    static final int MAX_BODY_LENGTH = 4000;
    static final String MSG_BODY_TOO_LONG = "El mensaje no puede superar los 4000 caracteres.";

    private final SupportTicketRepository ticketRepository;
    private final SupportMessageRepository messageRepository;
    private final SupportQueryRepository queries;
    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final AttachmentStorageService storageService;
    private final SupportNotifier notifier;
    private final PresenceTracker presenceTracker;

    // ==================================================================== comercio

    @Transactional(readOnly = true)
    public List<TicketSummary> listForTenant(Long tenantId, TicketStatusFilter status) {
        return queries.listForTenant(tenantId, status);
    }

    @Transactional(readOnly = true)
    public TicketDetail tenantDetail(Long tenantId, Long ticketId) {
        SupportTicket ticket = requireTenantTicket(tenantId, ticketId);
        return detail(ticket, Viewer.CUSTOMER);
    }

    /** Abre un ticket (o un chat) con su primer mensaje del cliente. */
    @Transactional
    public TicketDetail createTicket(AuthUser user, CreateTicketRequest request) {
        Long tenantId = user.tenantId();
        String body = requireBody(request.message());

        SupportTicket ticket = new SupportTicket();
        ticket.setTenantId(tenantId);
        ticket.setCreatedBy(user.id());
        ticket.setSubject(request.subject().strip());
        ticket.setCategory(request.categoryOrDefault());
        ticket.setPriority(request.priorityOrDefault());
        ticket.setChannel(request.channelOrDefault());
        ticket.setStatus(TicketStatus.OPEN);
        ticketRepository.saveAndFlush(ticket);

        MessageDto message = persistMessage(ticket, user.id(), MessageSenderType.CUSTOMER, body, null);
        ticket.setCustomerLastReadAt(message.createdAt());
        ticketRepository.flush();

        String tenantName = tenantName(tenantId);
        notifier.notifySupportOfNewTicket(ticket, tenantName, user.fullName());
        notifier.broadcastMessage(ticket.getId(), message);
        notifier.queueCreated(summary(ticket.getId(), Viewer.AGENT));
        return detail(ticket, Viewer.CUSTOMER);
    }

    /** Mensaje del comercio (texto, imagen o las dos cosas). */
    @Transactional
    public MessageDto tenantMessage(AuthUser user, Long ticketId, String rawBody, MultipartFile file) {
        SupportTicket ticket = requireTenantTicket(user.tenantId(), ticketId);
        requireOpen(ticket);
        String body = optionalBody(rawBody, file);

        Attachment attachment = storeAttachment(file, ticket.getTenantId(), user.id());
        MessageDto message = persistMessage(ticket, user.id(), MessageSenderType.CUSTOMER, body, attachment);
        // Escribir implica haber leído lo anterior.
        ticket.setCustomerLastReadAt(message.createdAt());
        if (ticket.getStatus() == TicketStatus.WAITING_CUSTOMER || ticket.getStatus() == TicketStatus.RESOLVED) {
            ticket.setStatus(TicketStatus.IN_PROGRESS);
            ticket.setResolvedAt(null);
        }
        ticketRepository.flush();

        notifier.notifySupportOfCustomerMessage(ticket, tenantName(ticket.getTenantId()), user.fullName(),
                preview(message));
        notifier.broadcastMessage(ticketId, message);
        publishUpdates(ticket.getId());
        return message;
    }

    /** Marca como leídos los mensajes del agente. */
    @Transactional
    public void tenantRead(Long tenantId, Long ticketId) {
        SupportTicket ticket = requireTenantTicket(tenantId, ticketId);
        Instant readAt = Timestamps.now();
        ticket.setCustomerLastReadAt(readAt);
        ticketRepository.flush();
        notifier.broadcastRead(ticketId, MessageSenderType.CUSTOMER, readAt);
        notifier.queueUpdated(summary(ticketId, Viewer.AGENT));
    }

    /** El comercio da por terminada la conversación. Idempotente. */
    @Transactional
    public TicketDetail tenantClose(AuthUser user, Long ticketId) {
        SupportTicket ticket = requireTenantTicket(user.tenantId(), ticketId);
        if (ticket.getStatus() == TicketStatus.CLOSED) {
            return detail(ticket, Viewer.CUSTOMER);
        }
        ticket.setStatus(TicketStatus.CLOSED);
        if (ticket.getResolvedAt() == null) {
            ticket.setResolvedAt(Timestamps.now());
        }
        MessageDto system = persistMessage(ticket, user.id(), MessageSenderType.SYSTEM,
                SupportTexts.closedByCustomer(user.fullName()), null);
        ticket.setCustomerLastReadAt(system.createdAt());
        ticketRepository.flush();

        notifier.broadcastMessage(ticketId, system);
        publishUpdates(ticketId);
        return detail(ticket, Viewer.CUSTOMER);
    }

    /** Calificación de la atención (1 a 5). Solo con el ticket resuelto o cerrado; se puede corregir. */
    @Transactional
    public TicketDetail tenantRate(AuthUser user, Long ticketId, RateTicketRequest request) {
        SupportTicket ticket = requireTenantTicket(user.tenantId(), ticketId);
        if (ticket.getStatus() != TicketStatus.RESOLVED && ticket.getStatus() != TicketStatus.CLOSED) {
            throw new ConflictException(SupportErrors.TICKET_NOT_RATEABLE, SupportErrors.MSG_NOT_RATEABLE);
        }
        boolean first = ticket.getRating() == null;
        ticket.setRating(request.rating());
        ticket.setRatingComment(request.comment() == null || request.comment().isBlank()
                ? null : request.comment().strip());
        if (first) {
            MessageDto system = persistMessage(ticket, user.id(), MessageSenderType.SYSTEM,
                    SupportTexts.rated(user.fullName(), request.rating()), null);
            ticket.setCustomerLastReadAt(system.createdAt());
            ticketRepository.flush();
            notifier.broadcastMessage(ticketId, system);
        } else {
            ticketRepository.flush();
        }
        publishUpdates(ticketId);
        return detail(ticket, Viewer.CUSTOMER);
    }

    // ==================================================================== soporte

    @Transactional(readOnly = true)
    public PageResponse<TicketSummary> listForAgent(AgentFilter filter, Long agentId, int page, int size) {
        return queries.listForAgent(filter, agentId, page, size);
    }

    @Transactional(readOnly = true)
    public TicketDetail agentDetail(Long ticketId) {
        return detail(requireTicket(ticketId), Viewer.AGENT);
    }

    @Transactional(readOnly = true)
    public SupportStatsDto stats(Long agentId) {
        return queries.stats(agentId);
    }

    /** Equipo de soporte activo, con presencia en vivo. */
    @Transactional(readOnly = true)
    public List<AgentDto> agents() {
        return userRepository.findByRoleAndActiveTrue(Role.SUPPORT_AGENT).stream()
                .map(agent -> new AgentDto(agent.getId(), agent.getFullName(), presenceTracker.isOnline(agent.getId())))
                .sorted(Comparator.comparing(AgentDto::fullName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /** Toma el ticket (sin {@code agentId} se lo asigna quien lo pide). */
    @Transactional
    public TicketSummary assign(AuthUser actor, Long ticketId, Long agentId) {
        SupportTicket ticket = requireTicket(ticketId);
        Long targetId = agentId == null ? actor.id() : agentId;
        User target = userRepository.findById(targetId)
                .filter(User::isActive)
                .filter(user -> user.getRole() == Role.SUPPORT_AGENT)
                .orElseThrow(() -> new BadRequestException(SupportErrors.AGENT_NOT_FOUND,
                        SupportErrors.MSG_AGENT_NOT_FOUND));
        if (Objects.equals(ticket.getAssignedTo(), target.getId())) {
            return summary(ticketId, Viewer.AGENT);
        }
        ticket.setAssignedTo(target.getId());
        MessageDto system = persistMessage(ticket, actor.id(), MessageSenderType.SYSTEM,
                SupportTexts.assigned(target.getFullName()), null);
        ticket.setAgentLastReadAt(system.createdAt());
        ticketRepository.flush();

        if (!Objects.equals(target.getId(), actor.id())) {
            notifier.notifyAgentAssigned(target.getId(), ticket, tenantName(ticket.getTenantId()), actor.fullName());
        }
        notifier.broadcastMessage(ticketId, system);
        return publishUpdates(ticketId);
    }

    /** Cambia el estado del ticket y se lo avisa al comercio. */
    @Transactional
    public TicketSummary changeStatus(AuthUser actor, Long ticketId, TicketStatus status) {
        SupportTicket ticket = requireTicket(ticketId);
        if (ticket.getStatus() == status) {
            return summary(ticketId, Viewer.AGENT);
        }
        ticket.setStatus(status);
        if (status == TicketStatus.RESOLVED || status == TicketStatus.CLOSED) {
            if (ticket.getResolvedAt() == null) {
                ticket.setResolvedAt(Timestamps.now());
            }
        } else {
            ticket.setResolvedAt(null);
        }
        MessageDto system = persistMessage(ticket, actor.id(), MessageSenderType.SYSTEM,
                SupportTexts.statusChanged(actor.fullName(), status), null);
        ticket.setAgentLastReadAt(system.createdAt());
        ticketRepository.flush();

        notifier.notifyCustomerOfStatus(ticket, status, actor.fullName());
        notifier.broadcastMessage(ticketId, system);
        return publishUpdates(ticketId);
    }

    /** Reclasifica prioridad y/o categoría. */
    @Transactional
    public TicketSummary patch(Long ticketId, PatchTicketRequest request) {
        if (request == null || request.isEmpty()) {
            throw new BadRequestException(ErrorCodes.VALIDATION_ERROR, SupportErrors.MSG_NOTHING_TO_UPDATE);
        }
        SupportTicket ticket = requireTicket(ticketId);
        if (request.priority() != null) {
            ticket.setPriority(request.priority());
        }
        if (request.category() != null) {
            ticket.setCategory(request.category());
        }
        ticketRepository.flush();
        return publishUpdates(ticketId);
    }

    /** Respuesta del agente. La primera fija {@code first_response_at} y pasa el ticket a "En curso" (SPEC §6.8). */
    @Transactional
    public MessageDto agentMessage(AuthUser agent, Long ticketId, String rawBody, MultipartFile file) {
        SupportTicket ticket = requireTicket(ticketId);
        requireOpen(ticket);
        String body = optionalBody(rawBody, file);

        Attachment attachment = storeAttachment(file, ticket.getTenantId(), agent.id());
        MessageDto message = persistMessage(ticket, agent.id(), MessageSenderType.AGENT, body, attachment);
        ticket.setAgentLastReadAt(message.createdAt());
        if (ticket.getAssignedTo() == null) {
            ticket.setAssignedTo(agent.id());
        }
        if (ticket.getFirstResponseAt() == null) {
            ticket.setFirstResponseAt(message.createdAt());
        }
        if (ticket.getStatus() == TicketStatus.OPEN || ticket.getStatus() == TicketStatus.RESOLVED) {
            ticket.setStatus(TicketStatus.IN_PROGRESS);
            ticket.setResolvedAt(null);
        }
        ticketRepository.flush();

        notifier.notifyCustomerOfAgentMessage(ticket, agent.fullName(), preview(message));
        notifier.broadcastMessage(ticketId, message);
        publishUpdates(ticketId);
        return message;
    }

    /** El agente marca como leídos los mensajes del comercio. */
    @Transactional
    public void agentRead(Long ticketId) {
        SupportTicket ticket = requireTicket(ticketId);
        Instant readAt = Timestamps.now();
        ticket.setAgentLastReadAt(readAt);
        ticketRepository.flush();
        notifier.broadcastRead(ticketId, MessageSenderType.AGENT, readAt);
        notifier.queueUpdated(summary(ticketId, Viewer.AGENT));
    }

    // ==================================================================== chat en vivo

    /**
     * "Está escribiendo…". Reenvía el aviso a la conversación; el emisor lo ignora por {@code userId}. No toca la base:
     * la autorización del destino ya la hizo el interceptor STOMP (SPEC §7).
     */
    public void typing(AuthUser user, Long ticketId, boolean typing) {
        MessageSenderType senderType = user.role() == Role.SUPPORT_AGENT
                ? MessageSenderType.AGENT : MessageSenderType.CUSTOMER;
        notifier.broadcastTyping(ticketId, user.id(), user.fullName(), senderType, typing);
    }

    // ==================================================================== internos

    /** Ticket del comercio del usuario; 404 si es de otro comercio (SPEC §3.4). */
    private SupportTicket requireTenantTicket(Long tenantId, Long ticketId) {
        return ticketRepository.findByIdAndTenantId(ticketId, tenantId)
                .orElseThrow(() -> new NotFoundException(SupportErrors.MSG_TICKET_NOT_FOUND));
    }

    private SupportTicket requireTicket(Long ticketId) {
        return ticketRepository.findById(ticketId)
                .orElseThrow(() -> new NotFoundException(SupportErrors.MSG_TICKET_NOT_FOUND));
    }

    private static void requireOpen(SupportTicket ticket) {
        if (ticket.getStatus() == TicketStatus.CLOSED) {
            throw new ConflictException(SupportErrors.TICKET_CLOSED, SupportErrors.MSG_TICKET_CLOSED);
        }
    }

    private Attachment storeAttachment(MultipartFile file, Long tenantId, Long uploadedBy) {
        return file == null || file.isEmpty()
                ? null : storageService.store(file, AttachmentPurpose.SUPPORT, tenantId, uploadedBy);
    }

    /** Guarda el mensaje, actualiza {@code last_message_at} y devuelve el DTO ya armado. */
    private MessageDto persistMessage(SupportTicket ticket, Long senderId, MessageSenderType senderType, String body,
                                      Attachment attachment) {
        SupportMessage message = new SupportMessage();
        message.setTicketId(ticket.getId());
        message.setSenderId(senderId);
        message.setSenderType(senderType);
        message.setBody(body);
        message.setAttachmentId(attachment == null ? null : attachment.getId());
        messageRepository.saveAndFlush(message);
        ticket.setLastMessageAt(message.getCreatedAt());
        return new MessageDto(message.getId(), ticket.getId(), senderId, senderName(senderId), senderType, body,
                AttachmentDto.of(attachment), message.getCreatedAt());
    }

    private String senderName(Long senderId) {
        return senderId == null ? null : userRepository.findById(senderId).map(User::getFullName).orElse(null);
    }

    private String tenantName(Long tenantId) {
        return tenantRepository.findById(tenantId).map(Tenant::getName).orElse("Comercio");
    }

    private static String preview(MessageDto message) {
        String preview = SupportQueryRepository.preview(message.body(), message.attachment() != null);
        return preview == null ? SupportQueryRepository.PREVIEW_IMAGE : preview;
    }

    private TicketSummary summary(Long ticketId, Viewer viewer) {
        return queries.findSummary(ticketId, viewer)
                .orElseThrow(() -> new NotFoundException(SupportErrors.MSG_TICKET_NOT_FOUND));
    }

    private TicketDetail detail(SupportTicket ticket, Viewer viewer) {
        return TicketDetail.of(summary(ticket.getId(), viewer), queries.messages(ticket.getId()),
                ticket.getRatingComment(), ticket.getFirstResponseAt());
    }

    /** Publica el ticket actualizado en la conversación y en la bandeja; devuelve la vista del agente. */
    private TicketSummary publishUpdates(Long ticketId) {
        TicketSummary agentView = summary(ticketId, Viewer.AGENT);
        notifier.broadcastTicket(agentView);
        notifier.queueUpdated(agentView);
        return agentView;
    }

    private static String requireBody(String raw) {
        String body = raw == null ? "" : raw.strip();
        if (body.isEmpty()) {
            throw new BadRequestException(SupportErrors.EMPTY_MESSAGE, SupportErrors.MSG_EMPTY_MESSAGE);
        }
        return checkLength(body);
    }

    /** Texto opcional: puede venir vacío si hay imagen, pero no los dos vacíos. */
    private static String optionalBody(String raw, MultipartFile file) {
        String body = raw == null ? "" : raw.strip();
        boolean hasFile = file != null && !file.isEmpty();
        if (body.isEmpty() && !hasFile) {
            throw new BadRequestException(SupportErrors.EMPTY_MESSAGE, SupportErrors.MSG_EMPTY_MESSAGE);
        }
        return body.isEmpty() ? null : checkLength(body);
    }

    private static String checkLength(String body) {
        if (body.length() > MAX_BODY_LENGTH) {
            throw new BadRequestException(ErrorCodes.VALIDATION_ERROR, MSG_BODY_TOO_LONG);
        }
        return body;
    }
}
