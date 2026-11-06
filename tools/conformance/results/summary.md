# Kotlin box conformance results

| | count |
|---|---:|
| total test files | 10070 |
| skipped (not applicable) | 4099 |
| **applicable** | **5971** |
| **pass** | **5425 (90.86%)** |
| compile-error | 381 |
| ice (compiler crash) | 42 |
| runtime-fail | 93 |
| crash (signal) | 28 |
| timeout | 2 |

## Native parity (also excludes tests Kotlin/Native itself does not pass or run)

| | count |
|---|---:|
| applicable (above) | 5971 |
| **native-parity applicable** | **5971** |
| **native-parity pass** | **5425 (90.86%)** |

Wall time: about 5000s (a full run on a loaded machine; the summary merges a re-run of the 99 enum / companion tests after the last compiler change).

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
| 156 | compile-error | `unresolved call '_'` | box/argumentOrder/kt17691WithEnabledFeature.kt |
| 32 | compile-error | `unresolved reference '_'` | box/callableReference/function/classMemberFromCompanionObject.kt |
| 28 | crash | `signal 11` | box/basics/k42000_1.kt |
| 18 | compile-error | `unresolved callable reference '_'` | box/callableReference/kt47988.kt |
| 15 | compile-error | `0_test.kt:N:N: error: unsupported construct: arithmetic on TObject(qualifiedName=kotlin.Any, cName=mk_Any, nullable=true, isInterface=false)` | box/inlineClasses/UIntArraySortExampleGeneric.kt |
| 11 | compile-error | `0_test.kt:N:N: error: unsupported construct: call to '_'` | box/bridges/test24.kt |
| 10 | compile-error | `unresolved type '_'` | box/annotations/nestedClassesInAnnotations.kt |
| 8 | ice | `internal compiler error: the C compiler (cc) rejected the C code that minic generated` | box/arrays/kt47483.kt |
| 8 | compile-error | `no matching callable for reference '_'` | box/callableReference/adaptedReferences/nestedClassConstructorWithDefault.kt |
| 8 | compile-error | `0_test.kt: error: unsupported construct: type '_'` | box/callableReference/property/enumNameOrdinal.kt |
| 8 | compile-error | `0_test.kt: error: unsupported construct: call of constructor of '_'` | box/enum/defaultCtor/noPrimaryConstructor.kt |
| 5 | compile-error | `0_test.kt: error: unsupported construct: comparison of Unit` | box/basics/unchecked_cast10.kt |
| 5 | compile-error | `no operator plus for <error>` | box/builderInference/pclaRootIsElvisSynthetic.kt |
| 5 | ice | `internal compiler error: mini.lower.LowerError: offset N: set is not resolved (in function box)` | box/defaultArguments/convention/incWithDefaults.kt |
| 4 | compile-error | `0_test.kt:N:N: error: unsupported construct: type '_'` | box/annotations/instances/nestedAnnotationInstances.kt |
| 4 | runtime-fail | `box() returned: FAIL: 3` | box/basics/unchecked_cast5.kt |
| 4 | compile-error | `0_test.kt: error: unsupported construct: call to '_'` | box/boxingOptimization/kt5493.kt |
| 4 | compile-error | `0_test.kt:N:N: error: unsupported construct: string conversion of a value of type Nothing` | box/controlStructures/breakContinueInExpressions/breakFromOuter.kt |
| 4 | compile-error | `for-loop range must have an iterator() method` | box/coroutines/kt25912.kt |
| 4 | runtime-fail | `box() returned: fail` | box/innerClass/doubleInner.kt |
| 4 | runtime-fail | `box() returned: fail 1` | box/operatorConventions/infixFunctionOverBuiltinMember.kt |
| 3 | compile-error | `0_test.kt:N:N: error: unsupported construct: construction of '_'` | box/annotations/instances/annotationsUnsignedTypes.kt |
| 3 | compile-error | `unresolved infix function and` | box/binaryOp/bitwiseOp.kt |
| 3 | compile-error | `type mismatch: expected kotlin.Long but was kotlin.Int` | box/binaryOp/overflowLong.kt |
| 3 | compile-error | `0_test.kt:N:N: error: unsupported construct: reference to constructor of '_'` | box/callableReference/arrayConstructor.kt |
| 3 | runtime-fail | `Exception in thread "_" java.lang.ClassCastException: class kotlin.Long cannot be cast to class kotlin.Int` | box/casts/objectToPrimitiveWithAssertion.kt |
| 3 | compile-error | `0_test.kt:N:N: error: unsupported construct: reference to a variable that is not a local of the current function ('_')` | box/closures/captureInSuperConstructorCall/outerCapturedInPrimaryConstructorDefaultParameter.kt |
| 3 | runtime-fail | `Exception in thread "_" java.lang.ArrayIndexOutOfBoundsException: Index N out of bounds for length N` | box/controlStructures/forInArray/forInArrayWithArrayVarUpdatedInLoopBody13.kt |
| 3 | compile-error | `0_test.kt: error: unsupported construct: class '_' implements a function type without an invoke function` | box/coroutines/suspendFunctionAsSupertype.kt |
| 3 | runtime-fail | `box() returned: Failure 0: l = caught;` | box/enum/initEntriesInCompanionObject.kt |
| 3 | compile-error | `syntax error: Expecting an element` | box/involvesIrInterpreter/inJavaPackage.kt |
| 3 | compile-error | `1_2.kt: error: unsupported construct: reference to a variable that is not a local of the current function ('_')` | boxInline/innerClasses/innerInlineFunCapturesOuter.kt |
| 2 | runtime-fail | `box() returned: fail 4: init receiver constraints` | box/argumentOrder/varargAndDefaultParameters_ForNative.kt |
| 2 | runtime-fail | `box() returned: FAIL: 0` | box/basics/unchecked_cast2.kt |
| 2 | runtime-fail | `box() returned: OK` | box/basics/unchecked_cast3.kt |
| 2 | runtime-fail | `box() returned: FAIL: 42` | box/bridges/test19.kt |
| 2 | runtime-fail | `Exception in thread "_" java.lang.AssertionError: box.::target$N@408ddb80 and box.::target$N@408ddb80 should NOT be equal` | box/callableReference/equality/coercionToUnit.kt |
| 2 | runtime-fail | `box() returned: Fail` | box/casts/asUnit.kt |
| 2 | compile-error | `0_test.kt:N:N: error: unsupported construct: call with omitted argument '_' of '_'` | box/classes/classObjectAsExtensionReceiver.kt |
| 2 | compile-error | `Suspend function type should be called only from a coroutine or another suspend function` | box/coroutines/kt51530.kt |
