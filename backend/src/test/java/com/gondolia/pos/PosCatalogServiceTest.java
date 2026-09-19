package com.gondolia.pos;

import static org.assertj.core.api.Assertions.assertThat;

import com.gondolia.pos.dto.PosPriceTier;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Tramos de precio del mostrador: agrupan lotes consecutivos con el mismo descuento (SPEC §4.2). */
class PosCatalogServiceTest {

    private static PosStockQueries.SellableLot lot(long id, int quantity, String pct) {
        return new PosStockQueries.SellableLot(id, "L" + id, LocalDate.of(2026, 10, 1), quantity,
                pct == null ? null : new BigDecimal(pct));
    }

    @Test
    void groupsConsecutiveLotsWithTheSameDiscount() {
        List<PosPriceTier> tiers = PosCatalogService.priceTiers(new BigDecimal("3800"), List.of(
                lot(1, 10, "25"), lot(2, 5, "25.00"), lot(3, 4, "10"), lot(4, 6, null), lot(5, 3, "0")));

        assertThat(tiers).hasSize(3);
        assertThat(tiers.get(0).quantity()).isEqualTo(15);
        assertThat(tiers.get(0).unitPrice()).isEqualByComparingTo("2850.00");
        assertThat(tiers.get(1).quantity()).isEqualTo(4);
        assertThat(tiers.get(1).unitPrice()).isEqualByComparingTo("3420.00");
        // Sin descuento o con 0 % es el mismo tramo, a precio de lista.
        assertThat(tiers.get(2).quantity()).isEqualTo(9);
        assertThat(tiers.get(2).discountPct()).isNull();
        assertThat(tiers.get(2).unitPrice()).isEqualByComparingTo("3800.00");
    }

    @Test
    void noLotsMeansNoTiers() {
        assertThat(PosCatalogService.priceTiers(new BigDecimal("3800"), List.of())).isEmpty();
    }
}
