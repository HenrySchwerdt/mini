# Kotlin box conformance results

| | count |
|---|---:|
| total test files | 10070 |
| skipped (not applicable) | 4099 |
| **applicable** | **5971** |
| **pass** | **4837 (81.01%)** |
| compile-error | 640 |
| ice (compiler crash) | 203 |
| runtime-fail | 227 |
| crash (signal) | 61 |
| timeout | 3 |

Wall time: 4106s.

## Skips

| reason | count |
|---|---:|
| jvm-dir | 1789 |
| target-backend | 1563 |
| jvm-directive | 305 |
| ignored-on-native | 145 |
| jvm-api | 135 |
| multi-module | 126 |
| compiler-config | 36 |

## Failure buckets (top 40)

| count | status | first diagnostic | example |
|---:|---|---|---|
| 225 | compile-error | `unresolved call '_'` | box/argumentOrder/kt17691WithEnabledFeature.kt |
| 60 | ice | `internal compiler error: the C compiler (cc) rejected the C code that minic generated` | box/arrays/kt47483.kt |
| 52 | crash | `signal 11` | box/basics/k42000_1.kt |
| 51 | compile-error | `unresolved reference '_'` | box/callableReference/bound/kCallableNameIntrinsic.kt |
| 44 | compile-error | `Suspend function '_' should be called only from a coroutine or another suspend function (a lambda passed to an inline function does not count: suspension points` | box/callableReference/adaptedReferences/suspendUnitConversion.kt |
| 23 | compile-error | `no matching callable for reference '_'` | box/callableReference/adaptedReferences/adaptedVarargFunImportedFromObject.kt |
| 18 | compile-error | `unresolved callable reference '_'` | box/callableReference/kt47988.kt |
| 17 | compile-error | `0_test.kt:N:N: error: unsupported construct: call to '_'` | box/arrays/indices.kt |
| 16 | compile-error | `0_test.kt:N:N: error: unsupported construct: call to array member '_'` | box/arrays/arrayInstanceOf.kt |
| 15 | compile-error | `0_test.kt:N:N: error: unsupported construct: arithmetic on TObject(qualifiedName=kotlin.Any, cName=mk_Any, nullable=true, isInterface=false)` | box/inlineClasses/UIntArraySortExampleGeneric.kt |
| 12 | compile-error | `Suspend function type should be called only from a coroutine or another suspend function (a lambda passed to an inline function does not count: suspension point` | box/callableReference/adaptedReferences/suspendConversion/crossInline.kt |
| 12 | ice | `internal compiler error: mini.backend.EmitError: local of type Unit (in builder.<lambda>$N.invoke)` | box/coroutines/coercionToUnit.kt |
| 10 | compile-error | `unresolved type '_'` | box/annotations/nestedClassesInAnnotations.kt |
| 10 | ice | `internal compiler error: mini.lower.LowerError: offset N: break / continue outside of a loop (in function <anonymous>)` | box/controlStructures/breakContinueInExpressions/inlinedBreakContinue/continueInTryBlock.kt |
| 10 | runtime-fail | `box() returned: fail` | box/dataClasses/equals/alreadyDeclared.kt |
| 10 | compile-error | `0_test.kt:N:N: error: unsupported construct: conversion from Nothing? to Boolean` | box/ieee754/equalsDouble_properIeeeComparisons.kt |
| 9 | compile-error | `for-loop range must have an iterator() method` | box/controlStructures/forInCharSequence.kt |
| 9 | crash | `signal 6` | box/coroutines/featureIntersection/interfaceMethodWithBody.kt |
| 9 | compile-error | `0_test.kt:N:N: error: unsupported construct: static field '_'` | box/properties/classFieldInsideLambda.kt |
| 9 | compile-error | `1_2.kt:N:N: error: unsupported construct: variable '_' of type Nothing` | boxInline/nonLocalReturns/explicitLocalReturn.kt |
| 8 | runtime-fail | `box() returned: Fail` | box/bridges/propertyDiamondFakeOverride.kt |
| 8 | compile-error | `0_test.kt: error: unsupported construct: type '_'` | box/callableReference/property/enumNameOrdinal.kt |
| 8 | compile-error | `0_test.kt: error: unsupported construct: reference to a variable that is not a local of the current function` | box/closures/kt10044.kt |
| 8 | compile-error | `0_test.kt: error: unsupported construct: call of constructor of '_'` | box/enum/defaultCtor/noPrimaryConstructor.kt |
| 8 | runtime-fail | `box() returned: fail 1` | box/ieee754/smartCastToDifferentTypes_properIeeeComparisons.kt |
| 7 | ice | `internal compiler error: mini.lower.LowerError: offset N: no type recorded for LiteralExpr (in function foo)` | box/bridges/methodWithDefaultParameter.kt |
| 7 | compile-error | `no operator plus for <error>` | box/builderInference/pclaRootIsElvisSynthetic.kt |
| 7 | compile-error | `unresolved this` | box/builderInference/issues/kt49160a.kt |
| 6 | compile-error | `0_test.kt:N:N: error: unsupported construct: invoke with omitted argument` | box/callableReference/adaptedReferences/suspendConversion/adaptedWithCoercionToUnit.kt |
| 6 | ice | `internal compiler error: mini.lower.LowerError: offset N: return target not found (in function <anonymous>)` | box/labels/infixCallLabelling.kt |
| 6 | runtime-fail | `Exception in thread "_" java.lang.ClassCastException: class kotlin.String cannot be cast to class kotlin.Int` | boxInline/dontReify/arrayWrite.kt |
| 5 | runtime-fail | `Exception in thread "_" java.lang.AssertionError: Expected <N>, actual <N>.` | box/arithmetic/basic_integralToFloat.kt |
| 5 | compile-error | `0_test.kt:N:N: error: unsupported construct: string conversion of a value of type Unit` | box/basics/unit1.kt |
| 5 | runtime-fail | `box() returned: (no output)` | box/branching/when7.kt |
| 5 | compile-error | `0_test.kt:N:N: error: unsupported construct: conversion from Nothing? to Nothing` | box/controlStructures/returnsNothing/ifElse.kt |
| 5 | ice | `internal compiler error: mini.lower.LowerError: offset N: set is not resolved (in function box)` | box/defaultArguments/convention/incWithDefaults.kt |
| 5 | compile-error | `2_2.kt:N:N: error: unsupported construct: call to '_'` | boxInline/annotations/instanceInAnonymousClass.kt |
| 4 | compile-error | `0_test.kt:N:N: error: unsupported construct: type '_'` | box/annotations/instances/nestedAnnotationInstances.kt |
| 4 | runtime-fail | `box() returned: FAIL: 3` | box/basics/unchecked_cast5.kt |
| 4 | compile-error | `0_test.kt: error: unsupported construct: call to '_'` | box/boxingOptimization/kt5493.kt |
