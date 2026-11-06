# Kotlin box conformance results

| | count |
|---|---:|
| total test files | 10070 |
| skipped (not applicable) | 4117 |
| **applicable** | **5953** |
| **pass** | **5653 (94.96%)** |
| compile-error | 219 |
| ice (compiler crash) | 31 |
| runtime-fail | 41 |
| crash (signal) | 7 |
| timeout | 2 |

## Native parity (also excludes tests Kotlin/Native itself does not pass or run)

| | count |
|---|---:|
| applicable (above) | 5953 |
| **native-parity applicable** | **5953** |
| **native-parity pass** | **5653 (94.96%)** |

Wall time: 4970s.

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
| 93 | compile-error | `unresolved call '_'` | box/builderInference/issues/kt53478.kt |
| 23 | compile-error | `unresolved reference '_'` | box/callableReference/function/classMemberFromCompanionObject.kt |
| 9 | ice | `internal compiler error: the C compiler (cc) rejected the C code that minic generated` | box/arrays/kt47483.kt |
| 8 | compile-error | `no matching callable for reference '_'` | box/callableReference/adaptedReferences/referenceToVarargWithDefaults.kt |
| 8 | compile-error | `0_test.kt: error: unsupported construct: call of constructor of '_'` | box/enum/defaultCtor/noPrimaryConstructor.kt |
| 7 | crash | `signal 11` | box/basics/k42000_1.kt |
| 5 | ice | `internal compiler error: mini.lower.LowerError: offset N: set is not resolved (in function box)` | box/defaultArguments/convention/incWithDefaults.kt |
| 4 | compile-error | `unresolved type '_'` | box/annotations/nestedClassesInAnnotations.kt |
| 4 | compile-error | `0_test.kt: error: unsupported construct: call to '_'` | box/boxingOptimization/kt5493.kt |
| 4 | compile-error | `unresolved callable reference '_'` | box/callableReference/function/referenceToCompanionMember.kt |
| 4 | compile-error | `0_test.kt:N:N: error: unsupported construct: string conversion of a value of type Nothing` | box/controlStructures/breakContinueInExpressions/breakFromOuter.kt |
| 3 | compile-error | `0_test.kt:N:N: error: unsupported construct: construction of '_'` | box/annotations/instances/annotationsUnsignedTypes.kt |
| 3 | compile-error | `0_test.kt:N:N: error: unsupported construct: type '_'` | box/annotations/instances/nestedAnnotationInstances.kt |
| 3 | compile-error | `type mismatch: expected kotlin.Long but was kotlin.Int` | box/binaryOp/overflowLong.kt |
| 3 | compile-error | `no operator plus for <error>` | box/builderInference/pclaRootIsElvisSynthetic.kt |
| 3 | compile-error | `0_test.kt: error: unsupported construct: class '_' implements a function type without an invoke function` | box/coroutines/suspendFunctionAsSupertype.kt |
| 3 | runtime-fail | `Exception in thread "_" java.lang.NullPointerException` | box/inlineClasses/inlineClassCollection/inlineCollectionOfInlineClassGeneric.kt |
| 3 | runtime-fail | `box() returned: fail 1` | box/specialBuiltins/bridgeNotEmptyMap.kt |
| 3 | compile-error | `1_2.kt: error: unsupported construct: reference to a variable that is not a local of the current function ('_')` | boxInline/innerClasses/innerInlineFunCapturesOuter.kt |
| 2 | compile-error | `0_test.kt: error: unsupported construct: type '_'` | box/callableReference/property/enumNameOrdinal.kt |
| 2 | runtime-fail | `box() returned: Fail` | box/casts/asUnit.kt |
| 2 | compile-error | `Suspend function type should be called only from a coroutine or another suspend function` | box/coroutines/kt51530.kt |
| 2 | compile-error | `0_test.kt:N:N: error: unsupported construct: call of '_'` | box/dataObjects/hashCode.kt |
| 2 | compile-error | `unresolved this` | box/defaultArguments/kt36853_fibonacci.kt |
| 2 | ice | `internal compiler error: mini.lower.LowerError: offset N: implicit receiver not found` | box/enum/kt20651_inlineLambda.kt |
| 2 | runtime-fail | `Exception in thread "_" java.lang.AssertionError: Expected the value to be false.` | box/initializers/object.kt |
| 2 | compile-error | `0_test.kt:N:N: error: unsupported construct: call to '_'` | box/involvesIrInterpreter/annotationClassWithInner.kt |
| 2 | compile-error | `syntax error: Expecting an element` | box/involvesIrInterpreter/inJavaPackage.kt |
| 2 | compile-error | `syntax error: unexpected character '_'` | box/involvesIrInterpreter/thisPlusStringWithObject.kt |
| 1 | runtime-fail | `box() returned: fail1` | box/annotations/resolveWithLowPriorityAnnotation.kt |
| 1 | compile-error | `1_a.kt:N:N: error: unsupported construct: type '_'` | box/annotations/instances/multifileEqHc.kt |
| 1 | compile-error | `0_test.kt:N:N: error: unsupported construct: conversion from Int to TObject(qualifiedName=kotlin.Array, cName=MkObjectArray, nullable=false, isInterface=false)` | box/arrays/kt4348.kt |
| 1 | timeout | `compile timeout` | box/arrays/primitiveArrays.kt |
| 1 | runtime-fail | `box() returned: FAIL 1: kotlin.Any@34e0722d` | box/basics/unchecked_cast10.kt |
| 1 | compile-error | `0_test.kt: error: unsupported construct: conversion from Nothing? to Int` | box/binaryOp/divisionByZero.kt |
| 1 | runtime-fail | `box() returned: OKOK` | box/binaryOp/eqNullableToPrimitiveWithSideEffects.kt |
| 1 | runtime-fail | `box() returned: FAIL 1: NotEmptyMap@1159882b` | box/bridges/special.kt |
| 1 | ice | `internal compiler error: mini.lower.LowerError: offset N: this not found (in function typeInfoSourceFunction)` | box/builderInference/issues/kt65300c.kt |
| 1 | ice | `internal compiler error: mini.lower.LowerError: offset N: call without resolution (in function testTypeInfoOriginInsideLocalClass)` | box/builderInference/oneParameter/oneTypeVariable/oneTypeInfoOrigin/targetTypes/GenericLocalClassWithLeakingTypeParameter.kt |
| 1 | compile-error | `type mismatch: expected (kotlin.Int, kotlin.Any) -> kotlin.Unit but was kotlin.reflect.KFunction2<kotlin.Int,kotlin.Any,kotlin.Int>` | box/callableReference/adaptedReferences/unitCoercionOverloads.kt |
