package com.gondolia.domain.pos;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PosSaleItemRepository extends JpaRepository<PosSaleItem, Long> {

    List<PosSaleItem> findBySaleIdOrderByIdAsc(Long saleId);

    List<PosSaleItem> findBySaleIdInOrderBySaleIdAscIdAsc(Collection<Long> saleIds);

    void deleteBySaleId(Long saleId);
}
