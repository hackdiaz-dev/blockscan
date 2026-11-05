package ru.blockscan.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.blockscan.app.core.DomainParser
import ru.blockscan.app.core.PlanResult
import ru.blockscan.app.core.ScanPlanner

class DomainParserTest {
    @Test fun normalizesUrlHostWithoutChangingWww() {
        assertEquals("www.example.com", DomainParser.canonical(" HTTPS://WWW.Example.COM:443/path?q=1#top "))
        assertEquals("example.com", DomainParser.canonical("Example.COM./"))
    }

    @Test fun convertsIdnToAscii() {
        assertEquals("xn--e1afmkfd.xn--p1ai", DomainParser.canonical("пример.рф"))
    }

    @Test fun rejectsMalformedHostAndNumericLabels() {
        listOf("999.1.1.1", "example.123", "-bad.example", "a..example", "http://user@example.com").forEach {
            assertNull("accepted $it", DomainParser.canonical(it))
        }
    }

    @Test fun massScanRejectsDomainsWithActionableMessage() {
        val cidr = ScanPlanner.fromCidr("discord.com", excludeEdges = false) as PlanResult.Error
        assertEquals("Домены можно проверять только в одиночном режиме", cidr.message)
        val list = ScanPlanner.fromList("1.1.1.1 discord.com") as PlanResult.Error
        assertEquals("Домены можно проверять только в одиночном режиме", list.message)
    }
}
