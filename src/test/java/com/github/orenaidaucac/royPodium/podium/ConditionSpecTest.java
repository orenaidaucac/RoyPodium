package com.github.orenaidaucac.royPodium.podium;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConditionSpecTest {

    @Test
    void evaluatesAllNumericOperatorsAndAliases() {
        assertTrue(ConditionSpec.parse("= 5").orElseThrow().evaluate("5").passed());
        assertTrue(ConditionSpec.parse("== 5").orElseThrow().evaluate("5.0").passed());
        assertTrue(ConditionSpec.parse("!= 5").orElseThrow().evaluate("6").passed());
        assertFalse(ConditionSpec.parse("!= 5").orElseThrow().evaluate("5").passed());
        assertTrue(ConditionSpec.parse("> 5").orElseThrow().evaluate("5.1").passed());
        assertFalse(ConditionSpec.parse("> 5").orElseThrow().evaluate("5").passed());
        assertTrue(ConditionSpec.parse("< 10").orElseThrow().evaluate("9").passed());
        assertTrue(ConditionSpec.parse(">= 5").orElseThrow().evaluate("5").passed());
        assertTrue(ConditionSpec.parse("<= 5").orElseThrow().evaluate("5").passed());
        assertTrue(ConditionSpec.parse("gte 1000").orElseThrow().evaluate("1,250.50").passed());
        assertTrue(ConditionSpec.parse("lt 10").orElseThrow().evaluate("9").passed());
    }

    @Test
    void handlesNonNumericAndInvalidSyntaxSafely() {
        ConditionSpec cond = ConditionSpec.parse(">= 5").orElseThrow();
        var nonNumeric = cond.evaluate("N/A");
        assertFalse(nonNumeric.passed());
        assertTrue(nonNumeric.failureReason().contains("Non-numeric"));

        var empty = cond.evaluate("");
        assertFalse(empty.passed());

        assertTrue(ConditionSpec.parse("invalid_op 5").isEmpty());
        assertTrue(ConditionSpec.parse("> abc").isEmpty());
        assertTrue(ConditionSpec.parse("").isEmpty());
    }

    @Test
    void supportsNonNumericEqualityAndInequality() {
        ConditionSpec ne = ConditionSpec.parse("!= none").orElseThrow();
        assertTrue(ne.evaluate("VIP").passed());
        assertFalse(ne.evaluate("NONE").passed());
    }
}
