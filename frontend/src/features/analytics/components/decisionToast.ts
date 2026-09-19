import { toast } from 'sonner';
import type { RecommendationDecision } from '../types';

/**
 * Toast de una decisión sobre una recomendación (o de un "Comprar N" del Inicio). Si es un pedido, ofrece mandarlo por
 * WhatsApp cuando el proveedor tiene teléfono o, si no, copiar el texto para mandárselo por otro medio.
 */
export function showDecisionToast(decision: RecommendationDecision) {
  const { whatsappUrl, whatsappText } = decision;
  toast.success(decision.message, {
    description: whatsappUrl
      ? 'Podés mandarle el pedido al proveedor por WhatsApp.'
      : whatsappText
        ? 'Copiá el texto del pedido para mandárselo al proveedor.'
        : undefined,
    action: whatsappUrl
      ? { label: 'Abrir WhatsApp', onClick: () => window.open(whatsappUrl, '_blank', 'noopener') }
      : whatsappText
        ? { label: 'Copiar pedido', onClick: () => copyOrder(whatsappText) }
        : undefined,
  });
}

function copyOrder(text: string) {
  if (!navigator.clipboard) {
    toast.error('No pudimos copiar el pedido en este navegador.');
    return;
  }
  navigator.clipboard
    .writeText(text)
    .then(() => toast.success('Copiamos el pedido: pegalo en el mensaje al proveedor.'))
    .catch(() => toast.error('No pudimos copiar el pedido en este navegador.'));
}
