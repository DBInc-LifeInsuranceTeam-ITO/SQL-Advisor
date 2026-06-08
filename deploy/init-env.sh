#!/bin/sh
set -eu

MODE=prod
OUTPUT_PATH=
FORCE=0
DRY_RUN=0

usage() {
    cat <<'EOF'
Usage: sh deploy/init-env.sh [--mode dev|prod] [--output PATH] [--force] [--dry-run]

Options:
  --mode, -m     Environment mode to initialize. Defaults to prod.
  --output, -o   Output env file path. Defaults to deploy/.env.<mode>.
  --force, -f    Overwrite an existing env file from the template.
  --dry-run, -n  Validate and show the intended action without writing.
EOF
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        --mode|-m)
            [ "$#" -ge 2 ] || { echo "Missing value for $1" >&2; exit 1; }
            MODE=$2
            shift 2
            ;;
        --output|-o)
            [ "$#" -ge 2 ] || { echo "Missing value for $1" >&2; exit 1; }
            OUTPUT_PATH=$2
            shift 2
            ;;
        --force|-f)
            FORCE=1
            shift
            ;;
        --dry-run|-n)
            DRY_RUN=1
            shift
            ;;
        --help|-h)
            usage
            exit 0
            ;;
        dev|prod)
            MODE=$1
            shift
            ;;
        *)
            echo "Unknown argument: $1" >&2
            usage >&2
            exit 1
            ;;
    esac
done

case "$MODE" in
    dev|prod) ;;
    *)
        echo "Mode must be dev or prod: $MODE" >&2
        exit 1
        ;;
esac

SCRIPT_DIR=$(CDPATH= cd "$(dirname "$0")" && pwd)
TEMPLATE_PATH="$SCRIPT_DIR/.env.$MODE.example"

if [ -z "$OUTPUT_PATH" ]; then
    TARGET_PATH="$SCRIPT_DIR/.env.$MODE"
else
    TARGET_PATH=$OUTPUT_PATH
fi

WORK_FILE="$SCRIPT_DIR/.init-env.$$"
trap 'rm -f "$WORK_FILE"' EXIT INT TERM

env_value() {
    awk -v key="$1" '
        BEGIN { pattern = "^[[:space:]]*" key "[[:space:]]*=" }
        $0 ~ pattern {
            sub(/^[^=]*=/, "")
            print
            exit
        }
    ' "$2"
}

is_empty_or_placeholder() {
    value=$(printf '%s' "$1" | tr -d '[:space:]')
    [ -z "$value" ] && return 0

    case "$1" in
        *change-me*) return 0 ;;
        *) return 1 ;;
    esac
}

set_env_value() {
    key=$1
    value=$2
    file=$3
    tmp_file="$file.set.$$"

    if grep -Eq "^[[:space:]]*$key[[:space:]]*=" "$file"; then
        awk -v key="$key" -v value="$value" '
            BEGIN { pattern = "^[[:space:]]*" key "[[:space:]]*="; done = 0 }
            $0 ~ pattern && done == 0 {
                print key "=" value
                done = 1
                next
            }
            { print }
        ' "$file" > "$tmp_file"
        mv "$tmp_file" "$file"
    else
        printf '%s=%s\n' "$key" "$value" >> "$file"
    fi
}

new_secret() {
    if command -v openssl >/dev/null 2>&1; then
        openssl rand -base64 32 | tr '+/' '-_' | tr -d '='
        return
    fi

    if [ -r /dev/urandom ] && command -v base64 >/dev/null 2>&1; then
        dd if=/dev/urandom bs=32 count=1 2>/dev/null | base64 | tr '+/' '-_' | tr -d '='
        return
    fi

    echo "openssl or readable /dev/urandom with base64 is required to generate prod secrets." >&2
    exit 1
}

add_missing_template_keys() {
    target=$1
    template=$2
    added=0

    while IFS= read -r line || [ -n "$line" ]; do
        case "$line" in
            [A-Za-z_]*=*) key=${line%%=*} ;;
            *) continue ;;
        esac

        if grep -Eq "^[[:space:]]*$key[[:space:]]*=" "$target"; then
            continue
        fi

        if [ "$added" -eq 0 ]; then
            {
                printf '\n'
                printf '# Added by deploy/init-env.sh from .env.%s.example\n' "$MODE"
            } >> "$target"
            added=1
        fi

        printf '%s\n' "$line" >> "$target"
    done < "$template"
}

sync_prod_database_settings() {
    file=$1
    postgres_password=$(env_value POSTGRES_PASSWORD "$file")
    spring_password=$(env_value SPRING_DATASOURCE_PASSWORD "$file")

    if ! is_empty_or_placeholder "$spring_password"; then
        database_password=$spring_password
    elif ! is_empty_or_placeholder "$postgres_password"; then
        database_password=$postgres_password
    else
        database_password=$(new_secret)
    fi

    if is_empty_or_placeholder "$postgres_password"; then
        set_env_value POSTGRES_PASSWORD "$database_password" "$file"
    fi

    if is_empty_or_placeholder "$spring_password"; then
        set_env_value SPRING_DATASOURCE_PASSWORD "$database_password" "$file"
    fi

    database_url=$(env_value DATABASE_URL "$file")
    if is_empty_or_placeholder "$database_url"; then
        username=$(env_value SPRING_DATASOURCE_USERNAME "$file")
        [ -n "$username" ] || username=sqladvisor
        set_env_value DATABASE_URL "postgresql://$username:$database_password@postgres:5432/sqladvisor" "$file"
    fi
}

lower() {
    printf '%s' "$1" | tr '[:upper:]' '[:lower:]'
}

ERROR_COUNT=0
WARNINGS=

add_error() {
    ERROR_COUNT=$((ERROR_COUNT + 1))
    echo "ERROR: $1" >&2
}

add_warning() {
    WARNINGS="${WARNINGS}WARNING: $1
"
}

add_required_warning() {
    key=$1
    message=$2
    value=$(env_value "$key" "$WORK_FILE")
    if is_empty_or_placeholder "$value"; then
        add_warning "$message"
    fi
}

validate_env() {
    if [ "$MODE" = prod ]; then
        for key in POSTGRES_PASSWORD SPRING_DATASOURCE_URL SPRING_DATASOURCE_USERNAME SPRING_DATASOURCE_PASSWORD DATABASE_URL; do
            value=$(env_value "$key" "$WORK_FILE")
            if is_empty_or_placeholder "$value"; then
                add_error "$key must be set for prod."
            fi
        done

        for key in POSTGRES_PASSWORD SPRING_DATASOURCE_PASSWORD; do
            value=$(env_value "$key" "$WORK_FILE")
            case "$value" in
                sqladvisor|postgres|password)
                    add_warning "$key uses a weak default value."
                    ;;
            esac
        done
    fi

    auth_enabled=$(lower "$(env_value APP_AUTH_ENABLED "$WORK_FILE")")
    if [ "$auth_enabled" = true ]; then
        auth_mode=$(lower "$(env_value APP_AUTH_MODE "$WORK_FILE")")
        [ -n "$auth_mode" ] || auth_mode=external

        if [ "$auth_mode" = external ]; then
            value=$(env_value GOOGLE_CLIENT_ID "$WORK_FILE")
            if is_empty_or_placeholder "$value"; then
                add_error "GOOGLE_CLIENT_ID is required when APP_AUTH_ENABLED=true and APP_AUTH_MODE=external."
            fi
        elif [ "$auth_mode" = internal ]; then
            value=$(env_value APP_INTERNAL_AUTH_EMAIL_DOMAIN "$WORK_FILE")
            if is_empty_or_placeholder "$value"; then
                add_error "APP_INTERNAL_AUTH_EMAIL_DOMAIN is required when APP_AUTH_ENABLED=true and APP_AUTH_MODE=internal."
            fi
        fi
    fi

    llm_provider=$(lower "$(env_value AWR_LLM_PROVIDER "$WORK_FILE")")
    [ -n "$llm_provider" ] || llm_provider=local
    case "$llm_provider" in
        openai) add_required_warning OPENAI_API_KEY "OPENAI_API_KEY is empty while AWR_LLM_PROVIDER=openai." ;;
        gemini) add_required_warning GEMINI_API_KEY "GEMINI_API_KEY is empty while AWR_LLM_PROVIDER=gemini." ;;
        anthropic) add_required_warning ANTHROPIC_API_KEY "ANTHROPIC_API_KEY is empty while AWR_LLM_PROVIDER=anthropic." ;;
        xai) add_required_warning XAI_API_KEY "XAI_API_KEY is empty while AWR_LLM_PROVIDER=xai." ;;
        internal) add_required_warning INTERNAL_LLM_BASE_URL "INTERNAL_LLM_BASE_URL is empty while AWR_LLM_PROVIDER=internal." ;;
        ollama) add_required_warning OLLAMA_BASE_URL "OLLAMA_BASE_URL is empty while AWR_LLM_PROVIDER=ollama." ;;
        local) ;;
        *) add_warning "AWR_LLM_PROVIDER has an unrecognized value: $llm_provider." ;;
    esac

    embedding_provider=$(lower "$(env_value AWR_EMBEDDING_PROVIDER "$WORK_FILE")")
    [ -n "$embedding_provider" ] || embedding_provider=none
    case "$embedding_provider" in
        openai) add_required_warning OPENAI_API_KEY "OPENAI_API_KEY is empty while AWR_EMBEDDING_PROVIDER=openai." ;;
        gemini) add_required_warning GEMINI_API_KEY "GEMINI_API_KEY is empty while AWR_EMBEDDING_PROVIDER=gemini." ;;
        internal)
            add_required_warning INTERNAL_EMBEDDING_BASE_URL "INTERNAL_EMBEDDING_BASE_URL is empty while AWR_EMBEDDING_PROVIDER=internal."
            add_required_warning INTERNAL_EMBEDDING_MODEL "INTERNAL_EMBEDDING_MODEL is empty while AWR_EMBEDDING_PROVIDER=internal."
            ;;
        ollama) add_required_warning OLLAMA_BASE_URL "OLLAMA_BASE_URL is empty while AWR_EMBEDDING_PROVIDER=ollama." ;;
        none) ;;
        *) add_warning "AWR_EMBEDDING_PROVIDER has an unrecognized value: $embedding_provider." ;;
    esac
}

if [ ! -f "$TEMPLATE_PATH" ]; then
    echo "Template file not found: $TEMPLATE_PATH" >&2
    exit 1
fi

if [ -f "$TARGET_PATH" ] && [ "$FORCE" -eq 0 ]; then
    cp "$TARGET_PATH" "$WORK_FILE"
    add_missing_template_keys "$WORK_FILE" "$TEMPLATE_PATH"
    ACTION=update
else
    cp "$TEMPLATE_PATH" "$WORK_FILE"
    if [ -f "$TARGET_PATH" ]; then
        ACTION=overwrite
    else
        ACTION=create
    fi
fi

if [ "$MODE" = prod ]; then
    sync_prod_database_settings "$WORK_FILE"
fi

validate_env

if [ -n "$WARNINGS" ]; then
    printf '%s' "$WARNINGS" >&2
fi

if [ "$ERROR_COUNT" -gt 0 ]; then
    exit 1
fi

if [ "$DRY_RUN" -eq 1 ]; then
    echo "[DRY-RUN] Would $ACTION $TARGET_PATH"
else
    target_dir=$(dirname "$TARGET_PATH")
    if [ -n "$target_dir" ] && [ ! -d "$target_dir" ]; then
        mkdir -p "$target_dir"
    fi
    cp "$WORK_FILE" "$TARGET_PATH"
    echo "Initialized $TARGET_PATH"
fi

echo
echo "Next steps:"
if [ "$MODE" = prod ]; then
    echo "  docker compose -f deploy/docker-compose.prod.yml config"
    echo "  docker compose -f deploy/docker-compose.prod.yml up -d --build"
else
    echo "  docker compose -f deploy/docker-compose.dev.yml config"
    echo "  docker compose -f deploy/docker-compose.dev.yml up -d --build"
fi
