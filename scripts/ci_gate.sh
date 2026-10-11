#!/usr/bin/env sh
set -eu

echo "Evaluating CI Gate component results:"
echo "  changes:  result=${CHANGES_RESULT:-}"
echo "  backend:  filter=${BACKEND_FILTER:-} result=${BACKEND_RESULT:-}"
echo "  frontend: filter=${FRONTEND_FILTER:-} result=${FRONTEND_RESULT:-}"
echo "  docker:   filter=${DOCKER_FILTER:-} result=${DOCKER_RESULT:-}"

# 1. Fail closed if changes detection job did not succeed
if [ "${CHANGES_RESULT:-}" != "success" ]; then
  echo "::error::Changes detection job did not succeed (status: '${CHANGES_RESULT:-}'). Blocking gate."
  exit 1
fi

# 2. Validate filter outputs are strictly booleans ('true' or 'false')
for pair in "backend:${BACKEND_FILTER:-}" "frontend:${FRONTEND_FILTER:-}" "docker:${DOCKER_FILTER:-}"; do
  name="${pair%%:*}"
  val="${pair#*:}"
  if [ "${val}" != "true" ] && [ "${val}" != "false" ]; then
    echo "::error::Missing or invalid filter output for ${name}: '${val}'. Blocking gate."
    exit 1
  fi
done

# 3. Active filters must correspond to 'success'
if [ "${BACKEND_FILTER}" = "true" ] && [ "${BACKEND_RESULT:-}" != "success" ]; then
  echo "::error::Backend changes detected, but build job was '${BACKEND_RESULT:-}' (expected 'success')."
  exit 1
fi

if [ "${FRONTEND_FILTER}" = "true" ] && [ "${FRONTEND_RESULT:-}" != "success" ]; then
  echo "::error::Frontend changes detected, but frontend job was '${FRONTEND_RESULT:-}' (expected 'success')."
  exit 1
fi

if [ "${DOCKER_FILTER}" = "true" ] && [ "${DOCKER_RESULT:-}" != "success" ]; then
  echo "::error::Docker packaging changes detected, but docker job was '${DOCKER_RESULT:-}' (expected 'success')."
  exit 1
fi

# 4. Inactive filters must correspond to 'skipped'
if [ "${BACKEND_FILTER}" = "false" ] && [ "${BACKEND_RESULT:-}" != "skipped" ]; then
  echo "::error::Backend changes not detected, but build job was '${BACKEND_RESULT:-}' (expected 'skipped')."
  exit 1
fi

if [ "${FRONTEND_FILTER}" = "false" ] && [ "${FRONTEND_RESULT:-}" != "skipped" ]; then
  echo "::error::Frontend changes not detected, but frontend job was '${FRONTEND_RESULT:-}' (expected 'skipped')."
  exit 1
fi

if [ "${DOCKER_FILTER}" = "false" ] && [ "${DOCKER_RESULT:-}" != "skipped" ]; then
  echo "::error::Docker packaging changes not detected, but docker job was '${DOCKER_RESULT:-}' (expected 'skipped')."
  exit 1
fi

# 5. Fail closed if any component job reported failure, cancellation, or unknown status
for pair in "build:${BACKEND_RESULT:-}" "frontend:${FRONTEND_RESULT:-}" "docker:${DOCKER_RESULT:-}"; do
  name="${pair%%:*}"
  res="${pair#*:}"
  if [ "${res}" != "success" ] && [ "${res}" != "skipped" ]; then
    echo "::error::Job ${name} ended with non-passing status '${res}'."
    exit 1
  fi
done

echo "All applicable component checks succeeded."
exit 0
