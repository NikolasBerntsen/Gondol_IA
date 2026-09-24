-- Módulo H (POS GondolIA): cierre de caja sin ventas. El mostrador deja cerrar un turno aunque no se haya cobrado
-- ninguna venta (el cajero abrió la caja por error o no vendió nada en el día), después de un aviso final que el cajero
-- confirma. El turno guarda que cerró sin ventas igual que guarda el arqueo del cierre: es lo que pasó en ese momento,
-- así que no cambia si después el administrador anula ventas de un turno ya cerrado.
ALTER TABLE pos_sessions ADD COLUMN closed_without_sales BOOLEAN NOT NULL DEFAULT FALSE;

-- Turnos que ya estaban cerrados: cerraron sin ventas si ninguna venta seguía vigente al momento del cierre (una
-- venta anulada después del cierre contaba como venta cuando se cerró la caja).
UPDATE pos_sessions s
   SET closed_without_sales = TRUE
 WHERE s.status = 'CLOSED'
   AND NOT EXISTS (SELECT 1
                     FROM pos_sales v
                    WHERE v.session_id = s.id
                      AND (v.voided_at IS NULL OR v.voided_at > s.closed_at));
