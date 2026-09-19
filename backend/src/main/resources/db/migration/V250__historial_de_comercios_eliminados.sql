-- Módulo C (consola de dueños): el historial de un comercio eliminado definitivamente sigue sabiendo de qué comercio
-- era. Al borrar el comercio, `tenant_events.tenant_id` queda NULL (ON DELETE SET NULL); antes de borrarlo,
-- TenantAdminService copia su id acá. Así las métricas de crecimiento siguen atribuyendo el alta, las bajas y las
-- reactivaciones al mismo comercio (una baja por comercio, no una por evento) y reconstruyen si estaba activo en
-- cada mes. Sin clave foránea a propósito: el comercio ya no existe.
-- Los eventos de comercios eliminados antes de esta migración no se pueden atribuir y las métricas los ignoran.
ALTER TABLE tenant_events ADD COLUMN deleted_tenant_id BIGINT;
