# Floating Surfaces and Draft Contract

## 1. Scope / Trigger
Any change to dialogs, contextual editors/readers, confirmation, hover/focus/touch hints, page forms, deep links or notifications. The user approved replacing the original visuals with a cohesive product design; preserve business workflows and existing server gates.

## 2. Signatures
`FloatingUIProvider` wraps the router. `Surface({kind:'dialog'|'drawer'|'page', onClose, form?, dirty?, label?, children})` consumes the existing modal-box/title/body structure. `DraftPage({dirty?,enabled?,children}, ref)` exposes `ref.current.commit()` for successful saves. `useConfirm()(options)` returns Promise<boolean>; options include kind (dialog/popconfirm), title, body, confirmLabel, optional cancelLabel/anchor. `useFormSaved()` commits the nearest Surface draft; `useDirtyValue(value, identity)` compares a controlled draft to its initial value. `useQueryParam(key,fallback)` persists selected subviews in URL parameters.

`data-tooltip` is text-only hint content; noninteractive triggers must be keyboard-focusable. Disabled native controls use a focusable wrapper. `data-document` + keyboard activation opens the shared long-document reader instead of stuffing an entire SPEC into a tooltip. `Toast({toast,onDismiss})` renders status/error feedback.

## 3. Contracts
- Dialogs/drawers use native `showModal()` for top-layer rendering, focus containment and inert background. Escape/backdrop/close cancel, and focus returns to a connected opener unless a newer modal owns focus.
- Drawers preserve page context; instance, Hook, employee and node drafts preview behind them without writing the server before Save. Read-only readers use the same surface without dirty tracking.
- Popconfirm is fixed-position, anchored to the explicit/pointer/keyboard trigger, clamped/flipped to viewport, dismissible outside/Escape, and restores focus. Large-impact or shared/cascading operations use dialogs. Permanent deletes do not pretend to support Undo.
- One tooltip is coordinated across dynamic rows. First delay 500 ms, neighbouring hints immediate within 1200 ms of the previous hint being visible, focus and touch access, pointer-events:none, viewport placement and aria-describedby. Live text updates and removed anchors are observed. Put the portal inside an open native dialog so the top layer does not hide it.
- Surface/DraftPage snapshots or supplied dirty flags register centrally. Closing/discarding asks first. Native beforeunload protects reload; React Router useBlocker protects entity/page navigation. Successful saves call commit before navigation. Cancel-edit restores the original text, not just the view mode.
- Portal events bubble through the React tree even when DOM ancestors differ. A parent's draft guard must ignore events outside its own DOM root, or cancelling a nested node/SPEC editor can incorrectly clear parent protection.
- Query keys: supplier `job`, `view`; Wiki `wiki`, `revision`, optional `raw=1`; employee `employee`; instance history `instance`; E2E `task`, `create=1`. Wiki links remain pinned to the requested revision. Reader-only URL changes do not discard the underlying supplier draft.
- Existing API contracts, workflow mode selection, model execution/evidence gates, permissions and destructive operation semantics are unchanged. Check HTTP success before reporting completion.

## 4. Validation & Error Matrix
| Boundary | Behaviour |
| --- | --- |
| Dirty close, cancel, reload or different entity/page | Ask before losing work; rejection retains the draft |
| Successful form save | Clear only its draft registration before navigation |
| Nested editor cancel | Discard only that editor; preserve parent changes |
| Inactive/unbound item deletion | Anchored confirmation; active/shared operation uses dialog |
| Removed or offscreen tooltip trigger | Remove or reposition hint; no detached aria relationship |
| Unknown/deleted Wiki revision/page | Show read error; never silently switch revision |
| Server rejects deletion or response | Preserve actionable state; do not show success |
| Narrow viewport/reduced-motion setting | Fit controls and drawers; respect reduced motion |

## 5. Good/Base/Bad Cases
Good: type an approval answer, navigate away, decline discard, then submit; the answer survives until a successful response. Open a Wiki raw source at the same revision and reload its URL.
Base: short task-details form remains a dialog; long contextual forms are drawers; substantial E2E creation and linked Wiki reading are pages.
Bad: call window.confirm, use title-only hints, open nested blocking readers, silently lose a parent SPEC draft, or render a tooltip in the body underneath a native modal.

## 6. Tests Required
`frontend/tests/floating-ui.spec.js` exercises compiled UI with mocked APIs: desktop/mobile rendering, focus/restoration, dirty close/navigation, anchored confirmation, consequential delete cancellation, tooltip delay/relationship/live updates/touch, deep-linked Wiki evidence, full-page E2E creation, nested SPEC/node protection, Markdown restoration, workflow answer drafts and mobile supplier overflow. Test the production preview; editing a context module during Vite hot reload can invalidate context identities and contaminate a browser run.

Use the pinned Playwright image matching the package version; run each scenario twice for final verification. Production images and `scripts.tests.test_stack_smoke` verify real deployment separately. Fixture screenshots are not real supplier records or provider delivery evidence.

## 7. Wrong vs Correct
Wrong: treat a popconfirm as a centered modal, let an action silently discard changes, or clear every form's dirty state when one form saves.
Correct: select the surface by task/impact, use shared focus/draft behaviour, clear the specific successful form, keep provenance URLs stable, and retain server-side gates as the authority. No pointer-following hint is needed without a continuous inspection area such as a chart.
