# yu5f VM-B1 correction — independent review, 2026-11-17

- Reviewer: Astra; actual model `gpt-6-astra`, effort `xhigh`.
- Scope: production candidate `f812ee8e3867a85d466f1d43dfb0df2c4af68933`, metadata HEAD `362ab9e9f7e54d1a6a788b08f6ce2f2adab51abb`, predecessor `e808a18`, worktree `/home/puflik/src/Plinth-yu5f`.
- Protection baseline: fourteen paths at `b52b256eae8486e6fb92c7094039460740755b25`; original twelve additionally anchored at `92c78bd`.
- Ownership: this report only; code/tests/task remain read-only. No additional agents, builds, commits, pushes, merges or releases.
- Status: **PASS for the scoped VM-B1 production correction**. No actionable correctness finding. This is VM review, not acceptance of the unfinished UI or merge approval.

## Evidence recorded during review

- Read repository instructions, workflow/roles, bounded task and continuation sections, prior VM-B1/test reviews and CI baseline audit. Root graphify query completed; graph is stale and source will be authoritative. No graph update.
- Initial worktree inspection confirms clean `work/yu5f` at expected metadata HEAD, with production commit immediately before it. Concurrent root documentation/local skill changes are left untouched.
- Coordinator reports pre-dispatch usage 37% primary/6% weekly and tracks exact-head CI `44116043234` independently; last supplied status is in progress. No duplicate CI download or Gradle invocation is planned if supplied final artifacts are consistent.
- No user-data layer is changed; this review makes no CRDT, migration, relinking or recovery decision, so data-review specialization is not required.
- Independent Git checks confirm HEAD and `origin/work/yu5f` exactly match the supplied metadata SHA. `e808a18..HEAD` changes only `SearchViewModel.kt` (41 insertions/16 deletions) and the executor's task report. Production and metadata commits separate those changes; `git diff --check` passes.
- Parsed authoritative task list and command: same ordered fourteen unique paths; all fourteen have zero diff from `b52b256`, original twelve zero diff from `92c78bd`. Entire `app/src/test`, `app/src/sharedTest`, `app/src/androidTest` trees have zero diff from `b52b256`. Supplied protection JSON agrees; no test weakening/suppression is present.
- Independently parsed actual worktree XML: nine suites, 85 tests, zero failures/errors/skips, timestamps `2026-11-17T15:33:52–54Z`. Suite counts: Yu4g 4, cancel 3, retry 6, ordinary VM 8, songs 15, new B1 3, progressive 23, lifecycle readiness 6, readiness 17. Supplied final summary matches these totals and per-suite counts.
- Final JVM log confirms executed test task, `BUILD SUCCESSFUL in 53s`, 45 actionable tasks (4 executed/41 up-to-date); final compile/style log confirms executed detekt and both test Kotlin compilations, `BUILD SUCCESSFUL in 2m 1s`, 68 tasks (11 executed/57 up-to-date). Both retain `uniffiKotlin` (UP-TO-DATE). Historical initial results are not substituted for final execution.
- Full ktlint gate includes every configured source set; final `runKtlintCheckOverMainSourceSet` executes, while `ktlintMainSourceSetCheck`/`ktlintCheck` wrappers report UP-TO-DATE. Other source sets are UP-TO-DATE or NO-SOURCE/SKIPPED, not falsely described as rerun. Initial gate has exactly one detekt issue: `reconcile` complexity 16 exceeds 14; final gate passes after private `consumeRetries` extraction. Added production lines contain no suppression/ignore. There is no committed pre-extraction source snapshot to byte-compare that intermediate edit, so the one-correction history is corroborated by initial/final logs and executor report, not independently reconstructed from Git.
- Task's prescribed commands and executor report exclude only `:app:cargoNdkBuild`; final logs independently show `uniffiKotlin` retained and the required style/compile/test tasks present. The logs do not echo the full invocation, so the precise flags are attributed to the recorded commands/report. No reviewer rerun is needed for the consistent final evidence.

## Findings and invariant traces

All source line references below are in `app/src/main/kotlin/io/github/puflik/plinth/ui/search/SearchViewModel.kt` at reviewed HEAD unless a test filename is given.

### Resumed settings and immediate retry

`collect` creates its cancellation scope, sets `reconciled=false`, and emits the cached snapshot before collecting fresh inputs (`:274–282`). Its network-only `onStart` immediately contributes the previous settled text only when it equals the current trimmed raw text; otherwise it contributes empty text (`:281`). Therefore current normalized configurations no longer need to wait for the restarted nonblank debounce to form a context. Configuration collection itself still waits for real settings flow values: no default/old configuration is invented.

`reconcile` installs the fresh context, clears attempts for a changed query/master-off/empty text, removes absent providers, and retargets waiting attempts when the full normalized configuration changes (`:293–310`). Only after those steps does it mark the collection reconciled and consume retry intentions (`:311–312`). Removed-provider intentions fail identity lookup after removal; master-off clears all attempts, and empty active membership prevents automatic start. The resulting snapshot is None for master-off and preserves the retained completed sibling for provider removal. No forbidden readiness wait, search or loading entry is created by those retries.

Before reconciliation, an eligible retry stores the exact cached `Attempt` by provider (`:329–337`), without readiness/search. After reconciliation, the same intent goes through `replaceRetry` immediately. `consumeRetries` takes and clears the pending map before dispatch, rechecks current normalized raw text and requires referential identity with the surviving attempt (`:320–326`). Retained IA is therefore consumed once using the new ARCHIVE_ONLY context. Dispatch assigns a fresh attempt and uses the unchanged readiness path (`:341–365, :382–401`); the final reconcile loop skips an already started retry job (`:314–316`). The later identical debounced text neither clears completed siblings nor retargets that waiting attempt because query/configuration are unchanged.

Runtime evidence: `Yu5fAdditionalSearchTest.kt:23–50` and `:54–78` prove removed/master-off no new wait/search, correct visible state and no replay after 301 ms. `:82–129` proves exactly one ARCHIVE_ONLY wait after duplicate taps, unchanged wait identity and no cancellation through 301 ms, no search before Applied, exactly one later IA search and final result. All three actual XML testcases complete without failure/error/skip; this closes the original B1 and its previously unreached later assertions.

### Raw query changes and pending identity

The three new guards intentionally keep raw query `concert`; they do not dynamically establish these additional branches. Source analysis independently establishes the following:

1. Cache query A, stop beyond grace, call `onQuery(B)` while stopped, then resubscribe and retry before B settles: `onQuery` updates `query.value` synchronously (`:145–148`); the new `queryText` closure trims that current value (`:108`). Initial network text is empty because B differs from cached A (`:281`), so reconciliation clears A attempts and starts none. A retry before initial reconciliation sees B != old context A; one after sees B != empty context. Both return at `:331`. Only the unchanged normal debounce later supplies B, which creates B attempts with new identity/generation. No A retry wait/search is authorized.
2. Resume A, then quickly call `onQuery(B)` immediately before retry, without advancing debounce: `retry` reads current B when its VM coroutine executes, not a captured tap-time blank flag; equality with context A fails (`:329–331`). This remains true even when configuration reconciliation has already finished. Whitespace-only changes intentionally compare equal after trim and reuse the same semantic query.
3. Queue an A retry while the new collection is unreconciled, then change raw text to B before dispatch: `consumeRetries` clears the queued intention and returns on A != current B (`:321–323`). If B has already become the reconciled context, query invalidation cleared the old attempt and referential identity fails instead (`:296–299, :325`). A discarded intention cannot replay later when raw text/configuration returns to A because its pending map entry was removed. If an A retry coroutine was merely scheduled and B arrives before it runs, the ordinary equality guard rejects it earlier.

These are bounded control-flow checks of the authorized retry correction, not claims that extra changed-query runtime tests were executed. Ordinary automatic searches retain the existing settled-query debounce contract; this review does not redefine all raw-keypress behavior.

### Cache, duplicate taps, lifecycle and ABA

- Same-context first frame is preserved by retained `uiState` replay (`:137–140`) and `output.trySend(snapshot)` (`:278`). With unchanged text/settings, reconcile retains every completed attempt. The new onStart is scoped only to network collection; it does not change library `settled` or its `onStart(shownFound)` (`:110–122`).
- Provider-specific double taps before reconciliation overwrite the same provider/old-instance entry. Once dispatched, the fresh attempt has `completed=false`; another provider-specific tap cannot replace it (`:334–336`). The protected progressive guard `Yu5fProgressiveSearchTest.kt:329–353`, readiness sibling guard `Yu5fReadinessSearchTest.kt:498–524`, and new retained-IA guard all pass.
- Retrying one completed section only cancels/replaces that section (`:341–349`). Completed siblings survive membership-preserving changes, and started siblings are not retargeted: `waiting` requires both not completed and not started (`:255–256, :305`). Protected running-sibling, completed-sibling and lifecycle guards pass. The legacy no-argument retry retains its broader all-attempt selection; provider duplicate suppression is not newly imposed on that separate API.
- On collection termination, pending intentions are cleared, incomplete attempts replaced, attempt scope cancelled and output/scope nulled (`:283–289`). WhileSubscribed grace remains 5000 ms; within grace the same collection and attempts survive. After grace, an old pending attempt cannot become current in a new subscription because its map identity was replaced, even if a cancellation-ignoring transport finishes later. Completed cache entries intentionally survive.
- Query/master-off invalidation still advances generation (`:296–299`); provider removal/retarget/retry changes identity. `current` requires live scope, exact generation and exact attempt (`:397–401`). The unchanged `await` consumes Superseded once and obtains a fresh waiter; late Applied/Failed/results fail `current` before starting/publishing (`:388–391, :409`). Pending intentions also use identity, never just provider/query text, so they cannot resurrect a replaced attempt after A→B→A. Existing Superseded/ABA and late-finally lifecycle tests pass.

### Scope and unchanged behavior

Read-only string comparisons with predecessor confirm exact unchanged blocks for library debounce/configuration normalization, track/song actions, and the attempt readiness/completion implementation. Constructor still requires `OnlineReadiness` with no fallback (`:98–104`); configuration normalization remains the existing helper (`:124–127`, `OnlineRepository.kt:147–151`). Production diff introduces only private orchestration state/helper/signature changes: no public API, added delay/timer, permanent collector, UI, data, dependency or transport change. Calls remain provider-specific and use the established readiness gate. Test harness teardown still asserts no aggregate search or direct settings application.

## Verdict and bounded next step

**PASS.** Accept the scoped correction `f812ee8e3867a85d466f1d43dfb0df2c4af68933` with metadata HEAD `362ab9e9f7e54d1a6a788b08f6ce2f2adab51abb` as resolving VM-B1. No additional production correction or protected-test change is requested by this review. Preserve baseline14 `b52b256` and original12 `92c78bd`.

Coordinator must finish the separate exact-head full CI gate (both flavors) before the next bounded UI stage. Last supplied update for run `44116043234`: Rust success, final Android/overall result not confirmed after a network error; coordinator is checking directly. This report does not claim CI success. After CI acceptance and a future authorized budget window, proceed to the already planned Terra UI/scroll work, starting with emulator reproduction before UI edits, followed by its independent/runtime acceptance. Coordinator explicitly schedules no further UI executor today (latest reported usage 66% primary/10% weekly). Entire yu5f remains unmerged because its UI work is incomplete; VM PASS is not task completion.

## Limits and final ownership

- No Gradle/CI/emulator/native/live-YouTube execution was started by this reviewer. Existing final local artifacts substantiate only nine scoped Github JVM suites and recorded style/test compilations; Fdroid/full suite and UI runtime are separate gates.
- Changed raw-query and pre-first-context pending-intent branches were statically traced; the three new B1 runtime guards reconcile current inputs via `runCurrent()` before tapping and do not directly force every deferred branch.
- Only this report was created/updated. Worktree code/tests/task, root concurrent changes, Git history, dependencies and graph were left untouched. No additional agents or data decisions. Graph remains stale until the normal end-of-wave update.

Final artifact SHA-256:

| Artifact under `/tmp/plinth-yu5f-vmb1-fix-20261117-` | SHA-256 |
| --- | --- |
| `jvm.log` | `3c8482440d22cafaac3c26856450e62ff0f02bc04dc1b91b03a209588830b69e` |
| `checks.log` | `148a662c4e86774741c452e5d52778d6297d2158d75b15c3e44858e51ac4df42` |
| `jvm-summary.json` | `3f39a6cf7daf8e47d4a22125d2e5e7dc2e3503220000017a7cebc78e211b971f` |
| `protection.json` | `8d4784087cde0a168f5eb82d6dbb74f1fe026c02e0c7c77fb22bb7e5969a8a9c` |
