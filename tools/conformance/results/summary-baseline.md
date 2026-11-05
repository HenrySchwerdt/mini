# Kotlin box conformance results

| | count |
|---|---:|
| total test files | 10070 |
| skipped (not applicable) | 4099 |
| **applicable** | **5971** |
| **pass** | **4008 (67.12%)** |
| compile-error | 1403 |
| ice (compiler crash) | 238 |
| runtime-fail | 210 |
| crash (signal) | 110 |
| timeout | 2 |

Wall time: 5262s.

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
| 260 | compile-error | `unresolved call '_'` | box/argumentOrder/kt17691WithEnabledFeature.kt |
| 250 | compile-error | `unresolved reference '_'` | box/callableReference/bound/kCallableNameIntrinsic.kt |
| 194 | compile-error | `unresolved infix function step` | box/ranges/stepped/unsigned/expression/downTo/emptyProgression.kt |
| 64 | crash | `signal 6` | box/bridges/delegation.kt |
| 62 | compile-error | `0_test.kt:N:N: error: unsupported construct: call to '_'` | box/arrays/indices.kt |
| 57 | ice | `internal compiler error: the C compiler (cc) rejected the C code that minic generated` | box/arrays/kt47483.kt |
| 47 | compile-error | `unresolved infix function downTo` | box/ranges/expression/emptyDownto.kt |
| 46 | crash | `signal 11` | box/basics/k42000_1.kt |
| 41 | compile-error | `Suspend function '_' should be called only from a coroutine or another suspend function (a lambda passed to an inline function does not count: suspension points` | box/callableReference/adaptedReferences/suspendUnitConversion.kt |
| 35 | compile-error | `0_test.kt: error: unsupported construct: call with omitted argument '_' of '_'` | box/arrays/multiDecl/MultiDeclForComponentMemberExtensions.kt |
| 34 | compile-error | `unresolved type '_'` | box/annotations/nestedClassesInAnnotations.kt |
| 34 | compile-error | `0_test.kt: error: unsupported construct: reference to a variable that is not a local of the current function` | box/builderInference/issues/kt52757.kt |
| 34 | compile-error | `0_test.kt:N:N: error: unsupported construct: receiver parameter` | box/closures/kt4137.kt |
| 23 | compile-error | `no matching callable for reference '_'` | box/callableReference/adaptedReferences/adaptedVarargFunImportedFromObject.kt |
| 23 | compile-error | `for-loop range must have an iterator() method` | box/controlStructures/forInCharSequence.kt |
| 16 | compile-error | `0_test.kt:N:N: error: unsupported construct: call to array member '_'` | box/arrays/arrayInstanceOf.kt |
| 16 | compile-error | `no operator plus for <error>` | box/builderInference/pclaRootIsElvisSynthetic.kt |
| 16 | compile-error | `unresolved callable reference '_'` | box/callableReference/kt47988.kt |
| 16 | compile-error | `0_test.kt: error: unsupported construct: conversion from TObject(qualifiedName=kotlin.Any, cName=mk_Any, nullable=true, isInterface=false) to Unit` | box/coroutines/coercionToUnit.kt |
| 15 | ice | `internal compiler error: mini.lower.LowerError: implicit receiver not found (in function <init>)` | box/classes/inner/kt6708.kt |
| 15 | compile-error | `unresolved infix function until` | box/controlflow/for_loops_let_with_nullable.kt |
| 15 | compile-error | `0_test.kt:N:N: error: unsupported construct: arithmetic on TObject(qualifiedName=kotlin.Any, cName=mk_Any, nullable=true, isInterface=false)` | box/inlineClasses/UIntArraySortExampleGeneric.kt |
| 13 | compile-error | `0_test.kt:N:N: error: unsupported construct: variable '_' of type Unit` | box/basics/unchecked_cast10.kt |
| 13 | compile-error | `0_test.kt:N:N: error: unsupported construct: reference to a variable that is not a local of the current function` | box/callableReference/bound/captureVarInInitBlock.kt |
| 13 | compile-error | `1_1.kt:N:N: error: unsupported construct: receiver parameter` | boxInline/anonymousObject/capturedLambdaInInline2.kt |
| 13 | compile-error | `0_1.kt:N:N: error: unsupported construct: receiver parameter` | boxInline/anonymousObject/kt13182.kt |
| 12 | compile-error | `Suspend function type should be called only from a coroutine or another suspend function (a lambda passed to an inline function does not count: suspension point` | box/callableReference/adaptedReferences/suspendConversion/crossInline.kt |
| 11 | compile-error | `0_test.kt:N:N: error: unsupported construct: call with omitted argument '_' of '_'` | box/classes/classObjectAsExtensionReceiver.kt |
| 10 | ice | `internal compiler error: mini.lower.LowerError: offset N: break / continue outside of a loop (in function <anonymous>)` | box/controlStructures/breakContinueInExpressions/inlinedBreakContinue/continueInTryBlock.kt |
| 10 | runtime-fail | `box() returned: fail` | box/dataClasses/equals/alreadyDeclared.kt |
| 10 | compile-error | `0_test.kt:N:N: error: unsupported construct: conversion from Nothing? to Boolean` | box/ieee754/equalsDouble_properIeeeComparisons.kt |
| 10 | compile-error | `2_2.kt:N:N: error: unsupported construct: receiver parameter` | boxInline/anonymousObject/kt42815.kt |
| 9 | ice | `internal compiler error: mini.lower.LowerError: offset N: implicit receiver not found` | box/enum/kt20651_inlineLambda.kt |
| 9 | ice | `internal compiler error: mini.lower.LowerError: offset N: return target not found (in function <anonymous>)` | box/functions/localReturnInsideFunctionExpression.kt |
| 9 | compile-error | `1_2.kt:N:N: error: unsupported construct: variable '_' of type Nothing` | boxInline/nonLocalReturns/explicitLocalReturn.kt |
| 8 | runtime-fail | `box() returned: Fail` | box/bridges/propertyDiamondFakeOverride.kt |
| 8 | ice | `internal compiler error: mini.lower.LowerError: offset N: implicit receiver not found (in function run)` | box/classes/privateOuterFunctions.kt |
| 8 | compile-error | `0_test.kt: error: unsupported construct: call of constructor of '_'` | box/enum/defaultCtor/noPrimaryConstructor.kt |
| 8 | runtime-fail | `box() returned: fail 1` | box/ieee754/smartCastToDifferentTypes_properIeeeComparisons.kt |
| 8 | compile-error | `0_test.kt:N:N: error: unsupported construct: static field '_'` | box/properties/classFieldInsideLambda.kt |
