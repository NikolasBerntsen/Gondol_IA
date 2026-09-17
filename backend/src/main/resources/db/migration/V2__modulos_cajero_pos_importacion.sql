-- =====================================================================
-- GondolIA - V2: módulos por tenant, rol Cajero, POS GondolIA e importación masiva
-- (ver SPEC.md §14, §15 y §16)
-- =====================================================================

-- ---------------------------------------------------------------------
-- Rol Cajero
-- ---------------------------------------------------------------------
ALTER TABLE users DROP CONSTRAINT users_role_tenant_chk;
ALTER TABLE users ADD CONSTRAINT users_role_tenant_chk CHECK (
    (role IN ('PLATFORM_OWNER', 'SUPPORT_AGENT') AND tenant_id IS NULL) OR
    (role IN ('TENANT_BOSS', 'TENANT_ADMIN', 'TENANT_EMPLOYEE', 'TENANT_CASHIER') AND tenant_id IS NOT NULL)
);

-- ---------------------------------------------------------------------
-- Módulos habilitados por tenant (los gestionan los dueños de GondolIA)
-- Sin fila = deshabilitado.
-- ---------------------------------------------------------------------
CREATE TABLE tenant_modules (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    module      VARCHAR(30) NOT NULL,                        -- TenantModule
    enabled     BOOLEAN NOT NULL DEFAULT FALSE,
    updated_by  BIGINT REFERENCES users(id) ON DELETE SET NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_tenant_modules UNIQUE (tenant_id, module)
);
CREATE INDEX idx_tenant_modules_module ON tenant_modules(module) WHERE enabled;

-- ---------------------------------------------------------------------
-- POS GondolIA
-- ---------------------------------------------------------------------
CREATE TABLE pos_registers (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    branch_id   BIGINT NOT NULL REFERENCES branches(id) ON DELETE CASCADE,
    name        VARCHAR(60) NOT NULL,
    active      BOOLEAN NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_pos_registers_branch_name UNIQUE (branch_id, name)
);
CREATE INDEX idx_pos_registers_tenant ON pos_registers(tenant_id);

-- Turno de caja (apertura → ventas → cierre con arqueo)
CREATE TABLE pos_sessions (
    id               BIGSERIAL PRIMARY KEY,
    tenant_id        BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    branch_id        BIGINT NOT NULL REFERENCES branches(id) ON DELETE CASCADE,
    register_id      BIGINT NOT NULL REFERENCES pos_registers(id) ON DELETE CASCADE,
    status           VARCHAR(10) NOT NULL DEFAULT 'OPEN',     -- PosSessionStatus
    opened_by        BIGINT REFERENCES users(id) ON DELETE SET NULL,
    closed_by        BIGINT REFERENCES users(id) ON DELETE SET NULL,
    opening_cash     NUMERIC(14,2) NOT NULL DEFAULT 0,
    expected_cash    NUMERIC(14,2),
    counted_cash     NUMERIC(14,2),
    cash_difference  NUMERIC(14,2),
    sales_count      INT NOT NULL DEFAULT 0,
    sales_total      NUMERIC(14,2) NOT NULL DEFAULT 0,
    voided_count     INT NOT NULL DEFAULT 0,
    voided_total     NUMERIC(14,2) NOT NULL DEFAULT 0,
    closing_note     VARCHAR(300),
    opened_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    closed_at        TIMESTAMPTZ,
    CONSTRAINT pos_sessions_opening_chk CHECK (opening_cash >= 0)
);
CREATE UNIQUE INDEX uq_pos_sessions_open_register ON pos_sessions(register_id) WHERE status = 'OPEN';
CREATE UNIQUE INDEX uq_pos_sessions_open_user ON pos_sessions(opened_by) WHERE status = 'OPEN';
CREATE INDEX idx_pos_sessions_branch_time ON pos_sessions(branch_id, opened_at DESC);
CREATE INDEX idx_pos_sessions_tenant_time ON pos_sessions(tenant_id, opened_at DESC);

-- Numeración correlativa de tickets por sucursal (se bloquea la fila al emitir)
CREATE TABLE pos_branch_counters (
    branch_id    BIGINT PRIMARY KEY REFERENCES branches(id) ON DELETE CASCADE,
    last_number  BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE pos_sales (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    branch_id       BIGINT NOT NULL REFERENCES branches(id) ON DELETE CASCADE,
    register_id     BIGINT NOT NULL REFERENCES pos_registers(id) ON DELETE CASCADE,
    session_id      BIGINT NOT NULL REFERENCES pos_sessions(id) ON DELETE CASCADE,
    number          BIGINT NOT NULL,
    ticket_code     VARCHAR(30) NOT NULL,                      -- String.format("%04d-%08d", branchId, number)
    status          VARCHAR(10) NOT NULL DEFAULT 'COMPLETED',  -- PosSaleStatus
    subtotal        NUMERIC(14,2) NOT NULL,                    -- a precio de lista
    discount_total  NUMERIC(14,2) NOT NULL DEFAULT 0,          -- descuentos por lote
    total           NUMERIC(14,2) NOT NULL,
    items_count     INT NOT NULL,
    units           INT NOT NULL,
    paid_total      NUMERIC(14,2) NOT NULL,                    -- suma de pagos (efectivo recibido incluido)
    change_amount   NUMERIC(14,2) NOT NULL DEFAULT 0,          -- vuelto
    customer_name   VARCHAR(150),
    customer_doc    VARCHAR(20),
    cashier_id      BIGINT REFERENCES users(id) ON DELETE SET NULL,
    batch_ref       VARCHAR(40) NOT NULL,                      -- = stock_movements.batch_ref ("P-...")
    has_shortage    BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    voided_at       TIMESTAMPTZ,
    voided_by       BIGINT REFERENCES users(id) ON DELETE SET NULL,
    void_reason     VARCHAR(300),
    CONSTRAINT uq_pos_sales_branch_number UNIQUE (branch_id, number),
    CONSTRAINT pos_sales_amounts_chk CHECK (total >= 0 AND paid_total >= 0 AND change_amount >= 0)
);
CREATE INDEX idx_pos_sales_session ON pos_sales(session_id);
CREATE INDEX idx_pos_sales_branch_time ON pos_sales(branch_id, created_at DESC);
CREATE INDEX idx_pos_sales_tenant_time ON pos_sales(tenant_id, created_at DESC);
CREATE UNIQUE INDEX uq_pos_sales_batch_ref ON pos_sales(tenant_id, batch_ref);

CREATE TABLE pos_sale_items (
    id                 BIGSERIAL PRIMARY KEY,
    sale_id            BIGINT NOT NULL REFERENCES pos_sales(id) ON DELETE CASCADE,
    product_id         BIGINT REFERENCES products(id) ON DELETE SET NULL,
    barcode            VARCHAR(32),
    product_name       VARCHAR(200) NOT NULL,
    quantity           INT NOT NULL,
    list_unit_price    NUMERIC(12,2) NOT NULL,
    discount_amount    NUMERIC(14,2) NOT NULL DEFAULT 0,
    line_total         NUMERIC(14,2) NOT NULL,
    shortage_quantity  INT NOT NULL DEFAULT 0,
    lots               JSONB,                                  -- [{lotId,lotNumber,expiryDate,quantity,unitPrice,discountPct}]
    CONSTRAINT pos_sale_items_qty_chk CHECK (quantity > 0)
);
CREATE INDEX idx_pos_sale_items_sale ON pos_sale_items(sale_id);
CREATE INDEX idx_pos_sale_items_product ON pos_sale_items(product_id);

CREATE TABLE pos_payments (
    id          BIGSERIAL PRIMARY KEY,
    sale_id     BIGINT NOT NULL REFERENCES pos_sales(id) ON DELETE CASCADE,
    method      VARCHAR(20) NOT NULL,                          -- PaymentMethod
    amount      NUMERIC(14,2) NOT NULL,
    reference   VARCHAR(100),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pos_payments_amount_chk CHECK (amount > 0)
);
CREATE INDEX idx_pos_payments_sale ON pos_payments(sale_id);

CREATE TABLE pos_cash_movements (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    session_id  BIGINT NOT NULL REFERENCES pos_sessions(id) ON DELETE CASCADE,
    type        VARCHAR(10) NOT NULL,                          -- CashMovementType
    amount      NUMERIC(14,2) NOT NULL,
    reason      VARCHAR(300) NOT NULL,
    user_id     BIGINT REFERENCES users(id) ON DELETE SET NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pos_cash_movements_amount_chk CHECK (amount > 0)
);
CREATE INDEX idx_pos_cash_movements_session ON pos_cash_movements(session_id);

-- ---------------------------------------------------------------------
-- Importación masiva (Excel/CSV) de productos y stock inicial
-- ---------------------------------------------------------------------
CREATE TABLE import_jobs (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    type            VARCHAR(20) NOT NULL DEFAULT 'PRODUCTS',   -- ImportType
    status          VARCHAR(20) NOT NULL,                      -- ImportStatus
    file_name       VARCHAR(255) NOT NULL,
    file_format     VARCHAR(10) NOT NULL,                      -- ImportFileFormat
    sheet_name      VARCHAR(100),
    sheet_names     JSONB,
    headers         JSONB NOT NULL,
    column_mapping  JSONB,                                     -- {fieldKey: header}
    options         JSONB,
    total_rows      INT NOT NULL DEFAULT 0,
    valid_rows      INT NOT NULL DEFAULT 0,
    warning_rows    INT NOT NULL DEFAULT 0,
    error_rows      INT NOT NULL DEFAULT 0,
    skipped_rows    INT NOT NULL DEFAULT 0,
    processed_rows  INT NOT NULL DEFAULT 0,
    result          JSONB,
    error_message   TEXT,
    created_by      BIGINT REFERENCES users(id) ON DELETE SET NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    applied_at      TIMESTAMPTZ
);
CREATE INDEX idx_import_jobs_tenant_time ON import_jobs(tenant_id, created_at DESC);

CREATE TABLE import_job_rows (
    id          BIGSERIAL PRIMARY KEY,
    job_id      BIGINT NOT NULL REFERENCES import_jobs(id) ON DELETE CASCADE,
    row_number  INT NOT NULL,
    raw         JSONB NOT NULL,                                -- valor original por encabezado
    data        JSONB,                                         -- valores normalizados editables
    status      VARCHAR(10) NOT NULL DEFAULT 'PENDING',        -- ImportRowStatus
    action      VARCHAR(10),                                   -- ImportRowAction
    messages    JSONB,                                         -- [{field,level,message}]
    product_id  BIGINT REFERENCES products(id) ON DELETE SET NULL,
    lot_id      BIGINT REFERENCES lots(id) ON DELETE SET NULL,
    CONSTRAINT uq_import_job_rows UNIQUE (job_id, row_number)
);
CREATE INDEX idx_import_job_rows_job_status ON import_job_rows(job_id, status);
