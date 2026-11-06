// `super.f()` without a qualifier: the member is looked up in the superclass and in the interfaces; `super@Outer.f()` in inner classes

open class Base {
    open fun baseFun(): String = "Base.baseFun"
    open fun unambiguous(): String = "Base.unambiguous"
    open val baseProp: String get() = "Base.baseProp"
}

interface Interface {
    fun interfaceFun(): String = "Interface.interfaceFun"
    fun unambiguous(): String
}

interface Other

interface DerivedInterface : Interface, Other {
    override fun interfaceFun(): String = "DerivedInterface.interfaceFun"
    override fun unambiguous(): String = "DerivedInterface.unambiguous"
    fun fromSuper(): String = super.interfaceFun()
}

class Derived : Base(), Interface {
    override fun baseFun(): String = "Derived.baseFun"
    override fun unambiguous(): String = "Derived.unambiguous"
    override fun interfaceFun(): String = "Derived.interfaceFun"
    override val baseProp: String get() = "Derived.baseProp"

    fun callsBase(): String = super.baseFun()
    fun callsUnambiguous(): String = super.unambiguous()
    fun getsBaseProp(): String = super.baseProp
    fun callsInterface(): String = super.interfaceFun()
    fun callsToString(): String = super.toString().length.toString()

    inner class In {
        fun viaOuterSuper(): String = super@Derived.interfaceFun() + "/" + super@Derived.baseFun()
    }
}

class Impl : DerivedInterface {
    override fun interfaceFun(): String = "Impl"
    fun both(): String = fromSuper() + "," + super.interfaceFun()
}

fun main() {
    val d = Derived()
    println(d.callsBase())
    println(d.callsUnambiguous())
    println(d.getsBaseProp())
    println(d.callsInterface())
    println(d.callsToString() != "0")
    println(d.In().viaOuterSuper())
    println(Impl().both())
}
