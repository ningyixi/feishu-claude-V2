# Error Handling

> How errors are handled in this project.

---

## Overview

<!--
Document your project's error handling conventions here.

Questions to answer:
- What error types do you define?
- How are errors propagated?
- How are errors logged?
- How are errors returned to clients?
-->

(To be filled by the team)

---

## Error Types

<!-- Custom error classes/types -->

(To be filled by the team)

---

## Error Handling Patterns

<!-- Custom try-catch patterns, error propagation -->

* Crawl failure grading (SiteCrawler contract): page-level `IOException` = that page failed (entry failure before any loaded page aborts the whole import; later failures keep `partial=true`); `SiteCrawler.FatalCrawlError` = abort entire import and mark loaded pages unsaved. Never downgrade content-integrity problems to Fatal just to look strict, or upgrade to IOException to look lenient.
* HTTP status is not ground truth for "content exists". Some documentation platforms answer 200 with an error shell (GitBook returns 200 + "Page Not Found" markdown both for unknown `.md` alias routes and, via `Accept: text/markdown` negotiation, for truly dead paths; only plain HTML requests get a correct 404). Guard content, not status: `DocParser.looksLikeSoftNotFound` is a two-condition AND (not-found-style heading within the first 10 lines outside fenced code, plus a "does not exist" self-report) so pages legitimately documenting 404 handling are not rejected. Probe hits fall back to the source URL; a final body that still matches throws `IOException` with a hint pointing at llms.txt (commits 68233c3 / 57b14c1).

---

## Common Mistakes

<!-- Error handling mistakes your team has made -->

* Swallowing a repository mutation exception into a WARN and continuing (see quality-guidelines.md Forbidden Patterns).
* Treating a length threshold (SiteCrawler MIN_BODY_CHARS) as a 404/empty-shell detector — GitBook's 404 shell is 2.3KB of plausible-looking markdown and passed it while poisoning the whole KB entry.
