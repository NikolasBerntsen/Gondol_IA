package com.gondolia.domain.support;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SupportTicketRepository extends JpaRepository<SupportTicket, Long>,
        JpaSpecificationExecutor<SupportTicket> {

    Optional<SupportTicket> findByIdAndTenantId(Long id, Long tenantId);

    /** Tenant dueño del ticket (autorización de suscripciones STOMP). */
    @Query("select t.tenantId from SupportTicket t where t.id = :id")
    Optional<Long> findTenantIdById(@Param("id") Long id);

    List<SupportTicket> findByTenantIdOrderByLastMessageAtDesc(Long tenantId);

    List<SupportTicket> findByTenantIdAndStatusInOrderByLastMessageAtDesc(Long tenantId,
                                                                         Collection<TicketStatus> statuses);

    long countByStatus(TicketStatus status);

    long countByStatusIn(Collection<TicketStatus> statuses);

    long countByAssignedToIsNullAndStatusIn(Collection<TicketStatus> statuses);

    long countByAssignedToAndStatusIn(Long assignedTo, Collection<TicketStatus> statuses);
}
