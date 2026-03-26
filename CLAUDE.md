# Story Reader — Claude Code Guidelines

## After making code changes

Always run static analysis before finishing a task:

```bash
./gradlew :app:detekt          # Kotlin static analysis (unused code, naming, etc.)
./gradlew :app:lintDebug       # Android Lint (KTX suggestions, SDK checks, resources)
```

Fix all errors reported. Warnings that are flagged as errors in the lint config must be resolved.

## Code quality rules

### Kotlin
- Remove unused imports immediately — do not leave them behind
- Unused private functions/properties must be deleted, not commented out
- `const val` names must be SCREAMING_SNAKE_CASE
- Do not mark functions `suspend` unless they contain actual suspension points (`await`, `withContext`, `delay`, etc.)
- Use KTX extension functions: `prefs.edit { }` not `prefs.edit().putX().apply()`, `string.toUri()` not `Uri.parse(string)`
- Prefix intentionally-unused parameters with `_` (e.g., `_unused: Type`)

### Android
- minSdk is 28 — do not add `Build.VERSION.SDK_INT >= X` checks for API levels ≤ 28
- Exported services that require it (e.g., MediaLibraryService) use `tools:ignore="ExportedService"`

### Gradle
- Dependencies live in `gradle/libs.versions.toml`
- Keep versions reasonably current; the pre-push hook will flag obsolete ones via lint

### Error handling
- **Repositories/data layer**: Return `Result<T>` using `runCatching` or explicit `Result.success`/`Result.failure`
- **ViewModel layer**: Handle repository results with `.fold()` or `.onSuccess { }.onFailure { }`. Never bare `try-catch` around repository calls
- **Silent failures**: Always log with `Log.w(TAG, message, exception)` when catching and discarding exceptions. Never use `catch (_: Exception)` without logging
- **`getOrNull()`/`getOrDefault()`**: Chain `.onFailure { Log.w(...) }` before extracting the value so failures are visible in logcat
- **Fire-and-forget**: Use `runCatching` with logging for operations where failure is acceptable (position saves, cover loading)
- Helper extensions in `com.storyreader.util.ResultExtensions` — `getOrDefaultLogging()`, `getOrNullLogging()`

## Running tests

```bash
./gradlew testDebugUnitTest    # Unit tests
```

## Project structure

- `app/src/main/java/com/storyreader/` — main source
  - `data/` — Room DB, repositories, sync
  - `reader/` — Readium/TTS integration
  - `ui/` — Compose screens and ViewModels
- `app/src/test/` — unit tests (Robolectric)
- `app/src/androidTest/` — instrumented tests (Compose UI tests)
