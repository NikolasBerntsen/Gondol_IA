package com.gondolia.pos;

import com.gondolia.common.error.ForbiddenException;
import com.gondolia.common.error.ErrorCodes;
import com.gondolia.domain.pos.PosSession;
import com.gondolia.domain.user.Role;
import com.gondolia.security.AuthUser;
import java.util.Objects;

/**
 * Reglas de permiso propias del POS (SPEC §3.3 y §15.2): el administrador opera cualquier turno de su alcance;
 * el empleado y el cajero, solo el suyo.
 */
public final class PosAccess {

    public static final String ONLY_OWN_SESSION = "Solo podés operar tu propio turno de caja.";
    public static final String ONLY_OWN_SALE =
            "Solo el administrador puede anular ventas de otro turno. Pedile que la anule.";
    /** Venta de un turno propio que ya cerró: el arqueo quedó cerrado y solo el administrador la puede anular. */
    public static final String ONLY_OPEN_SESSION_SALE =
            "El turno de esa venta ya cerró: solo el administrador puede anularla. Pedile que la anule.";

    private PosAccess() {
    }

    public static boolean isAdmin(AuthUser user) {
        return user != null && user.role() == Role.TENANT_ADMIN;
    }

    public static boolean owns(AuthUser user, PosSession session) {
        return user != null && session != null && Objects.equals(session.getOpenedBy(), user.id());
    }

    /** El administrador puede operar cualquier turno de su alcance; el resto, solo el propio. */
    public static void assertCanOperate(AuthUser user, PosSession session) {
        if (!isAdmin(user) && !owns(user, session)) {
            throw new ForbiddenException(ErrorCodes.FORBIDDEN, ONLY_OWN_SESSION);
        }
    }

    /** Ver un turno: el administrador ve todos los de su alcance; el resto, solo los propios. */
    public static void assertCanView(AuthUser user, PosSession session) {
        if (!isAdmin(user) && !owns(user, session)) {
            throw new ForbiddenException(ErrorCodes.FORBIDDEN, "Solo podés ver tus propios turnos de caja.");
        }
    }
}
