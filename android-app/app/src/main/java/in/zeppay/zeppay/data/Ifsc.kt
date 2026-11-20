package `in`.zeppay.zeppay.data

import java.io.InputStream
import java.util.Locale

/**
 * IFSC: an 11-character code for one bank branch. 4 letters (the bank), a zero, then 6
 * letters or digits (the branch). Checking the format only catches typos: a well-formed
 * code may not exist, and a listed one may have closed. Only the bank's own reply proves it.
 */
object Ifsc {
    private val FORMAT = Regex("^[A-Z]{4}0[A-Z0-9]{6}$")

    fun normalize(v: String): String = v.filterNot { it.isWhitespace() }.uppercase(Locale.US)
    fun isValidFormat(v: String): Boolean = FORMAT.matches(normalize(v))
    fun bankCode(v: String): String? = normalize(v).takeIf { it.length >= 4 && it.take(4).all { c -> c in 'A'..'Z' } }?.take(4)

    /** A plain-words reason the format is wrong, or null when it is well-formed. */
    fun problem(v: String): String? {
        val s = normalize(v)
        return when {
            s.isEmpty() -> "Enter the 11-character IFSC."
            s.any { !(it in 'A'..'Z' || it in '0'..'9') } -> "IFSC has only letters and numbers."
            s.length != 11 -> "IFSC is 11 characters; this has ${s.length}."
            !s.take(4).all { it in 'A'..'Z' } -> "The first 4 characters are letters (the bank), like SBIN."
            s[4] != '0' -> "The 5th character is always zero (0)."
            else -> null
        }
    }
}

data class Bank(val code: String, val name: String, val branches: Int)
data class Branch(val ifsc: String, val branch: String, val centre: String, val district: String, val state: String) {
    val place: String get() = listOf(centre, district.takeIf { !it.equals(centre, true) }, state).filter { !it.isNullOrBlank() }.joinToString(", ")
}

/** Where the bundled list came from, and what it can't promise. Shown in the app. */
data class DirectoryInfo(
    val source: String, val sourceUrl: String, val licence: String, val release: String,
    val rbiDate: String, val branches: Int, val banks: Int, val limits: String,
)

/**
 * The offline bank + branch directory bundled in the APK (assets/ifsc, built by
 * tools/ifsc/build_ifsc.py). Read-only, no network. It only helps the user *pick* an IFSC to
 * fill in; it never links, verifies or checks anyone's bank account.
 *
 * Files are read through [open] (assets on the phone, test resources in unit tests). One
 * bank's branches are loaded at a time to keep memory small on low-end phones.
 */
class IfscDirectory(private val open: (String) -> InputStream?) {
    private fun lines(name: String): List<String> =
        open(name)?.use { it.bufferedReader(Charsets.UTF_8).readLines() }.orEmpty().filter { it.isNotBlank() }

    val info: DirectoryInfo? by lazy {
        val m = lines("ifsc/meta.tsv").mapNotNull { l -> l.split('\t', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
        if (m.isEmpty()) null else DirectoryInfo(
            m["source"].orEmpty(), m["sourceUrl"].orEmpty(), m["licence"].orEmpty(), m["release"].orEmpty(),
            m["rbiDate"].orEmpty(), m["branches"]?.toIntOrNull() ?: 0, m["banks"]?.toIntOrNull() ?: 0, m["limits"].orEmpty(),
        )
    }

    /** False when no list is bundled; the app then only checks the format. */
    val available: Boolean get() = banks.isNotEmpty()

    val banks: List<Bank> by lazy {
        lines("ifsc/banks.tsv").mapNotNull { l ->
            val p = l.split('\t')
            if (p.size < 2 || p[0].length != 4) null else Bank(p[0], p[1], p.getOrNull(2)?.toIntOrNull() ?: 0)
        }
    }

    fun bank(code: String): Bank? = banks.firstOrNull { it.code == code.uppercase(Locale.US) }

    /** Banks whose name or 4-letter code matches every word of [q]. Code matches first, then name starts. */
    fun searchBanks(q: String, limit: Int = 30): List<Bank> {
        val words = words(q)
        if (words.isEmpty()) return banks.sortedByDescending { it.branches }.take(limit)
        val code = q.trim().uppercase(Locale.US)
        return banks.filter { b -> words.all { w -> b.name.lowercase(Locale.US).contains(w) || b.code.lowercase(Locale.US).startsWith(w) } }
            .sortedWith(compareBy<Bank>({ it.code != code }, { !it.name.lowercase(Locale.US).startsWith(words.first()) }, { -it.branches }))
            .take(limit)
    }

    private var cachedCode: String? = null
    private var cached: List<Branch> = emptyList()

    @Synchronized fun branches(code: String): List<Branch> {
        val c = code.uppercase(Locale.US)
        if (c == cachedCode) return cached
        if (c.length != 4 || !c.all { it in 'A'..'Z' }) return emptyList()
        val list = lines("ifsc/b/$c.tsv").mapNotNull { l ->
            val p = l.split('\t')
            if (p.size < 5 || !Ifsc.isValidFormat(p[0])) null else Branch(p[0], p[1], p[2], p[3], p[4])
        }
        cachedCode = c; cached = list
        return list
    }

    /** Branches of [bankCode] matching every word of [q] in branch, place, state or IFSC. */
    fun searchBranches(bankCode: String, q: String, limit: Int = 40): List<Branch> {
        val all = branches(bankCode)
        val words = words(q)
        if (words.isEmpty()) return all.take(limit)
        return all.filter { b ->
            val hay = "${b.branch} ${b.centre} ${b.district} ${b.state} ${b.ifsc}".lowercase(Locale.US)
            words.all { hay.contains(it) }
        }.sortedBy { !it.branch.lowercase(Locale.US).startsWith(words.first()) }.take(limit)
    }

    /** The listed branch for a well-formed [ifsc], or null (not listed: may still be valid). */
    fun lookup(ifsc: String): Branch? {
        val s = Ifsc.normalize(ifsc)
        if (!Ifsc.isValidFormat(s)) return null
        return branches(s.take(4)).firstOrNull { it.ifsc == s }
    }

    private fun words(q: String) = q.lowercase(Locale.US).split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
}
