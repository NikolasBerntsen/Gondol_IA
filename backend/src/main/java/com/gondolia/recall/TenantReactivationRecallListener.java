package com.gondolia.recall;

import com.gondolia.common.events.TenantStatusChangedEvent;
import com.gondolia.domain.announcement.RecallMatch;
import com.gondolia.domain.tenant.TenantStatus;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Cuando un comercio vuelve a estar {@code ACTIVE} (se rehabilita o se reactiva una baja), rebarre sus lotes contra
 * todos los recalls publicados: mientras estuvo bloqueado, {@code matchAnnouncement} solo alcanzaba a los comercios
 * {@code ACTIVE}, así que pudo quedar mercadería alcanzada sin cuarentena ni aviso.
 * <p>
 * Corre después del commit del cambio de estado, en su propia transacción; un error solo se registra en el log para no
 * afectar la operación que rehabilitó al comercio. Es idempotente: las coincidencias que ya existían no repiten
 * efectos.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TenantReactivationRecallListener {

    private final RecallMatchingService recallMatchingService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTenantStatusChanged(TenantStatusChangedEvent event) {
        if (event.to() != TenantStatus.ACTIVE || event.from() == TenantStatus.ACTIVE || event.tenantId() == null) {
            return;
        }
        try {
            List<RecallMatch> matches = recallMatchingService.checkTenant(event.tenantId());
            log.info("Comercio {} habilitado ({} → ACTIVE): {} coincidencias de recall vigentes", event.tenantId(),
                    event.from(), matches.size());
        } catch (RuntimeException ex) {
            log.error("No se pudieron revisar los recalls del comercio {} al habilitarlo: {}", event.tenantId(),
                    ex.getMessage(), ex);
        }
    }
}
