/*
 * kotlin.contracts: the contract DSL. Mini accepts `contract { ... }` and ignores it (no smart casts or definite-assignment
 * facts are derived from contracts).
 */
package kotlin.contracts

import kotlin.annotation.AnnotationTarget.*

@RequiresOptIn
@Retention(AnnotationRetention.BINARY)
@Target(CLASS, FUNCTION, PROPERTY)
public annotation class ExperimentalContracts

public interface Effect

public interface ConditionalEffect : Effect

public interface SimpleEffect {
    public infix fun implies(booleanExpression: Boolean): ConditionalEffect
}

public interface Returns : SimpleEffect

public interface ReturnsNotNull : SimpleEffect

public interface CallsInPlace : Effect

public enum class InvocationKind { AT_MOST_ONCE, AT_LEAST_ONCE, EXACTLY_ONCE, UNKNOWN }

public interface ContractBuilder {
    public fun returns(): Returns
    public fun returns(value: Any?): Returns
    public fun returnsNotNull(): ReturnsNotNull
    public fun callsInPlace(lambda: Any?, kind: InvocationKind): CallsInPlace
    public fun callsInPlace(lambda: Any?): CallsInPlace
}

public inline fun contract(builder: ContractBuilder.() -> Unit) {
}
