.PHONY: db-up db-down app-build app-up app-up-local app-down app-restart app-lint app-format app-test app-logs db-logs app-logs-static db-logs-static up up-local down

# Local builds keep their own tag so they never shadow the published image.
LOCAL_IMAGE := chess-probe

# Database commands
db-up:
	docker compose up postgres -d

db-down:
	docker compose down postgres

# Application commands
app-build:
	docker build -t $(LOCAL_IMAGE) backend

# Pulls the latest image published by main; never builds locally.
app-up:
	docker compose up app -d --pull always --no-build

# Runs the image built by `make app-build`; never pulls.
app-up-local:
	CHESSPROBE_IMAGE=$(LOCAL_IMAGE) docker compose up app -d --pull never --no-build

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

up-local: db-up app-build app-up-local

down: app-down db-down
