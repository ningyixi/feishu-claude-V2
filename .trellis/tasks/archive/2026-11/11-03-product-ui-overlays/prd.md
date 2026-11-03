# Product UI and floating-surface upgrade

## Authorization
User approved the complete overlay decision audit, including missing component implementations, then explicitly revoked the old visual-preservation constraint: redesign for a polished product. Existing business flows and copy remain intact; no new provider calls or destructive operations during testing.

## Scope
- Cohesive responsive application shell, navigation, typography, surfaces, forms and feedback.
- Shared accessible native dialog/drawer, anchored confirmation, coordinated hover/focus/tap tooltips, and common unsaved-change handling.
- Apply the approved audit to all 14 modal render sites, 9 confirmation calls and 66 native tooltip sites. Keep dialogs for consequential decisions, drawers for contextual editors, pages for substantial creation and linked Wiki reading.
- Durable URLs for selected work/content, and navigation protection for dirty drafts. Preserve supplier TEXT/WIKI, workflow gates and existing APIs.
- Real browser verification in OrbStack for keyboard, touch, focus, overlays, dirty guards, responsive layouts and deep links; frontend/container builds and existing stack smoke before commit/push.

## Decisions
No pointer-following tooltip or hover action menu is needed. No fake Undo: existing deletes are permanent. Consolidate duplicate modal and feedback foundations. Use an anchored confirm only for isolated permanent items; retain dialogs for operational impact and shared/cascading deletion.

## Completion
Document component contracts and changed files, deploy in OrbStack, commit and push under standing user authorization. Actual external model/Feishu calls are not part of UI testing.

## Delivered
- Approved audit applied through shared native surfaces, anchored confirmation, coordinated live hints, long-document readers, feedback and draft protection.
- New responsive shell and product tokens; full-page E2E creation and fixed-version Wiki reading; entity/subview URL state.
- Parent/nested draft boundaries, explicit Markdown restoration, successful-save draft reset and protected human workflow answers.
- 16 Chromium scenarios run twice against compiled production UI: 32 passed. Desktop and touch screenshots inspected. Frontend and both production image builds passed. Live Nginx browser and real API smoke passed after deployment.
- Browser API traffic is stubbed; no real model, Feishu or destructive user action was invoked. No backend Java/API/business contract changed.
- Practical scope: no chart/pointer-following hint, custom action hover menu, fabricated Undo or server-side autosave.

Complete touched-file manifest: `docs/界面升级文件清单.md`.
