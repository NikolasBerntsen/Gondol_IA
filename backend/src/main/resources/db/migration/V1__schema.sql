-- =====================================================================
-- GondolIA - Esquema inicial (contrato central, ver SPEC.md §4)
-- Multi-tenant por discriminador: toda tabla de datos de negocio lleva tenant_id.
-- Convenciones: ids BIGSERIAL, fechas-hora TIMESTAMPTZ (UTC), fechas DATE,
-- dinero NUMERIC(12,2) / NUMERIC(14,2), cantidades INT, enums como VARCHAR.
-- =====================================================================

-- ---------------------------------------------------------------------
-- Plataforma
-- ---------------------------------------------------------------------
CREATE TABLE tenants (
    id                 BIGSERIAL PRIMARY KEY,
    name               VARCHAR(150) NOT NULL,
    legal_name         VARCHAR(200),
    tax_id             VARCHAR(20),
    business_type      VARCHAR(30)  NOT NULL,              -- BusinessType
    plan               VARCHAR(20)  NOT NULL,              -- TenantPlan
    status             VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE', -- TenantStatus
    contact_name       VARCHAR(150),
    contact_email      VARCHAR(150),
    contact_phone      VARCHAR(50),
    address            VARCHAR(200),
    city               VARCHAR(100),
    province           VARCHAR(100),
    notes              TEXT,
    status_reason      VARCHAR(300),
    status_changed_at  TIMESTAMPTZ,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_tenants_status ON tenants(status);

-- Historial de eventos de tenant (métricas de crecimiento/churn/conversión).
-- tenant_id queda en NULL si el tenant se elimina definitivamente (se conserva la estadística).
CREATE TABLE tenant_events (
    id             BIGSERIAL PRIMARY KEY,
    tenant_id      BIGINT REFERENCES tenants(id) ON DELETE SET NULL,
    type           VARCHAR(30) NOT NULL,                   -- TenantEventType
    from_value     VARCHAR(50),
    to_value       VARCHAR(50),
    reason         VARCHAR(300),
    actor_user_id  BIGINT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_tenant_events_type_time ON tenant_events(type, created_at);

CREATE TABLE users (
    id                    BIGSERIAL PRIMARY KEY,
    tenant_id             BIGINT REFERENCES tenants(id) ON DELETE CASCADE,
    email                 VARCHAR(150) NOT NULL UNIQUE,     -- siempre en minúsculas
    password_hash         VARCHAR(100) NOT NULL,            -- BCrypt
    full_name             VARCHAR(150) NOT NULL,
    role                  VARCHAR(30)  NOT NULL,            -- Role
    active                BOOLEAN      NOT NULL DEFAULT TRUE,
    token_version         INT          NOT NULL DEFAULT 0,  -- se incrementa para invalidar JWTs
    must_change_password  BOOLEAN      NOT NULL DEFAULT FALSE,
    last_login_at         TIMESTAMPTZ,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT users_role_tenant_chk CHECK (
        (role IN ('PLATFORM_OWNER', 'SUPPORT_AGENT') AND tenant_id IS NULL) OR
        (role IN ('TENANT_BOSS', 'TENANT_ADMIN', 'TENANT_EMPLOYEE') AND tenant_id IS NOT NULL)
    )
);
CREATE INDEX idx_users_tenant ON users(tenant_id);
CREATE INDEX idx_users_role ON users(role);

CREATE TABLE tenant_settings (
    tenant_id               BIGINT PRIMARY KEY REFERENCES tenants(id) ON DELETE CASCADE,
    currency                VARCHAR(3)   NOT NULL DEFAULT 'ARS',
    stock_rotation          VARCHAR(10)  NOT NULL DEFAULT 'FIFO', -- StockRotation: FIFO (entró antes, sale antes) | FEFO
    expiry_warning_days     INT          NOT NULL DEFAULT 15,
    expiry_critical_days    INT          NOT NULL DEFAULT 5,
    default_lead_time_days  INT          NOT NULL DEFAULT 3,
    target_coverage_days    INT          NOT NULL DEFAULT 14,
    service_level           NUMERIC(4,3) NOT NULL DEFAULT 0.950,
    max_discount_pct        INT          NOT NULL DEFAULT 40,
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Sucursales (supermercados/locales) de un tenant. El stock, los lotes, las ventas,
-- las alertas y la IA son por sucursal; el catálogo de productos es compartido por el tenant.
CREATE TABLE branches (
    id                      BIGSERIAL PRIMARY KEY,
    tenant_id               BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name                    VARCHAR(100) NOT NULL,
    code                    VARCHAR(20),
    address                 VARCHAR(200),
    city                    VARCHAR(100),
    province                VARCHAR(100),
    phone                   VARCHAR(50),
    active                  BOOLEAN NOT NULL DEFAULT TRUE,
    pos_api_key_hash        VARCHAR(64),
    pos_api_key_prefix      VARCHAR(16),
    pos_api_key_created_at  TIMESTAMPTZ,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_branches_tenant_name UNIQUE (tenant_id, name)
);
CREATE INDEX idx_branches_tenant ON branches(tenant_id);
CREATE UNIQUE INDEX uq_branches_pos_key ON branches(pos_api_key_hash) WHERE pos_api_key_hash IS NOT NULL;

-- Sucursales asignadas a un usuario. Solo aplica a TENANT_EMPLOYEE;
-- TENANT_ADMIN y TENANT_BOSS acceden a todas las sucursales del tenant.
CREATE TABLE user_branches (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    branch_id   BIGINT NOT NULL REFERENCES branches(id) ON DELETE CASCADE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_user_branches UNIQUE (user_id, branch_id)
);
CREATE INDEX idx_user_branches_branch ON user_branches(branch_id);

-- ---------------------------------------------------------------------
-- Inventario (por tenant)
-- ---------------------------------------------------------------------
CREATE TABLE categories (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name        VARCHAR(100) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_categories_tenant_name UNIQUE (tenant_id, name)
);

CREATE TABLE suppliers (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name            VARCHAR(150) NOT NULL,
    contact_name    VARCHAR(150),
    phone           VARCHAR(50),
    email           VARCHAR(150),
    lead_time_days  INT,
    notes           TEXT,
    active          BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_suppliers_tenant ON suppliers(tenant_id);

CREATE TABLE products (
    id           BIGSERIAL PRIMARY KEY,
    tenant_id    BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    barcode      VARCHAR(32),
    name         VARCHAR(200) NOT NULL,
    brand        VARCHAR(100),
    description  TEXT,
    category_id  BIGINT REFERENCES categories(id) ON DELETE SET NULL,
    supplier_id  BIGINT REFERENCES suppliers(id) ON DELETE SET NULL,
    unit         VARCHAR(20)   NOT NULL DEFAULT 'UNIDAD',   -- ProductUnit
    cost_price   NUMERIC(12,2) NOT NULL DEFAULT 0,
    sale_price   NUMERIC(12,2) NOT NULL DEFAULT 0,
    min_stock    INT           NOT NULL DEFAULT 0,
    perishable   BOOLEAN       NOT NULL DEFAULT TRUE,
    active       BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT products_prices_chk CHECK (cost_price >= 0 AND sale_price >= 0 AND min_stock >= 0)
);
CREATE UNIQUE INDEX uq_products_tenant_barcode ON products(tenant_id, barcode) WHERE barcode IS NOT NULL;
CREATE INDEX idx_products_tenant ON products(tenant_id);
CREATE INDEX idx_products_barcode ON products(barcode);

-- Cada ingreso de mercadería crea su propio lote (aunque repita número de lote o vencimiento),
-- así un producto puede tener varios lotes/fechas y la rotación FIFO/FEFO es exacta.
CREATE TABLE lots (
    id                     BIGSERIAL PRIMARY KEY,
    tenant_id              BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    branch_id              BIGINT NOT NULL REFERENCES branches(id) ON DELETE CASCADE,
    product_id             BIGINT NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    origin_lot_id          BIGINT REFERENCES lots(id) ON DELETE SET NULL, -- lote de origen si vino por transferencia
    supplier_id            BIGINT REFERENCES suppliers(id) ON DELETE SET NULL,
    lot_number             VARCHAR(60),
    lot_number_normalized  VARCHAR(60),                    -- LotNumbers.normalize(lot_number)
    expiry_date            DATE,
    initial_quantity       INT           NOT NULL,
    quantity               INT           NOT NULL,          -- remanente
    cost_price             NUMERIC(12,2),
    received_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),    -- momento de ingreso (orden FIFO)
    status                 VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE', -- LotStatus
    source                 VARCHAR(20)   NOT NULL DEFAULT 'MANUAL', -- MovementSource
    discount_pct           NUMERIC(5,2),                    -- descuento activo (recomendación aceptada)
    discount_started_at    TIMESTAMPTZ,
    created_by             BIGINT REFERENCES users(id) ON DELETE SET NULL,
    created_at             TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT lots_qty_chk CHECK (quantity >= 0 AND initial_quantity >= 0)
);
CREATE INDEX idx_lots_tenant_product ON lots(tenant_id, product_id);
CREATE INDEX idx_lots_branch_product_active ON lots(branch_id, product_id, received_at) WHERE status = 'ACTIVE';
CREATE INDEX idx_lots_tenant_expiry_active ON lots(tenant_id, expiry_date) WHERE status = 'ACTIVE';
CREATE INDEX idx_lots_lot_norm ON lots(lot_number_normalized);

CREATE TABLE stock_movements (
    id            BIGSERIAL PRIMARY KEY,
    tenant_id     BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    branch_id     BIGINT NOT NULL REFERENCES branches(id) ON DELETE CASCADE,
    product_id    BIGINT NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    lot_id        BIGINT REFERENCES lots(id) ON DELETE SET NULL,
    type          VARCHAR(30) NOT NULL,                     -- MovementType
    quantity      INT NOT NULL,                             -- siempre > 0; el signo lo da el tipo
    unit_price    NUMERIC(12,2),
    discount_pct  NUMERIC(5,2),
    total_amount  NUMERIC(14,2),
    source        VARCHAR(20) NOT NULL DEFAULT 'MANUAL',    -- MovementSource
    batch_ref     VARCHAR(40),                              -- agrupa filas de una misma operación
    reason        VARCHAR(300),
    user_id       BIGINT REFERENCES users(id) ON DELETE SET NULL,
    occurred_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT stock_movements_qty_chk CHECK (quantity > 0)
);
CREATE INDEX idx_mov_tenant_time ON stock_movements(tenant_id, occurred_at);
CREATE INDEX idx_mov_branch_time ON stock_movements(branch_id, occurred_at);
CREATE INDEX idx_mov_branch_product_time ON stock_movements(branch_id, product_id, occurred_at);
CREATE INDEX idx_mov_tenant_type_time ON stock_movements(tenant_id, type, occurred_at);
CREATE INDEX idx_mov_batch_ref ON stock_movements(tenant_id, batch_ref);

-- ---------------------------------------------------------------------
-- Alertas e IA (por tenant)
-- ---------------------------------------------------------------------
CREATE TABLE alerts (
    id               BIGSERIAL PRIMARY KEY,
    tenant_id        BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    branch_id        BIGINT REFERENCES branches(id) ON DELETE CASCADE, -- NULL = alerta de todo el tenant
    type             VARCHAR(30) NOT NULL,                  -- AlertType
    severity         VARCHAR(10) NOT NULL,                  -- Severity
    status           VARCHAR(20) NOT NULL DEFAULT 'OPEN',   -- AlertStatus
    product_id       BIGINT REFERENCES products(id) ON DELETE CASCADE,
    lot_id           BIGINT REFERENCES lots(id) ON DELETE CASCADE,
    announcement_id  BIGINT,
    title            VARCHAR(200) NOT NULL,
    message          TEXT,
    dedupe_key       VARCHAR(150) NOT NULL,
    handled_by       BIGINT REFERENCES users(id) ON DELETE SET NULL,
    resolved_at      TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_alerts_open_dedupe ON alerts(tenant_id, dedupe_key) WHERE status IN ('OPEN', 'ACKNOWLEDGED');
CREATE INDEX idx_alerts_tenant_status ON alerts(tenant_id, status, created_at DESC);
CREATE INDEX idx_alerts_branch_status ON alerts(branch_id, status);

-- La IA analiza cada sucursal por separado (cada local tiene su propio patrón de ventas).
CREATE TABLE ai_runs (
    id                       BIGSERIAL PRIMARY KEY,
    tenant_id                BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    branch_id                BIGINT NOT NULL REFERENCES branches(id) ON DELETE CASCADE,
    status                   VARCHAR(20) NOT NULL,          -- AiRunStatus
    trigger_type             VARCHAR(20) NOT NULL,          -- AiRunTrigger
    model_version            VARCHAR(40),
    products_analyzed        INT,
    recommendations_created  INT,
    summary                  JSONB,
    error_message            TEXT,
    started_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at              TIMESTAMPTZ
);
CREATE INDEX idx_ai_runs_tenant_time ON ai_runs(tenant_id, started_at DESC);
CREATE INDEX idx_ai_runs_branch_time ON ai_runs(branch_id, started_at DESC);

CREATE TABLE product_insights (
    id                       BIGSERIAL PRIMARY KEY,
    tenant_id                BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    branch_id                BIGINT NOT NULL REFERENCES branches(id) ON DELETE CASCADE,
    product_id               BIGINT NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    run_id                   BIGINT REFERENCES ai_runs(id) ON DELETE SET NULL,
    pattern                  VARCHAR(40),                   -- SalesPattern
    pattern_description      VARCHAR(300),
    abc_class                VARCHAR(1),
    xyz_class                VARCHAR(1),
    avg_daily_sales          NUMERIC(10,3),
    trend_pct                NUMERIC(8,2),
    weekday_profile          JSONB,                         -- [lun..dom] multiplicadores
    forecast                 JSONB,                         -- [{date,yhat,lo,hi}]
    forecast_method          VARCHAR(30),
    days_of_cover            NUMERIC(10,2),
    predicted_stockout_date  DATE,
    reorder_point            INT,
    safety_stock             INT,
    suggested_order_qty      INT,
    anomalies                JSONB,                         -- [{date,quantity,expected,score,kind}]
    lot_risks                JSONB,                         -- [{lotId,...}]
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_product_insights UNIQUE (branch_id, product_id)
);
CREATE INDEX idx_product_insights_tenant ON product_insights(tenant_id);

CREATE TABLE recommendations (
    id                      BIGSERIAL PRIMARY KEY,
    tenant_id               BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    branch_id               BIGINT NOT NULL REFERENCES branches(id) ON DELETE CASCADE,
    run_id                  BIGINT REFERENCES ai_runs(id) ON DELETE SET NULL,
    type                    VARCHAR(30) NOT NULL,           -- RecommendationType
    status                  VARCHAR(20) NOT NULL DEFAULT 'PENDING', -- RecommendationStatus
    product_id              BIGINT REFERENCES products(id) ON DELETE CASCADE,
    lot_id                  BIGINT REFERENCES lots(id) ON DELETE CASCADE,
    title                   VARCHAR(200) NOT NULL,
    explanation             TEXT NOT NULL,
    suggested_quantity      INT,
    suggested_discount_pct  NUMERIC(5,2),
    suggested_date          DATE,
    priority                INT NOT NULL DEFAULT 50,        -- 1..100
    confidence              NUMERIC(4,3),                   -- 0..1
    expected_impact         NUMERIC(14,2),                  -- $ estimado ahorrado/recuperado
    dedupe_key              VARCHAR(150) NOT NULL,
    decided_by              BIGINT REFERENCES users(id) ON DELETE SET NULL,
    decided_at              TIMESTAMPTZ,
    decision_note           VARCHAR(300),
    outcome                 JSONB,                          -- resultado medido (feedback)
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_reco_pending_dedupe ON recommendations(branch_id, dedupe_key) WHERE status = 'PENDING';
CREATE INDEX idx_reco_tenant_status ON recommendations(tenant_id, status, priority DESC);
CREATE INDEX idx_reco_branch_status ON recommendations(branch_id, status, priority DESC);

-- ---------------------------------------------------------------------
-- Avisos, recalls y notificaciones
-- ---------------------------------------------------------------------
CREATE TABLE announcements (
    id                      BIGSERIAL PRIMARY KEY,
    kind                    VARCHAR(20) NOT NULL,             -- AnnouncementKind
    severity                VARCHAR(10) NOT NULL DEFAULT 'INFO',
    status                  VARCHAR(20) NOT NULL DEFAULT 'PUBLISHED', -- AnnouncementStatus
    title                   VARCHAR(200) NOT NULL,
    body                    TEXT NOT NULL,
    target_business_types   VARCHAR(200),                     -- NULL = todos; CSV de BusinessType
    recall_product_name     VARCHAR(200),
    recall_brand            VARCHAR(100),
    recall_barcode          VARCHAR(32),
    recall_all_lots         BOOLEAN NOT NULL DEFAULT FALSE,
    recall_expiry_from      DATE,
    recall_expiry_to        DATE,
    recall_reason           TEXT,
    recall_instructions     TEXT,
    affected_tenants_count  INT NOT NULL DEFAULT 0,           -- solo agregado, nunca cuáles
    recipients_count        INT NOT NULL DEFAULT 0,
    created_by              BIGINT REFERENCES users(id) ON DELETE SET NULL,
    published_at            TIMESTAMPTZ,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_announcements_status_time ON announcements(status, published_at DESC);
CREATE INDEX idx_announcements_recall_barcode ON announcements(recall_barcode) WHERE kind = 'RECALL';

CREATE TABLE announcement_recall_lots (
    id                     BIGSERIAL PRIMARY KEY,
    announcement_id        BIGINT NOT NULL REFERENCES announcements(id) ON DELETE CASCADE,
    lot_number             VARCHAR(60) NOT NULL,
    lot_number_normalized  VARCHAR(60) NOT NULL
);
CREATE INDEX idx_recall_lots_announcement ON announcement_recall_lots(announcement_id);
CREATE INDEX idx_recall_lots_norm ON announcement_recall_lots(lot_number_normalized);

CREATE TABLE announcement_reads (
    id               BIGSERIAL PRIMARY KEY,
    announcement_id  BIGINT NOT NULL REFERENCES announcements(id) ON DELETE CASCADE,
    user_id          BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    read_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_announcement_reads UNIQUE (announcement_id, user_id)
);

CREATE TABLE recall_matches (
    id                BIGSERIAL PRIMARY KEY,
    announcement_id   BIGINT NOT NULL REFERENCES announcements(id) ON DELETE CASCADE,
    tenant_id         BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    branch_id         BIGINT NOT NULL REFERENCES branches(id) ON DELETE CASCADE,
    product_id        BIGINT NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    lot_id            BIGINT NOT NULL REFERENCES lots(id) ON DELETE CASCADE,
    quantity_at_match INT NOT NULL,
    status            VARCHAR(20) NOT NULL DEFAULT 'OPEN',   -- RecallMatchStatus
    resolution        VARCHAR(30),                           -- RecallResolution
    resolution_note   VARCHAR(300),
    matched_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    acknowledged_at   TIMESTAMPTZ,
    acknowledged_by   BIGINT REFERENCES users(id) ON DELETE SET NULL,
    resolved_at       TIMESTAMPTZ,
    resolved_by       BIGINT REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT uq_recall_match UNIQUE (announcement_id, lot_id)
);
CREATE INDEX idx_recall_matches_tenant_status ON recall_matches(tenant_id, status);
CREATE INDEX idx_recall_matches_branch_status ON recall_matches(branch_id, status);

CREATE TABLE notifications (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    tenant_id       BIGINT REFERENCES tenants(id) ON DELETE CASCADE,
    type            VARCHAR(30) NOT NULL,                    -- NotificationType
    severity        VARCHAR(10) NOT NULL DEFAULT 'INFO',
    title           VARCHAR(200) NOT NULL,
    body            TEXT,
    link            VARCHAR(300),                            -- ruta del frontend
    reference_type  VARCHAR(30),
    reference_id    BIGINT,
    read_at         TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_notifications_user_time ON notifications(user_id, created_at DESC);
CREATE INDEX idx_notifications_user_unread ON notifications(user_id) WHERE read_at IS NULL;

-- ---------------------------------------------------------------------
-- Soporte
-- ---------------------------------------------------------------------
CREATE TABLE attachments (
    id             BIGSERIAL PRIMARY KEY,
    tenant_id      BIGINT REFERENCES tenants(id) ON DELETE CASCADE,
    uploaded_by    BIGINT REFERENCES users(id) ON DELETE SET NULL,
    purpose        VARCHAR(20)  NOT NULL,                    -- AttachmentPurpose
    original_name  VARCHAR(255),
    content_type   VARCHAR(100) NOT NULL,
    size_bytes     BIGINT       NOT NULL,
    storage_key    VARCHAR(255) NOT NULL UNIQUE,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE support_tickets (
    id                     BIGSERIAL PRIMARY KEY,
    tenant_id              BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    created_by             BIGINT REFERENCES users(id) ON DELETE SET NULL,
    assigned_to            BIGINT REFERENCES users(id) ON DELETE SET NULL,
    subject                VARCHAR(200) NOT NULL,
    category               VARCHAR(20) NOT NULL DEFAULT 'USO',     -- TicketCategory
    priority               VARCHAR(10) NOT NULL DEFAULT 'MEDIA',   -- TicketPriority
    status                 VARCHAR(20) NOT NULL DEFAULT 'OPEN',    -- TicketStatus
    channel                VARCHAR(10) NOT NULL DEFAULT 'TICKET',  -- TicketChannel
    rating                 INT,
    rating_comment         VARCHAR(500),
    last_message_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    first_response_at      TIMESTAMPTZ,
    customer_last_read_at  TIMESTAMPTZ,
    agent_last_read_at     TIMESTAMPTZ,
    resolved_at            TIMESTAMPTZ,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT support_tickets_rating_chk CHECK (rating IS NULL OR rating BETWEEN 1 AND 5)
);
CREATE INDEX idx_tickets_tenant_time ON support_tickets(tenant_id, last_message_at DESC);
CREATE INDEX idx_tickets_status_time ON support_tickets(status, last_message_at DESC);

CREATE TABLE support_messages (
    id             BIGSERIAL PRIMARY KEY,
    ticket_id      BIGINT NOT NULL REFERENCES support_tickets(id) ON DELETE CASCADE,
    sender_id      BIGINT REFERENCES users(id) ON DELETE SET NULL,
    sender_type    VARCHAR(10) NOT NULL,                     -- MessageSenderType
    body           TEXT,
    attachment_id  BIGINT REFERENCES attachments(id) ON DELETE SET NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_support_messages_ticket_time ON support_messages(ticket_id, created_at);
