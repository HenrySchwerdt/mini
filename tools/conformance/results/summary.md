# Kotlin box conformance results

| | count |
|---|---:|
| total test files | 10070 |
| skipped (not applicable) | 4099 |
| **applicable** | **5971** |
| **pass** | **5058 (84.71%)** |
| compile-error | 460 |
| ice (compiler crash) | 133 |
| runtime-fail | 245 |
| crash (signal) | 71 |
| timeout | 4 |

Wall time: 156s.

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
| 166 | compile-error | `unresolved call '_'` | box/argumentOrder/kt17691WithEnabledFeature.kt |
| 62 | crash | `signal 11` | box/basics/k42000_1.kt |
| 49 | compile-error | `unresolved reference '_'` | box/callableReference/bound/kCallableNameIntrinsic.kt |
| 40 | ice | `internal compiler error: the C compiler (cc) rejected the C code that minic generated` | box/arrays/kt47483.kt |
| 18 | compile-error | `unresolved callable reference '_'` | box/callableReference/kt47988.kt |
| 15 | compile-error | `0_test.kt:N:N: error: unsupported construct: arithmetic on TObject(qualifiedName=kotlin.Any, cName=mk_Any, nullable=true, isInterface=false)` | box/inlineClasses/UIntArraySortExampleGeneric.kt |
| 10 | compile-error | `unresolved type '_'` | box/annotations/nestedClassesInAnnotations.kt |
| 10 | ice | `internal compiler error: mini.lower.LowerError: offset N: break / continue outside of a loop (in function <anonymous>)` | box/controlStructures/breakContinueInExpressions/inlinedBreakContinue/continueInTryBlock.kt |
| 10 | runtime-fail | `box() returned: fail` | box/dataClasses/equals/alreadyDeclared.kt |
| 10 | compile-error | `0_test.kt:N:N: error: unsupported construct: conversion from Nothing? to Boolean` | box/ieee754/equalsDouble_properIeeeComparisons.kt |
| 9 | compile-error | `0_test.kt:N:N: error: unsupported construct: call to '_'` | box/bridges/test24.kt |
| 9 | compile-error | `for-loop range must have an iterator() method` | box/controlStructures/forInCharSequence.kt |
| 9 | crash | `signal 6` | box/coroutines/featureIntersection/interfaceMethodWithBody.kt |
| 9 | compile-error | `0_test.kt:N:N: error: unsupported construct: static field '_'` | box/properties/classFieldInsideLambda.kt |
| 8 | compile-error | `0_test.kt: error: unsupported construct: type '_'` | box/callableReference/property/enumNameOrdinal.kt |
| 8 | compile-error | `0_test.kt: error: unsupported construct: call of constructor of '_'` | box/enum/defaultCtor/noPrimaryConstructor.kt |
| 8 | runtime-fail | `box() returned: fail 1` | box/ieee754/smartCastToDifferentTypes_properIeeeComparisons.kt |
| 7 | runtime-fail | `box() returned: Fail` | box/bridges/propertyDiamondFakeOverride.kt |
| 7 | compile-error | `no operator plus for <error>` | box/builderInference/pclaRootIsElvisSynthetic.kt |
| 7 | compile-error | `unresolved this` | box/builderInference/issues/kt49160a.kt |
| 7 | compile-error | `no matching callable for reference '_'` | box/callableReference/adaptedReferences/nestedClassConstructorWithDefault.kt |
| 7 | compile-error | `0_test.kt: error: unsupported construct: reference to a variable that is not a local of the current function ('_')` | box/closures/captureInSuperConstructorCall/outerCapturedInObject2.kt |
| 6 | compile-error | `0_test.kt: error: unsupported construct: comparison of Unit` | box/basics/unchecked_cast10.kt |
| 6 | runtime-fail | `box() returned: (no output)` | box/branching/when7.kt |
| 6 | ice | `internal compiler error: mini.lower.LowerError: offset N: return target not found (in function <anonymous>)` | box/labels/infixCallLabelling.kt |
| 6 | runtime-fail | `Exception in thread "_" java.lang.ClassCastException: class kotlin.String cannot be cast to class kotlin.Int` | boxInline/dontReify/arrayWrite.kt |
| 5 | runtime-fail | `Exception in thread "_" java.lang.AssertionError: Expected <N>, actual <N>.` | box/arithmetic/basic_integralToFloat.kt |
| 5 | compile-error | `0_test.kt:N:N: error: unsupported construct: conversion from Nothing? to Nothing` | box/controlStructures/returnsNothing/ifElse.kt |
| 5 | ice | `internal compiler error: mini.lower.LowerError: offset N: set is not resolved (in function box)` | box/defaultArguments/convention/incWithDefaults.kt |
| 5 | compile-error | `2_2.kt:N:N: error: unsupported construct: call to '_'` | boxInline/annotations/instanceInAnonymousClass.kt |
| 4 | compile-error | `0_test.kt:N:N: error: unsupported construct: type '_'` | box/annotations/instances/nestedAnnotationInstances.kt |
| 4 | runtime-fail | `box() returned: FAIL: 3` | box/basics/unchecked_cast5.kt |
| 4 | compile-error | `0_test.kt: error: unsupported construct: call to '_'` | box/boxingOptimization/kt5493.kt |
| 4 | runtime-fail | `Exception in thread "_" java.lang.ClassCastException: class box.::foo$N cannot be cast to class kotlin.Function1` | box/callableReference/adaptedReferences/suspendConversion/bound.kt |
| 4 | compile-error | `0_test.kt:N:N: error: unsupported construct: reference to a variable that is not a local of the current function ('_')` | box/closures/captureInSuperConstructorCall/outerCapturedInLambda2.kt |
| 4 | runtime-fail | `box() returned: null` | box/contracts/destructuredVariable.kt |
| 4 | compile-error | `0_test.kt:N:N: error: unsupported construct: string conversion of a value of type Nothing` | box/controlStructures/breakContinueInExpressions/breakFromOuter.kt |
| 4 | timeout | `run timeout` | box/coroutines/kt49168.kt |
| 4 | runtime-fail | `Exception in thread "_" java.lang.AssertionError: Expected <O;tdf(O);K;tdf(K);OK;get(O);get(K);>, actual <O;K;OK;get(O);get(K);>.` | box/delegatedProperty/provideDelegate/inClass.kt |
| 3 | compile-error | `0_test.kt:N:N: error: unsupported construct: construction of '_'` | box/annotations/instances/annotationsUnsignedTypes.kt |
