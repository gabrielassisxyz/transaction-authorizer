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

        // Extra *significant* scale is rejected rather than rounded: rounding money
        // silently would absorb a caller's contract error into the ledger. Trailing
        // zeros carry no precision (they are how a caller serializes a fixed-scale
        // decimal), so they are normalized away before the check, or `10.5000` would
        // be refused while the identical `10.50` is accepted.
        fun ofDecimal(value: BigDecimal): Money {
            val significant = value.stripTrailingZeros()
            require(significant.scale() <= SCALE) {
                "value has more than $SCALE decimal places: ${value.toPlainString()}"
            }
            return Money(significant.movePointRight(SCALE).longValueExact())
        }
    }
}
