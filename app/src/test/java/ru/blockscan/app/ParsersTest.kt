package ru.blockscan.app

import org.junit.Assert.*
import org.junit.Test
import ru.blockscan.app.core.SseParser
import ru.blockscan.app.domain.*

class ParsersTest {
    private fun feedAll(p: SseParser, text: String) =
        text.lines().mapNotNull { p.feed(it) }

    @Test fun sseRealFormatWithoutSpaceAndComments() {
        val p = SseParser()
        val ev = feedAll(p, "event:started\ndata:{\"id\":\"x\"}\n\n:\nevent:result\ndata:{\"a\":1}\n\n")
        assertEquals(listOf("started", "result"), ev.map { it.event })
        assertEquals("{\"id\":\"x\"}", ev[0].data)
    }

    @Test fun sseMultilineDataSpaceAfterColonAndUnknownFields() {
        val p = SseParser()
        val ev = feedAll(p, "retry: 100\nid: 5\nevent: result\ndata: line1\ndata: line2\n\n")
        assertEquals(1, ev.size)
        assertEquals("line1\nline2", ev[0].data)
        assertEquals("result", ev[0].event)
    }

    @Test fun sseDefaultEventNameAndFlushOfUnterminatedEvent() {
        val p = SseParser()
        assertNull(p.feed("data:hello"))
        val e = p.flush()!!
        assertEquals("message", e.event)
        assertEquals("hello", e.data)
        assertNull(p.flush())
    }

    @Test fun realStreamHasStartedThenResultsThenDone() {
        val ev = Fixtures.probeEvents()
        assertEquals(39, ev.size)
        val started = ev.first() as ProbeEvent.Started
        assertEquals(37, started.online)
        assertEquals("1.1.1.1", started.target)
        assertEquals(37, ev.count { it is ProbeEvent.Result })
        val done = ev.last() as ProbeEvent.Done
        assertEquals(37, done.responseCount)
        assertEquals(37, done.online)
    }

    @Test fun realStaticResponse() {
        val s = CheckParser.parse(Res.text("check_1.1.1.1.json"))
        assertEquals("1.1.1.1", s.target)
        assertEquals("IPv4-адрес", s.targetType)
        assertEquals(true, s.listed)
        assertEquals("AS13335", s.asn)
        assertEquals("Cloudflare, Inc.", s.organisation)
        assertNull(s.countryCode)
        assertEquals(1, s.cdn.size)
        assertEquals("cloudflare", s.cdn[0].name)
        assertEquals("1.1.1.0/24", s.cdn[0].cidr)
        assertEquals(listOf("one.one.one.one"), s.reverse)
        assertEquals(14, s.complaints.size)
        assertEquals(16, s.complaints.sumOf { it.count })
        assertTrue(s.isCdn)
        assertTrue(s.unknownKeys.isEmpty())
    }

    @Test fun privateAddressResponseHasNoCdnAndNotListed() {
        val s = CheckParser.parse(Res.text("check_192.168.1.1.json"))
        assertEquals(false, s.listed)
        assertNull(s.asn)
        assertFalse(s.isCdn)
    }

    @Test fun subnetResponse() {
        val s = CheckParser.parse(Res.text("check_1.1.1.0_24.json"))
        assertEquals("IPv4-подсеть", s.targetType)
        assertEquals("256", s.subnetSize)
        assertEquals(254, s.ips.size)
    }

    @Test fun domainProbeParsesDnsAndHostEvidenceWithoutInterpretingTypes() {
        val raw = """{"job_id":"j","probe_id":"p","provider":"Probe","verdicts":["sni_block"],"host_results":[{"host_id":"h1","host":"discord.com","probe_evidence":{"type":"DataTimeout","bytes":128}},{"host_id":"h2","host":"cdn.discord.com","probe_evidence":{"type":"Good"}}],"dns":{"spoofing_detected":true,"suspicious_provider_count":2,"verdict_threshold":3,"samples_per_protocol":4,"observations":[{"provider":"Resolver A","protocol":"Udp","suspected_spoofing":true,"metadata":{"response_codes":["NOERROR"],"ipv4_count":1,"ipv6_count":1},"outcome":{"type":"Answer","addresses":["162.159.128.233","2606:4700::1111"]}},{"provider":"Resolver B","protocol":"Doh","suspected_spoofing":false,"metadata":{"response_codes":["NXDOMAIN"],"ipv4_count":0,"ipv6_count":0},"outcome":{"type":"NoRecords"}}]}}"""
        val result = ProbeParser.parseResult(raw)

        assertEquals(true, result.dns!!.spoofingDetected)
        assertEquals(2, result.dns.suspiciousProviderCount)
        assertEquals(listOf("162.159.128.233", "2606:4700::1111"), result.dns.observations.first().addresses)
        assertEquals("NoRecords", result.dns.observations.last().outcomeType)
        assertEquals(mapOf("DataTimeout" to 1, "Good" to 1), result.hostResults.groupingBy { it.evidenceType!! }.eachCount())
        assertEquals(2, result.hostResultsSize)
    }

    @Test fun unknownAndMistypedFieldsDoNotBreakParsing() {
        val raw = """{"id":"a","target":"1.2.3.4","blocked":"yes","new_field":{"x":1},"geo":"oops","complaints":[{"date":"d"},5],"cdn_providers":{"x":[]},"whitelist":{"k":1}}"""
        val s = CheckParser.parse(raw)
        assertNull(s.listed)
        assertEquals(listOf("new_field"), s.unknownKeys)
        assertNull(s.asn)
        assertEquals(1, s.complaints.size)
        assertEquals("x", s.cdn.single().name)
        assertNotNull(s.whitelist)
    }

    @Test fun invalidBodiesAreBadResponse() {
        for (bad in listOf("not json", "[]", "", "\"str\"")) {
            val e = try { CheckParser.parse(bad); null } catch (e: ApiException) { e }
            assertEquals("body=$bad", ApiException.Kind.BAD_RESPONSE, e?.kind)
        }
    }

    @Test fun probeEventMapping() {
        val err = ProbeParser.mapEvent("error", """{"code":429,"info":"Too Many Requests"}""") as ProbeEvent.ServerError
        assertEquals(429, err.code)
        assertEquals("Too Many Requests", err.message)
        assertNull(ProbeParser.mapEvent("ping", "{}"))
        assertTrue(ProbeParser.mapEvent("result", "not json") is ProbeEvent.Malformed)
        val plain = ProbeParser.mapEvent("error", "boom") as ProbeEvent.ServerError
        assertNull(plain.code)
    }

    @Test fun unknownVerdictBecomesUncertain() {
        val r = ProbeParser.parseResult("""{"verdicts":["ok","brand_new"],"host_results":null}""")
        assertEquals(listOf(Verdict.OK, Verdict.UNCERTAIN), r.verdicts)
        assertNull(r.hostResultsSize)
    }

    @Test fun statusResponse() {
        val s = ProbeParser.parseStatus(Res.text("status.json"))
        assertEquals("1.5.5", s.version)
        assertEquals(1_775_999L, s.domainCount)
        assertEquals(178_932_936L, s.v4Count)
        assertEquals("2026-11-02T14:48:34.469211492Z", s.lastUpdate)
    }
}
