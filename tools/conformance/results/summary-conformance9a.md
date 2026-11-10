# Kotlin box conformance results

| | count |
|---|---:|
| total test files | 10070 |
| skipped (not applicable) | 4117 |
| **applicable** | **5953** |
| **pass** | **5898 (99.08%)** |
| compile-error | 35 |
| ice (compiler crash) | 5 |
| runtime-fail | 12 |
| crash (signal) | 1 |
| timeout | 2 |

## Native parity (also excludes tests Kotlin/Native itself does not pass or run)

| | count |
|---|---:|
| applicable (above) | 5953 |
| **native-parity applicable** | **5953** |
| **native-parity pass** | **5898 (99.08%)** |

Wall time: 4802s.

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
| 13 | compile-error | `unresolved call '_'` | box/builderInference/issues/kt53478.kt |
| 7 | compile-error | `no matching callable for reference '_'` | box/callableReference/adaptedReferences/referenceToVarargWithDefaults.kt |
| 3 | compile-error | `no operator plus for <error>` | box/builderInference/pclaRootIsElvisSynthetic.kt |
| 3 | runtime-fail | `Exception in thread "_" java.lang.NullPointerException: null cannot be cast to non-null type` | box/fir/initializationInLocalInnerClassWithSecondaryConstructor.kt |
| 2 | timeout | `compile timeout` | box/arrays/primitiveArrays.kt |
| 2 | compile-error | `unresolved reference '_'` | box/volatile/messagePassing.kt |
| 1 | compile-error | `unresolved type '_'` | box/annotations/nestedClassesInAnnotations.kt |
| 1 | runtime-fail | `box() returned: OKOK` | box/binaryOp/eqNullableToPrimitiveWithSideEffects.kt |
| 1 | ice | `internal compiler error: mini.lower.LowerError: offset N: call without resolution (in function testTypeInfoOriginInsideLocalClass)` | box/builderInference/oneParameter/oneTypeVariable/oneTypeInfoOrigin/targetTypes/GenericLocalClassWithLeakingTypeParameter.kt |
| 1 | runtime-fail | `Exception in thread "_" java.lang.Exception: ks1 == ks2 (same file, same SAM type): equals: <get-ks3>.<lambda>$N@1131dd2e != <get-ks3>.<lambda>$N@1131dd2f` | box/callableReference/funInterfaceConstructor/funInterfaceConstructorEquality.kt |
| 1 | runtime-fail | `Exception in thread "_" java.lang.ClassCastException: class kotlin.Long cannot be cast to class kotlin.Int` | box/casts/objectToPrimitiveWithAssertion.kt |
| 1 | ice | `internal compiler error: mini.backend.EmitError: no type info for Ptr? (in box)` | box/casts/toNativePointers.kt |
| 1 | compile-error | `unresolved callable reference '_'` | box/coroutines/suspendFunctionMethodReference.kt |
| 1 | compile-error | `0_test.kt: error: unsupported construct: receiver parameter` | box/coroutines/featureIntersection/inlineSuspendFinally.kt |
| 1 | ice | `internal compiler error: mini.lower.LowerError: offset N: implicit receiver not found` | box/enum/kt20651_inlineLambda.kt |
| 1 | crash | `signal 11` | box/funInterface/intersectionTypeToFunInterfaceConversion.kt |
| 1 | runtime-fail | `Exception in thread "_" java.lang.Exception` | box/ieee754/smartCastToDifferentTypesWithNumericPromotion_properIeeeComparisons.kt |
| 1 | compile-error | `0_test.kt:N:N: error: unsupported construct: call with omitted argument '_' of '_'` | box/inference/kt38664.kt |
| 1 | compile-error | `type mismatch: expected Subtype<A,B> but was Subtype.Trivial<A,kotlin.Any>` | box/inference/kt42042.kt |
| 1 | ice | `internal compiler error: mini.lower.LowerError: offset N: call without resolution (in function box)` | box/inference/builderInference/kt51988.kt |
| 1 | runtime-fail | `Exception in thread "_" java.lang.AssertionError: xByteStatic(N) != xByteDyanmic(N). Expected <N>, actual <N> are not the same instance.` | box/initializers/static_smallIntIdentity.kt |
| 1 | compile-error | `0_test.kt: error: unsupported construct: call to '_'` | box/inlineClasses/lateinitInlineClasses.kt |
| 1 | compile-error | `no matching super constructor` | box/localClasses/anonymousObjectInExtension.kt |
| 1 | runtime-fail | `Exception in thread "_" java.lang.AssertionError: Should fail with exception` | box/nothingValue/nothingValueException.kt |
| 1 | ice | `internal compiler error: mini.lower.LowerError: offset N: name Enum is not a value (in function testCompanionObjectAccess)` | box/objectIntrinsics/objects.kt |
| 1 | compile-error | `0_test.kt:N:N: error: unsupported construct: call of abstract function '_'` | box/specialBuiltins/bridges.kt |
| 1 | runtime-fail | `Exception in thread "_" java.lang.AssertionError: OverOver: Override: OK01` | box/specialBuiltins/throwableComplex.kt |
| 1 | runtime-fail | `Exception in thread "_" java.lang.AssertionError: Expected the value to be true.` | box/stringTrim/stringTrim.kt |
| 1 | compile-error | `type mismatch: expected kotlin.Unit but was kotlin.Nothing?` | boxInline/complex/nestedCallsWithGenericToInt.kt |
| 1 | compile-error | `0_1.kt:N:N: error: unsupported construct: lambda passed to an inline function that cannot be expanded in place` | boxInline/private/effectivePrivate.kt |
| 1 | runtime-fail | `box() returned: fail 2` | boxInline/reified/kt7017.kt |
