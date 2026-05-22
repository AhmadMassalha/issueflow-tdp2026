# Spec 10 — Ticket CSV export & import

## Endpoints
- `GET /tickets/export?projectId={id}` → `text/csv` attachment
- `POST /tickets/import` (multipart/form-data: `file`, form field `projectId`) → `{ created, failed, errors: [{ row, message, code }] }`

## CSV format
Columns (in order): `id,title,description,status,priority,type,assigneeId,dueDate`.
- RFC 4180 quoting (handled correctly by `commons-csv`).
- Header row required on import. Unknown columns → 400 `CSV_UNKNOWN_COLUMN`.

## Acceptance criteria — Export
1. Exports only non-soft-deleted tickets of the given project, ordered by `id`.
2. `Content-Disposition: attachment; filename="tickets-project-<id>-<yyyyMMdd>.csv"`.
3. Empty result still returns header row + 200.
4. Stream the response (`StreamingResponseBody` or write directly to the `OutputStream`) to avoid loading all tickets in memory.

## Acceptance criteria — Import
5. `projectId` form field required; missing → 400.
6. File > 10 MB → 413 (Spring's `MaxUploadSizeExceededException`, mapped by the global handler). The 10 MB cap is already configured in `application.yaml`.
7. Non-`text/csv` MIME → 415 `CSV_UNSUPPORTED_TYPE`.
8. Each row is validated independently using the same DTO as `POST /tickets`. Row failures go into `errors[]` with the 1-based row index; valid rows are still created. Whole-file rollback only on parser failure (not per-row failure).
9. Audit log — ONE row per successfully imported ticket (`action=CREATE`, `actor=USER`).
10. Auto-assignment (spec 12) runs for rows without `assigneeId`.
