package com.coremaker.ledger.unit.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.coremaker.ledger.model.Currency;

class CurrencyShould {

    @Test
    void fitAmountsWithinItsDecimalPlaces() {

        assertThat(Currency.EUR.fits(new BigDecimal("125.50"))).isTrue();
        assertThat(Currency.EUR.fits(new BigDecimal("125.500"))).isTrue();
        assertThat(Currency.EUR.fits(new BigDecimal("125.505"))).isFalse();
        assertThat(Currency.USD.fits(new BigDecimal("0.01"))).isTrue();
        assertThat(Currency.USD.fits(new BigDecimal("0.001"))).isFalse();
        assertThat(Currency.JPY.fits(new BigDecimal("15000"))).isTrue();
        assertThat(Currency.JPY.fits(new BigDecimal("100.5"))).isFalse();
    }
}
