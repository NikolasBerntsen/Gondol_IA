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
  INFO: 'bg-sky-50 text-sky-600',
  WARNING: 'bg-amber-50 text-amber-600',
  CRITICAL: 'bg-red-100 text-red-600',
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
        'group flex w-full items-start gap-3 rounded-xl px-3 py-3 text-left transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-500',
        critical && unread ? 'bg-red-50/80 hover:bg-red-50' : unread ? 'bg-brand-50/50 hover:bg-brand-50' : 'hover:bg-slate-50',
        className,
      )}
    >
      <span
        className={cn(
          'mt-0.5 flex h-9 w-9 shrink-0 items-center justify-center rounded-xl',
          SEVERITY_ICON_CLASSES[notification.severity],
        )}
      >
        <Icon className="h-[18px] w-[18px]" aria-hidden="true" />
      </span>
      <span className="min-w-0 flex-1">
        <span className="flex items-start gap-2">
          <span
            className={cn(
              'min-w-0 flex-1 text-sm',
              unread ? 'font-semibold text-slate-900' : 'font-medium text-slate-700',
              critical && 'text-red-700',
              !expanded && 'line-clamp-2',
            )}
          >
            {notification.title}
          </span>
          {unread && (
            <span className={cn('mt-1.5 h-2 w-2 shrink-0 rounded-full', critical ? 'bg-red-500' : 'bg-brand-500')}>
              <span className="sr-only">No leída</span>
            </span>
          )}
        </span>
        {notification.body && (
          <span className={cn('mt-0.5 block text-sm text-slate-500', !expanded && 'line-clamp-2')}>
            {notification.body}
          </span>
        )}
        <time
          dateTime={notification.createdAt}
          title={formatDateTime(notification.createdAt)}
          className="mt-1 block text-xs text-slate-400"
        >
          {formatRelative(notification.createdAt)}
        </time>
      </span>
    </button>
  );
}
