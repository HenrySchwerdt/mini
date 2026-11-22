// use-site variance: in/out projections, copying between arrays and lists
open class Animal(val name: String)
class Dog(name: String) : Animal(name)
fun copy(from: Array<out Animal>, to: Array<in Animal>) { for (i in from.indices) to[i] = from[i] }
fun fill(dst: MutableList<in Dog>, n: Int) { for (i in 0 until n) dst.add(Dog("d$i")) }
fun names(src: List<out Animal>): String = src.joinToString { it.name }
class Box<T>(var v: T)
fun readOnly(b: Box<out Animal>): String = b.v.name
fun writeOnly(b: Box<in Dog>, d: Dog) { b.v = d }
fun main() {
    val dogs = arrayOf(Dog("a"), Dog("b"))
    val animals = arrayOfNulls<Animal>(2)
    copy(dogs, animals)
    println(animals.map { it?.name })
    val sink = ArrayList<Animal>()
    fill(sink, 3)
    println(names(sink))
    val any = ArrayList<Any>()
    fill(any, 2)
    println(any.size)
    val b = Box<Animal>(Animal("x"))
    println(readOnly(b))
    writeOnly(b, Dog("y"))
    println(b.v.name)
    val bd: Box<Dog> = Box(Dog("z"))
    println(readOnly(bd))
}
