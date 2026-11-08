# AI Engineering Workspace

A workspace where a developer, acting as CTO, recruits and leads AI agents that build real software.
Design documents live in `docs/` (discovery, architecture, UX/UI, increment reports).

## Prerequisites

Java 21, Maven 3.8+, Node 20.19+, Docker.

## Run locally

```bash
./scripts/init-env.sh                      # creates .env with random DB password and master key (once)
docker compose --env-file .env up -d       # PostgreSQL 16 (pgvector) on :55432, Redis 7 on :56379

# backend (port 8090)
cd backend && mvn -q package -DskipTests
cd .. && set -a && . ./.env && set +a && java -jar backend/target/aiw-backend-0.1.0-SNAPSHOT.jar

# frontend (port 5180, proxies /api and /ws to the backend)
cd frontend && npm install && npm run dev
```

Open http://localhost:5180, create an account and a workspace, then add an AI provider in Settings
(an Anthropic API key, or any OpenAI-compatible endpoint such as Ollama at `http://localhost:11434/v1`).

`AIW_MASTER_KEY` in `.env` encrypts stored API keys. If it is lost, stored keys cannot be decrypted.

## Tests

```bash
cd backend && mvn test        # integration tests with Testcontainers (Docker required)
cd frontend && npm run typecheck
```
