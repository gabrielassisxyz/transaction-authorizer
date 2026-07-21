package com.transactionauthorizer.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.math.BigDecimal

class MoneyTest {
    @ParameterizedTest
    @CsvSource(
        "10.50, 1050",
        "0.01, 1",
        "0, 0",
        "1, 100",
        "1.5, 150",
        "1E+3, 100000",
        // Trailing zeros are serialization, not precision: a caller emitting a
        // fixed-scale decimal must not be refused for a value it can represent.
        "10.5000, 1050",
        "10.500, 1050",
        "0.00, 0",
        "100.0000, 10000",
    )
    fun `converts a decimal amount to cents`(
        value: String,
        expectedCents: Long,
    ) {
        assertThat(Money.ofDecimal(BigDecimal(value)).cents).isEqualTo(expectedCents)
    }

    @ParameterizedTest
    @ValueSource(strings = ["10.505", "10.5050", "0.001"])
    fun `refuses an amount with more than two significant decimal places`(value: String) {
        assertThatThrownBy { Money.ofDecimal(BigDecimal(value)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("decimal places")
    }

    @Test
    fun `refuses an amount that does not fit in cents`() {
        assertThatThrownBy { Money.ofDecimal(BigDecimal("1E+30")) }
            .isInstanceOf(ArithmeticException::class.java)
    }

    @Test
    fun `refuses a negative amount`() {
        assertThatThrownBy { Money.ofDecimal(BigDecimal("-0.01")) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `renders back the decimal it was built from`() {
        assertThat(Money.ofDecimal(BigDecimal("130.00")).toBigDecimal()).isEqualByComparingTo("130.00")
        assertThat(Money(1050).toString()).isEqualTo("10.50 BRL")
    }

    @Test
    fun `adds and subtracts in cents`() {
        assertThat(Money(1050) + Money(50)).isEqualTo(Money(1100))
        assertThat(Money(1050) - Money(50)).isEqualTo(Money(1000))
    }

    @Test
    fun `refuses a subtraction that would go negative`() {
        assertThatThrownBy { Money(50) - Money(51) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `refuses an addition that would overflow`() {
        assertThatThrownBy { Money.MAX + Money(1) }
            .isInstanceOf(ArithmeticException::class.java)
    }

    @Test
    fun `orders by cents`() {
        assertThat(Money(50)).isLessThan(Money(51)).isGreaterThan(Money.ZERO)
    }
}
