# Story Reader – Improvements Backlog

Items are ordered roughly by priority. Mark completed items with `[x]`.

---

## Bugs

- [x] **1. Duplicate Nextcloud import button**
  The library screen shows both a top-bar cloud icon _and_ a floating action button that both trigger the same Nextcloud browser. Remove the redundant FAB cloud button; keep only the top-bar icon.

- [x] **2. No way to exit Nextcloud browser directly**
  When browsing sub-folders the back button only navigates up one folder at a time. Add a dedicated **×** (Close) icon button in the top bar that always exits straight back to the library.

- [x] **3. Bottom-bar chapter title always blank**
  `locator?.title` is frequently null in Readium; the field is not reliably populated. Fix by resolving the chapter title from the publication's table of contents by matching the current locator's resource href against TOC links.

- [x] **4. Bottom-bar progress has no decimal**
  `"${(it * 100).toInt()}%"` truncates to whole numbers. Change to one decimal place, e.g. `"%.1f".format(it * 100) + "%"`, so long books show meaningful precision.

- [x] **5. Reading settings (font size / theme / font family) have no effect**
  `updatePreferences` updates the `_preferences` StateFlow but `submitPreferences()` is never called on the `EpubNavigatorFragment`. Wire up a preferences observer in `onNavigatorReady` that calls `nav.submitPreferences(prefs)` whenever preferences change.

- [ ] **6. TTS does nothing beyond toggling a play/stop icon**
  `setTtsPlaying()` only flips a boolean; `TtsManager` is never instantiated or called. Properly integrate `TtsManager` into `ReaderViewModel`: initialize on navigator ready, call `start(currentLocator)` / `stop()` on toggle, and wire highlight synchronization via `TtsHighlightSynchronizer`.

---

## UX / Navigation

- [x] **7. Page turning by tapping (in addition to swiping)**
  Implemented via `DirectionalNavigationAdapter` registered on the `EpubNavigatorFragment` in `onNavigatorReady`. Left 30% of screen goes back, right 30% goes forward.
  Add tap-zone navigation: tapping the left ~30 % of the reading area goes to the previous page; tapping the right ~30 % goes to the next page. Use `EpubNavigatorFragment`'s `addInputListener` with a `DirectionalNavigationAdapter` or a manual `InputListener` that calls `goBackward()` / `goForward()`.

- [x] **8. Fullscreen / immersive reading mode**
  Tapping the **center** of the reading area should toggle the top app bar (and optionally the system status bar) to give a distraction-free reading experience. Show/hide the `TopAppBar` composable via a remembered boolean state; also toggle `WindowInsetsController` hide/show for a truly immersive feel.

---

## Library

- [x] **9. Show book covers in the library list**
  `BookEntity.coverUri` exists but is never populated. During import, extract the cover bitmap from the `Publication` (`publication.cover()`), resize it, save it to app-internal storage, and store the path in `coverUri`. Display the cover as a small thumbnail on the left side of each book card.

- [x] **10. Show "last read" date on book cards**
  Query the most recent `ReadingSessionEntity.startTime` for each book and display it on the book card as e.g. "Last read Mar 10" or "Never read".

---

## Stats & Tracking

- [x] **11. Stats screen – per-book history**
  New `StatsScreen` accessible via a bar-chart icon in the library top bar. Shows per-book cover, title, first-read date, last-read date, total reading time, session count, and progress bar. Sorted by most recently read.

- [x] **12. Smarter session time tracking – idle detection**
  `ReaderViewModel` now records a timestamp on every page turn. `ReadingRepositoryImpl.finalizeSession` takes these timestamps, computes per-page durations, discards outliers >3× average, and stores both adjusted (`durationSeconds`) and raw (`rawDurationSeconds`) durations. Database migrated to v2.

- [x] **13. Global reading stats & yearly goals**
  The Stats screen opens with a goals card showing year-to-date hours and books read with progress bars against configurable targets. All-time totals are shown below. Goals are persisted to SharedPreferences and editable via a dialog.

---

## Reading Experience (future enhancements)

- [x] **14. Persist reading preferences across sessions**
  Font size, theme, and font family are now saved to SharedPreferences in `ReaderViewModel` and restored on next open. Settings apply globally across all books.

- [ ] **15. TTS enhancements (after #6 is fixed)**
  Once TTS is working, add:
  - Voice selection from available system voices
  - Speed adjustment (0.5×–2.0×)
  - Visual word-level highlight following the spoken sentence (already scaffolded in `TtsHighlightSynchronizer`)

- [ ] **16. Full-text search within a book**
  Expose the search capability from the Readium navigator to let users search for text within the currently open book.

- [ ] **17. Bookmarks**
  Allow users to bookmark the current location and browse/navigate saved bookmarks via the TOC sheet or a dedicated tab.
