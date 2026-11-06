// A C pointer (also a nullable one) as the type argument of a non-reified inline function: it cannot be boxed into the erased `Any?`
// (`memScoped { f(..) }` returning a `CPointer<..>?`), and an exhaustive `when` over an enum keeps its pointer type.
@file:CInclude("stdlib.h")

import kotlin.native.CName
import kotlinx.cinterop.*

@CName("malloc")
external fun cMalloc(n: Long): COpaquePointer?

@CName("free")
external fun cFree(p: COpaquePointer?)

typealias TypeRef = COpaquePointer

enum class Kind { A, B, C, D }

inline fun <R> runIt(block: () -> R): R = block()

class Types(private val ta: TypeRef, private val tb: TypeRef, private val tc: TypeRef) {
    private fun ty(k: Kind): TypeRef = when (k) {
        Kind.A -> ta
        Kind.B -> tb
        Kind.C -> tc
        Kind.D -> ta
    }

    fun same(x: Kind, y: Kind): Boolean = ty(x) == ty(y)
}

fun named(size: Long): TypeRef {
    val t = memScoped { cMalloc(size) }
    if (t == null) throw IllegalStateException("no memory")
    return t
}

fun main() {
    val a = named(8)
    val b = named(8)
    val c = runIt { cMalloc(8) }
    println(c != null)
    val t = Types(a, b, c!!)
    println(t.same(Kind.A, Kind.D))
    println(t.same(Kind.A, Kind.B))
    println(t.same(Kind.B, Kind.C))
    cFree(a)
    cFree(b)
    cFree(c)
}
