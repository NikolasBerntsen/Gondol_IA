package com.gondolia.domain.support;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SupportMessageRepository extends JpaRepository<SupportMessage, Long> {

    List<SupportMessage> findByTicketIdOrderByCreatedAtAscIdAsc(Long ticketId);

    Optional<SupportMessage> findFirstByTicketIdOrderByCreatedAtDescIdDesc(Long ticketId);

    boolean existsByAttachmentId(Long attachmentId);

    Optional<SupportMessage> findFirstByAttachmentId(Long attachmentId);
}
