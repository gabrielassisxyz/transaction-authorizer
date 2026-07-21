package com.transactionauthorizer.domain

import java.math.BigDecimal

@JvmInline
value class Money(
    val cents: Long,
) : Comparable<Money> {
    init {
        require(cents >= 0) { "money value cannot be negative: $cents cents" }
    }

    operator fun plus(other: Money): Money = Money(Math.addExact(cents, other.cents))

    operator fun minus(other: Money): Money = Money(cents - other.cents)

    override fun compareTo(other: Money): Int = cents.compareTo(other.cents)

    fun toBigDecimal(): BigDecimal = BigDecimal.valueOf(cents, SCALE)

    override fun toString(): String = "${toBigDecimal().toPlainString()} $CURRENCY"

    companion object {
        const val CURRENCY: String = "BRL"
        const val SCALE: Int = 2

        val ZERO: Money = Money(0)
        val MAX: Money = Money(Long.MAX_VALUE)

        // Sub-cent precision is refused, not rounded: rounding here would absorb a
        // caller's contract error. Trailing zeros are serialization, so they go first.
        fun ofDecimal(value: BigDecimal): Money {
            val significant = value.stripTrailingZeros()
            require(significant.scale() <= SCALE) {
                "value has more than $SCALE decimal places: ${value.toPlainString()}"
            }
            return Money(significant.movePointRight(SCALE).longValueExact())
        }
    }
}
