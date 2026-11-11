// A library management system exercising many language features at once: sealed interfaces, typealiases, inline classes,
// companion factories, nested and inner classes, local functions, labeled loops, operator invoke, Result, destructuring,
// property accessors, lateinit, object singletons, interface default methods and generic constraints.

typealias Isbn = String
typealias Predicate<T> = (T) -> Boolean

@JvmInline
value class MemberId(val value: Int) {
    override fun toString() = "M" + value.toString().padStart(3, '0')
}

sealed interface Media {
    val isbn: Isbn
    val title: String
    val loanDays: Int get() = 14

    data class Book(override val isbn: Isbn, override val title: String, val author: String, val pages: Int) : Media
    data class Dvd(override val isbn: Isbn, override val title: String, val minutes: Int) : Media {
        override val loanDays: Int get() = 7
    }
    data class Magazine(override val isbn: Isbn, override val title: String, val issue: Int) : Media {
        override val loanDays = 3
    }
}

sealed class LibraryError(message: String) : Exception(message) {
    class NotFound(isbn: Isbn) : LibraryError("no such item: $isbn")
    class AlreadyLoaned(val title: String, val to: MemberId) : LibraryError("'$title' is already loaned to $to")
    class LimitReached(val member: MemberId, val limit: Int) : LibraryError("$member reached the limit of $limit loans")
    class Blocked(val member: MemberId, val fine: Int) : LibraryError("$member owes $fine cents")
}

class Member(val id: MemberId, val name: String) {
    var fineCents = 0
    lateinit var joined: String
    val loans = ArrayList<Loan>()
    val canBorrow get() = fineCents < 500 && loans.size < LIMIT

    companion object {
        const val LIMIT = 3
        fun create(counter: Counter, name: String, joined: String) = Member(MemberId(counter()), name).also { it.joined = joined }
    }
}

class Counter(private var n: Int = 0) {
    operator fun invoke(): Int = ++n
}

data class Loan(val media: Media, val member: MemberId, val dayOut: Int) {
    fun due() = dayOut + media.loanDays
}

object Fees {
    const val PER_DAY = 25
    fun forLate(daysLate: Int) = if (daysLate <= 0) 0 else daysLate * PER_DAY
}

class Library {
    private val catalog = LinkedHashMap<Isbn, Media>()
    private val members = LinkedHashMap<MemberId, Member>()
    private val loaned = HashMap<Isbn, Loan>()
    private val ids = Counter()
    var today = 1
    val ledger = ArrayList<String>()

    inner class Report {
        fun summary() = "day $today: ${catalog.size} items, ${loaned.size} loaned, ${members.size} members"
        fun overdue() = loaned.values.filter { today > it.due() }.map { "${it.media.title} (${today - it.due()}d late)" }
    }

    fun add(vararg items: Media) = items.forEach { catalog[it.isbn] = it }
    fun join(name: String): Member = Member.create(ids, name, "day $today").also { members[it.id] = it }

    fun lend(isbn: Isbn, id: MemberId): Result<Loan> = runCatching {
        val media = catalog[isbn] ?: throw LibraryError.NotFound(isbn)
        val m = members.getValue(id)
        loaned[isbn]?.let { throw LibraryError.AlreadyLoaned(media.title, it.member) }
        if (m.fineCents >= 500) throw LibraryError.Blocked(id, m.fineCents)
        if (m.loans.size >= Member.LIMIT) throw LibraryError.LimitReached(id, Member.LIMIT)
        Loan(media, id, today).also {
            loaned[isbn] = it
            m.loans.add(it)
            ledger.add("day $today: $id borrowed '${media.title}', due day ${it.due()}")
        }
    }

    fun giveBack(isbn: Isbn): Int {
        val loan = loaned.remove(isbn) ?: return 0
        val m = members.getValue(loan.member)
        m.loans.remove(loan)
        val fine = Fees.forLate(today - loan.due())
        m.fineCents += fine
        ledger.add("day $today: ${loan.member} returned '${loan.media.title}'" + if (fine > 0) ", fine $fine" else "")
        return fine
    }

    fun search(p: Predicate<Media>): List<Media> = catalog.values.filter(p)
    fun member(id: MemberId) = members.getValue(id)
    fun report() = Report()
}

fun describe(m: Media): String = when (m) {
    is Media.Book -> "book '${m.title}' by ${m.author}, ${m.pages} pages" + when (m.pages) {
        in 0..100 -> " (short)"
        in 101..400 -> ""
        else -> " (long)"
    }
    is Media.Dvd -> "dvd '${m.title}' (${m.minutes / 60}h${(m.minutes % 60).toString().padStart(2, '0')})"
    is Media.Magazine -> "magazine '${m.title}' #${m.issue}"
}

fun main() {
    val lib = Library()
    lib.add(
        Media.Book("111", "Dune", "Herbert", 612), Media.Book("222", "Emma", "Austen", 320), Media.Book("333", "Ubik", "Dick", 96),
        Media.Dvd("444", "Alien", 117), Media.Magazine("555", "Byte", 12), Media.Book("666", "Solaris", "Lem", 204),
    )
    val ann = lib.join("ann")
    val ben = lib.join("ben")
    println("members: ${ann.id} ${ann.name} (${ann.joined}), ${ben.id} ${ben.name}")
    lib.search { true }.forEach { println("  " + describe(it)) }
    println(lib.report().summary())

    fun attempt(isbn: Isbn, who: Member) {
        val r = lib.lend(isbn, who.id)
        r.onSuccess { println("  ${who.name} got '${it.media.title}' until day ${it.due()}") }
            .onFailure { println("  ${who.name} failed: ${(it as? LibraryError)?.message ?: it.toString()}") }
    }
    attempt("111", ann)
    attempt("111", ben)
    attempt("999", ben)
    attempt("444", ann)
    attempt("555", ann)
    attempt("222", ann)
    attempt("333", ben)
    lib.today = 9
    println("after a week: " + lib.report().overdue())
    println("fines paid: " + listOf("444", "555").map { lib.giveBack(it) })
    println("giving back unknown: ${lib.giveBack("000")}")
    lib.today = 30
    println(lib.report().overdue())
    println("fines: " + listOf("111", "222").map { lib.giveBack(it) } + " -> ann owes ${lib.member(ann.id).fineCents}")
    attempt("666", ann)
    println("ann can borrow: ${ann.canBorrow}, ben can borrow: ${ben.canBorrow}")
    outer@ for (m in listOf(ann, ben)) {
        for (l in m.loans) {
            println("${m.name} still has ${l.media.title}")
            if (l.media.title == "Ubik") break@outer
        }
    }
    println("-- ledger")
    lib.ledger.forEach { println(it) }
    val (books, others) = lib.search { true }.partition { it is Media.Book }
    println("books ${books.size}, others ${others.size}; long books: " + lib.search { it is Media.Book && it.pages > 300 }.map { it.title })
    println(lib.search { it.loanDays < 10 }.map { "${it.title}:${it.loanDays}" })
    println(Media.Book("1", "t", "a", 1) == Media.Book("1", "t", "a", 1))
    println(Media.Dvd("4", "x", 90).copy(title = "y"))
    println(MemberId(7).let { "$it ${it.value}" })
}
