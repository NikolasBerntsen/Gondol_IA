import {
  Bell,
  Info,
  LifeBuoy,
  Megaphone,
  MessageSquare,
  ShieldAlert,
  Sparkles,
  type LucideIcon,
} from 'lucide-react';
import type { NotificationDto, NotificationType, Severity } from '@/api/types';
import { cn } from '@/lib/cn';
import { formatDateTime, formatRelative } from '@/lib/format';

const TYPE_ICONS: Record<NotificationType, LucideIcon> = {
  ANNOUNCEMENT: Megaphone,
  RECALL_ALERT: ShieldAlert,
  ALERT: Bell,
  RECOMMENDATION: Sparkles,
  TICKET_MESSAGE: MessageSquare,
  TICKET_STATUS: LifeBuoy,
  SYSTEM: Info,
};

const SEVERITY_ICON_CLASSES: Record<Severity, string> = {
  INFO: 'bg-info-soft text-info-ink',
  WARNING: 'bg-warn-soft text-warn-ink',
  CRITICAL: 'bg-crit-soft text-crit-ink',
};

/** Franja de severidad de la fila (docs/design-system.md §5.5): esquinas rectas, nunca sobre tarjeta. */
const SEVERITY_STRIPE: Record<Severity, string> = {
  INFO: 'gd-stripe-info',
  WARNING: 'gd-stripe-warn',
  CRITICAL: 'gd-stripe-crit',
};

export function notificationIcon(type: NotificationType): LucideIcon {
  return TYPE_ICONS[type] ?? Info;
}

export interface NotificationItemProps {
  notification: NotificationDto;
  onClick?: (notification: NotificationDto) => void;
  /** Muestra el texto completo (en la página) en lugar de 2 líneas (en la campana). */
  expanded?: boolean;
  className?: string;
}

/** Fila de notificación: ícono por tipo, color por severidad, no leídas resaltadas. */
export function NotificationItem({ notification, onClick, expanded = false, className }: NotificationItemProps) {
  const Icon = notificationIcon(notification.type);
  const critical = notification.severity === 'CRITICAL';
  const unread = !notification.read;

  return (
    <button
      type="button"
      onClick={() => onClick?.(notification)}
      className={cn(
        'group flex w-full items-start gap-3 py-3 pl-4 pr-3 text-left transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
        SEVERITY_STRIPE[notification.severity],
        unread ? 'bg-muted/50 hover:bg-muted' : 'hover:bg-muted/40',
        className,
      )}
    >
      <span
        className={cn(
          'mt-0.5 grid h-9 w-9 shrink-0 place-items-center rounded-control',
          SEVERITY_ICON_CLASSES[notification.severity],
        )}
      >
        <Icon className="h-[18px] w-[18px]" aria-hidden="true" />
      </span>
      <span className="min-w-0 flex-1">
        <span className="flex items-start gap-2">
          <span
            className={cn(
              'min-w-0 flex-1 text-base',
              unread ? 'font-semibold text-foreground' : 'font-medium text-foreground',
              critical && 'text-crit-ink',
              !expanded && 'line-clamp-2',
            )}
          >
            {notification.title}
          </span>
          {unread && (
            <span className={cn('mt-1.5 h-2 w-2 shrink-0 rounded-full', critical ? 'bg-crit' : 'bg-primary')}>
              <span className="sr-only">No leída</span>
            </span>
          )}
        </span>
        {notification.body && (
          <span className={cn('mt-0.5 block text-sm text-muted-foreground', !expanded && 'line-clamp-2')}>
            {notification.body}
          </span>
        )}
        <time
          dateTime={notification.createdAt}
          title={formatDateTime(notification.createdAt)}
          className="mt-1 block font-mono text-[11px] text-muted-foreground"
        >
          {formatRelative(notification.createdAt)}
        </time>
      </span>
    </button>
  );
}
