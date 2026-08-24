# Claude Working Directory Feature - Implementation Summary

## Overview

Added a configurable **Claude working directory** (`claudeWorkingDir`) to the Feishu bot admin panel.
This directory controls where Claude Code CLI commands execute, both during connectivity tests
and when processing chat messages from the Feishu bot.

### Capabilities

1. **Configuration**: New field in the admin panel config form for setting Claude working directory
2. **Validation**: Connectivity test (`POST /api/test/claude`) checks directory existence before running Claude
3. **Execution**: Chat mode (`ClaudeCodeService.chat()`) sets ProcessBuilder working directory
4. **Hot-reload**: Changes take effect immediately without requiring app restart

---

## Files Changed

### Backend (6 files)

| File | Change |
|------|--------|
| `AppConfig.java` | Added `claudeWorkingDir` field + getter/setter |
| `AppConfigDTO.java` | Added `claudeWorkingDir` as 6th record component + `fromEntity()` mapping |
| `ClaudeCodeService.java` | Added `workingDir` field, `setWorkingDir()`, `pb.directory()` in `chat()` |
| `ConfigController.java` | Injected `ClaudeCodeService`, added save + propagation on save |
| `AppInitializer.java` | Added startup loading + null guards on all `.isBlank()` calls |
| `TestController.java` | Added `Files.exists()`/`Files.isDirectory()` validation + `pb.directory()` |

### Frontend (1 file)

| File | Change |
|------|--------|
| `Config.jsx` | Added `claudeWorkingDir` entry to FIELDS array |

---

## Architecture

### Data Flow

```
Admin Panel (Config.jsx)
  |
  | PUT /api/config
  v
ConfigController
  |-- saves to AppConfig (H2 DB)
  |-- claudeCodeService.setClaudeCmdPath()
  |-- claudeCodeService.setWorkingDir()
  |-- shellService.setWorkingDir()
  v
ClaudeCodeService (in-memory, volatile fields)
  |
  | chat() called by FeishuBotService
  v
ProcessBuilder
  .directory(new File(workingDir))
  .command(claude -p ...)
```

### Test Flow

```
Test.jsx
  |
  | POST /api/test/claude
  v
TestController.testClaude()
  |
  |-- Read claudeWorkingDir from DB
  |-- If non-blank:
  |      Files.exists(path)?       -- NO --> fail("does not exist")
  |      Files.isDirectory(path)?  -- NO --> fail("not a valid folder")
  |-- Build command
  |-- pb.directory(workDir)
  |-- Run claude -p hi
  v
TestResultDTO (success/fail + message + detail)
```

---

## Key Code Changes

### ClaudeCodeService.java - Working Directory Support

```java
// New field (volatile for thread safety with virtual threads)
private volatile String workingDir = "";

// New setter
public void setWorkingDir(String dir) {
    if (dir != null) {
        this.workingDir = dir;
    }
}

// In chat() method, after ProcessBuilder creation:
ProcessBuilder pb = new ProcessBuilder(command);
if (!workingDir.isBlank()) {
    File dir = new File(workingDir);
    if (dir.isDirectory()) {
        pb.directory(dir);
    }
}
```

### TestController.java - Directory Validation

```java
// Before the try block, validate working directory
String workDir = config != null ? config.getClaudeWorkingDir() : "";
if (workDir != null && !workDir.isBlank()) {
    Path dirPath = Path.of(workDir);
    if (!Files.exists(dirPath)) {
        return TestResultDTO.fail("Claude working directory does not exist", workDir);
    }
    if (!Files.isDirectory(dirPath)) {
        return TestResultDTO.fail("Claude working directory is not a valid folder", workDir);
    }
}
```

### ConfigController.java - Immediate Propagation (Bug Fix)

```java
// After configRepo.save(config):
// Propagate Claude settings immediately (previously only done at startup)
if (config.getClaudeCmdPath() != null && !config.getClaudeCmdPath().isBlank()) {
    claudeCodeService.setClaudeCmdPath(config.getClaudeCmdPath());
}
claudeCodeService.setWorkingDir(config.getClaudeWorkingDir());
```

---

## Bugs Found & Fixed

### Bug 1: ConfigController did not propagate claudeCmdPath on save (pre-existing)

- **Symptom**: Changing `claudeCmdPath` via admin panel required app restart for chat to use new path
- **Root cause**: `ConfigController.saveConfig()` never called `claudeCodeService.setClaudeCmdPath()`
- **Fix**: Added propagation of both `claudeCmdPath` and `claudeWorkingDir` after save

### Bug 2: NPE on startup with existing database (found during testing)

- **Symptom**: `NullPointerException: Cannot invoke String.isBlank() because return value of AppConfig.getClaudeWorkingDir() is null`
- **Root cause**: H2 `ddl-auto: update` adds new columns as NULL for existing rows; the Java default `""` only applies to new entity instances
- **Fix**: Added `config.getXxx() != null &&` null guards before all `.isBlank()` calls in `AppInitializer.java` and `ConfigController.java`
- **Files affected**: `AppInitializer.java` (3 guards), `ConfigController.java` (2 guards)

---

## Design Decisions

### Why a new field instead of reusing shellWorkingDir?

- `shellWorkingDir` is consumed by `ShellService` for a persistent shell process (long-lived cmd.exe/bash)
- Claude Code may target a completely different directory (e.g., a code repo vs a deployment folder)
- Separation of concerns: the admin can configure them independently

### Why validate only in the test endpoint, not on save?

- The user might create the directory after saving the config
- The test endpoint is the "check everything is ready" button
- Graceful fallback: if the directory is deleted at runtime, `chat()` falls back to JVM default

### Why volatile fields in ClaudeCodeService?

- Feishu message handlers run on virtual threads (`Thread.ofVirtual()`)
- `volatile` ensures visibility of `workingDir` and `claudeCmdPath` across threads
- No synchronization needed for reads; `chat()` is already `synchronized`

### Why isDirectory() guard in chat()?

- If the configured directory is deleted while the app is running, `new File(workingDir)` would still be non-blank
- The `isDirectory()` check gracefully falls back to JVM default instead of crashing
- The test endpoint catches this case earlier with a clear error message

---

## Verification Results

All 3 test scenarios passed end-to-end:

| Scenario | Expected | Actual |
|----------|----------|--------|
| Non-existent dir `Z:/nonexistent/path` | fail with "does not exist" | `success: false, message: "Claude working directory does not exist"` |
| Valid dir `Z:/YIXI/work/feishu-claude-V2` | pass, claude runs | `success: true, message: "Claude Code connected ok"` |
| Empty dir (backward compat) | skip validation, claude runs | `success: true, message: "Claude Code connected ok"` |

---

## Database

- **No migration script needed**: H2 with `ddl-auto: update` adds the `claude_working_dir` column automatically
- **Existing rows**: Column is added as NULL (not the Java default `""`)
- **Null safety**: All code paths handle NULL gracefully via null guards
- **Column type**: `varchar(255)` (JPA default for String fields)

---

## Backward Compatibility

- Empty/blank `claudeWorkingDir` means no working directory is set (ProcessBuilder uses JVM default)
- Existing installations behave identically until the field is configured
- Frontend form renders empty string as blank input (no special handling needed)
- Old 5-field JSON payloads from the frontend deserialize `claudeWorkingDir` as `null`, which is handled safely
