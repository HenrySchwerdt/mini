# macOS release compiler local-symbol stripping

## Result

On the published Mini 0.7.0 macOS arm64 package used for this experiment, `strip -x` on a copied `minic-native` reduced the compiler from 60,849,928 bytes to 47,100,464 bytes (13,749,464 bytes, 22.60%). Repacking the copied installation reduced the tarball from 20,761,241 bytes to 18,831,722 bytes (1,929,519 bytes, 9.29%). The regular-file installation payload fell from 65,591,728 bytes to 51,842,264 bytes.

The Mach-O `__LINKEDIT` segment fell from 19,283,968 to 5,537,792 bytes. The `__TEXT` segment and `__text` section were unchanged. Before stripping, the symbol table held 132,542 local symbols; afterward it held one. Both the `nm -gU` external symbol-name set and `dyld_info -exports` export-trie name set matched exactly before and after (36,692 names each). Their sorted-name SHA-256 values were both `fb27b4187c3a715bb74803acbf954c071aa9863703e8b1c504a1c6b3af754894`.

The trial used only temporary copies under `/tmp/campaign-size-striptrial-8ce.V4PKi7`; the source archive and original compiler were not modified. The result supports stripping the packaged macOS arm64 copy only. It does not support changing exported symbols, Linux packages, bootstrap stages, or generated user executables.

## Staged package regression check

`scripts/package-release.sh` now applies `strip -x` only to `STAGE/bin/minic-native` when `TARGET=macos-arm64`, then calls `scripts/test-package-release-symbols.sh` with the unstripped bootstrap stage and the stripped staged copy. The regression check compares both external defined symbols and the dyld export trie, verifies that local-symbol count fell, and requires the staged binary to be smaller. Missing `strip` or a strip/check failure aborts packaging.

## Smoke checks on the stripped staged copy

Using the copied 0.7.0 installation with the stripped compiler:

- Default LLVM API and explicit C Hello builds ran and matched.
- `cinterop_basics.kt` built and ran with both backends; each output matched the checked-in expected output.
- `classes_basic.kt` and `kclass.kt` built and ran with both backends; constructor and type-metadata outputs matched.
- `compiler/llvm-c/tests/repl-session.sh` passed, including cell rollback and ORC use.
- The installed `mini repl` handled `val`, `fun`, `Int` type info, an owned-value transfer, a failed cell, and a later duplicate declaration while retaining prior valid state.
- `compiler/llvm-c/tests/session-api-smoke.sh` passed with LLVM 18, including ORC constructor forwarding.
- A C `--debug` build still invoked `cc` with `-O0 -g -fno-omit-frame-pointer`. The packaging change does not touch the user-build path. On this host, a direct Apple Clang `-g` compile produced DWARF in the object file, while the plain linked executable did not retain a `__DWARF` segment; no claim is made here that a linked debug executable has usable DWARF.

The release compiler no longer carries its large local-symbol table, so local-symbolication information for that packaged `minic-native` is reduced. External exports are preserved, and this does not affect source or bootstrap compiler artifacts.
