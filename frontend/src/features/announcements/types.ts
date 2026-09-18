import type {
  AnnouncementKind,
  AnnouncementStatus,
  BusinessType,
  RecallMatchStatus,
  RecallResolution,
  Severity,
} from '@/api/types';

/** Datos del producto retirado de un recall (SPEC §6.7). */
export interface RecallDetail {
  productName: string;
  brand: string | null;
  barcode: string;
  lotNumbers: string[];
  allLots: boolean;
  expiryFrom: string | null;
  expiryTo: string | null;
  reason: string;
  instructions: string;
}

// ---------------------------------------------------------------------------
// Consola de dueños
// ---------------------------------------------------------------------------

export interface AnnouncementListItem {
  id: number;
  kind: AnnouncementKind;
  severity: Severity;
  status: AnnouncementStatus;
  title: string;
  body: string;
  targetBusinessTypes: BusinessType[];
  publishedAt: string | null;
  createdAt: string;
  createdByName: string | null;
  recipientsCount: number;
  affectedTenantsCount: number;
  recall: RecallDetail | null;
}

export interface AnnouncementDetail extends Omit<AnnouncementListItem, 'createdAt'> {
  createdAt: string;
  updatedAt: string;
  readCount: number;
  matchesOpen: number;
  matchesAcknowledged: number;
  matchesResolved: number;
}

export interface AnnouncementListParams {
  kind?: AnnouncementKind;
  status?: AnnouncementStatus;
  page?: number;
  size?: number;
}

export interface RecallRequestBody {
  productName: string;
  brand: string | null;
  barcode: string;
  lotNumbers: string[];
  allLots: boolean;
  expiryFrom: string | null;
  expiryTo: string | null;
  reason: string;
  instructions: string;
}

export interface CreateAnnouncementBody {
  kind: AnnouncementKind;
  severity: Severity;
  title: string;
  body: string;
  targetBusinessTypes: BusinessType[] | null;
  recall: RecallRequestBody | null;
}

export interface RecallPreviewBody {
  barcode: string;
  lotNumbers: string[];
  allLots: boolean;
  expiryFrom: string | null;
  expiryTo: string | null;
}

export interface RecallPreview {
  affectedTenantsCount: number;
  affectedLotsCount: number;
  affectedUnits: number;
}

// ---------------------------------------------------------------------------
// Comercio
// ---------------------------------------------------------------------------

export interface TenantAnnouncement {
  id: number;
  kind: AnnouncementKind;
  severity: Severity;
  title: string;
  body: string;
  publishedAt: string | null;
  read: boolean;
  affectsMe: boolean;
  myMatchesCount: number;
  myOpenMatchesCount: number;
  recall: RecallDetail | null;
}

export interface RecallMatch {
  id: number;
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
  quantityAtMatch: number;
  currentQuantity: number;
  status: RecallMatchStatus;
  matchedAt: string;
  acknowledgedAt: string | null;
  acknowledgedByName: string | null;
  resolvedAt: string | null;
  resolvedByName: string | null;
  resolution: RecallResolution | null;
  resolutionNote: string | null;
}

/** Filtro del listado de coincidencias: `ACTIVE` son las que todavía piden acción. */
export type RecallMatchFilter = 'ACTIVE' | 'OPEN' | 'ACKNOWLEDGED' | 'RESOLVED' | 'ALL';

export interface ResolveRecallBody {
  resolution: RecallResolution;
  note: string | null;
}
