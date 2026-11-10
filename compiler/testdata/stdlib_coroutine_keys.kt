import kotlin.coroutines.*

object BaseKey : CoroutineContext.Key<BaseElem>

open class BaseElem : AbstractCoroutineContextElement(BaseKey) {
    override fun toString(): String = "Base"
}

@OptIn(ExperimentalStdlibApi::class)
object DerivedKey : AbstractCoroutineContextKey<BaseElem, DerivedElem>(BaseKey, { it as? DerivedElem })

class DerivedElem : BaseElem() {
    override fun toString(): String = "Derived"
}

object OtherKey : CoroutineContext.Key<OtherElem>

class OtherElem : AbstractCoroutineContextElement(OtherKey) {
    override fun toString(): String = "Other"
}

@OptIn(ExperimentalStdlibApi::class)
fun main() {
    val d = DerivedElem()
    val b = BaseElem()
    println(d[BaseKey] === d)
    println(d[DerivedKey] === d)
    println(b[BaseKey] === b)
    println(b[DerivedKey])
    println(d[OtherKey])
    println(d.minusKey(DerivedKey) === EmptyCoroutineContext)
    println(d.minusKey(BaseKey) === EmptyCoroutineContext)
    println(b.minusKey(DerivedKey) === b)
    println(b.minusKey(BaseKey) === EmptyCoroutineContext)
    println(d.minusKey(OtherKey) === d)
    val o = OtherElem()
    val ctx = d + o
    println(ctx)
    println(ctx[DerivedKey])
    println(ctx[BaseKey])
    println(ctx[OtherKey])
    println(ctx.minusKey(DerivedKey))
    println(ctx.minusKey(BaseKey))
    println(ctx.minusKey(OtherKey))
    val ctx2 = b + o
    println(ctx2[DerivedKey])
    println(ctx2.minusKey(DerivedKey))
    println(d.getPolymorphicElement(DerivedKey) === d)
    println(b.getPolymorphicElement(DerivedKey))
    println(d.minusPolymorphicKey(DerivedKey) === EmptyCoroutineContext)
    println(b.minusPolymorphicKey(DerivedKey) === b)
    println(ctx.fold("") { acc, e -> acc + e })
}
