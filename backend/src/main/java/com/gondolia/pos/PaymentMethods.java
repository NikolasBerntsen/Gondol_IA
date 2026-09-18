package com.gondolia.pos;

import com.gondolia.domain.pos.PaymentMethod;

/** Etiquetas en español de los medios de pago (ticket y reportes). */
public final class PaymentMethods {

    private PaymentMethods() {
    }

    public static String label(PaymentMethod method) {
        if (method == null) {
            return "";
        }
        return switch (method) {
            case CASH -> "Efectivo";
            case DEBIT -> "Débito";
            case CREDIT -> "Crédito";
            case TRANSFER -> "Transferencia";
            case QR -> "QR";
        };
    }
}
