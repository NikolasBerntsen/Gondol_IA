package com.gondolia.pos;

import static org.assertj.core.api.Assertions.assertThat;

import com.gondolia.domain.pos.PaymentMethod;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Importes del POS: siempre dos decimales y descuentos por lote como en {@code StockService} (SPEC §4.2).
 */
class PosMoneyTest {

    @Test
    void scalesToTwoDecimals() {
        assertThat(PosMoney.scale(new BigDecimal("1234.5"))).isEqualByComparingTo("1234.50");
        assertThat(PosMoney.scale(new BigDecimal("1234.567"))).isEqualByComparingTo("1234.57");
        assertThat(PosMoney.orZero(null)).isEqualByComparingTo("0.00");
        assertThat(PosMoney.scale(new BigDecimal("1234.5")).scale()).isEqualTo(2);
    }

    @Test
    void appliesLotDiscount() {
        BigDecimal price = new BigDecimal("2100");
        assertThat(PosMoney.withDiscount(price, new BigDecimal("20"))).isEqualByComparingTo("1680.00");
        assertThat(PosMoney.withDiscount(price, null)).isEqualByComparingTo("2100.00");
        assertThat(PosMoney.withDiscount(price, BigDecimal.ZERO)).isEqualByComparingTo("2100.00");
        assertThat(PosMoney.withDiscount(new BigDecimal("999.99"), new BigDecimal("15")))
                .isEqualByComparingTo("849.99");
    }

    @Test
    void averagesUnitPriceOfALine() {
        assertThat(PosMoney.perUnit(new BigDecimal("27720"), 16)).isEqualByComparingTo("1732.50");
        assertThat(PosMoney.perUnit(new BigDecimal("100"), 0)).isEqualByComparingTo("0.00");
    }

    @Test
    void labelsPaymentMethodsInSpanish() {
        assertThat(PaymentMethods.label(PaymentMethod.CASH)).isEqualTo("Efectivo");
        assertThat(PaymentMethods.label(PaymentMethod.DEBIT)).isEqualTo("Débito");
        assertThat(PaymentMethods.label(PaymentMethod.CREDIT)).isEqualTo("Crédito");
        assertThat(PaymentMethods.label(PaymentMethod.TRANSFER)).isEqualTo("Transferencia");
        assertThat(PaymentMethods.label(PaymentMethod.QR)).isEqualTo("QR");
        assertThat(PaymentMethods.label(null)).isEmpty();
    }
}
