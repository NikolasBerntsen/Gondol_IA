package com.gondolia.domain.support;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AttachmentRepository extends JpaRepository<Attachment, Long> {

    Optional<Attachment> findByIdAndTenantId(Long id, Long tenantId);

    Optional<Attachment> findByStorageKey(String storageKey);
}
