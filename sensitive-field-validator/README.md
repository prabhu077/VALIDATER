# Sensitive Field Validator

A Spring Boot + Thymeleaf web app that automatically discovers and masks
sensitive PII fields in uploaded CSV datasets.

## Run

```
mvn spring-boot:run
```

Open **http://localhost:8080**

## Pages

| Page | URL | Description |
|------|-----|-------------|
| Register | `/register` | Create a validator account (in-memory, resets on restart) |
| Login | `/login` | Sign in with name + password |
| Home | `/home` | Dashboard with links to all features |
| Data Loading | `/data-loading` | Drag-and-drop a CSV → validation report (KPIs, type breakdown, searchable/filterable column table, mask-invariant results, pipeline trace) and an encrypted download |
| Audit Log | `/audit-log` | Your pipeline audit events (stages, warnings, rollbacks), filterable by level |
| History | `/history` | Past CSV uploads and field checks, newest first |
| Upgrade | `/payment` | Visual-only plan selection showcase |

## Key Features

- **Auto-detection** — `DiscoveryEngine` identifies 7 sensitive types:
  `EMAIL`, `PHONE`, `CARD`, `PAN`, `AADHAAR`, `PASSWORD`, `GST`
  using both column-name keywords and regex value-pattern matching.
- **Masking preview** — `MaskingUtil` renders a safe masked sample for
  every sensitive field (e.g. `j***@mail.com`, `XXXX XXXX 1234`).
- **Mask validation** — every sensitive column's masks are checked with `MaskingInvariantChecker`
  (non-leakage + structure). Failures and header-only detections are flagged *Needs review*.
- **Upload pipeline** — `UploadPipeline` runs RECEIVE → PARSE → DISCOVER_AND_VALIDATE → STORE →
  RECORD_HISTORY with `PipelineContext`, `AuditLogger` and `RollbackManager` (a failed late stage
  undoes the session write).
- **Encrypted download** — `EncryptionUtil` AES-128-CBC encrypts every
  sensitive column value; non-sensitive columns are left as-is. The
  encrypted CSV is streamed directly to the browser.

## Notes

- All storage (accounts + history) is **in-memory only** — demo purposes.
- Detection logic lives entirely in `DiscoveryEngine` and `MaskingUtil`;
  the web layer only displays the outcome.
- The Payment page is a visual showcase only — no card data is submitted
  or stored (`name` attributes are intentionally omitted from card fields).
