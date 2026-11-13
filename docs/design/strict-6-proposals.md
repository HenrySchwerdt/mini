# strict-6 proposals: the 19 remaining Strict rejections of the real-world programs

Status: **design only, nothing here is implemented.** Each proposal extends the specification (`docs/memory-model.md`), so the
decision is the user's. Basis: strict-5 (`claude/strict-5`, PR #122), `scripts/strict-coverage.sh --set realworld` = 16 of 35 accepted.

Method. The 19 rejected programs were checked again with `minic-native --check --error-limit=0` (all diagnostics, not only the first one that
`docs/realworld.md` lists). Line numbers below are lines of the files in `tests/realworld` (without the `@file:MemoryMode` line the checker adds).
"Would be accepted" is an *estimate from the diagnostic list*: a program counts only if every diagnostic it has falls into a group the
variant removes. Second-order diagnostics that appear after the first ones disappear cannot be excluded until it is implemented.

## 0. What the checker does today (the architecture the proposals extend)

* `compiler/backend/src/memory/Transfer.kt`, `takeOwnership` (l. 229): a value that is handed to a new owner (owned parameter, return, owning field)
  must be fresh or a real owner; a borrowed one is **M0109** (`borrowed x is returned as owned`, `... stored in an owned field`).
* The call transfer (l. 436-476): a tracked argument for a *foreign* (Managed/library) callee whose summary says the parameter escapes is **M0110**,
  *except* when `countsReferences(fn)` (collections, vararg, `Pair`, `Triple`, `refArraySet`) and `ownedByDefault(v)` (l. 580-591) hold:
  the keeper then retains its own count. `ownedByDefault` demands `sites` = only `SK.Default` and that the value is **not borrowed**
  (`sl.bits and B == 0`). A borrowed parameter has no sites at all (its allocator is unknown), so it fails both tests.
* Escaping closures (l. 854-863): capture of an owner = a *borrowing capture* (field not counted); if the closure is kept: **M0112** (owner) / **M0109** (borrowed).
* Strict-5a: the default allocator allocates **reference counted** objects (count 1, belonging to the owner); the object header always has a count
  (`MkObject`: type, refcount, flags). Strict-5c already lets a collection add its own count of such an object.

**Common observation.** Every rejection below is rejected because the checker does not know the allocator of a *borrowed* value, so it
cannot give the new keeper a share. But in all 19 programs the class of the value is a plain class without `@AllocatedBy` (the programs are ordinary Kotlin,
no allocator anywhere). If the **static class of the value and all its subclasses is default-allocated** ("default-closed", checkable
from class declarations and the `.mlib` metadata), then the object is reference counted *whatever* the borrower is, and a keeper can
retain it. That is the one fact the proposals build on (**F**).

## 1. A borrowed value is returned as owner or stored in an owned field (9 programs)

Evidence (original line numbers):
`bank_domain.kt:113` `fun open(account: Account) = account.also { repo.save(it.id, it) }` (returns the parameter);
`cli_tool.kt:49` `val parsed = Parsed(cmd)` (`cmd` found in `commands`, stored in owned field `Parsed.command`, l. 11);
`huffman_coding.kt:27` `return queue[0]` (a loan of an element); `html_dsl.kt:30-33` `protected fun <T : Node> add(child: T, ...): T { children.add(child); return child }`
(11 diagnostics, one per `head`/`body`/`div` ... l. 55-85); `mini_interpreter.kt:251-252` `Env(env)` where `Env(val parent: Env?)` (l. 207) owns its parent (4 diagnostics);
`json_codec.kt:21, 50` `return v`; `bignum_arithmetic.kt:78` `return result`; `trie_autocomplete.kt:26` `return n` (loop variable over the trie);
`library_system.kt:72` `fun add(vararg items: Media) = items.forEach { catalog[it.isbn] = it }`.

### 1A. Counted upgrade of a borrowed value of a default-closed class (retain on escape)

Rule: when a borrowed value whose static class is default-closed is returned, stored in an owned field or passed to an `@Owned` parameter, the
compiler emits a **retain** and treats the result as a fresh owner (the caller or the field releases it). The borrower's own owner is unchanged.
M0109 remains for every other class (Heap, Pool, Arena, unknown).
* Soundness: the object cannot die while the new owner holds its count; the original owner's `free()` or scope end releases only its own
  count; a use through the original owner after `free()` stays M0101 (the static rules do not change). The one new fact is F; the closed-world check
  must cover subclasses (sealed/final or all subclasses in the program/mlib; an open class that a library user may subclass with `@AllocatedBy(Heap)` is not default-closed).
  Retain cycles (a child keeping its parent that keeps the child) leak, as for Managed `Rc`; `Env.parent` is acyclic.
* Cost: one non-atomic increment on the escaping path, one decrement when the new owner ends. No cost elsewhere.
* Annotation: none.
* Effort: **M** (`takeOwnership` branch for `v.src` borrowed / `v.refs`, a class-closure query, one MIR retain at the return/field store; the retain op exists since 5c).
* Accepted (alone): `bank_domain` (1 diagnostic), `cli_tool` (1), `huffman_coding` (1), `html_dsl` (11 of the same kind), `mini_interpreter` (4 of the same kind) = **5**.
  With 3A in addition: `library_system` (M0109 at l. 82 plus four M0110) and probably `json_codec` (M0109 x5 plus one M0110 at l. 184, `x.entries.associate { ... }`, unexamined).
  **Not helped:** `bignum_arithmetic` has M0101 at l. 139 and 143 (`val t = a + b; a = b; b = t` in `repeat`: use after free of `t`) and M0110 at l. 161, and `trie_autocomplete` has an M0101 at l. 72
  (`n.children.entries.first()` loan ended by a mutating call, 5b). Those are separate checker issues (possibly false positives, to be examined first), not ownership gaps.

### 1B. Lifetime annotation on the result (`: @Borrowed T`, or a new `@Borrows(param)` that names the lender)

Already in the language: `: @Borrowed T` (4.3) and `@Borrowed val f`. A new `@Borrows(item)` would let the *caller* know which argument the result is a loan of
(today `carries` is derived by the summary for closures/local objects only).
* Soundness: yes, the existing loan rules (valid until the lender is freed/moved/overwritten, M0101 after).
* Cost: none at run time.
* Annotation: on every such function and field, and the *callers must stop treating the result as an owner*. `bank_domain.open` is used as a value stored in a map: no change of the program makes that a borrow.
* Effort: S for what exists, M for `@Borrows`. Accepted without editing the programs: **0**. It is the right tool for code that really lends (`first(h)`), not for code that shares.

### 1C. Explicit clone (`@Owned` copy / `.copy()`)

The checker would require the program to copy (`account.copy()`) where it returns a borrowed value.
* Soundness: yes. Cost: allocation and copy per call. Annotation: edits in the program, and **the semantics change** (identity: `bank_domain.open` must return *the same* account that the repository stores; `html_dsl.add` must return the node that is already in `children`; a copy would silently drop later mutations). Reject: it makes Kotlin programs wrong, not safe. Effort: M. Accepted: 0 without semantic edits.

### 1D. Ban plus fix-it (status quo, better message)

Keep M0109, improve the help text: today "declare the parameter `@Owned` (and move at the call), or use `.move()`" is wrong for a shared value; it should say
"the class is default-allocated: the value can be shared" (once 1A exists) or "return `@Borrowed`" otherwise. Effort S. Accepted: 0.

**Recommendation 1: 1A**, with the 1D message for the classes that are not default-closed. 1B stays available for code that wants to lend.
Programs: +5 alone, +7 with 3A (+1 conditional).

## 2. Owner and borrower mixed on paths (2 programs)

Evidence: `state_machine.kt:44-45` the `when` yields the incoming state `s` (a parameter, borrowed) in six branches and a new `VendState(...)` in others (M0109 "new default-allocated object on some paths, borrowed on others");
`expression_calculator.kt:62` `val op = if (unary) Tok.Op('~') else t` (a fresh object or the loop variable `t` borrowed from `tokens`), and `:54-55` `out.add(t)` (M0110, group 3).

### 2A. Falls out of 1A

With the counted upgrade, the borrowed arm is retained, so both arms are owners (fresh count 1, retained count +1): the join is an owner, no new rule. Soundness, cost, annotation as 1A (a retain on the borrowed arm only). Effort **S** on top of 1A (the join in `Transfer.kt:767` stops reporting when both sides are owners after the upgrade).
Accepted: `state_machine` (1 diagnostic, sole), `expression_calculator` (with 3A: M0110 at l. 54-56, 67, 69 are the same group) = **2**.

### 2B. Require the program to write the clone (`else t.copy()`) / 2C. split the paths

2B has the semantics problem of 1C. 2C (analyse each path with its own ownership and duplicate the continuation) explodes code and does not give the caller one answer; reject.

**Recommendation 2: 2A** (no separate work). Programs +2 (with 3A).

## 3. A borrowed value goes into another collection (4 programs, plus 3 that share it)

Evidence: `aoc_dynamic_programming.kt:55` `chosen.add(items[idx - 1])`; `aoc_grid_paths.kt:37` `queue.addLast(start)` (15 diagnostics, all this kind: `prev[start] = start`, `queue.addLast(next)`, ...);
`graph_algorithms.kt:62` `chosen.add(e)` (`e` = loop variable over `edges.sortedWith`); `priority_scheduler.kt:66` `out.add(t)` (`t = ready.pop()`, 4 diagnostics incl. l. 130);
also `library_system.kt:72`, `expression_calculator.kt:54-55`. The element classes are `data class Item/Edge<N>/Task/Point` (default-closed).

### 3A. The 5c rule for borrowed values of a default-closed class

`ownedByDefault(v)` is widened to: (sites all `Default`, owner or fresh) **or** (static class default-closed, any value that is not null). The keeper (`kotlin.collections.*`, vararg, `Pair`, `refArraySet`) retains its own count exactly like in 5c.
* Soundness: same argument as 5c (the collection holds a count of its own), and the loan rules of 5b still hold for the element read back. The borrowed value's lender may be freed; the object stays alive in the collection. A *use* of the borrowed value after its lender's `free()` stays M0101.
* Cost: one retain on add, one release when the collection drops the element (the release path exists). No extra cost for programs that do not do this.
* Annotation: none. Effort: **S** (one predicate, the closed-class query shared with 1A).
* Accepted (alone): `aoc_dynamic_programming` (1), `graph_algorithms` (1), `priority_scheduler` (4), `aoc_grid_paths` (15) = **4**, all with only diagnostics of this kind. With 1A/2A: `library_system`, `expression_calculator` (+2) and likely `json_codec`.

### 3B. Opt-in counted class for the other allocators (`@Shared`/`@Counted`)

A class marked `@Counted` promises that its objects are counted (header count is always there) and that `free()` means "release"; borrowed values of it may then be retained, even when `@AllocatedBy(Heap)`.
* Soundness: yes if `free()` becomes a release for those objects. Cost: counting only for these. Annotation: on the class. Effort: **L** (changes `Heap.free` semantics: today the free is immediate and a later use is memory unsafe, so every Heap-related rule of section 5 would have to be restated). Accepted: **0** of the 35 (none of them uses an allocator). Not recommended now.

### 3C. Statement-scoped borrowing collection (memory-model 5.4, plan 1)

Needs statement-scoped release of temporaries first. Covers only temporaries (`listOf(a, b).size`), not `chosen`/`queue`/`prev`, which live across loops and are returned. Effort **L**. Accepted: **0**.

### 3D. Ban plus fix-it (`@Owned` copy)

Same objection as 1C (copy per element, identity changes: `prev[start] = start`). Accepted: 0.

**Recommendation 3: 3A.** It is the cheapest and the most productive proposal (4 programs alone, 7 with 1A/2A, and it removes the common cause for the later ones).

## 4. A kept closure captures a borrowed `this` or an owner (3 programs)

Evidence: `language_tour.kt:57` `override fun iterator() = iterator { for (y in 0 until h) for (x in 0 until w) yield(x to y) }` (captures borrowed `this`; sole diagnostic);
`event_system.kt:104` `bus.register({ true }) { shop.apply(it) }` where `register` does `handlers.add(filter to handler)` (l. 37): the local owner `shop` is captured by a closure that the bus keeps (M0112, sole diagnostic);
`coroutine_scheduler.kt:32` `post { body.startCoroutine(this, ...) }` with `post` = `ready.addLast(block)` (l. 26-28) captures `this`; the same file has eight more diagnostics (M0112 at l. 132/156/163/164, M0104 at l. 153-156, M0107 at l. 121/161) about suspend lambdas.

### 4A. Counting capture for escaping closures over a default-closed class

Today a capture of an owner is *borrowing* (the closure field is not counted), which is why a kept closure is M0112. Proposal: for a closure that the checker finds **escaping** (the sites that give M0112/M0109 now), capture default-closed values **counted** (retain at closure creation, release when the closure object dies; the closure is already a reference-counted object whose dispose releases its fields). Closures that do not escape keep the free, borrowing capture.
* Soundness: the closure keeps the object alive, so nothing dangles; the later use of the owner after `free()` stays M0101. Not a loan any more, so no M0112/M0109 for these captures. Risk: **retain cycles** (`Scheduler` -> `ready` -> closure -> `Scheduler`) leak: ASAN/LSAN would report them in `strict-leak`. Strict has no cycle collector; the diagnostic could stay a *warning* for a capture of `this` into a field of `this`.
* Cost: one retain/release pair per escaping closure and captured value. Annotation: none. Effort: **M-L** (the checker part is small, the code generator has to know which captures of a closure are counted; Managed capture of `Rc` values already does the same).
* Accepted: `language_tour` (1), `event_system` (1) = **2**. `coroutine_scheduler` would lose the M0112 and probably most of the others, but its M0104/M0107 (`p` moved, `output` borrowed) are not shown to be of this group: **unverified, count 0**.

### 4B. Lifetime annotation on the callee's parameter (`block: @Scoped () -> Unit`, "not kept")

The author promises that `post`/`register`/`iterator` does not keep the closure. **Unsound for exactly these three** (the scheduler and the bus keep it): the annotation would be a lie the checker cannot check across Managed code. Reject (only valid for callees whose body is Strict and verified).

### 4C. Copy to a local and capture the copy / explicit capture list

Capture lists are not Kotlin. Copying the owner (`val s = shop`) only makes another alias. Reject.

### 4D. Ban plus fix-it

Message: "the closure is kept by `register`; capture a copy of the fields it needs, or make the class `@Counted`". Effort S. Accepted: 0.

**Recommendation 4: 4A, restricted to escaping closures and default-closed classes, with the cycle caveat documented.** Programs +2 (`coroutine_scheduler` unlikely).

## 5. Two borrowed values in a `Pair` (1 program)

Evidence: `thread_pool.kt:58` `val (first, second) = if (from.id < to.id) from to to else to to from` (two borrowed parameters into `Pair`, M0110 twice).
The same file has M0204 at l. 114-116 and M0104 at l. 115-119: `Account` objects are moved into `thread { ... }` lambdas, and a default-allocated object cannot be moved into a thread (2.2a).

### 5A. Falls out of 3A (`kotlin.Pair` is in `countsReferences`)

The Pair retains both. This removes the two M0110 and nothing else. Effort: none beyond 3A. Accepted: **0** (the thread diagnostics remain, and they are correct under the current rule).

### 5B. Default-allocated objects across threads (retain per captured thread, atomic count)

The runtime already switches all counts to atomic from the first started thread (`docs/threads.md`: `mk_threads_active`, `MK_ATOMIC_RC`). The checker could let a default-closed object be *shared* with a thread (retain for the lambda, no move) instead of M0204.
* Soundness: the lifetime is safe (atomic counts). **Data races on the fields are not excluded** (Strict says a thread needs a `ThreadSafe` allocator; for `Account` the program uses a lock): a decision about what Strict promises about races. Cost: atomic RC (already paid once threads exist). Annotation: none, or `@ThreadSafe class Account` as the explicit opt-in (recommended). Effort: **L** (thread-boundary rules in 5.9, M0204 text, `ThreadSafe` allocator semantics). Accepted: `thread_pool` (maybe; its M0104 at l. 115-119 need the share-not-move reading) = **0-1**.

### 5C. Ban plus fix-it

Status quo: `thread_pool` is correctly rejected today for the thread moves. Message improvement only.

**Recommendation 5: take 5A for free with 3A, do not decide 5B now** (a race-freedom question, one program).

## 6. Overall recommendation

| group | proposal | effort | run-time cost | annotations | programs (alone / cumulative, of 35) |
|---|---|---|---|---|---|
| (base) | strict-5 | - | - | - | 16 |
| 3 borrowed into a collection | **3A** retain for default-closed classes | S | 1 retain per add | none | +4 -> 20 |
| 1 borrowed returned / stored as owner | **1A** counted upgrade | M | 1 retain per escape | none | +5 -> 25 |
| 2 mixed paths | **2A** (join of two owners after 1A) | S | 1 retain on the borrowed arm | none | +1 -> 26 |
| 1+2+3 combined | the three share the closed-class query and the retain op | | | | +`library_system`, `expression_calculator` -> 28; `json_codec` probably -> 29 |
| 4 closure over borrowed `this` / owner | **4A** counting capture for escaping closures | M-L | 1 retain per capture | none | +`language_tour`, `event_system` -> 30-31 |
| 5 pair | 5A with 3A; 5B not now | - | - | - | +0 |

Combination **3A + 1A + 2A (+4A)**: **28 of 35 certain by the diagnostic lists, 29 with `json_codec`, 31 with 4A**. All of it rests on one new
fact (F: the class is default-closed, a purely static property) and on retain/release operations that 5c already has in the back end; no
annotation is needed in the programs. The run-time cost is one non-atomic count update per sharing event, only at the events that are rejected today.

Left over after all of it (4 programs, none an ownership gap of the kind above):
`bignum_arithmetic` and `trie_autocomplete` (M0101 on loans after reassignment / collection changes; to be examined as checker issues first,
maybe false positives), `coroutine_scheduler` (suspend lambdas: M0104/M0107/M0112 on owners captured across suspension points), `thread_pool` (default objects moved into threads, 5B).

Order of work if approved: (1) the default-closed class query (the shared piece, S), (2) 3A, (3) 1A + 2A, (4) strict-corpus: every rejected-before program needs a *rejected* twin
for a Heap class and for an open class with a Heap subclass (no false negatives), plus leak tests under `strict-leak`/`strict-llvm-leak`, (5) 4A with the cycle test, (6) the two M0101 issues.
Soundness gate for each step: `strict-corpus --run` with 0 false positives and 0 false negatives.
