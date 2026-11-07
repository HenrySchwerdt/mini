# Kotlin box conformance results

| | count |
|---|---:|
| total test files | 10070 |
| skipped (not applicable) | 4117 |
| **applicable** | **5953** |
| **pass** | **5778 (97.06%)** |
| compile-error | 126 |
| ice (compiler crash) | 26 |
| runtime-fail | 21 |
| crash (signal) | 0 |
| timeout | 2 |

## Native parity (also excludes tests Kotlin/Native itself does not pass or run)

| | count |
|---|---:|
| applicable (above) | 5953 |
| **native-parity applicable** | **5953** |
| **native-parity pass** | **5778 (97.06%)** |

Wall time: 90s.

## Skips

| reason | count |
|---|---:|
| jvm-dir | 1789 |
| target-backend | 1563 |
| jvm-directive | 305 |
| ignored-on-native | 145 |
| jvm-api | 134 |
| multi-module | 126 |
| compiler-config | 36 |
| non-default-flag | 19 |

## Failure buckets (top 40)

| count | status | first diagnostic | example |
|---:|---|---|---|
| 31 | compile-error | `unresolved call '_'` | box/builderInference/issues/kt53478.kt |
| 13 | compile-error | `unresolved reference '_'` | box/callableReference/function/classMemberFromCompanionObject.kt |
| 9 | ice | `internal compiler error: the C compiler (cc) rejected the C code that minic generated` | box/arrays/kt47483.kt |
| 8 | compile-error | `no matching callable for reference '_'` | box/callableReference/adaptedReferences/referenceToVarargWithDefaults.kt |
| 6 | compile-error | `0_test.kt: error: unsupported construct: call of constructor of '_'` | box/primitiveTypes/numberToChar/abstractMethodInSuperinterface.kt |
| 4 | compile-error | `unresolved callable reference '_'` | box/callableReference/function/referenceToCompanionMember.kt |
| 3 | compile-error | `0_test.kt:N:N: error: unsupported construct: construction of '_'` | box/annotations/instances/annotationsUnsignedTypes.kt |
| 3 | compile-error | `0_test.kt:N:N: error: unsupported construct: type '_'` | box/annotations/instances/nestedAnnotationInstances.kt |
| 3 | compile-error | `no operator plus for <error>` | box/builderInference/pclaRootIsElvisSynthetic.kt |
| 3 | compile-error | `0_test.kt: error: unsupported construct: class '_' implements a function type without an invoke function` | box/coroutines/suspendFunctionAsSupertype.kt |
| 3 | compile-error | `0_test.kt: error: unsupported construct: call to '_'` | box/inlineClasses/equalityForBoxesOfNullableValuesOfInlineClass.kt |
| 3 | compile-error | `1_2.kt: error: unsupported construct: reference to a variable that is not a local of the current function ('_')` | boxInline/innerClasses/innerInlineFunCapturesOuter.kt |
| 2 | compile-error | `0_test.kt: error: unsupported construct: type '_'` | box/callableReference/property/enumNameOrdinal.kt |
| 2 | compile-error | `Suspend function type should be called only from a coroutine or another suspend function` | box/coroutines/kt51530.kt |
| 2 | compile-error | `0_test.kt:N:N: error: unsupported construct: call of '_'` | box/dataObjects/hashCode.kt |
| 2 | ice | `internal compiler error: mini.lower.LowerError: offset N: implicit receiver not found` | box/enum/kt20651_inlineLambda.kt |
| 2 | compile-error | `0_test.kt:N:N: error: unsupported construct: call to '_'` | box/involvesIrInterpreter/annotationClassWithInner.kt |
| 2 | compile-error | `syntax error: Expecting an element` | box/involvesIrInterpreter/inJavaPackage.kt |
| 2 | compile-error | `syntax error: unexpected character '_'` | box/involvesIrInterpreter/thisPlusStringWithObject.kt |
| 2 | runtime-fail | `Exception in thread "_" java.lang.IllegalStateException: N` | box/topLevelInitializtion/failInInitializer1.kt |
| 1 | compile-error | `unresolved type '_'` | box/annotations/nestedClassesInAnnotations.kt |
| 1 | compile-error | `1_a.kt:N:N: error: unsupported construct: type '_'` | box/annotations/instances/multifileEqHc.kt |
| 1 | timeout | `compile timeout` | box/arrays/primitiveArrays.kt |
| 1 | runtime-fail | `box() returned: FAIL 1: kotlin.Any@e9abe92d` | box/basics/unchecked_cast10.kt |
| 1 | runtime-fail | `box() returned: OKOK` | box/binaryOp/eqNullableToPrimitiveWithSideEffects.kt |
| 1 | ice | `internal compiler error: mini.lower.LowerError: offset N: this not found (in function typeInfoSourceFunction)` | box/builderInference/issues/kt65300c.kt |
| 1 | ice | `internal compiler error: mini.lower.LowerError: offset N: call without resolution (in function testTypeInfoOriginInsideLocalClass)` | box/builderInference/oneParameter/oneTypeVariable/oneTypeInfoOrigin/targetTypes/GenericLocalClassWithLeakingTypeParameter.kt |
| 1 | compile-error | `type mismatch: expected (kotlin.Int, kotlin.Any) -> kotlin.Unit but was kotlin.reflect.KFunction2<kotlin.Int,kotlin.Any,kotlin.Int>` | box/callableReference/adaptedReferences/unitCoercionOverloads.kt |
| 1 | compile-error | `type mismatch: expected kotlin.CharSequence but was kotlin.Array<kotlin.String>` | box/callableReference/adaptedReferences/varargWithDefaultValue.kt |
| 1 | runtime-fail | `Exception in thread "_" java.lang.AssertionError: box.<lambda>$N@1361145c and box.<lambda>$N@1361145c should NOT be equal` | box/callableReference/equality/coercionToUnitWithDefaults.kt |
| 1 | runtime-fail | `Exception in thread "_" java.lang.Exception: ks1 == ks2 (same file, same SAM type): equals: <get-ks3>.<lambda>$N@c49aa92e != <get-ks3>.<lambda>$N@c49aa92f` | box/callableReference/funInterfaceConstructor/funInterfaceConstructorEquality.kt |
| 1 | ice | `internal compiler error: java.lang.IllegalStateException: no C name for <get-name>` | box/callableReference/function/local/enumExtendsTrait.kt |
| 1 | compile-error | `0_test.kt:N:N: error: unsupported construct: main function with unsupported parameters (only `_` and `_`)` | box/casts/kt53677.kt |
| 1 | runtime-fail | `Exception in thread "_" java.lang.ClassCastException: class kotlin.Long cannot be cast to class kotlin.Int` | box/casts/objectToPrimitiveWithAssertion.kt |
| 1 | ice | `internal compiler error: mini.backend.EmitError: no type info for Ptr? (in box)` | box/casts/toNativePointers.kt |
| 1 | compile-error | `0_test.kt: error: unsupported construct: supertype '_' of '_'` | box/casts/functions/asFunKSmall.kt |
| 1 | compile-error | `type checking has run into a recursive problem (ok)` | box/closures/localClassFunClosure.kt |
| 1 | runtime-fail | `box() returned: Fail` | box/collections/inheritFromAbstractMutableListInt.kt |
| 1 | compile-error | `linking /tmp/kb-q233i7ut/a.out failed` | box/controlStructures/breakContinueInExpressions/inlinedBreakContinue/initializerBlock.kt |
| 1 | ice | `internal compiler error: mini.lower.LowerError: offset N: unknown loop label outer (in function <anonymous>)` | box/controlStructures/breakContinueInExpressions/inlinedBreakContinue/lambdaPassedToInlineFunction.kt |
