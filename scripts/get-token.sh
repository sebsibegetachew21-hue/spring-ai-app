#!/usr/bin/env bash
set -euo pipefail

: "${AUTH0_DOMAIN:?Missing AUTH0_DOMAIN (e.g. dev-xxxx.us.auth0.com)}"
: "${AUTH0_CLIENT_ID:?Missing AUTH0_CLIENT_ID}"
: "${AUTH0_CLIENT_SECRET:?Missing AUTH0_CLIENT_SECRET}"
: "${AUTH0_AUDIENCE:?Missing AUTH0_AUDIENCE}"

curl -s --request POST \
  --url "https://${AUTH0_DOMAIN}/oauth/token" \
  --header 'content-type: application/json' \
  --data "{
    \"client_id\":\"${AUTH0_CLIENT_ID}\",
    \"client_secret\":\"${AUTH0_CLIENT_SECRET}\",
    \"audience\":\"${AUTH0_AUDIENCE}\",
    \"grant_type\":\"client_credentials\",
    \"scope\":\"chat:access\"
  }"
