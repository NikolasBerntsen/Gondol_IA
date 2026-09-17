// Componentes firma de GondolIA (docs/design-system.md §5). Importá desde '@/components/gondola'.
export { BarcodeDigits, type BarcodeDigitsProps } from './BarcodeDigits';
export {
  DEFAULT_EXPIRY_THRESHOLDS,
  ExpiryChip,
  expiryBucketOf,
  type ExpiryChipProps,
  type ExpiryThresholds,
} from './ExpiryChip';
export { LotRankChip, type LotRankChipProps } from './LotRankChip';
export { PriceTag, type PriceTagProps } from './PriceTag';
export {
  SeverityItem,
  SeverityRow,
  SeverityStripe,
  severityStripe,
  stripeClass,
  type SeverityItemProps,
  type SeverityRowProps,
  type StripeSeverity,
} from './SeverityRow';
export { Sparkline, type SparklineProps } from './Sparkline';
export { StatusPill, toneDot, toneSoft, toneText, type StatusPillProps, type Tone } from './StatusPill';
export {
  STOCK_PILL_LABELS,
  StockStatusPill,
  stockSeverity,
  type StockPillStatus,
  type StockStatusPillProps,
} from './StockStatusPill';
export { TICKET_LEGEND, Ticket80mm, type Ticket80mmData, type Ticket80mmItem, type Ticket80mmPayment, type Ticket80mmProps } from './Ticket80mm';
