# UI + optimisation pass

## UI
- Data Loading: drag-and-drop upload with client-side checks (type, empty, >10 MB), KPI cards, sensitive-type
  breakdown bar, column search + filter chips (All / Sensitive / Non-sensitive / Needs review), a **Mask check**
  column (invariant results per column), collapsible **pipeline trace**, "Discard dataset" button.
- New **Audit Log** page (`/audit-log`), per-user, filterable by level.
- One shared sidebar (`templates/fragments/layout.html`) replaces four copy-pasted copies; active item is set per page.
- Home page now lists the 7 types the engine really detects with their real mask formats
  (it used to show SSN / Full Name / +1 phone, which are not detected).
- Inline `<style>` moved into `style.css`; responsive tweaks, keyboard focus styles, reduced-motion support.
- Sensitive columns show the **masked preview only** — the raw sample is no longer rendered for them.

## Wiring the backend pieces into the app
- `UploadPipeline` (new) + `ScanService` (new, pure Java) replace the duplicated review-building code in the controller.
- `MaskingInvariantChecker`, `PipelineContext`, `AuditLogger`, `RollbackManager` now run on every upload.
- `AuditLogger` is a Spring bean (`@Component`).

## Fixes
- `MaskingUtil`: an EMAIL-typed column holding a value without `@` was previewed as `***` + the raw value. Now `***`.
- `CsvLoader`: strips a UTF-8 BOM, names blank headers `column_N`, de-duplicates repeated headers, pads short rows.
- Download filename is sanitised (header-injection); download requires a logged-in session.
- Upload > 10 MB shows a friendly message; refresh after upload no longer re-submits (Post/Redirect/Get).

## Performance
- CSV parsed once per upload (was twice, each via `readAll()`).
- `EncryptionUtil`: key, `SecureRandom` and `Cipher` reused. Measured 6–12x faster on 300k cells; output format unchanged.
- Download resolves columns once instead of a map lookup per cell; 64 KB buffered writer.
- Content-hashed static URLs with long cache, gzip on, 30-minute session timeout.

## Tests
- `ScanPipelineTest` (new): EMAIL-leak regression, ScanService, BOM/duplicate headers, bad input, rollback on late
  failure, audit log never contains cell values.
