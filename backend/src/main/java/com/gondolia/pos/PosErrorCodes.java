package com.gondolia.pos;

/**
 * Códigos de error propios del POS GondolIA (SPEC §15). Los compartidos ({@code REGISTER_BUSY},
 * {@code SESSION_ALREADY_OPEN}, {@code PAYMENT_INSUFFICIENT}, {@code ALREADY_VOIDED}, {@code INSUFFICIENT_STOCK},
 * {@code MODULE_DISABLED}…) están en {@code com.gondolia.common.error.ErrorCodes}.
 */
public final class PosErrorCodes {

    /** El turno de caja no está abierto (409). */
    public static final String SESSION_NOT_OPEN = "SESSION_NOT_OPEN";
    /** La caja está desactivada (409). */
    public static final String REGISTER_INACTIVE = "REGISTER_INACTIVE";
    /** Ya existe otra caja con ese nombre en la sucursal (409). */
    public static final String REGISTER_NAME_TAKEN = "REGISTER_NAME_TAKEN";
    /** La caja tiene un turno abierto y no se puede desactivar ni mudar de sucursal (409). */
    public static final String REGISTER_IN_USE = "REGISTER_IN_USE";
    /** El producto tiene stock en cuarentena por un recall: no se puede vender (409). */
    public static final String PRODUCT_RECALLED = "PRODUCT_RECALLED";
    /** El vuelto no se puede dar porque el excedente no llegó en efectivo (400). */
    public static final String CHANGE_NOT_ALLOWED = "CHANGE_NOT_ALLOWED";

    private PosErrorCodes() {
    }
}
