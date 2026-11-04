# Frontend Guidelines

Read [Floating UI](./floating-ui.md) before editing application navigation, forms, hints, confirmations, readers or feedback. The frontend uses React 18, React Router's data router, Vite and the shared product tokens in `ProductUI.css`.

Quality checks: production build, Chromium browser scenarios against the compiled preview, mobile overflow checks, and the running Nginx/API smoke. Browser tests mock all HTTP API calls and must not invoke model or Feishu providers.

Read [Glass material](./glass-material.md) before changing surface materials, global appearance preferences, background sampling or SVG backdrop lenses.
