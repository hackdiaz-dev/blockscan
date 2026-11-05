package ru.blockscan.app.core

import java.math.BigInteger
import java.net.IDN

enum class IpVersion(val bits: Int) { V4(32), V6(128) }

data class IpAddr(val version: IpVersion, val value: BigInteger) {
    override fun toString(): String = IpFormat.format(version, value)
}

object IpFormat {
    fun format(version: IpVersion, value: BigInteger): String =
        if (version == IpVersion.V4) v4(value) else v6(value)

    fun v4(value: BigInteger): String {
        val n = value.toLong()
        return "${(n shr 24) and 255}.${(n shr 16) and 255}.${(n shr 8) and 255}.${n and 255}"
    }

    /** Каноническая запись RFC 5952: нижний регистр, самая длинная серия нулей (>= 2) сворачивается в "::". */
    fun v6(value: BigInteger): String {
        val mask = BigInteger.valueOf(0xFFFF)
        val groups = IntArray(8) { i -> value.shiftRight(16 * (7 - i)).and(mask).toInt() }
        var bestStart = -1
        var bestLen = 0
        var i = 0
        while (i < 8) {
            if (groups[i] == 0) {
                var j = i
                while (j < 8 && groups[j] == 0) j++
                if (j - i > bestLen) { bestStart = i; bestLen = j - i }
                i = j
            } else {
                i++
            }
        }
        if (bestLen < 2) bestStart = -1
        val sb = StringBuilder()
        var k = 0
        while (k < 8) {
            if (k == bestStart) {
                sb.append("::")
                k += bestLen
                continue
            }
            if (sb.isNotEmpty() && sb[sb.length - 1] != ':') sb.append(':')
            sb.append(groups[k].toString(16))
            k++
        }
        return sb.toString()
    }
}

object IpParser {
    fun parse(text: String): IpAddr? {
        val s = text.trim()
        if (s.isEmpty()) return null
        return if (s.contains(':')) {
            parseV6(s)?.let { IpAddr(IpVersion.V6, it) }
        } else {
            parseV4(s)?.let { IpAddr(IpVersion.V4, it) }
        }
    }

    /** Строгий разбор: четыре десятичных октета, без ведущих нулей (исключает восьмеричную неоднозначность). */
    fun parseV4(s: String): BigInteger? {
        val parts = s.split('.')
        if (parts.size != 4) return null
        var v = 0L
        for (p in parts) {
            if (p.isEmpty() || p.length > 3 || !p.all { it in '0'..'9' }) return null
            if (p.length > 1 && p[0] == '0') return null
            val n = p.toInt()
            if (n > 255) return null
            v = v * 256 + n
        }
        return BigInteger.valueOf(v)
    }

    fun parseV6(input: String): BigInteger? {
        var s = input
        if (s.isEmpty() || s.contains('%') || !s.contains(':')) return null
        if (s.contains('.')) {
            val lastColon = s.lastIndexOf(':')
            val v4 = parseV4(s.substring(lastColon + 1)) ?: return null
            val n = v4.toLong()
            val hi = ((n shr 16) and 0xFFFF).toInt()
            val lo = (n and 0xFFFF).toInt()
            s = s.substring(0, lastColon + 1) + hi.toString(16) + ":" + lo.toString(16)
        }
        if (s.contains(":::")) return null
        val dbl = s.indexOf("::")
        if (dbl != s.lastIndexOf("::")) return null
        val groups: List<Int>
        if (dbl >= 0) {
            val head = s.substring(0, dbl)
            val tail = s.substring(dbl + 2)
            val h = if (head.isEmpty()) emptyList() else head.split(':')
            val t = if (tail.isEmpty()) emptyList() else tail.split(':')
            if (h.size + t.size > 7) return null
            val hv = ArrayList<Int>()
            for (x in h) hv.add(hexGroup(x) ?: return null)
            val tv = ArrayList<Int>()
            for (x in t) tv.add(hexGroup(x) ?: return null)
            groups = hv + List(8 - hv.size - tv.size) { 0 } + tv
        } else {
            val p = s.split(':')
            if (p.size != 8) return null
            val gv = ArrayList<Int>()
            for (x in p) gv.add(hexGroup(x) ?: return null)
            groups = gv
        }
        var result = BigInteger.ZERO
        for (g in groups) result = result.shiftLeft(16).or(BigInteger.valueOf(g.toLong()))
        return result
    }

    private fun hexGroup(g: String): Int? {
        if (g.isEmpty() || g.length > 4) return null
        if (!g.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return g.toInt(16)
    }
}

/** Strict public DNS host name parser used for the single-target domain check. */
object DomainParser {
    fun canonical(text: String): String? {
        var input = text.trim()
        if (input.isEmpty() || input.any { it.isWhitespace() }) return null
        if (input.contains("://")) {
            val scheme = input.substringBefore("://").lowercase()
            if (scheme != "http" && scheme != "https") return null
            input = input.substringAfter("://")
        }
        input = input.substringBefore('/').substringBefore('?').substringBefore('#')
        if (input.contains('@') || input.startsWith('[')) return null
        if (input.count { it == ':' } == 1) {
            val port = input.substringAfter(':')
            val portNumber = port.toIntOrNull() ?: return null
            if (port.isEmpty() || !port.all(Char::isDigit) || portNumber !in 1..65535) return null
            input = input.substringBefore(':')
        } else if (':' in input) return null
        input = input.removeSuffix(".")
        if (input.isEmpty() || input.length > 253) return null
        val ascii = runCatching { IDN.toASCII(input, IDN.USE_STD3_ASCII_RULES).lowercase() }.getOrNull() ?: return null
        if (ascii.length > 253) return null
        val labels = ascii.split('.')
        if (labels.size < 2 || labels.any { it.isEmpty() || it.length > 63 }) return null
        if (labels.any { label -> label.all(Char::isDigit) }) return null
        return ascii.takeIf { labels.all { label -> label.first().isLetterOrDigit() && label.last().isLetterOrDigit() } }
    }
}

data class Cidr(val version: IpVersion, val network: BigInteger, val prefix: Int) {
    val hostBits: Int get() = version.bits - prefix
    val size: BigInteger get() = BigInteger.ONE.shiftLeft(hostBits)
    val last: BigInteger get() = network.add(size).subtract(BigInteger.ONE)

    fun contains(ip: IpAddr): Boolean =
        ip.version == version && ip.value >= network && ip.value <= last

    override fun toString(): String = "${IpFormat.format(version, network)}/$prefix"

    companion object {
        fun parse(text: String): ParsedCidr? {
            val t = text.trim()
            val slash = t.indexOf('/')
            if (slash <= 0 || slash != t.lastIndexOf('/')) return null
            val addr = IpParser.parse(t.substring(0, slash)) ?: return null
            val ps = t.substring(slash + 1)
            if (ps.isEmpty() || ps.length > 3 || !ps.all { it in '0'..'9' }) return null
            val prefix = ps.toInt()
            if (prefix > addr.version.bits) return null
            val hostMask = BigInteger.ONE.shiftLeft(addr.version.bits - prefix).subtract(BigInteger.ONE)
            val network = addr.value.andNot(hostMask)
            return ParsedCidr(Cidr(addr.version, network, prefix), network != addr.value)
        }
    }
}

data class ParsedCidr(val cidr: Cidr, val hadHostBits: Boolean)

/** Результат калькулятора подсетей. Все значения считаются локально. */
data class SubnetInfo(
    val cidr: Cidr,
    val network: String,
    val mask: String,
    val broadcast: String?,
    val firstAddress: String,
    val lastAddress: String,
    val firstHost: String?,
    val lastHost: String?,
    val total: BigInteger,
)

object SubnetCalc {
    fun info(c: Cidr): SubnetInfo {
        val bits = c.version.bits
        val all = BigInteger.ONE.shiftLeft(bits).subtract(BigInteger.ONE)
        val hostMask = BigInteger.ONE.shiftLeft(c.hostBits).subtract(BigInteger.ONE)
        val mask = IpFormat.format(c.version, all.xor(hostMask))
        val v4 = c.version == IpVersion.V4
        val hasHosts = c.hostBits >= 2
        return SubnetInfo(
            cidr = c,
            network = IpFormat.format(c.version, c.network),
            mask = mask,
            broadcast = if (v4) IpFormat.format(c.version, c.last) else null,
            firstAddress = IpFormat.format(c.version, c.network),
            lastAddress = IpFormat.format(c.version, c.last),
            firstHost = if (v4 && hasHosts) IpFormat.format(c.version, c.network.add(BigInteger.ONE)) else null,
            lastHost = if (v4 && hasHosts) IpFormat.format(c.version, c.last.subtract(BigInteger.ONE)) else null,
            total = c.size,
        )
    }
}

object IpClassifier {
    private val nonPublic: List<Cidr> = listOf(
        "0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8", "169.254.0.0/16",
        "172.16.0.0/12", "192.0.0.0/24", "192.0.2.0/24", "192.168.0.0/16", "198.18.0.0/15",
        "198.51.100.0/24", "203.0.113.0/24", "224.0.0.0/4", "240.0.0.0/4",
        "::/128", "::1/128", "fc00::/7", "fe80::/10", "ff00::/8", "2001:db8::/32",
    ).mapNotNull { Cidr.parse(it)?.cidr }

    fun isNonPublic(ip: IpAddr): Boolean = nonPublic.any { it.contains(ip) }
}

/** Набор адресов к проверке. Последовательность ленивая: большие диапазоны не загружаются в память. */
class TargetSet(val count: Long, val description: String, private val gen: () -> Sequence<String>) {
    fun sequence(): Sequence<String> = gen()
}

sealed interface PlanResult {
    data class Ok(val set: TargetSet, val warnings: List<String> = emptyList()) : PlanResult
    data class Error(val message: String) : PlanResult
}

object ScanPlanner {
    /** Жёсткий предел: защита от переполнения и случайного запуска огромных диапазонов. */
    const val MAX_ADDRESSES = 65_536L

    private fun tooBig(count: BigInteger): PlanResult.Error =
        PlanResult.Error("Слишком много адресов: $count. Максимум за один запуск: $MAX_ADDRESSES.")

    private fun rangeSet(version: IpVersion, start: BigInteger, end: BigInteger, description: String): TargetSet {
        val count = end.subtract(start).add(BigInteger.ONE).toLong()
        return TargetSet(count, description) {
            sequence {
                var cur = start
                while (cur <= end) {
                    yield(IpFormat.format(version, cur))
                    cur = cur.add(BigInteger.ONE)
                }
            }
        }
    }

    /** Подсеть CIDR (или одиночный IP). excludeEdges убирает адрес сети и broadcast только для IPv4 с префиксом <= /30. */
    fun fromCidr(text: String, excludeEdges: Boolean): PlanResult {
        val t = text.trim()
        if (t.isEmpty()) return PlanResult.Error("Введите подсеть CIDR или IP-адрес")
        if (DomainParser.canonical(t) != null) return PlanResult.Error("Домены можно проверять только в одиночном режиме")
        val warnings = ArrayList<String>()
        val cidr: Cidr
        if (t.contains('/')) {
            val p = Cidr.parse(t) ?: return PlanResult.Error("Некорректная подсеть CIDR")
            cidr = p.cidr
            if (p.hadHostBits) warnings += "Адрес приведён к адресу сети: $cidr"
        } else {
            val ip = IpParser.parse(t) ?: return PlanResult.Error("Некорректный IP-адрес")
            cidr = Cidr(ip.version, ip.value, ip.version.bits)
        }
        var start = cidr.network
        var end = cidr.last
        if (excludeEdges) {
            if (cidr.version == IpVersion.V4 && cidr.hostBits >= 2) {
                start = start.add(BigInteger.ONE)
                end = end.subtract(BigInteger.ONE)
            } else {
                warnings += "Исключение сетевого и broadcast-адреса не применяется для $cidr"
            }
        }
        val count = end.subtract(start).add(BigInteger.ONE)
        if (count > BigInteger.valueOf(MAX_ADDRESSES)) return tooBig(count)
        return PlanResult.Ok(rangeSet(cidr.version, start, end, "$cidr"), warnings)
    }

    fun fromRange(from: String, to: String): PlanResult {
        if (DomainParser.canonical(from) != null || DomainParser.canonical(to) != null) {
            return PlanResult.Error("Домены можно проверять только в одиночном режиме")
        }
        val a = IpParser.parse(from) ?: return PlanResult.Error("Некорректный начальный IP")
        val b = IpParser.parse(to) ?: return PlanResult.Error("Некорректный конечный IP")
        if (a.version != b.version) return PlanResult.Error("Начало и конец диапазона должны быть одной версии IP")
        if (a.value > b.value) return PlanResult.Error("Начальный IP больше конечного")
        val count = b.value.subtract(a.value).add(BigInteger.ONE)
        if (count > BigInteger.valueOf(MAX_ADDRESSES)) return tooBig(count)
        return PlanResult.Ok(rangeSet(a.version, a.value, b.value, "$a – $b"))
    }

    /** Список IP через пробел, запятую, точку с запятой или перенос строки. Дубликаты убираются, порядок сохраняется. */
    fun fromList(text: String): PlanResult {
        val tokens = text.split(Regex("[\\s,;]+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return PlanResult.Error("Список пуст")
        if (tokens.any { DomainParser.canonical(it) != null }) {
            return PlanResult.Error("Домены можно проверять только в одиночном режиме")
        }
        val ok = LinkedHashSet<String>()
        var invalid = 0
        val badSamples = ArrayList<String>()
        for (tk in tokens) {
            val ip = IpParser.parse(tk)
            if (ip == null) {
                invalid++
                if (badSamples.size < 3) badSamples += tk
            } else {
                ok += ip.toString()
            }
        }
        if (ok.isEmpty()) return PlanResult.Error("В списке нет корректных IP-адресов")
        if (ok.size > MAX_ADDRESSES) return tooBig(BigInteger.valueOf(ok.size.toLong()))
        val warnings = ArrayList<String>()
        if (invalid > 0) warnings += "Пропущено некорректных записей: $invalid (например: ${badSamples.joinToString(", ")})"
        val items = ok.toList()
        return PlanResult.Ok(TargetSet(items.size.toLong(), "список из ${items.size} IP") { items.asSequence() }, warnings)
    }
}
