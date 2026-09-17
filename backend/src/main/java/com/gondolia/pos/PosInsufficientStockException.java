package com.gondolia.pos;

import com.gondolia.common.error.ConflictException;
import com.gondolia.common.error.ErrorCodes;
import java.util.List;

/**
 * 409 {@code INSUFFICIENT_STOCK} del cobro, con el detalle por producto que pide SPEC §15.2
 * ({@code details:[{productId,productName,requested,available}]}). Lo serializa {@link PosExceptionHandler} con el
 * formato de error estándar más el campo {@code details}.
 */
public class PosInsufficientStockException extends ConflictException {

    /** Faltante de un producto en la sucursal del turno. */
    public record Detail(Long productId, String productName, int requested, int available) {
    }

    private final transient List<Detail> details;

    public PosInsufficientStockException(String message, List<Detail> details) {
        super(ErrorCodes.INSUFFICIENT_STOCK, message);
        this.details = List.copyOf(details);
    }

    public List<Detail> getDetails() {
        return details;
    }
}
