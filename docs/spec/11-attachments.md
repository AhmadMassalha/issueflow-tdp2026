# Spec 11 — Attachments

## Endpoints
- `POST /tickets/{ticketId}/attachments`  (multipart/form-data: `file`)
- `DELETE /tickets/{ticketId}/attachments/{attachmentId}`
- (Implied) `GET /tickets/{ticketId}/attachments/{attachmentId}` to download. The README doesn't list it explicitly; implement it for completeness (otherwise uploaded files are write-only) and note in `prompts.md`.

## Entity
| Field | Type |
|---|---|
| id | BIGINT PK |
| ticketId | BIGINT FK |
| filename | VARCHAR(255) |
| contentType | VARCHAR(64) |
| sizeBytes | INTEGER |
| data | BYTEA (`@Lob`) |
| uploadedBy | BIGINT FK -> users |
| createdAt | TIMESTAMPTZ |

## Acceptance criteria
1. Allowed MIME: `image/png`, `image/jpeg`, `application/pdf`, `text/plain`. Else 415 `ATTACHMENT_UNSUPPORTED_TYPE`. Validate using `file.getContentType()` AND, as a defense in depth, sniff the first few magic bytes for PNG/JPEG/PDF (optional, document if skipped).
2. Max 10 MB — Spring's `MaxUploadSizeExceededException` → 413 `ATTACHMENT_TOO_LARGE`. Re-check in service in case the limit changes.
3. Ticket must exist and not be soft-deleted → 404 `TICKET_NOT_FOUND`.
4. Delete: 200 with no body; 404 `ATTACHMENT_NOT_FOUND` if missing.
5. Download (if implemented): streams the bytes with the original `contentType` and `filename`.
6. Audit log: `CREATE` (entityType=ATTACHMENT) on upload, `DELETE` on removal.
