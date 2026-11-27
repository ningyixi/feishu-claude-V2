# Quality Guidelines

> Code quality standards for backend development.

---

## Overview

<!--
Document your project's quality standards here.

Questions to answer:
- What patterns are forbidden?
- What linting rules do you enforce?
- What are your testing requirements?
- What code review standards apply?
-->

(To be filled by the team)

---

## Forbidden Patterns

<!-- Patterns that should never be used and why -->

* Committing usages of methods/files that were not `git add`-ed together with them. On 2026-11-25 a feature commit shipped without its repository-interface dependency; local whole-tree builds stayed green and a broken master went unnoticed until 2026-11-27 (fixed in dba48eb). Whole-tree compile success is NOT evidence of commit-tree buildability.
* Catching RuntimeException around a Spring Data derived delete to "not block" a flow — it converts a transaction-contract violation into silent data corruption (see database-guidelines.md, tombstone case 3de5a03).

---

## Required Patterns

<!-- Patterns that must always be used -->

* Deploy artifacts must be built from the pushed git state, not from the working directory: `git worktree add <tmp> origin/master` + `mvn package -DskipTests`, then ship that jar. This catches partial-add breakage before production does and keeps unrelated in-progress edits out of releases (standard since 11-27).

---

## Testing Requirements

<!-- What level of testing is expected -->

* Mock unit tests do not exercise Spring proxy behavior (transactions, AOP). Any new derived `deleteBy...`/`updateBy...` repository method must carry `@Transactional` on the interface at declaration time, and correctness of that wiring is verified against a real context (MySQL `*MysqlIT` tests or a live endpoint check), never by mocks.
* Fetch/parse guards against remote-content tricks (e.g. GitBook soft 404) use captured real-response fixtures with zero network in unit tests (see DocParserSoftNotFoundTest), plus one out-of-suite live check recorded in the task PRD.

---

## Code Review Checklist

<!-- What reviewers should check -->

(To be filled by the team)
