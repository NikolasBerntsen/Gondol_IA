// Tipos y enums compartidos entre la fundación y los módulos (SPEC §3, §4.1, §5.2, §6.1, §6.2, §7).
// Los DTOs propios de cada módulo viven en `features/<modulo>/types.ts`.

// ---------------------------------------------------------------------------
// Roles
// ---------------------------------------------------------------------------

export type Role =
  | 'PLATFORM_OWNER'
  | 'SUPPORT_AGENT'
  | 'TENANT_BOSS'
  | 'TENANT_ADMIN'
  | 'TENANT_EMPLOYEE'
  | 'TENANT_CASHIER';

export const ROLES: readonly Role[] = [
  'PLATFORM_OWNER',
  'SUPPORT_AGENT',
  'TENANT_BOSS',
  'TENANT_ADMIN',
  'TENANT_EMPLOYEE',
  'TENANT_CASHIER',
];

export const PLATFORM_ROLES: readonly Role[] = ['PLATFORM_OWNER', 'SUPPORT_AGENT'];
export const TENANT_ROLES: readonly Role[] = ['TENANT_BOSS', 'TENANT_ADMIN', 'TENANT_EMPLOYEE', 'TENANT_CASHIER'];

export const ROLE_LABELS: Record<Role, string> = {
  PLATFORM_OWNER: 'Dueño GondolIA',
  SUPPORT_AGENT: 'Soporte',
  TENANT_BOSS: 'Jefe',
  TENANT_ADMIN: 'Administrador',
  TENANT_EMPLOYEE: 'Empleado',
  TENANT_CASHIER: 'Cajero',
};

export function isTenantRole(role: Role | null | undefined): boolean {
  return !!role && TENANT_ROLES.includes(role);
}

export function isPlatformRole(role: Role | null | undefined): boolean {
  return !!role && PLATFORM_ROLES.includes(role);
}

// ---------------------------------------------------------------------------
// Tenants y sucursales
// ---------------------------------------------------------------------------

export type TenantStatus = 'ACTIVE' | 'DISABLED' | 'CANCELLED';

export const TENANT_STATUS_LABELS: Record<TenantStatus, string> = {
  ACTIVE: 'Activo',
  DISABLED: 'Deshabilitado',
  CANCELLED: 'Dado de baja',
};

export type TenantPlan = 'FREEMIUM' | 'BASICO' | 'PROFESIONAL';

export const TENANT_PLANS: readonly TenantPlan[] = ['FREEMIUM', 'BASICO', 'PROFESIONAL'];

export const PLAN_LABELS: Record<TenantPlan, string> = {
  FREEMIUM: 'Freemium',
  BASICO: 'Básico',
  PROFESIONAL: 'Profesional',
};

/** Precio mensual en ARS **por sucursal activa** (SPEC §4.1). */
export const PLAN_MONTHLY_PRICE_PER_BRANCH: Record<TenantPlan, number> = {
  FREEMIUM: 0,
  BASICO: 25000,
  PROFESIONAL: 55000,
};

/** Máximo de sucursales activas por plan (SPEC §3.5). */
export const PLAN_MAX_BRANCHES: Record<TenantPlan, number> = {
  FREEMIUM: 1,
  BASICO: 3,
  PROFESIONAL: 10,
};

// ---------------------------------------------------------------------------
// Módulos por tenant (SPEC §14)
// ---------------------------------------------------------------------------

/** Módulos que los dueños de GondolIA habilitan por cliente (`ModuleCatalog`, SPEC §14.1). */
export type TenantModule = 'POS_GONDOLIA' | 'POS_INTEGRATION' | 'MULTI_BRANCH';

export const TENANT_MODULES: readonly TenantModule[] = ['POS_GONDOLIA', 'POS_INTEGRATION', 'MULTI_BRANCH'];

export const TENANT_MODULE_LABELS: Record<TenantModule, string> = {
  POS_GONDOLIA: 'Punto de venta GondolIA',
  POS_INTEGRATION: 'Integración con POS propio',
  MULTI_BRANCH: 'Multi-sucursal',
};

export const TENANT_MODULE_DESCRIPTIONS: Record<TenantModule, string> = {
  POS_GONDOLIA:
    'Cajas por sucursal, cobro con escáner, medios de pago, tickets, anulaciones y cierre de caja.',
  POS_INTEGRATION:
    'API key por sucursal (webhook de ventas), importación CSV de ventas y simulador para probar la integración.',
  MULTI_BRANCH: 'Más de una sucursal (hasta el límite del plan), transferencias y vista consolidada.',
};

/** Adicional mensual en ARS **por sucursal activa** (SPEC §14.1). */
export const TENANT_MODULE_MONTHLY_PRICE: Record<TenantModule, number> = {
  POS_GONDOLIA: 12000,
  POS_INTEGRATION: 8000,
  MULTI_BRANCH: 0,
};

/** Estado de un módulo para un cliente (`TenantModuleStatus`, consola de dueños). */
export interface TenantModuleStatus {
  module: TenantModule;
  name: string;
  description: string;
  monthlyPricePerBranch: number;
  enabled: boolean;
  updatedAt: string | null;
  updatedByName: string | null;
}

export type BusinessType = 'KIOSCO' | 'ALMACEN' | 'DIETETICA' | 'MINIMERCADO' | 'FARMACIA' | 'OTRO';

export const BUSINESS_TYPES: readonly BusinessType[] = [
  'KIOSCO',
  'ALMACEN',
  'DIETETICA',
  'MINIMERCADO',
  'FARMACIA',
  'OTRO',
];

export const BUSINESS_TYPE_LABELS: Record<BusinessType, string> = {
  KIOSCO: 'Kiosco',
  ALMACEN: 'Almacén',
  DIETETICA: 'Dietética',
  MINIMERCADO: 'Minimercado',
  FARMACIA: 'Farmacia',
  OTRO: 'Otro',
};

export type TenantEventType = 'CREATED' | 'PLAN_CHANGED' | 'DISABLED' | 'ENABLED' | 'CANCELLED' | 'REACTIVATED' | 'DELETED';

export const TENANT_EVENT_TYPE_LABELS: Record<TenantEventType, string> = {
  CREATED: 'Alta',
  PLAN_CHANGED: 'Cambio de plan',
  DISABLED: 'Deshabilitado',
  ENABLED: 'Habilitado',
  CANCELLED: 'Baja',
  REACTIVATED: 'Reactivado',
  DELETED: 'Eliminado',
};

export type StockRotation = 'FIFO' | 'FEFO';

export const STOCK_ROTATION_LABELS: Record<StockRotation, string> = {
  FIFO: 'FIFO: primero sale lo que entró antes',
  FEFO: 'FEFO: primero sale lo que vence antes',
};

/** Sucursal accesible por el usuario (`MeDto.branches`, `BranchAccessService.BranchRef`). */
export interface BranchRef {
  id: number;
  name: string;
  code: string | null;
}

/** Valor del header `X-Branch-Id`: una sucursal o la vista consolidada (SPEC §3.5). */
export type BranchScope = number | 'all';

/** Campos que trae cada fila de datos por sucursal (SPEC §6). */
export interface BranchScoped {
  branchId: number;
  branchName: string;
}

// ---------------------------------------------------------------------------
// Inventario (enums compartidos entre módulos)
// ---------------------------------------------------------------------------

export type ProductUnit = 'UNIDAD' | 'KG' | 'LITRO' | 'PAQUETE' | 'CAJA';

export const PRODUCT_UNIT_LABELS: Record<ProductUnit, string> = {
  UNIDAD: 'Unidad',
  KG: 'Kilogramo',
  LITRO: 'Litro',
  PAQUETE: 'Paquete',
  CAJA: 'Caja',
};

export type LotStatus = 'ACTIVE' | 'DEPLETED' | 'EXPIRED_DISCARDED' | 'RECALLED';

export const LOT_STATUS_LABELS: Record<LotStatus, string> = {
  ACTIVE: 'Activo',
  DEPLETED: 'Agotado',
  EXPIRED_DISCARDED: 'Vencido descartado',
  RECALLED: 'En cuarentena',
};

export type MovementType =
  | 'ENTRY'
  | 'SALE'
  | 'ADJUSTMENT_IN'
  | 'ADJUSTMENT_OUT'
  | 'WASTE_EXPIRED'
  | 'WASTE_DAMAGED'
  | 'RECALL_REMOVAL'
  | 'TRANSFER_OUT'
  | 'TRANSFER_IN';

export const MOVEMENT_TYPE_LABELS: Record<MovementType, string> = {
  ENTRY: 'Ingreso',
  SALE: 'Venta',
  ADJUSTMENT_IN: 'Ajuste positivo',
  ADJUSTMENT_OUT: 'Ajuste negativo',
  WASTE_EXPIRED: 'Baja por vencimiento',
  WASTE_DAMAGED: 'Baja por daño',
  RECALL_REMOVAL: 'Retiro por recall',
  TRANSFER_OUT: 'Transferencia enviada',
  TRANSFER_IN: 'Transferencia recibida',
};

/**
 * Origen de un movimiento (SPEC §4.1). `POS` es el POS externo del cliente (webhook, CSV o simulador) y
 * `POS_GONDOLIA` es nuestro Punto de venta: las etiquetas tienen que distinguirlos siempre.
 */
export type MovementSource =
  | 'MANUAL'
  | 'SCAN'
  | 'OCR'
  | 'CSV'
  | 'POS'
  | 'POS_GONDOLIA'
  | 'IMPORT'
  | 'SEED'
  | 'SYSTEM';

/** Espejo exacto de `MovementLabels.source` del backend (el `sourceLabel` de ventas y movimientos). */
export const MOVEMENT_SOURCE_LABELS: Record<MovementSource, string> = {
  MANUAL: 'Manual',
  SCAN: 'Escáner',
  OCR: 'Lectura de etiqueta',
  CSV: 'Importación CSV',
  POS: 'POS externo',
  POS_GONDOLIA: 'POS GondolIA',
  IMPORT: 'Importación masiva',
  SEED: 'Datos demo',
  SYSTEM: 'Sistema',
};

/** Etiqueta en español de un origen; si llega uno que el front no conoce, lo muestra tal cual. */
export function movementSourceLabel(source: string): string {
  return (MOVEMENT_SOURCE_LABELS as Record<string, string>)[source] ?? source;
}

/** Estado de stock de un producto (SPEC §4.2). */
export type StockStatus = 'OK' | 'LOW' | 'OUT';

/** Glosario fijo de docs/design-system.md §9: Sin stock / Crítico / Bajo / OK. */
export const STOCK_STATUS_LABELS: Record<StockStatus, string> = {
  OK: 'OK',
  LOW: 'Bajo',
  OUT: 'Sin stock',
};

/** Estado en "Artículos a reponer" (SPEC §4.2). */
export type ReorderStatus = 'SIN_STOCK' | 'CRITICO' | 'BAJO';

export const REORDER_STATUS_LABELS: Record<ReorderStatus, string> = {
  SIN_STOCK: 'Sin stock',
  CRITICO: 'Crítico',
  BAJO: 'Bajo',
};

/** Buckets de vencimiento (SPEC §4.2). */
export type ExpiryBucket = 'EXPIRED' | 'CRITICAL' | 'WARNING' | 'UPCOMING' | 'OK';

export const EXPIRY_BUCKET_LABELS: Record<ExpiryBucket, string> = {
  EXPIRED: 'Vencido',
  CRITICAL: 'Crítico',
  WARNING: 'Por vencer',
  UPCOMING: 'Próximo',
  OK: 'Vigente',
};

// ---------------------------------------------------------------------------
// Alertas e IA
// ---------------------------------------------------------------------------

export type Severity = 'INFO' | 'WARNING' | 'CRITICAL';

export const SEVERITY_LABELS: Record<Severity, string> = {
  INFO: 'Informativa',
  WARNING: 'Advertencia',
  CRITICAL: 'Crítica',
};

export type AlertType =
  | 'EXPIRING_SOON'
  | 'EXPIRED'
  | 'LOW_STOCK'
  | 'OUT_OF_STOCK'
  | 'RECALL_MATCH'
  | 'ANOMALY'
  | 'STOCKOUT_PREDICTED'
  | 'SALE_WITHOUT_STOCK';

export const ALERT_TYPE_LABELS: Record<AlertType, string> = {
  EXPIRING_SOON: 'Por vencer',
  EXPIRED: 'Vencido',
  LOW_STOCK: 'Stock bajo',
  OUT_OF_STOCK: 'Sin stock',
  RECALL_MATCH: 'Recall',
  ANOMALY: 'Anomalía',
  STOCKOUT_PREDICTED: 'Quiebre previsto',
  SALE_WITHOUT_STOCK: 'Venta sin stock',
};

export type AlertStatus = 'OPEN' | 'ACKNOWLEDGED' | 'RESOLVED' | 'DISMISSED';

export const ALERT_STATUS_LABELS: Record<AlertStatus, string> = {
  OPEN: 'Abierta',
  ACKNOWLEDGED: 'Vista',
  RESOLVED: 'Resuelta',
  DISMISSED: 'Descartada',
};

export type AiRunStatus = 'RUNNING' | 'OK' | 'ERROR';
export type AiRunTrigger = 'SCHEDULED' | 'MANUAL' | 'STARTUP';

export type SalesPattern =
  | 'ALTA_ROTACION_ESTABLE'
  | 'ESTACIONAL_SEMANAL'
  | 'INTERMITENTE'
  | 'EN_CRECIMIENTO'
  | 'EN_DECLIVE'
  | 'BAJA_ROTACION'
  | 'SIN_MOVIMIENTO'
  | 'DATOS_INSUFICIENTES';

export const SALES_PATTERN_LABELS: Record<SalesPattern, string> = {
  ALTA_ROTACION_ESTABLE: 'Alta rotación estable',
  ESTACIONAL_SEMANAL: 'Estacional semanal',
  INTERMITENTE: 'Intermitente',
  EN_CRECIMIENTO: 'En crecimiento',
  EN_DECLIVE: 'En declive',
  BAJA_ROTACION: 'Baja rotación',
  SIN_MOVIMIENTO: 'Sin movimiento',
  DATOS_INSUFICIENTES: 'Datos insuficientes',
};

export type RecommendationType = 'REORDER' | 'DISCOUNT' | 'REMOVE_EXPIRED' | 'REVIEW_ANOMALY' | 'REDUCE_PURCHASE';

export const RECOMMENDATION_TYPE_LABELS: Record<RecommendationType, string> = {
  REORDER: 'Reponer',
  DISCOUNT: 'Aplicar descuento',
  REMOVE_EXPIRED: 'Retirar vencidos',
  REVIEW_ANOMALY: 'Revisar anomalía',
  REDUCE_PURCHASE: 'Reducir compra',
};

export type RecommendationStatus = 'PENDING' | 'ACCEPTED' | 'DISCARDED' | 'EXPIRED';

export const RECOMMENDATION_STATUS_LABELS: Record<RecommendationStatus, string> = {
  PENDING: 'Pendiente',
  ACCEPTED: 'Aceptada',
  DISCARDED: 'Descartada',
  EXPIRED: 'Vencida',
};

// ---------------------------------------------------------------------------
// Avisos y recalls
// ---------------------------------------------------------------------------

export type AnnouncementKind = 'GENERAL' | 'RECALL';

export const ANNOUNCEMENT_KIND_LABELS: Record<AnnouncementKind, string> = {
  GENERAL: 'Aviso general',
  RECALL: 'Recall',
};

export type AnnouncementStatus = 'DRAFT' | 'PUBLISHED' | 'ARCHIVED';

export const ANNOUNCEMENT_STATUS_LABELS: Record<AnnouncementStatus, string> = {
  DRAFT: 'Borrador',
  PUBLISHED: 'Publicado',
  ARCHIVED: 'Archivado',
};

export type RecallMatchStatus = 'OPEN' | 'ACKNOWLEDGED' | 'RESOLVED';

export const RECALL_MATCH_STATUS_LABELS: Record<RecallMatchStatus, string> = {
  OPEN: 'Pendiente',
  ACKNOWLEDGED: 'Entendido',
  RESOLVED: 'Resuelto',
};

export type RecallResolution = 'REMOVED_FROM_STOCK' | 'RETURNED_TO_SUPPLIER' | 'NOT_FOUND_IN_STORE';

export const RECALL_RESOLUTION_LABELS: Record<RecallResolution, string> = {
  REMOVED_FROM_STOCK: 'Retirado del stock',
  RETURNED_TO_SUPPLIER: 'Devuelto al proveedor',
  NOT_FOUND_IN_STORE: 'No se encontró en el local',
};

/** Push de `/user/queue/security-alerts` (SPEC §7). */
export interface RecallAlertMessage {
  matchId: number;
  announcementId: number;
  branchId: number;
  branchName: string;
  title: string;
  severity: Severity;
  reason: string | null;
  instructions: string | null;
  productId: number;
  productName: string;
  barcode: string | null;
  lotId: number;
  lotNumber: string | null;
  expiryDate: string | null;
  quantity: number;
  matchedAt: string;
}

// ---------------------------------------------------------------------------
// Soporte
// ---------------------------------------------------------------------------

export type AttachmentPurpose = 'SUPPORT' | 'OCR';

export type TicketCategory = 'TECNICO' | 'USO' | 'FACTURACION' | 'SUGERENCIA' | 'OTRO';

export const TICKET_CATEGORY_LABELS: Record<TicketCategory, string> = {
  TECNICO: 'Problema técnico',
  USO: 'Consulta de uso',
  FACTURACION: 'Facturación',
  SUGERENCIA: 'Sugerencia',
  OTRO: 'Otro',
};

export type TicketPriority = 'BAJA' | 'MEDIA' | 'ALTA' | 'URGENTE';

export const TICKET_PRIORITY_LABELS: Record<TicketPriority, string> = {
  BAJA: 'Baja',
  MEDIA: 'Media',
  ALTA: 'Alta',
  URGENTE: 'Urgente',
};

export type TicketStatus = 'OPEN' | 'IN_PROGRESS' | 'WAITING_CUSTOMER' | 'RESOLVED' | 'CLOSED';

export const TICKET_STATUS_LABELS: Record<TicketStatus, string> = {
  OPEN: 'Abierto',
  IN_PROGRESS: 'En curso',
  WAITING_CUSTOMER: 'Esperando respuesta',
  RESOLVED: 'Resuelto',
  CLOSED: 'Cerrado',
};

export type TicketChannel = 'TICKET' | 'CHAT';

export const TICKET_CHANNEL_LABELS: Record<TicketChannel, string> = {
  TICKET: 'Ticket',
  CHAT: 'Chat en vivo',
};

export type MessageSenderType = 'CUSTOMER' | 'AGENT' | 'SYSTEM';

/** `GET /api/presence/support` y `/topic/support/presence`. */
export interface PresenceDto {
  agentsOnline: number;
}

// ---------------------------------------------------------------------------
// Notificaciones
// ---------------------------------------------------------------------------

export type NotificationType =
  | 'ANNOUNCEMENT'
  | 'RECALL_ALERT'
  | 'ALERT'
  | 'RECOMMENDATION'
  | 'TICKET_MESSAGE'
  | 'TICKET_STATUS'
  | 'SYSTEM';

export const NOTIFICATION_TYPE_LABELS: Record<NotificationType, string> = {
  ANNOUNCEMENT: 'Aviso',
  RECALL_ALERT: 'Alerta de recall',
  ALERT: 'Alerta',
  RECOMMENDATION: 'Recomendación',
  TICKET_MESSAGE: 'Mensaje de soporte',
  TICKET_STATUS: 'Estado de ticket',
  SYSTEM: 'Sistema',
};

export interface NotificationDto {
  id: number;
  type: NotificationType;
  severity: Severity;
  title: string;
  body: string | null;
  /** Ruta del frontend a la que lleva la notificación (p. ej. `/app/recalls`). */
  link: string | null;
  referenceType: string | null;
  referenceId: number | null;
  read: boolean;
  /** Instante ISO-8601 UTC. */
  createdAt: string;
}

export interface CountDto {
  count: number;
}

// ---------------------------------------------------------------------------
// Autenticación
// ---------------------------------------------------------------------------

export interface TenantInfo {
  id: number;
  name: string;
  plan: TenantPlan;
  businessType: BusinessType;
  currency: string;
  stockRotation: StockRotation;
  /** Máximo **efectivo** de sucursales: sin `MULTI_BRANCH` es 1 (SPEC §14.1). */
  maxBranches: number;
  /** Módulos habilitados por los dueños de GondolIA (SPEC §14). */
  modules: TenantModule[];
}

export interface MeDto {
  id: number;
  email: string;
  fullName: string;
  role: Role;
  mustChangePassword: boolean;
  /** `null` para roles de plataforma. */
  tenant: TenantInfo | null;
  /** Sucursales accesibles (vacío para roles de plataforma). */
  branches: BranchRef[];
}

export interface LoginRequest {
  email: string;
  password: string;
}

export interface TokenResponse {
  token: string;
  expiresAt: string;
}

export interface LoginResponse extends TokenResponse {
  user: MeDto;
}

export interface ChangePasswordRequest {
  currentPassword: string;
  newPassword: string;
}

/**
 * Mensaje de `/user/queue/session` (SPEC §5.3, §7, §14.1):
 * - `FORCE_LOGOUT`: cierra la sesión mostrando `message`.
 * - `MODULES_CHANGED`: cambiaron los módulos del comercio → `refreshMe()`.
 */
export interface SessionEventMessage {
  type: 'FORCE_LOGOUT' | 'MODULES_CHANGED';
  code?: string;
  message?: string;
}

// ---------------------------------------------------------------------------
// Paginación y errores
// ---------------------------------------------------------------------------

export interface PageResponse<T> {
  content: T[];
  /** 0-based. */
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface PageParams {
  /** 0-based. */
  page?: number;
  /** ≤ 100. */
  size?: number;
  /** `campo,asc` | `campo,desc`. */
  sort?: string;
}

export interface FieldErrorDto {
  field: string;
  message: string;
}

export interface ErrorResponse {
  timestamp: string;
  status: number;
  error: string;
  code: string;
  message: string;
  path: string;
  fieldErrors?: FieldErrorDto[] | null;
}

/** Códigos de error comunes del backend (SPEC §5.2). Los módulos pueden devolver otros. */
export type CommonErrorCode =
  | 'VALIDATION_ERROR'
  | 'NOT_FOUND'
  | 'UNAUTHORIZED'
  | 'FORBIDDEN'
  | 'BAD_CREDENTIALS'
  | 'TENANT_DISABLED'
  | 'TENANT_CANCELLED'
  | 'USER_DISABLED'
  | 'CONFLICT'
  | 'INSUFFICIENT_STOCK'
  | 'AI_UNAVAILABLE'
  | 'BRANCH_REQUIRED'
  | 'BRANCH_FORBIDDEN'
  | 'BRANCH_LIMIT_REACHED'
  | 'BRANCH_HAS_STOCK'
  | 'LOT_NOT_TRANSFERABLE'
  | 'INTERNAL_ERROR';
