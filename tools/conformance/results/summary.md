# Kotlin box conformance results

| | count |
|---|---:|
| total test files | 10070 |
| skipped (not applicable) | 4099 |
| **applicable** | **5971** |
| **pass** | **5415 (90.69%)** |
| compile-error | 281 |
| ice (compiler crash) | 103 |
| runtime-fail | 149 |
| crash (signal) | 21 |
| timeout | 2 |

Wall time: 3434s.

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
| 113 | compile-error | `unresolved call '_'` | box/arithmetic/basic_doubleBasic.kt |
| 37 | ice | `internal compiler error: the C compiler (cc) rejected the C code that minic generated` | box/arrays/kt47483.kt |
| 27 | compile-error | `unresolved reference '_'` | box/callableReference/function/classMemberFromCompanionObject.kt |
| 21 | crash | `signal 11` | box/basics/k42000_1.kt |
| 11 | compile-error | `0_test.kt:N:N: error: unsupported construct: call to '_'` | box/bridges/test24.kt |
| 8 | compile-error | `no matching callable for reference '_'` | box/callableReference/adaptedReferences/referenceToVarargWithDefaults.kt |
| 8 | compile-error | `0_test.kt: error: unsupported construct: type '_'` | box/callableReference/property/enumNameOrdinal.kt |
| 8 | compile-error | `0_test.kt: error: unsupported construct: call of constructor of '_'` | box/enum/defaultCtor/noPrimaryConstructor.kt |
| 7 | compile-error | `unresolved type '_'` | box/annotations/nestedClassesInAnnotations.kt |
| 6 | runtime-fail | `box() returned: (no output)` | box/branching/when7.kt |
| 6 | runtime-fail | `box() returned: Fail` | box/casts/asUnit.kt |
| 6 | runtime-fail | `box() returned: fail 1` | box/operatorConventions/infixFunctionOverBuiltinMember.kt |
| 6 | runtime-fail | `Exception in thread "_" java.lang.ClassCastException: class kotlin.String cannot be cast to class kotlin.Int` | boxInline/dontReify/arrayWrite.kt |
| 5 | runtime-fail | `Exception in thread "_" java.lang.AssertionError: Expected <N>, actual <N>.` | box/arithmetic/basic_integralToFloat.kt |
| 5 | compile-error | `no operator plus for <error>` | box/builderInference/pclaRootIsElvisSynthetic.kt |
| 5 | compile-error | `unresolved callable reference '_'` | box/callableReference/bound/kt15446.kt |
| 5 | ice | `internal compiler error: mini.lower.LowerError: offset N: set is not resolved (in function box)` | box/defaultArguments/convention/incWithDefaults.kt |
| 5 | runtime-fail | `box() returned: fail` | box/inlineClasses/checkForInstanceOfInlineClassGeneric.kt |
| 4 | compile-error | `0_test.kt:N:N: error: unsupported construct: type '_'` | box/annotations/instances/nestedAnnotationInstances.kt |
| 4 | runtime-fail | `box() returned: FAIL: 3` | box/basics/unchecked_cast5.kt |
| 4 | compile-error | `0_test.kt: error: unsupported construct: call to '_'` | box/boxingOptimization/kt5493.kt |
| 4 | runtime-fail | `box() returned: null` | box/contracts/destructuredVariable.kt |
| 4 | compile-error | `0_test.kt:N:N: error: unsupported construct: string conversion of a value of type Nothing` | box/controlStructures/breakContinueInExpressions/breakFromOuter.kt |
| 4 | compile-error | `for-loop range must have an iterator() method` | box/coroutines/kt25912.kt |
| 4 | ice | `internal compiler error: mini.backend.EmitError: no size for Unit` | box/dataClasses/toString/unitComponent.kt |
| 3 | compile-error | `0_test.kt:N:N: error: unsupported construct: construction of '_'` | box/annotations/instances/annotationsUnsignedTypes.kt |
| 3 | compile-error | `unresolved infix function and` | box/binaryOp/bitwiseOp.kt |
| 3 | compile-error | `type mismatch: expected kotlin.Long but was kotlin.Int` | box/binaryOp/overflowLong.kt |
| 3 | runtime-fail | `Exception in thread "_" java.lang.ClassCastException: class kotlin.Long cannot be cast to class kotlin.Int` | box/casts/objectToPrimitiveWithAssertion.kt |
| 3 | runtime-fail | `box() returned: FAIL` | box/classes/kt2477.kt |
| 3 | runtime-fail | `box() returned: lengthCtr != 5, was: '9'` | box/controlStructures/forInCharSequenceWithIndex/forInCharSequenceWithIndexCheckSideEffects.kt |
| 3 | compile-error | `0_test.kt: error: unsupported construct: class '_' implements a function type without an invoke function` | box/coroutines/suspendFunctionAsSupertype.kt |
| 3 | compile-error | `0_test.kt:N:N: error: unsupported construct: conversion from TObject(qualifiedName=kotlin.Any, cName=mk_Any, nullable=true, isInterface=false) to Nothing` | box/specialBuiltins/emptyList.kt |
| 3 | compile-error | `1_2.kt: error: unsupported construct: reference to a variable that is not a local of the current function ('_')` | boxInline/innerClasses/innerInlineFunCapturesOuter.kt |
| 2 | runtime-fail | `box() returned: fail O` | box/argumentOrder/argumentOrderInObjectSuperCall.kt |
| 2 | runtime-fail | `box() returned: fail 4: init receiver constraints` | box/argumentOrder/varargAndDefaultParameters_ForNative.kt |
| 2 | ice | `internal compiler error: mini.lower.LowerError: offset N: unary operator is not resolved (in function box)` | box/arithmetic/basic_shifts.kt |
| 2 | runtime-fail | `box() returned: FAIL: 0` | box/basics/unchecked_cast2.kt |
| 2 | runtime-fail | `box() returned: OK` | box/basics/unchecked_cast3.kt |
| 2 | runtime-fail | `box() returned: FAIL: 42` | box/bridges/test19.kt |
