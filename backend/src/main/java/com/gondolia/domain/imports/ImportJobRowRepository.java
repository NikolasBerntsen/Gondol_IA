package com.gondolia.domain.imports;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ImportJobRowRepository extends JpaRepository<ImportJobRow, Long> {

    /** Cantidad de filas por estado (contadores del job). */
    interface StatusCount {
        ImportRowStatus getStatus();

        Long getTotal();
    }

    Optional<ImportJobRow> findByIdAndJobId(Long id, Long jobId);

    Optional<ImportJobRow> findByJobIdAndRowNumber(Long jobId, int rowNumber);

    Page<ImportJobRow> findByJobIdOrderByRowNumberAsc(Long jobId, Pageable pageable);

    Page<ImportJobRow> findByJobIdAndStatusOrderByRowNumberAsc(Long jobId, ImportRowStatus status, Pageable pageable);

    Page<ImportJobRow> findByJobIdAndStatusInOrderByRowNumberAsc(Long jobId, Collection<ImportRowStatus> statuses,
                                                                Pageable pageable);

    /** Bloque de filas a aplicar, en el orden del archivo (la aplicación procesa de a 200). */
    List<ImportJobRow> findByJobIdAndStatusInAndRowNumberGreaterThanOrderByRowNumberAsc(
            Long jobId, Collection<ImportRowStatus> statuses, int rowNumber, Pageable pageable);

    List<ImportJobRow> findByJobIdAndIdIn(Long jobId, Collection<Long> ids);

    long countByJobIdAndStatus(Long jobId, ImportRowStatus status);

    long countByJobIdAndStatusIn(Long jobId, Collection<ImportRowStatus> statuses);

    void deleteByJobId(Long jobId);

    @Query("""
            select r.status as status, count(r) as total from ImportJobRow r
            where r.jobId = :jobId
            group by r.status
            """)
    List<StatusCount> countByStatus(@Param("jobId") Long jobId);
}
