package ru.blockscan.app

import org.junit.Assert.*
import org.junit.Test
import ru.blockscan.app.domain.*

class VerdictTest {
    private fun probe(vararg v: Verdict, cdnUnblocked: Boolean? = false, hostResults: Int? = 0) =
        ProbeResult("j", "p", null, null, null, v.toList(), cdnUnblocked, hostResults, null, null)

    @Test fun realFixtureTotals() {
        val probes = fixtureResultRaws().map { ProbeParser.parseResult(it) }
        assertEquals(37, probes.size)
        for (cdn in listOf(true, false)) {
            val s = summarize(probes, cdn)
            assertEquals(Verdict.OK, s.winner)
            assertEquals(37, s.totalProbes)
            assertEquals(5, s.blockingProbes)
            assertEquals(25, s.counts[Verdict.OK])
            assertEquals(7, s.counts[Verdict.UNCERTAIN])
            assertEquals(5, s.counts[Verdict.TSPU_BLOCK])
        }
    }

    @Test fun majorityWinsAndTiesGoToHigherPriority() {
        assertEquals(Verdict.TSPU_BLOCK, VerdictLogic.select(listOf(probe(Verdict.TSPU_BLOCK), probe(Verdict.TSPU_BLOCK), probe(Verdict.OK)), false))
        assertEquals(Verdict.TSPU_BLOCK, VerdictLogic.select(listOf(probe(Verdict.TSPU_BLOCK), probe(Verdict.OK)), false))
        assertEquals(Verdict.OK, VerdictLogic.select(listOf(probe(Verdict.OK), probe(Verdict.OK), probe(Verdict.TSPU_BLOCK)), false))
    }

    @Test fun cdnHostResultsRuleMatchesWebsiteAndBlockingVerdictsWin() {
        val hostCheck = probe(Verdict.OK, cdnUnblocked = false, hostResults = 22)
        assertEquals(Verdict.CDN_BLOCK, VerdictLogic.top(hostCheck, isStaticCdn = true))
        assertEquals(Verdict.OK, VerdictLogic.top(hostCheck.copy(cdnUnblocked = true), isStaticCdn = true))
        assertEquals(Verdict.SNI_BLOCK, VerdictLogic.top(
            probe(Verdict.OK, Verdict.SNI_BLOCK, cdnUnblocked = false, hostResults = 22), isStaticCdn = true,
        ))
        assertEquals(Verdict.OK, VerdictLogic.top(probe(Verdict.OK, hostResults = 0), isStaticCdn = true))
    }

    @Test fun uncertainOrEmptyGivesNoWinner() {
        assertNull(VerdictLogic.select(emptyList(), false))
        assertNull(VerdictLogic.select(listOf(probe(Verdict.UNCERTAIN), probe(Verdict.UNCERTAIN), probe(Verdict.OK)), false))
    }

    @Test fun cdnRules() {
        // cdn_block без CDN в списках считается ok
        assertEquals(Verdict.OK, VerdictLogic.top(probe(Verdict.CDN_BLOCK), isStaticCdn = false))
        assertEquals(Verdict.CDN_BLOCK, VerdictLogic.top(probe(Verdict.CDN_BLOCK), isStaticCdn = true))
        // ok + CDN в списках + непустой host_results + cdn_unblocked=false -> cdn_block
        assertEquals(Verdict.CDN_BLOCK, VerdictLogic.top(probe(Verdict.OK, hostResults = 3), true))
        // пустой host_results (так выглядит проверка по IP) оставляет ok
        assertEquals(Verdict.OK, VerdictLogic.top(probe(Verdict.OK, hostResults = 0), true))
        assertEquals(Verdict.OK, VerdictLogic.top(probe(Verdict.OK, cdnUnblocked = true, hostResults = 3), true))
    }

    @Test fun blockingVerdictDropsOk() {
        val d = VerdictLogic.display(probe(Verdict.OK, Verdict.SNI_BLOCK), false)
        assertEquals(listOf(Verdict.SNI_BLOCK), d)
    }

    @Test fun overallFromDoneStream() {
        val r = rec(DynamicStatus.DONE, fixtureResultRaws(), online = 37)
        assertEquals(Overall.COMPLETE, r.overall())
        assertEquals(StaticStatus.LISTED, r.staticStatus())
    }

    @Test fun doneWithoutProbesIsUnknown() {
        assertEquals(Overall.UNKNOWN, rec(DynamicStatus.DONE, emptyList()).overall())
    }

    @Test fun majorityBlockedGivesBlocked() {
        val blocked = List(3) { """{"verdicts":["tspu_block"],"host_results":[]}""" } + """{"verdicts":["ok"],"host_results":[]}"""
        assertEquals(Overall.BLOCKED, rec(DynamicStatus.DONE, blocked).overall())
    }

    @Test fun rknStaticListingDoesNotReplaceSuccessfulScannerVerdict() {
        val static = """{"id":"j","target":"discord.com","target_type":"Домен","blocked":true,"rkn_domain":"discord.com","ips":["162.159.128.233"],"cdn_providers":{},"geo":{"location":"-"},"blocked_subnets":[],"reverse_lookup":[],"complaints":[]}"""
        val rec = CheckRecord(
            target = "discord.com", startedAt = 0, staticRaw = static,
            dyn = DynamicStatus.DONE, responseCount = 1,
            probeRaw = listOf("""{"verdicts":["ok"],"host_results":[{"probe_evidence":{"type":"Good"}}]}"""),
        )
        assertEquals("discord.com", rec.static()!!.rknDomain)
        assertEquals(Overall.COMPLETE, rec.overall())
    }

    @Test fun staticOnlyNeverBecomesComplete() {
        val notListed = Fixtures.checkJson("8.8.8.8", listed = false)
        for (d in listOf(DynamicStatus.NOT_REQUESTED, DynamicStatus.UNAVAILABLE)) {
            assertEquals(Overall.NOT_LISTED, rec(d, staticRaw = notListed).overall())
        }
        assertEquals(Overall.BLOCKED, rec(DynamicStatus.UNAVAILABLE).overall())
        assertNotEquals(Overall.COMPLETE, rec(DynamicStatus.UNAVAILABLE, staticRaw = notListed).overall())
    }

    @Test fun inProgressAndFailureStates() {
        assertEquals(Overall.DYNAMIC_PENDING, rec(DynamicStatus.QUEUED).overall())
        assertEquals(Overall.DYNAMIC_PENDING, rec(DynamicStatus.RUNNING).overall())
        assertEquals(Overall.DYNAMIC_PARTIAL, rec(DynamicStatus.RUNNING, fixtureResultRaws().take(3)).overall())
        assertEquals(Overall.DYNAMIC_PARTIAL, rec(DynamicStatus.PARTIAL, fixtureResultRaws().take(3)).overall())
        assertEquals(Overall.ERROR, rec(DynamicStatus.FAILED).overall())
        assertEquals(Overall.ERROR, rec(DynamicStatus.NOT_REQUESTED, staticRaw = null, staticError = "x").overall())
        assertEquals(Overall.UNKNOWN, rec(DynamicStatus.NOT_REQUESTED, staticRaw = null).overall())
    }

    @Test fun terminalFlag() {
        assertFalse(rec(DynamicStatus.RUNNING).isTerminal)
        assertFalse(rec(DynamicStatus.QUEUED).isTerminal)
        assertTrue(rec(DynamicStatus.DONE).isTerminal)
        assertTrue(rec(DynamicStatus.PARTIAL).isTerminal)
        assertFalse(rec(DynamicStatus.NOT_REQUESTED, staticRaw = null).isTerminal)
    }
}
