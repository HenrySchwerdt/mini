# Kotlin box conformance results

| | count |
|---|---:|
| total test files | 10070 |
| skipped (not applicable) | 4117 |
| **applicable** | **5953** |
| **pass** | **5838 (98.07%)** |
| compile-error | 74 |
| ice (compiler crash) | 15 |
| runtime-fail | 23 |
| crash (signal) | 1 |
| timeout | 2 |

## Native parity (also excludes tests Kotlin/Native itself does not pass or run)

| | count |
|---|---:|
| applicable (above) | 5953 |
| **native-parity applicable** | **5953** |
| **native-parity pass** | **5838 (98.07%)** |

Wall time: 2878s.

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
| 24 | compile-error | `unresolved call '_'` | box/builderInference/issues/kt53478.kt |
| 10 | compile-error | `unresolved reference '_'` | box/callableReference/function/classMemberFromCompanionObject.kt |
| 8 | compile-error | `no matching callable for reference '_'` | box/callableReference/adaptedReferences/referenceToVarargWithDefaults.kt |
| 4 | ice | `internal compiler error: the C compiler (cc) rejected the C code that minic generated` | box/arrays/kt47483.kt |
| 4 | compile-error | `unresolved callable reference '_'` | box/callableReference/function/referenceToCompanionMember.kt |
| 3 | compile-error | `no operator plus for <error>` | box/builderInference/pclaRootIsElvisSynthetic.kt |
| 3 | runtime-fail | `Exception in thread "_" java.lang.ClassCastException: value cannot be cast to kotlin.Nothing` | box/specialBuiltins/emptyList.kt |
| 2 | compile-error | `Suspend function type should be called only from a coroutine or another suspend function` | box/coroutines/kt51530.kt |
| 2 | compile-error | `syntax error: Expecting an element` | box/involvesIrInterpreter/inJavaPackage.kt |
| 2 | compile-error | `syntax error: unexpected character '_'` | box/involvesIrInterpreter/thisPlusStringWithObject.kt |
| 2 | runtime-fail | `Exception in thread "_" java.lang.IllegalStateException: N` | box/topLevelInitializtion/failInInitializer1.kt |
| 1 | compile-error | `unresolved type '_'` | box/annotations/nestedClassesInAnnotations.kt |
| 1 | timeout | `compile timeout` | box/arrays/primitiveArrays.kt |
| 1 | runtime-fail | `box() returned: FAIL 1: kotlin.Any@efcf392d` | box/basics/unchecked_cast10.kt |
| 1 | runtime-fail | `box() returned: OKOK` | box/binaryOp/eqNullableToPrimitiveWithSideEffects.kt |
| 1 | ice | `internal compiler error: mini.lower.LowerError: offset N: call without resolution (in function testTypeInfoOriginInsideLocalClass)` | box/builderInference/oneParameter/oneTypeVariable/oneTypeInfoOrigin/targetTypes/GenericLocalClassWithLeakingTypeParameter.kt |
| 1 | runtime-fail | `Exception in thread "_" java.lang.Exception: ks1 == ks2 (same file, same SAM type): equals: <get-ks3>.<lambda>$N@27ce7d2e != <get-ks3>.<lambda>$N@27ce7d2f` | box/callableReference/funInterfaceConstructor/funInterfaceConstructorEquality.kt |
| 1 | compile-error | `0_test.kt: error: unsupported construct: type '_'` | box/callableReference/property/enumNameOrdinal.kt |
| 1 | compile-error | `0_test.kt:N:N: error: unsupported construct: main function with unsupported parameters (only `_` and `_`)` | box/casts/kt53677.kt |
| 1 | runtime-fail | `Exception in thread "_" java.lang.ClassCastException: class kotlin.Long cannot be cast to class kotlin.Int` | box/casts/objectToPrimitiveWithAssertion.kt |
| 1 | ice | `internal compiler error: mini.backend.EmitError: no type info for Ptr? (in box)` | box/casts/toNativePointers.kt |
| 1 | runtime-fail | `box() returned: Fail` | box/collections/inheritFromAbstractMutableListInt.kt |
| 1 | ice | `internal compiler error: mini.lower.LowerError: offset N: unknown loop label outer (in function <anonymous>)` | box/controlStructures/breakContinueInExpressions/inlinedBreakContinue/lambdaPassedToInlineFunction.kt |
| 1 | compile-error | `0_test.kt: error: unsupported construct: receiver parameter` | box/coroutines/featureIntersection/inlineSuspendFinally.kt |
| 1 | runtime-fail | `Exception in thread "_" java.lang.AssertionError: kotlin.Unit` | box/coroutines/featureIntersection/suspendFunctionIsAs.kt |
| 1 | runtime-fail | `box() returned: fail 04: 0` | box/coroutines/intrinsicSemantics/releaseIntercepted.kt |
| 1 | runtime-fail | `box() returned: fail: suspendHere(A.foo(*));suspended;resuming;null;null;suspendHere(A.foo(@));s` | box/coroutines/unitTypeReturn/unitSafeCall.kt |
| 1 | compile-error | `0_test.kt: error: unsupported construct: conversion from Unit to TObject(qualifiedName=kotlin.Any, cName=mk_Any, nullable=true, isInterface=false)` | box/coroutines/varSpilling/safeCallElvis.kt |
| 1 | compile-error | `0_test.kt:N:N: error: unsupported construct: comparison of Int with Long` | box/diagnostics/functions/tailRecursion/realIteratorFoldl.kt |
| 1 | ice | `internal compiler error: mini.lower.LowerError: offset N: implicit receiver not found` | box/enum/kt20651_inlineLambda.kt |
| 1 | ice | `internal compiler error: mini.lower.LowerError: offset N: implicit receiver not found (in function <anonymous>)` | box/extensionFunctions/kt13312.kt |
| 1 | runtime-fail | `box() returned: OO` | box/extensionFunctions/contextReceivers/contextAndNoContextOverloads.kt |
| 1 | runtime-fail | `box() returned: Fail _B_` | box/fir/functionsDifferInTypeParameterBounds2.kt |
| 1 | runtime-fail | `Exception in thread "_" java.lang.NullPointerException: null cannot be cast to non-null type` | box/fir/initializationInLocalInnerClassWithSecondaryConstructor.kt |
| 1 | compile-error | `unresolved this` | box/funInterface/funInterfaceWithReceiver.kt |
| 1 | crash | `signal 11` | box/funInterface/intersectionTypeToFunInterfaceConversion.kt |
| 1 | runtime-fail | `Exception in thread "_" java.lang.Exception` | box/ieee754/smartCastToDifferentTypesWithNumericPromotion_properIeeeComparisons.kt |
| 1 | compile-error | `type mismatch: expected Subtype<A,B> but was Subtype.Trivial<A,kotlin.Any>` | box/inference/kt42042.kt |
| 1 | ice | `internal compiler error: mini.lower.LowerError: offset N: call without resolution (in function box)` | box/inference/builderInference/kt51988.kt |
| 1 | runtime-fail | `Exception in thread "_" java.lang.AssertionError: xByteStatic(N) != xByteDyanmic(N). Expected <N>, actual <N> are not the same instance.` | box/initializers/static_smallIntIdentity.kt |
