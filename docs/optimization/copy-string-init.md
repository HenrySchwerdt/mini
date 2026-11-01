# Rejected copy-initialized String allocation pilot

The prototype kept `mk_alloc_sized_site` zeroing every public allocation, and
added a default-allocator-only partial-zero path for copied strings. Only
`mk_string_substring` and `mk_sb_to_string` used it. The new path zeroed the
hidden allocator prefix and String header/length prefix, copied every live
UTF-16 unit before returning, and preserved any trailing struct padding as
zero. Public `mk_string_alloc`, UTF-8 construction, and concatenation remained
fully zeroed.

## Decision

Rejected. Across 11 paired JSON rounds, Mini wall time did not improve beyond
noise, while retired instructions rose 1.65% (paired median ratio 1.01654,
bootstrap 95% interval 1.01635–1.01717). The JSON app remained 1.216x the strict
C-O3 wall time. The fixed 153-input self-compile likewise showed no wall gain
(median 0.99972x) and instructions rose 0.793%. Focused copy controls and a
generic allocator-only control all increased instructions. The implementation
added an out-of-line forwarding branch to ordinary `mk_alloc_sized_site`,
offsetting the skipped payload memset.

## Validation and measurement

- Branch base: `713f0fa` (`713f0fa` integration runtime); app compiler object
  SHA-256 `a1d01d1eed05ff161a8b8095d2efca9721a7a421d269c050015fda9a8065e5f0`.
- Baseline runtime: C17 `clang -std=c17 -O2 -I runtime/include`, 18 objects;
  archive SHA-256 `74b9543f1ad2eaac1a73fdf1f21d5420297e758b7d9b99c0f7586de01a0fce6e`.
- Candidate runtime used the same flags and archive, replacing only `alloc.o`,
  `string.o`, and `v1s.o`; archive SHA-256
  `3cc0e69c506695c6b1faa9e9e677108c4e1e4f8570fcde21c37e941e7c077d8a`.
- JSON: one warmup plus 11 rotated paired runs for baseline Mini, candidate
  Mini, strict C-O3, and C-O3-native. Every run printed
  `7055506 1072916 885982430`.
- Self-compile: one warmup plus five alternating paired runs on the fixed 153
  inputs from `/tmp/campaign-core-baseline`. Every run matched C SHA-256
  `a882afa376e862b647d5abf512b327849f4c4653b113442c956b93d5f500d33e` and MIR
  SHA-256 `7abf6027e765fedd96ffac6e9b995968d53de676d1ca539ccc0d3d209d22a943`.
- Copy controls: five paired rounds of 100,000 short/long substring copies,
  short/long builder snapshots, and a 128-unit public-allocation control.
- Focused `test_string` (77 checks) and `test_v1s` (462 checks) passed under
  ASan+UBSan, UBSan with size-class allocation enabled, ASan+UBSan with
  `MK_PLAIN_MALLOC`, and `MK_STATS`; stats runs ended with allocations equal to
  frees and zero live objects. ASan leak detection is unsupported on this
  platform.
- JSONL samples were flushed and fsynced after each child. Raw samples and
  summaries are in `bench/results/campaign-copy-string-init-*`.

## Source and code-size hashes

The prototype source hashes were:

| File | SHA-256 |
| --- | --- |
| `runtime/src/alloc.c` | `d86a38d4b72130eaea1be2a917eee3fa21276014a1b9aa9414554ed3d625792f` |
| `runtime/src/internal.h` | `0956d91cf93e7b5d520aa938f8c75926ca643e654da789caf9ac18e4191c8c9f` |
| `runtime/src/string.c` | `2bf479bfe8f569d59a22fdb42af9ed2a863dc197a4779c55f22d02dcf9a25618` |
| `runtime/src/v1s.c` | `5d6fe6ffb33b6e5909ca316d2eba31e6f9400b5119214e0ca0efcea63cdcdb72` |

At O2, the three changed objects grew by 176 and 181 text bytes in `alloc.o`
and `string.o`, and shrank by 104 text bytes in `v1s.o`. The candidate archive
was 696 bytes larger.

## Failed static-inline follow-on

A narrower String-only destination helper and a `static inline` shared core were compiled once at O2. Disassembly still showed an out-of-line allocator core and a four-instruction public forwarding thunk. The variant failed its predeclared overhead gate; no further timings were run and it is not integrated. The source and object hash are preserved in [campaign-copy-string-init-inline-gate.json](../../bench/results/campaign-copy-string-init-inline-gate.json).

A final forced-inline experiment was authorized but not implemented: the worker reached its usage limit. No result or performance claim exists for that proposal.
