# Kotlin box conformance results

| | count |
|---|---:|
| total test files | 10070 |
| skipped (not applicable) | 4099 |
| **applicable** | **5971** |
| **pass** | **5711 (95.65%)** |
| compile-error | 130 |
| ice (compiler crash) | 27 |
| runtime-fail | 86 |
| crash (signal) | 14 |
| timeout | 3 |

## Native parity (also excludes tests Kotlin/Native itself does not pass or run)

| | count |
|---|---:|
| applicable (above) | 5971 |
| **native-parity applicable** | **5971** |
| **native-parity pass** | **5711 (95.65%)** |

Wall time: 207s.

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
| 33 | compile-error | `unresolved call '_'` | box/builderInference/issues/kt53478.kt |
| 14 | crash | `signal 11` | box/basics/k42000_1.kt |
| 13 | compile-error | `unresolved reference '_'` | box/callableReference/function/classMemberFromCompanionObject.kt |
| 9 | ice | `internal compiler error: the C compiler (cc) rejected the C code that minic generated` | box/arrays/kt47483.kt |
| 8 | compile-error | `no matching callable for reference '_'` | box/callableReference/adaptedReferences/referenceToVarargWithDefaults.kt |
| 6 | compile-error | `0_test.kt: error: unsupported construct: call of constructor of '_'` | box/primitiveTypes/numberToChar/abstractMethodInSuperinterface.kt |
| 4 | runtime-fail | `box() returned: FAIL: 3` | box/basics/unchecked_cast5.kt |
| 4 | compile-error | `unresolved callable reference '_'` | box/callableReference/function/referenceToCompanionMember.kt |
| 4 | runtime-fail | `box() returned: Fail` | box/casts/asUnit.kt |
| 4 | runtime-fail | `box() returned: fail 1` | box/operatorConventions/infixFunctionOverBuiltinMember.kt |
| 3 | compile-error | `0_test.kt:N:N: error: unsupported construct: construction of '_'` | box/annotations/instances/annotationsUnsignedTypes.kt |
| 3 | compile-error | `0_test.kt:N:N: error: unsupported construct: type '_'` | box/annotations/instances/nestedAnnotationInstances.kt |
| 3 | compile-error | `no operator plus for <error>` | box/builderInference/pclaRootIsElvisSynthetic.kt |
| 3 | runtime-fail | `Exception in thread "_" java.lang.ClassCastException: class kotlin.Long cannot be cast to class kotlin.Int` | box/casts/objectToPrimitiveWithAssertion.kt |
| 3 | compile-error | `0_test.kt: error: unsupported construct: class '_' implements a function type without an invoke function` | box/coroutines/suspendFunctionAsSupertype.kt |
| 3 | compile-error | `0_test.kt: error: unsupported construct: call to '_'` | box/inlineClasses/equalityForBoxesOfNullableValuesOfInlineClass.kt |
| 3 | runtime-fail | `Exception in thread "_" java.lang.NullPointerException` | box/inlineClasses/inlineClassCollection/inlineCollectionOfInlineClassGeneric.kt |
| 3 | compile-error | `1_2.kt: error: unsupported construct: reference to a variable that is not a local of the current function ('_')` | boxInline/innerClasses/innerInlineFunCapturesOuter.kt |
| 2 | runtime-fail | `box() returned: fail 4: init receiver constraints` | box/argumentOrder/varargAndDefaultParameters_ForNative.kt |
| 2 | runtime-fail | `box() returned: FAIL: 0` | box/basics/unchecked_cast2.kt |
| 2 | runtime-fail | `box() returned: OK` | box/basics/unchecked_cast3.kt |
| 2 | runtime-fail | `box() returned: FAIL: 42` | box/bridges/test19.kt |
| 2 | runtime-fail | `Exception in thread "_" java.lang.AssertionError: box.::target$N@1eef and box.::target$N@1eef should NOT be equal` | box/callableReference/equality/coercionToUnit.kt |
| 2 | compile-error | `0_test.kt: error: unsupported construct: type '_'` | box/callableReference/property/enumNameOrdinal.kt |
| 2 | compile-error | `Suspend function type should be called only from a coroutine or another suspend function` | box/coroutines/kt51530.kt |
| 2 | runtime-fail | `box() returned: Fail toString` | box/dataClasses/nonTrivialFinalMemberInSuperClass.kt |
| 2 | compile-error | `0_test.kt:N:N: error: unsupported construct: call of '_'` | box/dataObjects/hashCode.kt |
| 2 | ice | `internal compiler error: mini.lower.LowerError: offset N: implicit receiver not found` | box/enum/kt20651_inlineLambda.kt |
| 2 | runtime-fail | `Exception in thread "_" java.lang.AssertionError: Expected the value to be false.` | box/initializers/object.kt |
| 2 | runtime-fail | `>.` | box/inline/inline24.kt |
| 2 | compile-error | `0_test.kt:N:N: error: unsupported construct: call to '_'` | box/involvesIrInterpreter/annotationClassWithInner.kt |
| 2 | compile-error | `syntax error: Expecting an element` | box/involvesIrInterpreter/inJavaPackage.kt |
| 2 | compile-error | `syntax error: unexpected character '_'` | box/involvesIrInterpreter/thisPlusStringWithObject.kt |
| 2 | timeout | `run timeout` | box/lower/forLoopsLowering/listsCME.kt |
| 1 | compile-error | `unresolved type '_'` | box/annotations/nestedClassesInAnnotations.kt |
| 1 | runtime-fail | `box() returned: fail1` | box/annotations/resolveWithLowPriorityAnnotation.kt |
| 1 | compile-error | `1_a.kt:N:N: error: unsupported construct: type '_'` | box/annotations/instances/multifileEqHc.kt |
| 1 | timeout | `compile timeout` | box/arrays/primitiveArrays.kt |
| 1 | runtime-fail | `box() returned: FAIL 1: kotlin.Any@3a4a722d` | box/basics/unchecked_cast10.kt |
| 1 | runtime-fail | `box() returned: FAIL: zzz` | box/basics/unchecked_cast13.kt |
