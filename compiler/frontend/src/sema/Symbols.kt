package mini.frontend.sema

import mini.frontend.*

/*
 * Symbols of the semantic model. A symbol is created from the AST by [Collector]; everything that needs other
 * symbols (supertypes, signatures, implicit return types) is filled lazily by [Sema] with an in-progress marker for
 * cycle detection.
 */

object Ids {
    private var n = 0
    fun next(): Int {
        n++
        return n
    }
}

sealed class Sym(val name: String) {
    var start: Int = 0
    val id: Int = Ids.next()
}

class FileInfo(val path: String, val text: String, val ast: KtFile, val pkg: String) {
    lateinit var scope: FileScope
    var index: Int = 0
    /** An API stub of a precompiled library: the bodies [mini.frontend.StubRules] does not keep are not checked. */
    var declOnly: Boolean = false
}

class TypeParamSym(name: String, val variance: Variance, val reified: Boolean, val decl: TypeParam?, val index: Int) : Sym(name) {
    /** upper bounds (empty means `Any?`), resolved lazily */
    var bounds: List<Type> = emptyList()
    var boundsState: Int = 0
    lateinit var scope: Scope
    var constraintsDecl: List<TypeConstraint> = emptyList()
    var ownerDescription: String = ""
}

class ClassSym(
    name: String,
    val kind: ClassKind,
    val pkg: String,
    val outer: ClassSym?,
    val decl: ClassDecl?,
    val file: FileInfo?,
) : Sym(name) {
    var isLocal = false
    var isAnonymous = false
    var isEnumEntryBody = false
    var checked = false
    /** >= 0 for the synthetic KFunctionN / KPropertyN classes of callable references (their function arity) */
    var fnArity = -1
    var samCtor: FunSym? = null

    /** scope the declaration appears in (type resolution of the header starts here) */
    lateinit var scope: Scope
    val typeParams = ArrayList<TypeParamSym>()
    var supertypeState = 0
    var supertypes: List<Type> = emptyList()
    val nested = LinkedHashMap<String, ClassSym>()
    val aliases = LinkedHashMap<String, TypeAliasSym>()
    val funs = LinkedHashMap<String, ArrayList<FunSym>>()
    val props = LinkedHashMap<String, ArrayList<PropSym>>()
    val ctors = ArrayList<FunSym>()
    val entries = ArrayList<EnumEntrySym>()
    var companion: ClassSym? = null
    var primaryCtor: FunSym? = null

    /** the scope of the class body (members, type parameters), created on demand */
    var bodyScope: ClassScope? = null
    var headerScope: Scope? = null
    var membersDone = false
    var thisTypeCache: ClassType? = null

    fun mods(): Modifiers? = decl?.mods
    fun has(k: String): Boolean {
        val m = decl?.mods ?: return false
        return m.has(k)
    }

    val isInterface: Boolean get() = kind == ClassKind.INTERFACE
    val isObject: Boolean get() = kind == ClassKind.OBJECT
    val isEnum: Boolean get() = has("enum")
    val isData: Boolean get() = has("data")
    val isSealed: Boolean get() = has("sealed")
    val isInner: Boolean get() = has("inner")
    val isCompanion: Boolean get() = has("companion")
    val isAbstract: Boolean get() = has("abstract") || kind == ClassKind.INTERFACE
    val isValue: Boolean get() = has("value") || has("inline")
    val isFunInterface: Boolean get() = has("fun")
    val isAnnotation: Boolean get() = has("annotation")

    fun fqName(): String {
        val o = outer
        if (o != null) return o.fqName() + "." + name
        return if (pkg.isEmpty()) name else pkg + "." + name
    }

    /** class name as the oracle renders it */
    fun renderName(): String {
        if (isAnonymous) return if (pkg.isEmpty()) "<anonymous>" else pkg + ".<anonymous>"
        if (isLocal) return "<local>." + name
        return fqName()
    }

    fun thisType(): ClassType {
        val c = thisTypeCache
        if (c != null) return c
        val args = ArrayList<TArg>()
        for (tp in typeParams) args.add(TArg(TypeParamType(tp, false, false), Variance.INV))
        val t = ClassType(this, args, false)
        thisTypeCache = t
        return t
    }

    /** true for classes whose instances can be created / referenced through an implicit receiver in nested position */
    fun isStaticLike(): Boolean = kind == ClassKind.OBJECT
}

class TypeAliasSym(name: String, val pkg: String, val decl: TypeAliasDecl, val outer: ClassSym?, val file: FileInfo?) : Sym(name) {
    val typeParams = ArrayList<TypeParamSym>()
    lateinit var scope: Scope
    var expanded: Type? = null
    var state = 0
}

/** Value parameters, local variables, lambda parameters, loop variables, catch parameters. */
class VarSym(name: String, var type: Type?, val isVar: Boolean, val isParam: Boolean, val declStart: Int) : Sym(name) {
    var decl: Param? = null
    var hasDefault = false
    var isVararg = false
    var isCrossinline = false
    var isNoinline = false
    var varargElement: Type? = null
    /** local `var` assigned inside a lambda: never smart-castable */
    var capturedAndModified = false
    /** true for a `val` without initializer at declaration (assigned later) */
    var isLateInit = false
    var ownerFun: Any? = null
    /** facts that hold when this boolean `val` is true / false (a condition stored in a variable) */
    var condTrue: List<FactAdd>? = null
    var condFalse: List<FactAdd>? = null
    /** the implicit `field` of an accessor */
    var isField = false
}

enum class FunKind { NORMAL, CONSTRUCTOR, LOCAL, ANONYMOUS, SYNTH, FUNCTION_INVOKE }

class FunSym(name: String, val decl: FunDecl?, val owner: ClassSym?, val file: FileInfo?) : Sym(name) {
    var kind = FunKind.NORMAL
    var isPrimary = false
    var secondary: SecondaryCtor? = null
    val typeParams = ArrayList<TypeParamSym>()
    var receiver: Type? = null
    var params: List<VarSym> = emptyList()
    var returnType: Type? = null
    /** 0 nothing, 1 resolving signature, 2 signature done */
    var sigState = 0
    /** 0 nothing, 1 inferring, 2 done (only for implicit return types) */
    var retState = 0
    lateinit var scope: Scope
    var primaryParams: List<Param> = emptyList()
    var primaryMods: Modifiers? = null
    /** declaring class of a synthesized member, for the callee rendering */
    var synthOwnerOverride: ClassSym? = null
    var invokeArity = 0
    var bodyChecked = false
    var invokeSuspend = false

    fun mods(): Modifiers? {
        if (isPrimary) return primaryMods
        if (secondary != null) return secondary?.mods
        return decl?.mods
    }

    fun has(k: String): Boolean {
        val m = mods() ?: return false
        return m.has(k)
    }

    val isOperator: Boolean get() = has("operator")
    val isInfix: Boolean get() = has("infix")
    val isInline: Boolean get() = has("inline")
    val isOverride: Boolean get() = has("override")
    val isAbstract: Boolean get() = has("abstract") || (owner != null && owner.isInterface && decl != null && decl.body == null && kind == FunKind.NORMAL)
    val isExtension: Boolean get() = decl?.receiver != null
    val isStatic: Boolean get() = owner == null
}

class PropSym(name: String, val decl: PropertyDecl?, val ctorParam: Param?, val owner: ClassSym?, val file: FileInfo?) : Sym(name) {
    var isVar = false
    val typeParams = ArrayList<TypeParamSym>()
    var receiver: Type? = null
    var type: Type? = null
    var sigState = 0
    /** 0 nothing, 1 inferring, 2 done */
    var typeState = 0
    lateinit var scope: Scope
    var synthetic = false
    var isLocal = false
    var initChecked = false

    fun mods(): Modifiers? {
        if (decl != null) return decl.mods
        return ctorParam?.mods
    }

    fun has(k: String): Boolean {
        val m = mods() ?: return false
        return m.has(k)
    }

    val isExtension: Boolean get() = decl?.receiver != null
    val hasCustomGetter: Boolean get() = decl?.getter != null && decl.getter.body != null
    val isDelegated: Boolean get() = decl?.delegate != null
    val isOverride: Boolean get() = has("override")
}

class EnumEntrySym(name: String, val owner: ClassSym, val decl: EnumEntryDecl) : Sym(name)

// ---- scopes -----------------------------------------------------------------------------------------------------

/** A package: its top-level classifiers, functions and properties. */
class PackageInfo(val name: String) {
    val classes = LinkedHashMap<String, ClassSym>()
    val aliases = LinkedHashMap<String, TypeAliasSym>()
    val funs = LinkedHashMap<String, ArrayList<FunSym>>()
    val props = LinkedHashMap<String, ArrayList<PropSym>>()
}

sealed class Scope(val parent: Scope?)

/** One resolved import: simple (`a.b.C`, possibly aliased) or star. */
class ImportInfo(val path: String, val star: Boolean, val alias: String?) {
    /** name the import introduces (simple imports) */
    val importedName: String
        get() {
            if (alias != null) return alias
            val i = path.lastIndexOf('.')
            return if (i < 0) path else path.substring(i + 1)
        }
    val originalName: String
        get() {
            val i = path.lastIndexOf('.')
            return if (i < 0) path else path.substring(i + 1)
        }
    val qualifier: String
        get() {
            if (star) return path
            val i = path.lastIndexOf('.')
            return if (i < 0) "" else path.substring(0, i)
        }
}

class FileScope(val file: FileInfo, val imports: List<ImportInfo>) : Scope(null)

class ClassScope(val cls: ClassSym, parent: Scope?, val instance: Boolean) : Scope(parent)

class TypeParamScope(val tps: List<TypeParamSym>, parent: Scope?) : Scope(parent)

/** Local declarations of a block / function body / lambda. */
class LocalScope(parent: Scope?) : Scope(parent) {
    val vars = LinkedHashMap<String, VarSym>()
    val funs = LinkedHashMap<String, ArrayList<FunSym>>()
    val classes = LinkedHashMap<String, ClassSym>()
    val props = LinkedHashMap<String, PropSym>()

    fun addFun(f: FunSym) {
        var l = funs[f.name]
        if (l == null) {
            l = ArrayList()
            funs[f.name] = l
        }
        l.add(f)
    }
}

/** An implicit receiver introduced by an extension function or a lambda with receiver. */
class ReceiverScope(val type: Type, val label: String?, parent: Scope?) : Scope(parent) {
    var fn: FunSym? = null
    /** the lambda this is the receiver of (B2-3) */
    var lambda: LambdaExpr? = null
    /** the extension property this is the receiver of (B2-3) */
    var prop: PropSym? = null
}
