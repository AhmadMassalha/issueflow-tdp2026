<p align="center">
  <a href="https://spring.io/projects/spring-boot" target="blank"><img src="https://spring.io/img/spring-2.svg" width="200" alt="Spring Logo" /></a>
</p>

# IssueFlow – Ticket Management Backend Platform

## Overview
IssueFlow is a backend service designed to handle a lightweight project and issue tracking platform.
The system manages users, projects, tickets (issues), comments on tickets, audit logs, ticket dependencies, attachments, and bulk ticket import/export.

## Functionality
The system provides the following APIs:

- **Users API**: Manages user identities behind ticket assignments and comments.
- **Projects API**: Manages top-level containers that group related tickets.
- **Tickets API**: Manages the core work items (issues) tracked in the system.
- **Comments API**: Manages user comments on tickets.
- **Audit Log API**: Read-only log of all state-changing actions in the system.
- **Dependencies API**: Manages ticket-to-ticket blocker relationships.
- **Attachments API**: Manages file attachments on tickets.
- **Export/Import API**: Supports bulk ticket export and import via CSV.
- **Soft Delete API**: Tickets and projects are soft-deleted and can be restored by ADMIN users.
- **Mentions API**: `@username` mentions in comments are validated, persisted, and retrievable per user.
- **Auto-Escalation**: A background scheduler automatically escalates ticket priority when a `dueDate` is exceeded.
- **Auto-Assignment**: Tickets without an explicit assignee are automatically assigned to the least-loaded DEVELOPER in the project.

## Technical Aspects
The system is built using Java 21 or Java 25 with Spring Boot 3 or Spring Boot 4, leveraging its robust framework for creating RESTful APIs. Data persistence is managed using PostgreSQL via Spring Data JPA (Hibernate).

## Homework Task
Candidates are expected to design and implement the above APIs, adhering to RESTful principles, including input validation, proper error handling, and relevant tests.

---

## APIs

### Users APIs

| API Description      | Endpoint                    | Request Body                                                                                          | Response Status | Response Body                                                                                                        |
|----------------------|-----------------------------|-------------------------------------------------------------------------------------------------------|-----------------|----------------------------------------------------------------------------------------------------------------------|
| Get all users        | GET /users                  |                                                                                                       | 200 OK          | `[ { "id": 1, "username": "jdoe", "email": "jdoe@example.com", "fullName": "John Doe", "role": "DEVELOPER" } ]`    |
| Get user by ID       | GET /users/:userId          |                                                                                                       | 200 OK          | `{ "id": 1, "username": "jdoe", "email": "jdoe@example.com", "fullName": "John Doe", "role": "DEVELOPER" }`        |
| Create a user        | POST /users                 | `{ "username": "jdoe", "email": "jdoe@example.com", "fullName": "John Doe", "role": "DEVELOPER" }`   | 200 OK          | `{ "id": 1, "username": "jdoe", "email": "jdoe@example.com", "fullName": "John Doe", "role": "DEVELOPER" }`        |
| Update a user        | POST /users/update/:userId  | `{ "fullName": "Jane Doe", "role": "ADMIN" }`                                                         | 200 OK          |                                                                                                                      |
| Delete a user        | DELETE /users/:userId       |                                                                                                       | 200 OK          |                                                                                                                      |
---
### Authentication APIs

| API Description         | Endpoint         | Request Body                                          | Response Status | Response Body |
|-------------------------|------------------|-------------------------------------------------------|-----------------|---------------|
| Login (obtain JWT)      | POST /auth/login | `{ "username": "jdoe", "password": "secret" }`       | 200 OK          | `{ "accessToken": "<jwt>", "tokenType": "Bearer", "expiresIn": 3600 }` |
| Logout (invalidate token) | POST /auth/logout |                                                     | 200 OK          | |
| Get current user        | GET /auth/me     |    

---

### Projects APIs

| API Description       | Endpoint                          | Request Body                                                                   | Response Status | Response Body                                                                                                    |
|-----------------------|-----------------------------------|--------------------------------------------------------------------------------|-----------------|------------------------------------------------------------------------------------------------------------------|
| Get all projects      | GET /projects                     |                                                                                | 200 OK          | `[ { "id": 1, "name": "Sample Project", "description": "A sample project", "ownerId": 1 } ]`                   |
| Get project by ID     | GET /projects/:projectId          |                                                                                | 200 OK          | `{ "id": 1, "name": "Sample Project", "description": "A sample project", "ownerId": 1 }`                       |
| Create a project      | POST /projects                    | `{ "name": "Sample Project", "description": "A sample project", "ownerId": 1 }` | 200 OK        | `{ "id": 1, "name": "Sample Project", "description": "A sample project", "ownerId": 1 }`                       |
| Update a project      | PATCH /projects/:projectId        | `{ "name": "Updated Name", "description": "Updated description" }`             | 200 OK          |                                                                                                                  |
| Soft-delete a project | DELETE /projects/:projectId       |                                                                                | 200 OK          |                                                                                                                  |


---

### Tickets APIs

| API Description               | Endpoint                                   | Request Body                                                                                                                               | Response Status | Response Body                                                                                                                                                                |
|-------------------------------|--------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------|-----------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Get tickets by project        | GET /tickets?projectId=:projectId          |                                                                                                                                                         | 200 OK          | `[ { "id": 1, "title": "Fix login bug", "description": "...", "status": "TODO", "priority": "HIGH", "type": "BUG", "projectId": 1, "assigneeId": 2, "dueDate": "2026-04-01T00:00:00Z", "isOverdue": false } ]` |
| Get ticket by ID              | GET /tickets/:ticketId                     |                                                                                                                                                         | 200 OK          | `{ "id": 1, "title": "Fix login bug", "description": "...", "status": "TODO", "priority": "HIGH", "type": "BUG", "projectId": 1, "assigneeId": 2, "dueDate": "2026-04-01T00:00:00Z", "isOverdue": false }` |
| Create a ticket               | POST /tickets                              | `{ "title": "Fix login bug", "description": "...", "status": "TODO", "priority": "HIGH", "type": "BUG", "projectId": 1, "assigneeId": 2, "dueDate": "2026-04-01T00:00:00Z" }` | 200 OK          | `{ "id": 1, "title": "Fix login bug", "description": "...", "status": "TODO", "priority": "HIGH", "type": "BUG", "projectId": 1, "assigneeId": 2, "dueDate": "2026-04-01T00:00:00Z", "isOverdue": false }` |
| Update a ticket               | PATCH /tickets/:ticketId                   | `{ "title": "...", "description": "...", "status": "IN_PROGRESS", "priority": "MEDIUM", "assigneeId": 3, "dueDate": "2026-04-01T00:00:00Z" }`    | 200 OK          |                                                                                                                                                                                                                      |
| Soft-delete a ticket          | DELETE /tickets/:ticketId                  |                                                                                                                                                         | 200 OK          |                                                                                                                                                                              |
| Export tickets to CSV         | GET /tickets/export?projectId=:projectId   |                                                                                                                                            | 200 OK          | CSV file with fields: id, title, description, status, priority, type, assigneeId                                                                                             |
| Import tickets from CSV       | POST /tickets/import                       | multipart/form-data: `file` (CSV), `projectId` (form field)                                                                               | 200 OK          | `{ "created": 42, "failed": 3, "errors": [...] }`                                                                                                                           |

---

### Comments APIs

| API Description          | Endpoint                                          | Request Body                                          | Response Status | Response Body                                                                                                                                                                              |
|--------------------------|---------------------------------------------------|-------------------------------------------------------|-----------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Get comments for ticket  | GET /tickets/:ticketId/comments                   |                                                       | 200 OK          | `[ { "id": 1, "ticketId": 1, "authorId": 2, "content": "Hello @jdoe!", "mentionedUsers": [{ "id": 1, "username": "jdoe", "fullName": "John Doe" }] } ]`              |
| Add a comment            | POST /tickets/:ticketId/comments                  | `{ "authorId": 2, "content": "Hello @jdoe!" }`       | 200 OK          | `{ "id": 1, "ticketId": 1, "authorId": 2, "content": "Hello @jdoe!", "mentionedUsers": [{ "id": 1, "username": "jdoe", "fullName": "John Doe" }] }` |
| Update a comment         | PATCH /tickets/:ticketId/comments/:commentId      | `{ "content": "Updated comment." }`                   | 200 OK          |                                                                                                                                                                                            |
| Delete a comment         | DELETE /tickets/:ticketId/comments/:commentId     |                                                       | 200 OK          |                                                                                                                                                                                            |

---

### Audit Log APIs

| API Description  | Endpoint        | Query Params                                          | Response Status | Response Body                                                                                                                        |
|------------------|-----------------|-------------------------------------------------------|-----------------|--------------------------------------------------------------------------------------------------------------------------------------|
| Get audit logs   | GET /audit-logs | Optional: `entityType`, `entityId`, `action`, `actor` | 200 OK          | `[ { "id": 1, "action": "CREATE", "entityType": "TICKET", "entityId": 5, "performedBy": 2, "actor": "USER", "timestamp": "2026-03-01T10:00:00Z" } ]` |

---

### Ticket Dependencies APIs

| API Description     | Endpoint                                            | Request Body          | Response Status | Response Body                                                             |
|---------------------|-----------------------------------------------------|-----------------------|-----------------|---------------------------------------------------------------------------|
| Add a dependency    | POST /tickets/:ticketId/dependencies                | `{ "blockedBy": 42 }` | 200 OK          |                                                                           |
| List dependencies   | GET /tickets/:ticketId/dependencies                 |                       | 200 OK          | `[ { "id": 42, "title": "Blocking ticket", "status": "IN_PROGRESS" } ]`  |
| Remove a dependency | DELETE /tickets/:ticketId/dependencies/:blockerId   |                       | 200 OK          |                                                                           |

---

### Attachments APIs

| API Description   | Endpoint                                              | Request Body                | Response Status | Response Body                                                                           |
|-------------------|-------------------------------------------------------|-----------------------------|-----------------|-----------------------------------------------------------------------------------------|
| Upload attachment | POST /tickets/:ticketId/attachments                   | multipart/form-data: `file` | 200 OK          | `{ "id": 1, "ticketId": 1, "filename": "screenshot.png", "contentType": "image/png" }` |
| Delete attachment | DELETE /tickets/:ticketId/attachments/:attachmentId   |                             | 200 OK          |                                                                                         |

---

### Soft Delete APIs

Tickets and projects support **soft delete** only — deleted records are hidden from standard responses but can be restored by `ADMIN` users. Permanent (hard) deletion is not exposed through the API.

#### Tickets

| API Description                  | Endpoint                                        | Request Body | Response Status | Response Body                                                                                                        |
|----------------------------------|-------------------------------------------------|--------------|-----------------|----------------------------------------------------------------------------------------------------------------------|
| List soft-deleted tickets        | GET /tickets/deleted?projectId=:projectId       |              | 200 OK          | `[ { "id": 1, "title": "...", "status": "TODO", "priority": "HIGH", "type": "BUG", "projectId": 1 } ]`             |
| Restore a soft-deleted ticket    | POST /tickets/:ticketId/restore                 |              | 200 OK          |                                                                                                                      |

#### Projects

| API Description                  | Endpoint                          | Request Body | Response Status | Response Body                                                               |
|----------------------------------|-----------------------------------|--------------|-----------------|-----------------------------------------------------------------------------|
| List soft-deleted projects       | GET /projects/deleted             |              | 200 OK          | `[ { "id": 1, "name": "Sample Project", "description": "...", "ownerId": 1 } ]` |
| Restore a soft-deleted project   | POST /projects/:projectId/restore |              | 200 OK          |                                                                             |

---

### Mentions APIs

| API Description              | Endpoint                         | Query Params                  | Response Status | Response Body                                                                                                                                                     |
|------------------------------|----------------------------------|-------------------------------|-----------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Get mentions for a user      | GET /users/:userId/mentions      | Optional: `page`, `pageSize`  | 200 OK          | `{ "data": [ { "id": 1, "ticketId": 3, "authorId": 2, "content": "Hey @jdoe ...", "mentionedUsers": [{ "id": 1, "username": "jdoe", "fullName": "John Doe" }] } ], "total": 10, "page": 1 }` |

---

### Workload API

| API Description             | Endpoint                              | Response Status | Response Body                                                                                             |
|-----------------------------|---------------------------------------|-----------------|-----------------------------------------------------------------------------------------------------------|
| Get project workload        | GET /projects/:projectId/workload     | 200 OK          | `[ { "userId": 1, "username": "jdoe", "openTicketCount": 3 }, { "userId": 2, "username": "asmith", "openTicketCount": 5 } ]` |

---

## Jump Start
For your convenience, `compose.yml` includes a PostgreSQL DB and the app is already configured to connect to it.

Document your exact setup, build, and run steps in `run.md` (install dependencies, start the database, build the project, run the application, and run the tests).

## Description

[Spring Boot](https://spring.io/projects/spring-boot) Java starter project. Supports **Java 21** or **Java 25** with **Spring Boot 3** or **Spring Boot 4**.

## Build

```bash
# using Maven wrapper
$ ./mvnw clean package
```

## Running the app

```bash
# run with Maven
$ ./mvnw spring-boot:run

# run the packaged jar
$ java -jar target/issueflow-*.jar
```

## Test

```bash
# run all tests (Maven)
$ ./mvnw test
```

## AI & Agents

We encourage you to use AI during the process. Document how you used the agent and add all relevant files (skills, instructions, plan, etc.).

Add the main and relevant prompts that show your interaction with the agents in a `prompts.md` file.

---

## License

This project is [MIT licensed](LICENSE).
