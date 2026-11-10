.PHONY: db-up db-down app-build app-up app-down app-restart app-lint app-format app-test app-logs db-logs app-logs-static db-logs-static up down

# Database commands
db-up:
	docker compose up postgres -d

db-down:
	docker compose down postgres

# Application commands
app-build:
	docker build -t chess-probe backend

app-up:
	docker compose up app -d

app-down:
	docker compose stop app
	docker compose rm -f app

app-restart: app-down app-up

# Development commands
app-lint:
	cd backend && ./gradlew ktlintCheck
	cd backend && ./gradlew detektMain detektTest

app-format:
	cd backend && ./gradlew ktlintFormat

app-test:
	cd backend && ./gradlew test

# Log commands
app-logs:
	docker logs chessprobe-app -f

db-logs:
	docker logs chessprobe-postgres -f

app-logs-static:
	docker logs chessprobe-app --tail 50

db-logs-static:
	docker logs chessprobe-postgres --tail 50

# Full environment
up: db-up app-up

down: app-down db-down
