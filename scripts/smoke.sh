#!/usr/bin/env bash
# Comprehensive end-to-end smoke test for IssueFlow.
#
# Purpose:
#   The Maven test suite (462 tests) runs against H2 in PostgreSQL mode and
#   covers unit, controller, repository, and integration layers. H2 is a
#   good-enough approximation for most JPA features, but a few PostgreSQL-
#   specific behaviours (notably BYTEA vs JDBC BLOB binding) do not surface
#   on H2. This script is the "does it actually work against real Postgres?"
#   gate before declaring the build shippable.
#
# Coverage: 87 assertions across 12 endpoint groups:
#   1.  Auth (login, /auth/me, wrong-password 401)
#   2.  Users CRUD + validation errors (duplicate username, bad role, regex)
#   3.  Projects CRUD + duplicate-name 409
#   4.  Tickets CRUD + FSM transitions + auto-assign + INVALID_ASSIGNEE +
#       optimistic locking
#   5.  Comments + @mentions (create, list, edit re-evaluates mentions,
#       /users/{id}/mentions surfaces them)
#   6.  Dependencies (add, list, prevent DONE with open blocker, reject
#       self-dep, reject duplicate, reject cycle, remove)
#   7.  Attachments (upload PNG with magic-byte sniff, download, delete,
#       reject magic-byte mismatch, reject unsupported MIME, reject missing
#       file part)
#   8.  CSV (export the project, import a 3-row CSV with quoted commas)
#   9.  Workload (auto-assign + manual + CSV import all reflected)
#   10. Soft delete + restore + ALREADY_ACTIVE
#   11. Audit log with filters (action, actor, entityType)
#   12. Logout + revoked-token deny-list
#
# Requirements:
#   - App running on http://localhost:8080 with seeded admin/admin
#   - PostgreSQL accessible on localhost:5432 with a clean `issueflow` DB
#     (the script counts rows, so stale data will break audit-log assertions)
#   - jq installed (used for JSON field extraction)
#
# Exit codes:
#   0 = all 87 checks passed
#   1 = at least one check failed; failures are listed at the end
#
# See run.md § "Automated smoke test" for the full recipe (reset DB, start
# app, run script). See prompts.md § "Post-implementation smoke test" for
# the BYTEA/JDBC bug this script caught.

set -u
BASE=http://localhost:8080
PASS=0
FAIL=0
declare -a FAILURES

check() {
    local label="$1" expected="$2" actual="$3"
    if [[ "$expected" == "$actual" ]]; then
        echo "  PASS  $label  ($actual)"
        PASS=$((PASS+1))
    else
        echo "  FAIL  $label  expected=$expected got=$actual"
        FAILURES+=("$label  expected=$expected got=$actual")
        FAIL=$((FAIL+1))
    fi
}

check_ne() {
    local label="$1" notexpected="$2" actual="$3"
    if [[ "$actual" != "$notexpected" ]]; then
        echo "  PASS  $label  ($actual)"
        PASS=$((PASS+1))
    else
        echo "  FAIL  $label  did not expect=$notexpected got=$actual"
        FAILURES+=("$label  did not expect=$notexpected")
        FAIL=$((FAIL+1))
    fi
}

post()   { curl -sS -o /tmp/.body -w "%{http_code}" -X POST   -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" "$BASE$1" -d "$2"; }
patch_() { curl -sS -o /tmp/.body -w "%{http_code}" -X PATCH  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" "$BASE$1" -d "$2"; }
get()    { curl -sS -o /tmp/.body -w "%{http_code}" -X GET    -H "Authorization: Bearer $TOKEN" "$BASE$1"; }
delete() { curl -sS -o /tmp/.body -w "%{http_code}" -X DELETE -H "Authorization: Bearer $TOKEN" "$BASE$1"; }
body()   { cat /tmp/.body; }
body_jq() { jq -r "$1" </tmp/.body; }

# =====================================================================
echo "=== 1. AUTH ==="
HTTP=$(curl -sS -o /tmp/.body -w "%{http_code}" -X POST -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin"}' $BASE/auth/login)
check "POST /auth/login (admin/admin)" 200 "$HTTP"
TOKEN=$(body_jq '.accessToken')
[[ -n "$TOKEN" && "$TOKEN" != "null" ]] && echo "  TOKEN acquired (len=${#TOKEN})" || { echo "  FAIL no token"; exit 1; }

HTTP=$(get /auth/me); check "GET /auth/me" 200 "$HTTP"
ADMIN_ID=$(body_jq '.id'); echo "  admin.id=$ADMIN_ID"
check "  /auth/me role" "ADMIN" "$(body_jq .role)"

# Wrong password
HTTP=$(curl -sS -o /tmp/.body -w "%{http_code}" -X POST -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"wrong"}' $BASE/auth/login)
check "POST /auth/login wrong password -> 401" 401 "$HTTP"

# =====================================================================
echo ""
echo "=== 2. USERS CRUD ==="
HTTP=$(get /users); check "GET /users" 200 "$HTTP"

HTTP=$(post /users '{"username":"alice","email":"alice@x.io","fullName":"Alice","role":"DEVELOPER","password":"alicepw123"}')
check "POST /users alice" 200 "$HTTP"
ALICE_ID=$(body_jq '.id'); echo "  alice.id=$ALICE_ID"

HTTP=$(post /users '{"username":"bob","email":"bob@x.io","fullName":"Bob","role":"DEVELOPER","password":"bobpassw123"}')
check "POST /users bob" 200 "$HTTP"
BOB_ID=$(body_jq '.id'); echo "  bob.id=$BOB_ID"

HTTP=$(post /users '{"username":"alice","email":"alice2@x.io","fullName":"Dup","role":"DEVELOPER","password":"otherpw1234"}')
check "POST /users duplicate username -> 409" 409 "$HTTP"
check "  err code" "USER_DUPLICATE_USERNAME" "$(body_jq .code)"

HTTP=$(post /users '{"username":"al","email":"x@y.io","fullName":"x","role":"DEVELOPER","password":"validpw1"}')
check "POST /users short username (regex fail) -> 400" 400 "$HTTP"

HTTP=$(post /users '{"username":"badrole","email":"r@y.io","fullName":"R","role":"GUEST","password":"validpw1"}')
check "POST /users invalid role -> 400" 400 "$HTTP"
check "  err code" "USER_INVALID_ROLE" "$(body_jq .code)"

HTTP=$(get /users/$ALICE_ID); check "GET /users/{aliceId}" 200 "$HTTP"

HTTP=$(post /users/update/$ALICE_ID '{"fullName":"Alice Smith","role":"DEVELOPER"}')
check "POST /users/update/{aliceId}" 200 "$HTTP"

HTTP=$(post /users '{"username":"tmp_del","email":"tmp@x.io","fullName":"Temp","role":"DEVELOPER","password":"tmppw1234"}')
TMP_ID=$(body_jq '.id')
HTTP=$(delete /users/$TMP_ID); check "DELETE /users/{id} (controller returns 204)" 204 "$HTTP"

# =====================================================================
echo ""
echo "=== 3. PROJECTS CRUD ==="
HTTP=$(get /projects); check "GET /projects" 200 "$HTTP"

# Owner must be DEVELOPER for auto-assign membership to work (ADR 0007)
HTTP=$(post /projects "{\"name\":\"Demo\",\"description\":\"smoke project\",\"ownerId\":$ALICE_ID}")
check "POST /projects (owner=alice DEVELOPER)" 200 "$HTTP"
PROJ_ID=$(body_jq '.id'); echo "  project.id=$PROJ_ID"

HTTP=$(post /projects "{\"name\":\"Demo\",\"description\":\"dup\",\"ownerId\":$ALICE_ID}")
check "POST /projects duplicate name -> 409" 409 "$HTTP"
check "  err code" "PROJECT_DUPLICATE_NAME" "$(body_jq .code)"

HTTP=$(get /projects/$PROJ_ID); check "GET /projects/{id}" 200 "$HTTP"

HTTP=$(patch_ /projects/$PROJ_ID '{"description":"updated description"}')
check "PATCH /projects/{id}" 200 "$HTTP"
check "  desc updated" "updated description" "$(body_jq .description)"

# =====================================================================
echo ""
echo "=== 4. TICKETS CRUD + FSM + auto-assign ==="
# Alice is owner + DEVELOPER -> she's the only member, auto-assign picks her.
HTTP=$(post /tickets "{\"projectId\":$PROJ_ID,\"title\":\"Auto1\",\"description\":\"a\",\"status\":\"TODO\",\"priority\":\"MEDIUM\",\"type\":\"BUG\"}")
check "POST /tickets (no assignee, triggers auto-assign)" 200 "$HTTP"
AA_ID=$(body_jq '.id')
AA_ASSIGNEE=$(body_jq '.assigneeId')
echo "  ticket.id=$AA_ID assignee=$AA_ASSIGNEE (expecting alice=$ALICE_ID)"
check "  auto-assigned to alice" "$ALICE_ID" "$AA_ASSIGNEE"

HTTP=$(post /tickets "{\"projectId\":$PROJ_ID,\"title\":\"Manual1\",\"description\":\"b\",\"status\":\"TODO\",\"priority\":\"HIGH\",\"type\":\"FEATURE\",\"assigneeId\":$ALICE_ID}")
check "POST /tickets explicit assignee=alice (project member)" 200 "$HTTP"
TICKET_ID=$(body_jq '.id')
TICKET_V=$(body_jq '.version')
echo "  ticket.id=$TICKET_ID version=$TICKET_V"

# Reject explicit assignment to a non-member (bob)
HTTP=$(post /tickets "{\"projectId\":$PROJ_ID,\"title\":\"BadAssign\",\"description\":\"c\",\"status\":\"TODO\",\"priority\":\"LOW\",\"type\":\"BUG\",\"assigneeId\":$BOB_ID}")
check "POST /tickets explicit assignee=bob (not a member) -> 422" 422 "$HTTP"
check "  err code" "INVALID_ASSIGNEE" "$(body_jq .code)"

HTTP=$(get "/tickets?projectId=$PROJ_ID"); check "GET /tickets?projectId" 200 "$HTTP"
HTTP=$(get /tickets/$TICKET_ID); check "GET /tickets/{id}" 200 "$HTTP"

# FSM forward
HTTP=$(patch_ /tickets/$TICKET_ID "{\"status\":\"IN_PROGRESS\",\"version\":$TICKET_V}")
check "PATCH status TODO->IN_PROGRESS" 200 "$HTTP"
TICKET_V=$(body_jq '.version')

# FSM backward (illegal)
HTTP=$(patch_ /tickets/$TICKET_ID "{\"status\":\"TODO\",\"version\":$TICKET_V}")
check "PATCH backward IN_PROGRESS->TODO -> 409" 409 "$HTTP"
check "  err code" "TICKET_INVALID_TRANSITION" "$(body_jq .code)"

# FSM skip-ahead (illegal)
HTTP=$(patch_ /tickets/$TICKET_ID "{\"status\":\"DONE\",\"version\":$TICKET_V}")
check "PATCH skip-ahead IN_PROGRESS->DONE -> 409" 409 "$HTTP"
check "  err code" "TICKET_INVALID_TRANSITION" "$(body_jq .code)"

# Priority change resets is_overdue cycle (slice 5 D6 wiring for slice 14)
HTTP=$(patch_ /tickets/$TICKET_ID "{\"priority\":\"CRITICAL\",\"version\":$TICKET_V}")
check "PATCH priority change" 200 "$HTTP"
TICKET_V=$(body_jq '.version')

# Optimistic lock
HTTP=$(patch_ /tickets/$TICKET_ID '{"title":"stale","version":1}')
check "PATCH stale version -> 409" 409 "$HTTP"
check "  err code" "TICKET_VERSION_CONFLICT" "$(body_jq .code)"

# =====================================================================
echo ""
echo "=== 5. COMMENTS + MENTIONS ==="
# Comments route: /tickets/{ticketId}/comments
HTTP=$(post /tickets/$TICKET_ID/comments '{"content":"first comment, hey @alice take a look"}')
check "POST /tickets/{id}/comments with @mention" 200 "$HTTP"
COMMENT_ID=$(body_jq '.id')
MENTIONS_COUNT=$(body_jq '.mentionedUsers | length')
check "  mentionedUsers count=1" "1" "$MENTIONS_COUNT"
check "  mentioned[0].username" "alice" "$(body_jq '.mentionedUsers[0].username')"
COMMENT_V=$(body_jq '.version')

HTTP=$(get /tickets/$TICKET_ID/comments); check "GET /tickets/{id}/comments" 200 "$HTTP"
check "  list length=1" "1" "$(body_jq 'length')"

# Update comment removes the mention
HTTP=$(patch_ /tickets/$TICKET_ID/comments/$COMMENT_ID "{\"content\":\"updated without mention\",\"version\":$COMMENT_V}")
check "PATCH comment (re-evaluates mentions)" 200 "$HTTP"
check "  mentionedUsers now empty" "0" "$(body_jq '.mentionedUsers | length')"

HTTP=$(get /users/$ALICE_ID/mentions); check "GET /users/{aliceId}/mentions" 200 "$HTTP"
echo "  mention total=$(body_jq '.total')  (removed on PATCH, should be 0)"

# Re-add a mention via a new comment to verify the mentions endpoint surfaces it
HTTP=$(post /tickets/$TICKET_ID/comments '{"content":"second comment @alice"}')
HTTP=$(get /users/$ALICE_ID/mentions)
check "  /mentions total=1 after new mention" "1" "$(body_jq '.total')"

# =====================================================================
echo ""
echo "=== 6. TICKET DEPENDENCIES ==="
HTTP=$(post /tickets "{\"projectId\":$PROJ_ID,\"title\":\"Blocker\",\"description\":\"d\",\"status\":\"TODO\",\"priority\":\"LOW\",\"type\":\"TECHNICAL\",\"assigneeId\":$ALICE_ID}")
BLOCKER_ID=$(body_jq '.id'); echo "  blocker.id=$BLOCKER_ID"

HTTP=$(post /tickets/$TICKET_ID/dependencies "{\"blockedBy\":$BLOCKER_ID}")
check "POST /tickets/{id}/dependencies -> 201" 201 "$HTTP"

HTTP=$(get /tickets/$TICKET_ID/dependencies); check "GET /tickets/{id}/dependencies" 200 "$HTTP"
check "  dep list length=1" "1" "$(body_jq 'length')"

# Walk TICKET forward to IN_REVIEW so we can try DONE
TICKET_V=$(get /tickets/$TICKET_ID > /dev/null && body_jq '.version')
HTTP=$(patch_ /tickets/$TICKET_ID "{\"status\":\"IN_REVIEW\",\"version\":$TICKET_V}")
check "PATCH ticket IN_PROGRESS->IN_REVIEW" 200 "$HTTP"
TICKET_V=$(body_jq '.version')

HTTP=$(patch_ /tickets/$TICKET_ID "{\"status\":\"DONE\",\"version\":$TICKET_V}")
check "PATCH DONE with open blocker -> 409" 409 "$HTTP"
check "  err code" "TICKET_HAS_OPEN_BLOCKERS" "$(body_jq .code)"

# Self-dependency
HTTP=$(post /tickets/$TICKET_ID/dependencies "{\"blockedBy\":$TICKET_ID}")
check "POST self-dep -> 422" 422 "$HTTP"
check "  err code" "DEPENDENCY_SELF" "$(body_jq .code)"

# Duplicate
HTTP=$(post /tickets/$TICKET_ID/dependencies "{\"blockedBy\":$BLOCKER_ID}")
check "POST duplicate dep -> 409" 409 "$HTTP"
check "  err code" "DEPENDENCY_EXISTS" "$(body_jq .code)"

# Cycle: blocker now blocked by ticket (forming cycle TICKET->BLOCKER->TICKET)
HTTP=$(post /tickets/$BLOCKER_ID/dependencies "{\"blockedBy\":$TICKET_ID}")
check "POST cycle -> 422" 422 "$HTTP"
check "  err code" "DEPENDENCY_CYCLE" "$(body_jq .code)"

HTTP=$(delete /tickets/$TICKET_ID/dependencies/$BLOCKER_ID)
check "DELETE /tickets/{id}/dependencies/{blockerId} -> 204" 204 "$HTTP"

# =====================================================================
echo ""
echo "=== 7. ATTACHMENTS ==="
# Minimal valid PNG (8-byte signature + IHDR + tiny IDAT + IEND)
printf '\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR\x00\x00\x00\x01\x00\x00\x00\x01\x08\x06\x00\x00\x00\x1f\x15\xc4\x89\x00\x00\x00\rIDAT\x78\x9c\x62\x00\x00\x00\x00\x05\x00\x01\x0d\x0a\x2d\xb4\x00\x00\x00\x00IEND\xaeB`\x82' > /tmp/.tiny.png

HTTP=$(curl -sS -o /tmp/.body -w "%{http_code}" -X POST -H "Authorization: Bearer $TOKEN" \
  -F "file=@/tmp/.tiny.png;type=image/png" \
  $BASE/tickets/$TICKET_ID/attachments)
check "POST /tickets/{id}/attachments PNG" 200 "$HTTP"
ATT_ID=$(body_jq '.id'); echo "  attachment.id=$ATT_ID"

HTTP=$(curl -sS -o /tmp/.dl -w "%{http_code}" -H "Authorization: Bearer $TOKEN" \
  $BASE/tickets/$TICKET_ID/attachments/$ATT_ID)
check "GET /tickets/{tid}/attachments/{aid} (download)" 200 "$HTTP"
DL_SIZE=$(wc -c < /tmp/.dl | tr -d ' ')
ORIG_SIZE=$(wc -c < /tmp/.tiny.png | tr -d ' ')
check "  download size matches upload ($ORIG_SIZE)" "$ORIG_SIZE" "$DL_SIZE"

# Bad magic bytes
echo "I AM NOT A PNG" > /tmp/.fake.png
HTTP=$(curl -sS -o /tmp/.body -w "%{http_code}" -X POST -H "Authorization: Bearer $TOKEN" \
  -F "file=@/tmp/.fake.png;type=image/png" \
  $BASE/tickets/$TICKET_ID/attachments)
check "POST attachment with magic-byte mismatch -> 415" 415 "$HTTP"

# Unsupported declared MIME
HTTP=$(curl -sS -o /tmp/.body -w "%{http_code}" -X POST -H "Authorization: Bearer $TOKEN" \
  -F "file=@/tmp/.fake.png;type=application/x-msdownload" \
  $BASE/tickets/$TICKET_ID/attachments)
check "POST attachment unsupported MIME -> 415" 415 "$HTTP"

# Missing file part
HTTP=$(curl -sS -o /tmp/.body -w "%{http_code}" -X POST -H "Authorization: Bearer $TOKEN" \
  -F "notafile=anything" $BASE/tickets/$TICKET_ID/attachments)
check "POST attachment missing file part -> 400" 400 "$HTTP"

HTTP=$(delete /tickets/$TICKET_ID/attachments/$ATT_ID)
check "DELETE attachment -> 204" 204 "$HTTP"

# =====================================================================
echo ""
echo "=== 8. CSV EXPORT / IMPORT ==="
HTTP=$(curl -sS -o /tmp/.csv -w "%{http_code}" -H "Authorization: Bearer $TOKEN" \
  "$BASE/tickets/export?projectId=$PROJ_ID")
check "GET /tickets/export" 200 "$HTTP"
ROWS=$(wc -l < /tmp/.csv | tr -d ' ')
echo "  exported csv lines=$ROWS (incl. header)"
head -1 /tmp/.csv | sed 's/^/    header: /'

# Import a CSV with all 8 columns. Use printf to avoid heredoc quote-escaping
# pitfalls; RFC 4180 escapes " inside a quoted field as "".
{
  echo 'id,title,description,status,priority,type,assigneeId,dueDate'
  printf ',"Imp1","desc with, commas",TODO,LOW,BUG,%s,\n' "$ALICE_ID"
  printf ',"Imp2","quotes ""inside""",TODO,HIGH,FEATURE,%s,\n' "$ALICE_ID"
  printf ',"Imp3","ok",TODO,MEDIUM,TECHNICAL,%s,\n' "$ALICE_ID"
} > /tmp/.import.csv

HTTP=$(curl -sS -o /tmp/.body -w "%{http_code}" -X POST -H "Authorization: Bearer $TOKEN" \
  -F "file=@/tmp/.import.csv;type=text/csv" -F "projectId=$PROJ_ID" \
  $BASE/tickets/import)
check "POST /tickets/import" 200 "$HTTP"
echo "  import summary: $(body)"
check "  created=3" "3" "$(body_jq '.created')"
check "  failed=0" "0" "$(body_jq '.failed')"

# =====================================================================
echo ""
echo "=== 9. WORKLOAD ==="
HTTP=$(get /projects/$PROJ_ID/workload)
check "GET /projects/{id}/workload" 200 "$HTTP"
WL_LEN=$(body_jq 'length')
check "  workload has alice" "1" "$WL_LEN"
ALICE_LOAD=$(body_jq '.[0].openTicketCount')
echo "  alice openTicketCount=$ALICE_LOAD"

# =====================================================================
echo ""
echo "=== 10. SOFT DELETE + RESTORE ==="
HTTP=$(delete /tickets/$BLOCKER_ID)
check "DELETE /tickets/{id} (soft) -> 204" 204 "$HTTP"

HTTP=$(get /tickets/$BLOCKER_ID)
check "GET deleted ticket -> 404" 404 "$HTTP"

HTTP=$(get "/tickets/deleted?projectId=$PROJ_ID")
check "GET /tickets/deleted?projectId" 200 "$HTTP"
DEL_COUNT=$(body_jq 'length')
echo "  deleted ticket count=$DEL_COUNT"

HTTP=$(post /tickets/$BLOCKER_ID/restore '')
check "POST /tickets/{id}/restore" 200 "$HTTP"

HTTP=$(get /tickets/$BLOCKER_ID)
check "GET restored ticket -> 200" 200 "$HTTP"

# Restore an already-active one -> 409 ALREADY_ACTIVE
HTTP=$(post /tickets/$BLOCKER_ID/restore '')
check "POST restore already-active -> 409" 409 "$HTTP"
check "  err code" "ALREADY_ACTIVE" "$(body_jq .code)"

# Project soft-delete + deleted listing
HTTP=$(get /projects/deleted)
check "GET /projects/deleted" 200 "$HTTP"

# =====================================================================
echo ""
echo "=== 11. AUDIT LOG ==="
HTTP=$(get "/audit-logs?pageSize=100")
check "GET /audit-logs" 200 "$HTTP"
TOTAL=$(body_jq '.total')
echo "  audit total=$TOTAL"

HTTP=$(get "/audit-logs?action=AUTO_ASSIGN")
check "GET /audit-logs?action=AUTO_ASSIGN" 200 "$HTTP"
AA_TOTAL=$(body_jq '.total')
echo "  AUTO_ASSIGN rows=$AA_TOTAL  (expect >=1 for auto-assigned tickets + CSV imports)"
check_ne "  AUTO_ASSIGN >=1" "0" "$AA_TOTAL"

HTTP=$(get "/audit-logs?actor=SYSTEM")
check "GET /audit-logs?actor=SYSTEM" 200 "$HTTP"
echo "  SYSTEM rows=$(body_jq '.total')"

HTTP=$(get "/audit-logs?entityType=TICKET")
check "GET /audit-logs?entityType=TICKET" 200 "$HTTP"
echo "  TICKET-entity rows=$(body_jq '.total')"

# =====================================================================
echo ""
echo "=== 12. LOGOUT + deny-list ==="
HTTP=$(curl -sS -o /tmp/.body -w "%{http_code}" -X POST -H "Authorization: Bearer $TOKEN" $BASE/auth/logout)
check "POST /auth/logout" 200 "$HTTP"

HTTP=$(get /auth/me)
check "GET /auth/me with revoked token -> 401" 401 "$HTTP"

# =====================================================================
echo ""
echo "================================================================"
echo "RESULT: $PASS passed, $FAIL failed"
if (( FAIL > 0 )); then
    echo "FAILURES:"
    for f in "${FAILURES[@]}"; do echo "  - $f"; done
    exit 1
fi
echo "ALL SMOKE TESTS PASSED"
