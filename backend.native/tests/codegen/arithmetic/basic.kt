package codegen.arithmetic.basic

import kotlin.test.*

// Check that compiler doesn't optimize it to `true`
fun selfCmp1(x: Int) = x + 1 > x

fun selfCmp2(x: Int) = x - 1 < x

@Test
fun primitiveComparisons() {
    assertEquals(0f.compareTo(-0f), 1)
    assertEquals(0.0.compareTo(-0.0), 1)

    assertFalse(selfCmp1(Int.MAX_VALUE))
    assertFalse(selfCmp2(Int.MIN_VALUE))

    assertFailsWith(ArithmeticException::class, { 5 / 0 })
    assertEquals(Double.NaN, 0.0 / 0.0)

    println("OK")
}