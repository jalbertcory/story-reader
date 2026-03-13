# Story Reader – Improvements Backlog

Items are ordered roughly by priority. Mark completed items with `[x]`.

---

## Bugs

- [x] **6. TTS does nothing beyond toggling a play/stop icon**
  Properly integrated `TtsManager` into `ReaderViewModel`: initialize on navigator ready, call
  `start(currentLocator)` / `stop()` on toggle, wired TTS controls in `ReaderScreen`.

- [x] **23. Fullscreen bottom system bar still visible**
  `ReaderStatusBar` was rendered unconditionally. Wrapped it in `AnimatedVisibility(visible = showBars)`
  with slide-up/down animation. Also fixed bottom padding to be 0 when bars are hidden.

- [x] **24. Location jumps when toggling fullscreen**
  WebView resize during show/hide bars causes rapid locator updates. Fixed by debouncing position
  saves with a 300 ms delay so the final stable position after reflow is used.

---

## UX / Navigation

---

## Reader Settings

- [x] **18. Eyestrain "Night" theme**
  Added a fourth "Night" theme: pure black background + orange (#FF7722) text, with
  `publisherStyles = false` so the colors actually apply. Applied via custom `backgroundColor`
  and `textColor` fields in `EpubPreferences`.

- [x] **19. Theme buttons preview their own colors**
  Replaced plain `TextButton` theme selectors with styled `Surface` cards showing the actual
  background and text color of each theme. Active theme is highlighted with a colored border.

- [x] **20. Font buttons rendered in their own typeface**
  Each font option button renders its label in the corresponding Compose `FontFamily` so users
  can see the typeface before selecting it.

- [x] **21. Font size slider: smaller range and finer steps**
  Changed range from 0.5×–3.0× to 0.5×–2.0× with 29 steps (0.05× increments). Added a Reset
  button to return to 100%.

- [x] **22. App chrome matches reading theme**
  The reader `Scaffold`, settings sheet, and controls all switch to a `darkColorScheme()` when
  the Dark or Night reading theme is active, so the UI chrome blends with the book content.

---

## Library

---

## TTS

- [x] **25. TTS with MediaSession (notification + watch controls)**
  Created `TtsMediaService` (`MediaSessionService`) to host the `MediaSession`, enabling system
  media notification and watch/headphone integration. `ReaderViewModel` binds to the service
  when TTS starts and unbinds/stops when TTS stops.

- [x] **26. TTS in-screen media controls**
  When TTS is active a control bar appears above the status bar with: Skip Previous, Play/Pause,
  Stop, and Skip Next buttons. The play button in the top bar now opens TTS (not just toggles an
  icon). Controls disappear when TTS is stopped.

- [ ] **15. TTS enhancements (after core TTS works)**
  Once TTS is confirmed working, add:
  - Voice selection from available system voices
  - Speed adjustment (0.5×–2.0×)
  - Visual word-level highlight following the spoken sentence (already scaffolded in `TtsHighlightSynchronizer`)

---

## Stats & Tracking

- [x] **27. Track estimated words read and WPM per session**
  Added `wordsRead: Int` to `ReadingSessionEntity`. Each finalized session estimates words as
  `(adjustedDurationSeconds / 60 × 200)` (200 WPM average). Database migration 2→3 adds the
  column. Stats screen shows total estimated words and a words-read goal.

- [x] **28. Goals: hours + words read (replaces books started)**
  Replaced the "books started per year" goal with a "words read per year" goal (default 500 000).
  Stats screen goal card updated accordingly.

---

## Reading Experience (future enhancements)

- [ ] **16. Full-text search within a book**
  Expose the search capability from the Readium navigator to let users search for text within the
  currently open book.

- [ ] **17. Bookmarks**
  Allow users to bookmark the current location and browse/navigate saved bookmarks via the TOC
  sheet or a dedicated tab.
