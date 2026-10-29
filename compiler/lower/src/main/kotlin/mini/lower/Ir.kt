package mini.lower

import mini.frontend.sema.*

/*
 * The intermediate tree of the B2-3 lowering. It has the shape of the Kotlin IR that K2's fir2ir produces (the
 * representation `jvm-bridge/Ir2Mir` consumes): declarations with parents, calls whose argument list is aligned with the
 * callee's parameters (null = omitted, default value), explicit implicit-receiver accesses, desugared loops, `when`,
 * safe calls, elvis, compound assignments, string templates, lambdas as function expressions, fake overrides,
 * synthesized data / enum members. [Ast2Ir] builds it from the typed AST of the semantic analysis (mini.frontend.sema);
 * [Ir2Mir] lowers it to MIR exactly like the K2 path does. Types are the semantic types of the frontend.
 *
 * Origins are plain strings (the names K2 uses, e.g. "PLUS", "FOR_LOOP", "DEFAULT_PROPERTY_ACCESSOR").
 */

abstract class IrElement {
    var startOffset: Int = -1
}

/** Something a declaration can be nested in: a file, a class or a function. */
interface IrDeclarationParent

abstract class IrStatement : IrElement()

abstract class IrDeclaration : IrStatement() {
    var parent: IrDeclarationParent? = null
    var origin: String = "DEFINED"
}

abstract class IrExpression(var type: Type) : IrStatement(), IrVarargElement

// ---- files ----------------------------------------------------------------------------------------------------

class IrFile(val path: String, val text: String, val packageFqName: String, val isStdlib: Boolean) : IrElement(), IrDeclarationParent {
    val declarations = ArrayList<IrDeclaration>()
    var isBuiltin = false
    private var lineStarts: IntArray? = null

    /** the file name without directories */
    val name: String
        get() {
            val i = path.lastIndexOf('/')
            return if (i < 0) path else path.substring(i + 1)
        }

    fun lineCol(offset: Int): String {
        var ls = lineStarts
        if (ls == null) {
            val l = ArrayList<Int>()
            l.add(0)
            var i = 0
            while (i < text.length) {
                if (text[i] == '\n') l.add(i + 1)
                i++
            }
            val arr = IntArray(l.size)
            var k = 0
            while (k < l.size) {
                arr[k] = l[k]
                k++
            }
            lineStarts = arr
            ls = arr
        }
        var lo = 0
        var hi = ls.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (ls[mid] <= offset) lo = mid else hi = mid - 1
        }
        return (lo + 1).toString() + ":" + (offset - ls[lo] + 1)
    }
}

// ---- declarations ---------------------------------------------------------------------------------------------

enum class IrClassKind { CLASS, INTERFACE, ENUM_CLASS, ENUM_ENTRY, ANNOTATION_CLASS, OBJECT }
enum class IrModality { FINAL, OPEN, ABSTRACT, SEALED }
enum class IrParameterKind { DispatchReceiver, ExtensionReceiver, Context, Regular }

class IrClass(val name: String, val sym: ClassSym?) : IrDeclaration(), IrDeclarationParent {
    var kind: IrClassKind = IrClassKind.CLASS
    var modality: IrModality = IrModality.FINAL
    /** "public", "private", "protected", "internal" or "local" */
    var visibility: String = "public"
    var isInner = false
    var isValue = false
    var isCompanion = false
    var superTypes: List<Type> = emptyList()
    var typeParameters: List<TypeParamSym> = emptyList()
    val declarations = ArrayList<IrDeclaration>()
    var thisReceiver: IrValueParameter? = null
    /** the fully qualified name, for local classes the enclosing function path */
    var fqName: String = name
    var membersBuilt = false

    val constructors: List<IrConstructor>
        get() {
            val out = ArrayList<IrConstructor>()
            for (d in declarations) if (d is IrConstructor) out.add(d)
            return out
        }

    val kotlinFqName: String get() = fqName
}

abstract class IrFunction(var name: String) : IrDeclaration(), IrDeclarationParent {
    val parameters = ArrayList<IrValueParameter>()
    var typeParameters: List<TypeParamSym> = emptyList()
    lateinit var returnType: Type
    var bodyValue: IrBody? = null
    var bodyBuilder: (() -> IrBody?)? = null
    var visibility: String = "public"
    var modality: IrModality = IrModality.FINAL
    var isInline = false
    var isExternal = false
    var isSuspend = false
    var isOperator = false
    var sym: FunSym? = null
    /** the value of `@CSymbol("...")`, if the function is natively implemented */
    var cSymbolName: String? = null

    var body: IrBody?
        get() {
            val b = bodyBuilder
            if (b != null) {
                bodyBuilder = null
                bodyValue = b()
            }
            return bodyValue
        }
        set(v) {
            bodyBuilder = null
            bodyValue = v
        }

    val hasBody: Boolean get() = bodyValue != null || bodyBuilder != null
}

class IrSimpleFunction(name: String) : IrFunction(name) {
    var isFakeOverride = false
    var overridden: List<IrSimpleFunction> = emptyList()
    var correspondingProperty: IrProperty? = null
}

class IrConstructor : IrFunction("<init>") {
    var isPrimary = false
}

class IrValueParameter(
    override var name: String,
    override var type: Type,
    val kind: IrParameterKind,
    val index: Int,
) : IrValueDeclaration() {
    var defaultValueBuilder: (() -> IrExpressionBody?)? = null
    var defaultValueValue: IrExpressionBody? = null
    var varargElementType: Type? = null
    var isNoinline = false
    var isCrossinline = false
    var isHidden = false

    var defaultValue: IrExpressionBody?
        get() {
            val b = defaultValueBuilder
            if (b != null) {
                defaultValueBuilder = null
                defaultValueValue = b()
            }
            return defaultValueValue
        }
        set(v) {
            defaultValueBuilder = null
            defaultValueValue = v
        }

    val hasDefault: Boolean get() = defaultValueValue != null || defaultValueBuilder != null
}

/** Variables and parameters. */
abstract class IrValueDeclaration : IrDeclaration() {
    abstract val name: String
    abstract val type: Type
}

class IrVariable(override var name: String, override var type: Type, val isVar: Boolean) : IrValueDeclaration() {
    var initializer: IrExpression? = null
    var isLateinit = false
    var sym: VarSym? = null
}

class IrProperty(val name: String, val sym: PropSym?) : IrDeclaration() {
    var backingField: IrField? = null
    var getter: IrSimpleFunction? = null
    var setter: IrSimpleFunction? = null
    var isLateinit = false
    var isVar = false
    var isFakeOverride = false
    var overridden: List<IrProperty> = emptyList()
    var cSymbolName: String? = null
}

class IrField(val name: String, var type: Type) : IrDeclaration() {
    var initializerBuilder: (() -> IrExpressionBody?)? = null
    var initializerValue: IrExpressionBody? = null
    var isStatic = false
    var isFinal = false
    var correspondingProperty: IrProperty? = null

    var initializer: IrExpressionBody?
        get() {
            val b = initializerBuilder
            if (b != null) {
                initializerBuilder = null
                initializerValue = b()
            }
            return initializerValue
        }
        set(v) {
            initializerBuilder = null
            initializerValue = v
        }
}

class IrEnumEntry(val name: String) : IrDeclaration() {
    var initializerBuilder: (() -> IrExpressionBody?)? = null
    var initializerValue: IrExpressionBody? = null
    var correspondingClass: IrClass? = null

    var initializerExpression: IrExpressionBody?
        get() {
            val b = initializerBuilder
            if (b != null) {
                initializerBuilder = null
                initializerValue = b()
            }
            return initializerValue
        }
        set(v) {
            initializerBuilder = null
            initializerValue = v
        }
}

class IrAnonymousInitializer : IrDeclaration() {
    var bodyBuilder: (() -> IrBlockBody)? = null
    var bodyValue: IrBlockBody? = null
    val body: IrBlockBody
        get() {
            val b = bodyBuilder
            if (b != null) {
                bodyBuilder = null
                bodyValue = b()
            }
            return bodyValue!!
        }
}

class IrLocalDelegatedProperty(val name: String) : IrDeclaration() {
    var delegate: IrVariable? = null
    lateinit var getter: IrSimpleFunction
    var setter: IrSimpleFunction? = null
}

// ---- bodies ---------------------------------------------------------------------------------------------------

abstract class IrBody : IrElement()

class IrBlockBody(val statements: ArrayList<IrStatement>) : IrBody()

class IrExpressionBody(val expression: IrExpression) : IrBody()

// ---- expressions ----------------------------------------------------------------------------------------------

class IrConst(type: Type, val value: Any?) : IrExpression(type)

class IrGetValue(val owner: IrValueDeclaration, type: Type, val origin: String? = null) : IrExpression(type)

class IrSetValue(val owner: IrValueDeclaration, var value: IrExpression, type: Type, val origin: String? = null) : IrExpression(type)

/**
 * A call of a function or accessor. [arguments] is aligned with [callee].parameters (dispatch receiver, context
 * parameters, extension receiver, regular parameters); null = omitted (the callee's default value applies).
 */
abstract class IrFunctionAccessExpression(type: Type) : IrExpression(type) {
    val arguments = ArrayList<IrExpression?>()
    var typeArguments: List<Type?> = emptyList()
    var origin: String? = null
}

class IrCall(var callee: IrSimpleFunction, type: Type) : IrFunctionAccessExpression(type) {
    /** `super.f()`: the class named by the qualifier */
    var superQualifier: IrClass? = null
}

class IrConstructorCall(val callee: IrConstructor, type: Type) : IrFunctionAccessExpression(type)

class IrDelegatingConstructorCall(val callee: IrConstructor, type: Type) : IrFunctionAccessExpression(type)

class IrEnumConstructorCall(val callee: IrConstructor, type: Type) : IrFunctionAccessExpression(type)

class IrInstanceInitializerCall(val classOwner: IrClass, type: Type) : IrExpression(type)

class IrGetEnumValue(val entry: IrEnumEntry, type: Type) : IrExpression(type)

class IrGetObjectValue(val classOwner: IrClass, type: Type) : IrExpression(type)

class IrGetField(val field: IrField, val receiver: IrExpression?, type: Type) : IrExpression(type)

class IrSetField(val field: IrField, val receiver: IrExpression?, var value: IrExpression, type: Type) : IrExpression(type)

class IrStringConcatenation(val arguments: ArrayList<IrExpression>, type: Type) : IrExpression(type)

class IrFunctionExpression(val function: IrSimpleFunction, type: Type, val origin: String) : IrExpression(type)

class IrFunctionReference(val target: IrFunction, type: Type) : IrFunctionAccessExpression(type)

/** `::prop` of a property (only `PROPERTY_REFERENCE_FOR_DELEGATE` and `::x.isInitialized` are consumed). */
class IrPropertyReference(val property: IrProperty, type: Type) : IrFunctionAccessExpression(type)

class IrClassReference(val classType: Type, type: Type) : IrExpression(type)

class IrGetClass(val argument: IrExpression, type: Type) : IrExpression(type)

class IrTry(var tryResult: IrExpression, val catches: ArrayList<IrCatch>, var finallyExpression: IrExpression?, type: Type) : IrExpression(type)

class IrCatch(val catchParameter: IrVariable, var result: IrExpression) : IrElement()

/** A vararg element: an expression or a spread. */
interface IrVarargElement

class IrVararg(val varargElementType: Type, val elements: ArrayList<IrVarargElement>, type: Type) : IrExpression(type)

class IrSpreadElement(val expression: IrExpression) : IrElement(), IrVarargElement

class IrThrow(var value: IrExpression, type: Type) : IrExpression(type)

class IrBranch(var condition: IrExpression, var result: IrExpression) : IrElement()

class IrWhen(val branches: ArrayList<IrBranch>, type: Type, val origin: String? = null) : IrExpression(type)

/** A block with its own scope ([origin] e.g. "FOR_LOOP", "SAFE_CALL", "ELVIS"). */
open class IrContainerExpression(val statements: ArrayList<IrStatement>, type: Type, val origin: String?) : IrExpression(type)

class IrBlock(statements: ArrayList<IrStatement>, type: Type, origin: String?) : IrContainerExpression(statements, type, origin)

class IrComposite(statements: ArrayList<IrStatement>, type: Type, origin: String?) : IrContainerExpression(statements, type, origin)

enum class IrTypeOperator { CAST, IMPLICIT_CAST, IMPLICIT_NOTNULL, IMPLICIT_COERCION_TO_UNIT, SAFE_CAST, INSTANCEOF, NOT_INSTANCEOF, SAM_CONVERSION, IMPLICIT_INTEGER_COERCION }

class IrTypeOperatorCall(val operator: IrTypeOperator, type: Type, var argument: IrExpression, val typeOperand: Type) : IrExpression(type)

/** A return from [returnTarget] (a function, or the lambda function of a function expression). */
class IrReturn(val returnTarget: IrFunction, var value: IrExpression, type: Type) : IrExpression(type)

abstract class IrLoop(type: Type) : IrExpression(type) {
    lateinit var condition: IrExpression
    var body: IrExpression? = null
    var label: String? = null
    var origin: String? = null
}

class IrWhileLoop(type: Type) : IrLoop(type)

class IrDoWhileLoop(type: Type) : IrLoop(type)

class IrBreak(val loop: IrLoop, type: Type) : IrExpression(type)

class IrContinue(val loop: IrLoop, type: Type) : IrExpression(type)
