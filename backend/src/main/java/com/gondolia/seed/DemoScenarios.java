package com.gondolia.seed;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * Escenarios armados a mano sobre la simulación de cada comercio rico (SPEC §11): el lote del recall en vivo, el
 * recall pendiente del dulce de leche, el caso "lote nuevo que vence antes que uno viejo", sobrestock por vencer
 * con descuento aceptado (con resultado medido para el feedback de la IA), productos bajo mínimo, picos anómalos,
 * vencidos pendientes, decisiones sobre recomendaciones de reposición y ventas manuales periódicas.
 */
final class DemoScenarios {

    /** Lote del recall en vivo (SPEC §11): Sopa de tomate La Huerta 340 g, EAN 7791234500017. */
    static final String LIVE_RECALL_LOT = "L2409A";
    static final String LIVE_RECALL_PRODUCT = "sopa-tomate";
    static final LocalDate LIVE_RECALL_EXPIRY = LocalDate.of(2027, 9, 30);

    /**
     * Recall pendiente para mostrar en la demo: Dulce de leche Dulce Valle 400 g, lote DV2603B. Se publicó ayer a la
     * tarde; GondolIA lo detectó en el acto en Don Pepe y en El Sol Centro (lote en cuarentena, alerta abierta y
     * notificaciones sin leer) y nadie lo atendió todavía: se resuelve en vivo desde Seguridad alimentaria.
     */
    static final String PENDING_RECALL_LOT = "DV2603B";
    static final String PENDING_RECALL_PRODUCT = "dulce-leche";
    static final int PENDING_RECALL_DAYS_AGO = 1;
    static final LocalTime PENDING_RECALL_PUBLISHED_AT = LocalTime.of(18, 40);
    /** El barrido de lotes corre en la misma transacción que la publicación: la coincidencia queda en el acto. */
    static final LocalTime PENDING_RECALL_MATCHED_AT = PENDING_RECALL_PUBLISHED_AT.plusSeconds(1);

    /** Números de lote que el generador nunca produce (para que los recalls solo alcancen a los lotes armados). */
    static final List<String> RESERVED_LOT_NUMBERS = List.of(LIVE_RECALL_LOT, PENDING_RECALL_LOT);

    /**
     * Lote cargado a mano en un día y hora dados. {@code expiryDaysFromToday} es relativo a hoy (negativo = ya
     * venció); {@code fixedExpiry} lo reemplaza si no es null. {@code keepExpired}: si vence con stock nadie lo
     * descarta (queda como "vencido pendiente").
     */
    record ScriptedLot(String branch, String product, int daysAgo, int hour, int quantity, int expiryDaysFromToday,
                       LocalDate fixedExpiry, String lotNumber, boolean keepExpired, String tag) {
    }

    /** Descuento aceptado desde Inteligencia IA sobre un lote con tag. {@code measured}: ya pasaron los 7 días. */
    record DiscountDecision(String branch, String lotTag, int daysAgo, int pct, String note) {
    }

    /** Recomendación de descuento que el administrador descartó. */
    record DiscardedDiscount(String branch, String product, int daysAgo, int pct, String note) {
    }

    /** Recomendación de reposición aceptada (fuerza el pedido ese día) o descartada. */
    record ReorderDecision(String branch, String product, int daysAgo, boolean accepted, String note) {
    }

    /** Proveedor que deja de entregar un producto desde un día (queda bajo mínimo o sin stock). */
    record SupplyStop(String branch, String product, int fromDaysAgo) {
    }

    /** Pico anómalo de ventas. */
    record Spike(String branch, String product, int daysAgo, double factor) {
    }

    /**
     * Venta manual que el administrador carga cada dos semanas (Ventas → Registrar venta): de 3 a 5 productos de las
     * categorías dadas, de {@code minUnits} a {@code maxUnits} unidades cada uno, sin pasar del stock vendible.
     */
    record ManualOrders(String branch, DayOfWeek weekday, LocalTime time, List<String> categories, int minUnits,
                        int maxUnits) {
    }

    /**
     * Coincidencia del recall pendiente con el lote de tag {@code lotTag}: desde ese momento el lote queda en
     * cuarentena y nadie la confirmó ni la resolvió.
     */
    record RecallCase(String branch, String lotTag, int daysAgo, LocalTime matchedAt) {
    }

    record Scenario(List<ScriptedLot> lots, List<DiscountDecision> discounts, List<DiscardedDiscount> discarded,
                    List<ReorderDecision> reorders, List<SupplyStop> supplyStops, List<Spike> spikes,
                    List<RecallCase> recalls, List<ManualOrders> manualOrders) {

        static Scenario empty() {
            return new Scenario(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                    List.of());
        }
    }

    private DemoScenarios() {
    }

    static Scenario forTenant(String tenantKey) {
        return switch (tenantKey) {
            case DemoWorld.DON_PEPE -> donPepe();
            case DemoWorld.EL_SOL -> elSol();
            case DemoWorld.VIDA_SANA -> vidaSana();
            default -> Scenario.empty();
        };
    }

    private static Scenario donPepe() {
        return new Scenario(
                List.of(
                        new ScriptedLot("PRI", LIVE_RECALL_PRODUCT, 34, 10, 36, 0, LIVE_RECALL_EXPIRY,
                                LIVE_RECALL_LOT, false, "recall-live"),
                        // Lote del recall pendiente: entró a la mañana y a la tarde salió el recall. Con FIFO se
                        // venden antes los lotes más viejos, así la cuarentena lo agarra entero (o casi).
                        new ScriptedLot("PRI", PENDING_RECALL_PRODUCT, PENDING_RECALL_DAYS_AGO, 10, 24, 67, null,
                                PENDING_RECALL_LOT, false, "recall-pending"),
                        // Caso FIFO: el lote más nuevo vence antes que el que entró antes.
                        new ScriptedLot("PRI", "crema", 12, 9, 36, 20, null, null, false, "fifo-old"),
                        new ScriptedLot("PRI", "crema", 2, 10, 12, 8, null, null, false, "fifo-new"),
                        // Sobrestock por vencer → descuento aceptado hace 40 días (resultado medido).
                        new ScriptedLot("PRI", "yogur-firme", 43, 9, 72, -33, null, null, false, "promo-yogur"),
                        new ScriptedLot("PRI", "jamon", 24, 9, 30, -13, null, null, false, "promo-jamon"),
                        // Vencido pendiente de descarte.
                        new ScriptedLot("PRI", "manteca", 25, 9, 90, -2, null, null, true, "vencido"),
                        // Varios lotes vivos (compra grande por oferta del mayorista).
                        new ScriptedLot("PRI", "fideos-spaghetti", 26, 11, 60, 400, null, null, false, null),
                        new ScriptedLot("PRI", "atun", 20, 11, 24, 700, null, null, false, null),
                        new ScriptedLot("PRI", "arvejas", 30, 11, 24, 650, null, null, false, null)),
                List.of(new DiscountDecision("PRI", "promo-yogur", 40, 20,
                                "Lo pusimos en la heladera de la entrada con cartel de oferta"),
                        new DiscountDecision("PRI", "promo-jamon", 21, 25, "Oferta en el mostrador de fiambres")),
                List.of(),
                List.of(new ReorderDecision("PRI", "fideos-tirabuzon", 15, true, "Pedido hecho por WhatsApp"),
                        new ReorderDecision("PRI", "arroz", 30, false, "Tenemos stock en el depósito de atrás")),
                List.of(new SupplyStop("PRI", "yerba", 24), new SupplyStop("PRI", "aceite", 16),
                        new SupplyStop("PRI", "galletitas-agua", 15), new SupplyStop("PRI", "lavandina", 19),
                        new SupplyStop("PRI", LIVE_RECALL_PRODUCT, 40), new SupplyStop("PRI", "crema", 12)),
                List.of(new Spike("PRI", "cola", 38, 4.0), new Spike("PRI", "papas", 3, 5.0)),
                List.of(new RecallCase("PRI", "recall-pending", PENDING_RECALL_DAYS_AGO, PENDING_RECALL_MATCHED_AT)),
                List.of());
    }

    private static Scenario elSol() {
        return new Scenario(
                List.of(
                        new ScriptedLot("FIS", LIVE_RECALL_PRODUCT, 21, 10, 30, 0, LIVE_RECALL_EXPIRY,
                                LIVE_RECALL_LOT, false, "recall-live"),
                        new ScriptedLot("CEN", PENDING_RECALL_PRODUCT, PENDING_RECALL_DAYS_AGO, 10, 30, 67, null,
                                PENDING_RECALL_LOT, false, "recall-pending"),
                        // Casos FIFO por sucursal.
                        new ScriptedLot("CEN", "queso-untable", 10, 9, 48, 22, null, null, false, "fifo-old"),
                        new ScriptedLot("CEN", "queso-untable", 2, 10, 12, 6, null, null, false, "fifo-new"),
                        new ScriptedLot("FIS", "crema", 12, 9, 36, 20, null, null, false, "fifo-old-fis"),
                        new ScriptedLot("FIS", "crema", 2, 10, 12, 8, null, null, false, "fifo-new-fis"),
                        new ScriptedLot("ECH", "queso-untable", 9, 9, 30, 21, null, null, false, "fifo-old-ech"),
                        new ScriptedLot("ECH", "queso-untable", 1, 10, 12, 5, null, null, false, "fifo-new-ech"),
                        // Liquidación en curso (aceptada hace 2 días, todavía sin medir).
                        new ScriptedLot("CEN", "jamon", 6, 9, 40, 4, null, null, false, "liquidacion"),
                        // Sobrestock con descuento ya medido.
                        new ScriptedLot("CEN", "yogur-firme", 33, 9, 80, -23, null, null, false, "promo-yogur"),
                        new ScriptedLot("CEN", "crema", 19, 9, 40, -9, null, null, false, "promo-crema"),
                        new ScriptedLot("FIS", "salchichas", 27, 9, 40, -17, null, null, false, "promo-salchichas"),
                        // Vencidos pendientes.
                        new ScriptedLot("FIS", "salame", 30, 9, 70, -1, null, null, true, "vencido-fis"),
                        new ScriptedLot("ECH", "pan-integral", 8, 8, 30, -2, null, null, true, "vencido-ech"),
                        new ScriptedLot("CEN", "leche-choco", 22, 9, 100, -3, null, null, true, "vencido-cen"),
                        // Varios lotes vivos.
                        new ScriptedLot("CEN", "arroz", 24, 11, 40, 420, null, null, false, null),
                        new ScriptedLot("CEN", "atun", 18, 11, 24, 690, null, null, false, null),
                        new ScriptedLot("FIS", "fideos-spaghetti", 21, 11, 60, 380, null, null, false, null),
                        new ScriptedLot("ECH", "yerba", 17, 11, 20, 350, null, null, false, null)),
                List.of(new DiscountDecision("CEN", "promo-yogur", 30, 20,
                                "Oferta 2x1 encubierta: lo pusimos al 20% en la punta de góndola"),
                        new DiscountDecision("CEN", "promo-crema", 16, 15, null),
                        new DiscountDecision("FIS", "promo-salchichas", 24, 20, "Cartel en la heladera"),
                        new DiscountDecision("CEN", "liquidacion", 2, 25, "Liquidación antes del vencimiento")),
                List.of(new DiscardedDiscount("FIS", "queso-cremoso", 12, 15,
                        "Preferimos devolverlo al proveedor, lo cambia sin cargo")),
                List.of(new ReorderDecision("CEN", "cerveza", 8, true, "Pedido confirmado con Bebidas del Litoral"),
                        new ReorderDecision("FIS", "cola", 17, true, null),
                        new ReorderDecision("CEN", "lavandina", 20, false,
                                "Esperamos la oferta del proveedor de la semana que viene")),
                List.of(new SupplyStop("CEN", "leche-desc", 12), new SupplyStop("FIS", "huevos", 11),
                        new SupplyStop("CEN", "detergente", 24), new SupplyStop("FIS", "arroz", 24),
                        new SupplyStop("ECH", "cola", 13), new SupplyStop("CEN", "galletitas-dulces", 17),
                        new SupplyStop("ECH", "agua", 20), new SupplyStop("FIS", LIVE_RECALL_PRODUCT, 25),
                        new SupplyStop("CEN", "queso-untable", 10), new SupplyStop("FIS", "crema", 12),
                        new SupplyStop("ECH", "queso-untable", 9)),
                List.of(new Spike("CEN", "papas", 4, 5.0), new Spike("FIS", "cerveza", 45, 3.5),
                        new Spike("ECH", "alfajor", 2, 4.5)),
                List.of(new RecallCase("CEN", "recall-pending", PENDING_RECALL_DAYS_AGO, PENDING_RECALL_MATCHED_AT)),
                // Pedido del club del barrio para el fin de semana: lo cobran por transferencia y la administradora
                // lo carga a mano (datos-demo §4: ventas de las cuatro fuentes).
                List.of(new ManualOrders("CEN", DayOfWeek.FRIDAY, LocalTime.of(18, 40),
                        List.of("Bebidas", "Bebidas alcohólicas", "Galletitas y snacks"), 6, 18)));
    }

    private static Scenario vidaSana() {
        return new Scenario(
                List.of(
                        // Con FEFO el lote más nuevo que vence antes sale primero (comportamiento correcto).
                        new ScriptedLot("NCB", "kombucha", 12, 9, 24, 40, null, null, false, "fefo-old"),
                        new ScriptedLot("NCB", "kombucha", 3, 10, 18, 10, null, null, false, "fefo-new"),
                        new ScriptedLot("NCB", "yogur-coco", 35, 9, 60, -24, null, null, false, "promo-yogur"),
                        new ScriptedLot("NCB", "hummus", 14, 9, 30, -4, null, null, false, "promo-hummus"),
                        new ScriptedLot("NCB", "tofu", 22, 9, 40, -3, null, null, true, "vencido-ncb"),
                        new ScriptedLot("CDR", "hummus", 19, 9, 36, -2, null, null, true, "vencido-cdr"),
                        new ScriptedLot("NCB", "almendras", 28, 11, 30, 200, null, null, false, null),
                        new ScriptedLot("CDR", "lentejas", 22, 11, 36, 500, null, null, false, null),
                        new ScriptedLot("NCB", "chia", 40, 11, 20, 330, null, null, false, null)),
                List.of(new DiscountDecision("NCB", "promo-yogur", 31, 20, "Promo en la heladera con cartel"),
                        new DiscountDecision("NCB", "promo-hummus", 11, 25, null)),
                List.of(new DiscardedDiscount("CDR", "granola", 18, 15,
                        "Preferimos devolverlo al proveedor, nos lo cambia sin cargo")),
                List.of(new ReorderDecision("NCB", "mix-frutos", 6, true, "Pedido a Frutos del Valle"),
                        new ReorderDecision("CDR", "avena", 25, false, "Lo traemos desde Nueva Córdoba")),
                List.of(new SupplyStop("NCB", "chia", 24), new SupplyStop("CDR", "avena", 24),
                        new SupplyStop("NCB", "granola", 15), new SupplyStop("CDR", "leche-almendras", 10),
                        new SupplyStop("NCB", "kombucha", 12)),
                List.of(new Spike("NCB", "mix-frutos", 5, 5.0), new Spike("CDR", "granola", 33, 4.0)),
                List.of(),
                // Venta mayorista a otras dietéticas: el administrador la registra a mano.
                List.of(new ManualOrders("NCB", DayOfWeek.THURSDAY, LocalTime.of(11, 15),
                        List.of("Frutos secos", "Legumbres", "Cereales y harinas"), 4, 10)));
    }
}
