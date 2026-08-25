#!/bin/bash
#
# Provision a running OneDev with the CraftGenie conventions.
#
# Creates, for one customer, the arrangement the CraftGenie integration design assumes:
#
#   * a project tree      <group>/<customer>/{backend,ui}, so branch protections,
#                         webhooks and workspace specs are inherited by both repos
#   * role-based AI users cg-implementer, cg-reviewer, cg-architect - identities that
#                         humans mention and assign, while execution happens in the
#                         CraftGenie Agent Gateway
#   * an issue link       "Backend counterpart" <-> "UI counterpart", which is what turns
#                         cross-repo branch resolution from a guess into a lookup
#   * saved queries       cross-repo issue and pull request views over the whole tree
#   * a webhook           on the customer project, inherited by both repos
#
# Every step is idempotent: re-running reports "exists" and changes nothing.
#
#   ./cg-provision.sh              provision using craftgenie-dev/.env
#   ./cg-provision.sh --dry-run    show what would happen, change nothing
#   ./cg-provision.sh --customer beta

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="$ROOT/craftgenie-dev/.env"

if [ -t 1 ]; then
	BOLD=$'\033[1m'; DIM=$'\033[2m'; RED=$'\033[31m'; GREEN=$'\033[32m'
	YELLOW=$'\033[33m'; BLUE=$'\033[34m'; RESET=$'\033[0m'
else
	BOLD=""; DIM=""; RED=""; GREEN=""; YELLOW=""; BLUE=""; RESET=""
fi

# All of these write to stderr on purpose: ensure_project returns a project id through
# command substitution, and anything printed to stdout inside $(...) would be captured
# as part of that id rather than shown to the user.
info()    { printf '%s==>%s %s\n' "$BLUE" "$RESET" "$*" >&2; }
created() { printf '%s  +%s %s\n' "$GREEN" "$RESET" "$*" >&2; }
exists()  { printf '%s  =%s %s %s(exists)%s\n' "$DIM" "$RESET" "$*" "$DIM" "$RESET" >&2; }
skipped() { printf '%s  -%s %s\n' "$YELLOW" "$RESET" "$*" >&2; }
die()     { printf '%serror%s %s\n' "$RED" "$RESET" "$*" >&2; exit 1; }

DRY_RUN=0

# ------------------------------------------------------------------ configuration

while [ $# -gt 0 ]; do
	case "$1" in
		--dry-run)  DRY_RUN=1; shift ;;
		--customer) CG_CUSTOMER_OVERRIDE="$2"; shift 2 ;;
		--group)    CG_GROUP_OVERRIDE="$2"; shift 2 ;;
		-h|--help)  sed -n '2,22p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
		*)          die "Unknown argument: $1" ;;
	esac
done

[ -f "$ENV_FILE" ] || die "Missing $ENV_FILE - run ./cg-dev.sh up first"
set -a; . "$ENV_FILE"; set +a

: "${ONEDEV_SERVER_URL:=http://localhost:6610}"
: "${ONEDEV_INITIAL_USER:=admin}"
: "${CG_GROUP:=craftgenie}"
: "${CG_CUSTOMER:=acme}"
: "${CG_BACKEND_NAME:=backend}"
: "${CG_UI_NAME:=ui}"
CG_GROUP="${CG_GROUP_OVERRIDE:-$CG_GROUP}"
CG_CUSTOMER="${CG_CUSTOMER_OVERRIDE:-$CG_CUSTOMER}"

[ -n "${ONEDEV_INITIAL_PASSWORD:-}" ] \
	|| die "ONEDEV_INITIAL_PASSWORD is not set in $ENV_FILE"

# Checked here rather than where the webhook is created, so a misconfiguration costs nothing:
# ensure_webhook runs last, and failing there would leave every project and query already made.
#
# Refused rather than sent empty or left out. Empty fails validation - WebHook.getSecret is
# @NotEmpty and ProjectSetting.getWebHooks is @Valid, so the whole settings POST is rejected with
# "webHooks[0].secret: must not be empty". Leaving the property out succeeds, but OneDev then
# generates a secret of its own, and a secret only OneDev knows is worse than no webhook: events
# arrive signed with something the receiver cannot check, so the signature stops meaning anything
# while still looking like a control that is switched on.
if [ -n "${CG_WEBHOOK_URL:-}" ] && [ -z "${CG_WEBHOOK_SECRET:-}" ]; then
	die "CG_WEBHOOK_SECRET is required when CG_WEBHOOK_URL is set - the receiver needs it to \
verify the X-OneDev-Signature header. Set both in $ENV_FILE, or clear CG_WEBHOOK_URL to skip \
webhook provisioning."
fi

API="$ONEDEV_SERVER_URL/~api"
AUTH="$ONEDEV_INITIAL_USER:$ONEDEV_INITIAL_PASSWORD"

CUSTOMER_PATH="$CG_GROUP/$CG_CUSTOMER"
BACKEND_PATH="$CUSTOMER_PATH/$CG_BACKEND_NAME"
UI_PATH="$CUSTOMER_PATH/$CG_UI_NAME"

# ------------------------------------------------------------------ REST helpers

# api <METHOD> <path> [body] -> body on stdout; non-2xx is fatal and prints the response.
api() {
	local method="$1" path="$2" body="${3:-}" out status
	local args=(-sS -w '\n%{http_code}' -u "$AUTH" -X "$method"
		-H 'Accept: application/json' -H 'Content-Type: application/json')
	[ -n "$body" ] && args+=(-d "$body")
	out=$(curl "${args[@]}" "$API$path") || die "Request failed: $method $path"
	status=$(printf '%s' "$out" | tail -n1)
	body=$(printf '%s' "$out" | sed '$d')
	case "$status" in
		2*) printf '%s' "$body" ;;
		*)  die "$method $path -> HTTP $status: $body" ;;
	esac
}

# Distinguishes "not there" from "request broke", which matters for idempotency:
# treating a 500 as absent would make this script create duplicates.
api_find() {
	local path="$1" out status
	out=$(curl -sS -w '\n%{http_code}' -u "$AUTH" -H 'Accept: application/json' "$API$path") \
		|| die "Request failed: GET $path"
	status=$(printf '%s' "$out" | tail -n1)
	case "$status" in
		2*) printf '%s' "$out" | sed '$d' ;;
		404) printf '' ;;
		*)  die "GET $path -> HTTP $status" ;;
	esac
}

would() {
	if [ "$DRY_RUN" = "1" ]; then
		skipped "would create $*"
		return 0
	fi
	return 1
}

# ------------------------------------------------------------------ projects

# A project id is always numeric. Anything else means the call failed inside a command
# substitution, where die()'s exit only unwinds the subshell.
require_id() {
	local id="$1" what="$2"
	case "$id" in
		''|*[!0-9]*) die "Could not resolve $what - see the error above" ;;
	esac
	printf '%s' "$id"
}

project_id() {
	# The path is a URL segment, so a nested path needs its slashes preserved but
	# spaces and the like encoded. Project names are restricted enough that this is safe.
	api_find "/projects/ids/$1" | tr -d '"'
}

ensure_project() {
	local path="$1" name="$2" parent_id="${3:-}" description="$4"
	local id
	id=$(project_id "$path")
	if [ -n "$id" ]; then
		exists "project $path"
		printf '%s' "$id"
		return
	fi
	if would "project $path"; then printf ''; return; fi

	local payload
	# gitPackConfig and codeAnalysisSetting are @NotNull on ProjectData, but every field
	# inside them is optional - empty objects get OneDev's defaults.
	payload=$(jq -n --arg name "$name" --arg description "$description" \
		--argjson parentId "${parent_id:-null}" \
		'{name: $name, description: $description, codeManagement: true,
		  issueManagement: true, packManagement: false, timeTracking: false,
		  gitPackConfig: {}, codeAnalysisSetting: {}}
		 + (if $parentId == null then {} else {parentId: $parentId} end)')
	# No pipe here: a pipeline reports the status of its last command, which would hide
	# a failed POST behind a successful tr.
	id=$(api POST "/projects" "$payload")
	id=${id//\"/}
	created "project $path"
	printf '%s' "$id"
}

# ------------------------------------------------------------------ AI users

ensure_ai_user() {
	local login="$1" full_name="$2" system_prompt="$3"
	# /users/ids/{name} 404s when absent, which api_find turns into an empty string.
	# The collection endpoint takes 'term', not 'query', and rejects anything else.
	if [ -n "$(api_find "/users/ids/$login")" ]; then
		exists "AI user @$login"
		return
	fi
	if would "AI user @$login"; then return; fi

	# No modelSetting: under the CraftGenie design an AI user is an identity, not a
	# runtime. Model and provider selection belongs to the Agent Gateway. Leaving it
	# unset keeps OneDev from trying to drive the agent itself.
	local payload
	payload=$(jq -n --arg name "$login" --arg fullName "$full_name" --arg prompt "$system_prompt" \
		'{type: "AI", name: $name, fullName: $fullName,
		  aiSetting: {entitleToAll: true, systemPrompt: $prompt,
		              proactive: false, maxLoopCount: 3,
		              pullRequestAssigneeResponsibilities: []}}')
	api POST "/users" "$payload" >/dev/null
	created "AI user @$login"
}

# ------------------------------------------------------------------ issue link

ensure_link_spec() {
	local name="$1" opposite="$2"
	local found
	found=$(api GET "/link-specs?name=$(printf '%s' "$name" | jq -sRr @uri)")
	if [ "$(printf '%s' "$found" | jq 'length')" != "0" ]; then
		exists "issue link \"$name\" <-> \"$opposite\""
		return
	fi
	if would "issue link \"$name\" <-> \"$opposite\""; then return; fi

	local payload
	payload=$(jq -n --arg name "$name" --arg opposite "$opposite" \
		'{name: $name, multiple: false, opposite: {name: $opposite, multiple: false}}')
	api POST "/link-specs" "$payload" >/dev/null
	created "issue link \"$name\" <-> \"$opposite\""
}

# ------------------------------------------------------------------ saved queries

# Adds named queries to a global setting without disturbing the ones already there.
# The settings endpoint replaces the whole object, so this reads, merges and writes back.
ensure_saved_queries() {
	local endpoint="$1" label="$2" queries_json="$3"
	local current merged added
	current=$(api GET "$endpoint")
	# Append only names that are not already present, preserving existing order.
	# unique_by() would have worked for dedup but it sorts, silently reshuffling the
	# queries OneDev ships and any the team has since added.
	merged=$(printf '%s' "$current" | jq --argjson new "$queries_json" '
		(.namedQueries // []) as $cur
		| .namedQueries = ($cur + ($new | map(select(
			.name as $n | ($cur | map(.name) | index($n)) == null))))')
	added=$(( $(printf '%s' "$merged" | jq '.namedQueries | length') \
		- $(printf '%s' "$current" | jq '(.namedQueries // []) | length') ))
	if [ "$added" -eq 0 ]; then
		exists "$label saved queries"
		return
	fi
	if would "$added $label saved queries"; then return; fi
	api POST "$endpoint" "$merged" >/dev/null
	created "$added $label saved quer$([ "$added" = 1 ] && echo y || echo ies)"
}

# ------------------------------------------------------------------ webhook

ensure_webhook() {
	local project_id="$1" url="$2" secret="$3"
	if [ -z "$url" ]; then
		skipped "webhook (CG_WEBHOOK_URL is not set)"
		return
	fi
	# Only reachable in a dry run: ensure_project deliberately returns an empty id for a
	# project it would have created, and require_id has already stopped a real run without
	# one. Reading settings first would mean GET /projects//setting and a fatal 404 on
	# exactly the case the preview exists for - a customer who has never been provisioned.
	if [ -z "$project_id" ]; then
		would "webhook -> $url" || die "No project to attach the webhook to"
		return
	fi
	local setting
	setting=$(api GET "/projects/$project_id/setting")
	if printf '%s' "$setting" | jq -e --arg u "$url" '(.webHooks // []) | map(select(.postUrl == $u)) | length > 0' >/dev/null; then
		exists "webhook -> $url"
		return
	fi
	if would "webhook -> $url"; then return; fi

	local updated
	updated=$(printf '%s' "$setting" | jq --arg u "$url" --arg s "$secret" '
		.webHooks = ((.webHooks // []) + [{
			postUrl: $u,
			eventTypes: ["ISSUE", "PULL_REQUEST", "BUILD"],
			secret: $s,
			headers: []
		}])')
	api POST "/projects/$project_id/setting" "$updated" >/dev/null
	created "webhook -> $url"
}

# ------------------------------------------------------------------ main

printf '%sCraftGenie provisioning%s  %s%s%s\n' "$BOLD" "$RESET" "$DIM" "$ONEDEV_SERVER_URL" "$RESET"
[ "$DRY_RUN" = "1" ] && printf '%s(dry run - nothing will be changed)%s\n' "$YELLOW" "$RESET"
echo

api GET "/users/me" >/dev/null || die "Cannot authenticate to $API as $ONEDEV_INITIAL_USER"

info "Project tree"
GROUP_ID=$(ensure_project "$CG_GROUP" "$CG_GROUP" "" "CraftGenie customers")
CUSTOMER_ID=""
if [ "$DRY_RUN" = "0" ]; then
	GROUP_ID=$(require_id "$GROUP_ID" "project $CG_GROUP")
fi
CUSTOMER_ID=$(ensure_project "$CUSTOMER_PATH" "$CG_CUSTOMER" "$GROUP_ID" \
	"$CG_CUSTOMER - settings here are inherited by both repositories")
if [ "$DRY_RUN" = "0" ]; then
	CUSTOMER_ID=$(require_id "$CUSTOMER_ID" "project $CUSTOMER_PATH")
fi
ensure_project "$BACKEND_PATH" "$CG_BACKEND_NAME" "$CUSTOMER_ID" "$CG_CUSTOMER backend (Java)" >/dev/null
ensure_project "$UI_PATH" "$CG_UI_NAME" "$CUSTOMER_ID" "$CG_CUSTOMER frontend (React)" >/dev/null

echo
info "AI users (identities - execution lives in the Agent Gateway)"
ensure_ai_user "cg-implementer" "CraftGenie Implementer" \
	"You implement assigned issues across the backend and frontend repositories of a CraftGenie project. Before changing any API contract, DTO or endpoint, read the corresponding code in the sibling repository. Never invent an API shape."
ensure_ai_user "cg-reviewer" "CraftGenie Reviewer" \
	"You review pull requests for correctness and for consistency between the backend and frontend repositories. You have read access only; report problems rather than fixing them."
ensure_ai_user "cg-architect" "CraftGenie Architect" \
	"You answer design questions spanning the backend and frontend repositories of a CraftGenie project, and propose approaches before implementation begins."

echo
info "Issue links"
ensure_link_spec "Backend counterpart" "UI counterpart"

echo
info "Saved queries"
ensure_saved_queries "/settings/issue" "issue" "$(jq -n --arg p "$CUSTOMER_PATH" '[
	{name: ("CraftGenie / " + $p + " - open"),
	 query: ("\"Project\" is \"" + $p + "/**\" and \"State\" is \"Open\"")},
	{name: ("CraftGenie / " + $p + " - waiting on an agent"),
	 query: ("\"Project\" is \"" + $p + "/**\" and \"State\" is \"Open\" and mentioned \"cg-implementer\"")}
]')"
ensure_saved_queries "/settings/pull-request" "pull request" "$(jq -n --arg p "$CUSTOMER_PATH" '[
	{name: ("CraftGenie / " + $p + " - open"),
	 query: ("\"Target Project\" is \"" + $p + "/**\" and open")},
	{name: ("CraftGenie / " + $p + " - ready to merge"),
	 query: ("\"Target Project\" is \"" + $p + "/**\" and ready to merge")},
	{name: ("CraftGenie / " + $p + " - needs my action"),
	 query: ("\"Target Project\" is \"" + $p + "/**\" and need my action")}
]')"

echo
info "Webhook on $CUSTOMER_PATH (inherited by both repositories)"
ensure_webhook "$CUSTOMER_ID" "${CG_WEBHOOK_URL:-}" "${CG_WEBHOOK_SECRET:-}"

echo
printf '%sDone.%s\n' "$BOLD" "$RESET"
printf '  projects   %s/%s\n' "$ONEDEV_SERVER_URL" "$BACKEND_PATH"
printf '             %s/%s\n' "$ONEDEV_SERVER_URL" "$UI_PATH"
printf '  issues     %s/~issues\n' "$ONEDEV_SERVER_URL"
printf '  requests   %s/~pulls\n' "$ONEDEV_SERVER_URL"
