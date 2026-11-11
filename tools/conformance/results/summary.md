# Kotlin box conformance results

| | count |
|---|---:|
| total test files | 10070 |
| skipped (not applicable) | 4117 |
| **applicable** | **5953** |
| **pass** | **5939 (99.76%)** |
| compile-error | 11 |
| ice (compiler crash) | 0 |
| runtime-fail | 3 |
| crash (signal) | 0 |
| timeout | 0 |

## Native parity (also excludes tests Kotlin/Native itself does not pass or run)

| | count |
|---|---:|
| applicable (above) | 5953 |
| **native-parity applicable** | **5953** |
| **native-parity pass** | **5939 (99.76%)** |

Wall time: 5186s.

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
| 5 | compile-error | `unresolved call '_'` | box/callableReference/function/genericCallableReferenceWithReifiedTypeParam.kt |
| 2 | compile-error | `no matching callable for reference '_'` | box/callableReference/equality/conversionCombinations.kt |
| 1 | runtime-fail | `Exception in thread "_" java.lang.ClassCastException: class kotlin.Long cannot be cast to class kotlin.Int` | box/casts/objectToPrimitiveWithAssertion.kt |
| 1 | compile-error | `type mismatch: expected Subtype<A,B> but was Subtype.Trivial<A,kotlin.Any>` | box/inference/kt42042.kt |
| 1 | runtime-fail | `Exception in thread "_" java.lang.AssertionError: xByteStatic(N) != xByteDyanmic(N). Expected <N>, actual <N> are not the same instance.` | box/initializers/static_smallIntIdentity.kt |
| 1 | compile-error | `no matching super constructor` | box/localClasses/anonymousObjectInExtension.kt |
| 1 | runtime-fail | `Exception in thread "_" java.lang.AssertionError: Expected the value to be true.` | box/stringTrim/stringTrim.kt |
| 1 | compile-error | `unresolved reference '_'` | box/volatile/messagePassing.kt |
| 1 | compile-error | `type mismatch: expected kotlin.Unit but was kotlin.Nothing?` | boxInline/complex/nestedCallsWithGenericToInt.kt |
