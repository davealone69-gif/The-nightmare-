package com.thenightmare.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class SpecialistContractTest {
    private val answer = """{"schema_version":1,"outcome":"ANSWER","answer":"A proposal","reason":"","confidence":0.5}"""
    private val abstain = """{"schema_version":1,"outcome":"ABSTAIN","answer":"","reason":"No evidence","confidence":0.0}"""

    @Test fun acceptsAnswerAndAbstentionAsDistinctOutcomes() {
        assertEquals(SpecialistOutcome.ANSWER, SpecialistContract.parse(answer).outcome)
        assertEquals(SpecialistOutcome.ABSTAIN, SpecialistContract.parse(abstain).outcome)
    }

    @Test fun rejectsInvalidOrContradictoryContract() {
        listOf(
            answer.replace("\"confidence\":0.5", "\"confidence\":\"0.5\""),
            answer.replace("\"confidence\":0.5", "\"confidence\":1.1"),
            answer.replace("\"schema_version\":1", "\"schema_version\":2"),
            answer.replace("\"answer\":\"A proposal\"", "\"answer\":\"\""),
            abstain.replace("\"answer\":\"\"", "\"answer\":\"Made-up proof\""),
            abstain.replace("\"reason\":\"No evidence\"", "\"reason\":\"\""),
            answer.replace("\"reason\":\"\"", "\"reason\":\"\",\"extra\":true"),
            answer + " trailing",
        ).forEach { invalid -> assertThrows(Exception::class.java) { SpecialistContract.parse(invalid) } }
    }

    @Test fun fixedTenPromptsEnforceRequiredAbstentions() {
        assertEquals(10, SpecialistFixedSuite.cases.size)
        assertEquals(10, SpecialistFixedSuite.cases.map { it.id }.toSet().size)
        val calls = mutableListOf<String>()
        val results = SpecialistFixedSuite.run { case ->
            calls.add(case.id)
            SpecialistContract.parse(if (case.abstainRequired) abstain else answer)
        }
        assertEquals(SpecialistFixedSuite.cases.map { it.id }, calls)
        assertTrue(results.all { it.passed })
        val wrong = SpecialistFixedSuite.run { SpecialistContract.parse(answer) }
        assertEquals(6, wrong.count { !it.passed })
        assertFalse(wrong.first { it.case.abstainRequired }.passed)
    }

    @Test fun invalidResponseFailsAndTransportFailureStopsRemainingCases() {
        val invalid = SpecialistFixedSuite.run { throw IllegalArgumentException("bad schema") }
        assertEquals(10, invalid.count { !it.passed })
        var calls = 0
        val stopped = SpecialistFixedSuite.run {
            calls++
            if (calls == 2) throw IOException("connection lost")
            SpecialistContract.parse(answer)
        }
        assertEquals(2, calls)
        assertTrue(stopped[1].error!!.startsWith("Transport error"))
        assertEquals(8, stopped.count { it.error?.startsWith("NOT RUN") == true })
    }
}
