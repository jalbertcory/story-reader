# Release Fixes

## Must Fix

- [x] **1. Add `*.jks` / `*.keystore` to `.gitignore`**
- [x] **2. Cancel `serviceScope` in `TtsMediaService.onDestroy()`** — already done at line 271
- [x] **3. Add timeout to `TtsMediaService.bind()`**
- [x] **4. Add backup exclusion rules (dataExtractionRules)**
- [x] **5. Wrap `importSingleBook()` delete+insert in a DB transaction**
- [x] **6. Store and unbind `ServiceConnection` in `TtsMediaService.bind()`**
- [x] **7. Wrap `GoogleDriveCredentialsManager` with `KeystoreEncryptedPrefs`**
- [x] **8. Enable `exportSchema` in Room `AppDatabase`**
- [x] **9. Guard debug logging with `BuildConfig.DEBUG`**
- [x] **10. Enable R8/ProGuard for release builds**
