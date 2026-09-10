# stale-tests

Unit tests quarantined on 2026-09-10 because the test source set did NOT compile
(722 errors), which blocked `:app:testDebugUnitTest` entirely.

## Why they do not compile

These files were carried in from an abandoned upstream merge (2.4.10 / 2.5.0) and
reference APIs that do NOT exist anywhere in the current main sources, e.g.:

    formatWorkspaceTree, hasSkillWebviewMeta, buildIframeWrapperHtml,
    shouldWrapInIframe, buildTypeScript, decodeChatModelId, encodeChatModelId,
    deriveLocalModelCapabilities, enableAfterFirstDownload, finishRunLlm,
    finishSubAgentWait, terminalJobUpdate, historyRetentionFor,
    MAX_HISTORY_RETENTION, registerInstalledModel, AudioFocusHolder,
    clampMaxToolSteps, AutoCompactionThresholdMode,
    contextCompactionTargetTokensK, getContextCompactionTargetTokens

Verified by grep over app/src/main + ai/src + agent-tools/src: 19 of 20 sampled
symbols have ZERO hits. So they cannot be repaired by editing the tests alone --
they are a checklist of feature code that the local tree does not have yet.

## How to use this directory

* Treat each file as a feature checklist item for the pending upstream port
  (see 真源/归档/专题/upstream-跟进记录-20260910.md).
* When a feature is ported, move its test back with `git mv` into
  `app/src/test/java/...` and make it compile against the real local API.
* Nothing here is compiled or executed. PRs that re-add a file must also make
  `:app:compileDebugUnitTestKotlin` pass.
