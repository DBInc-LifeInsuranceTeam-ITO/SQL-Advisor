#!/bin/sh
set -eu

MODE=prod
OUTPUT_PATH=
FORCE=0
DRY_RUN=0
INTERACTIVE=1

if [ ! -t 0 ]; then
    INTERACTIVE=0
fi

usage() {
    cat <<'EOF'
Usage: sh deploy/init-env.sh [--mode dev|prod] [--output PATH] [--force] [--dry-run] [--interactive|--non-interactive]

Options:
  --mode, -m         Environment mode to initialize. Defaults to prod.
  --output, -o       Output env file path. Defaults to deploy/.env.<mode>.
  --force, -f        Overwrite an existing env file from the template.
  --dry-run, -n      Validate and show the intended action without writing.
  --interactive      Force onboarding prompts even when stdin is not a terminal.
  --non-interactive  Skip onboarding prompts and only sync/validate templates.
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
        --non-interactive)
            INTERACTIVE=0
            shift
            ;;
        --interactive)
            INTERACTIVE=1
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

if command -v mktemp >/dev/null 2>&1; then
    WORK_FILE=$(mktemp "$SCRIPT_DIR/.init-env.XXXXXX")
else
    WORK_FILE="$SCRIPT_DIR/.init-env.$$"
fi
TTY_STTY=

cleanup() {
    if [ -n "$TTY_STTY" ]; then
        if [ -r /dev/tty ]; then
            stty "$TTY_STTY" < /dev/tty 2>/dev/null || true
        else
            stty "$TTY_STTY" 2>/dev/null || true
        fi
        TTY_STTY=
    fi
    rm -f "$WORK_FILE"
}

trap cleanup EXIT INT TERM

env_value() {
    awk -v key="$1" '
        BEGIN { pattern = "^[[:space:]]*" key "[[:space:]]*=" }
        $0 ~ pattern {
            sub(/^[^=]*=/, "")
            sub(/\r$/, "")
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

current_or_default() {
    key=$1
    default_value=$2
    file=$3
    current_value=$(env_value "$key" "$file")

    if is_empty_or_placeholder "$current_value"; then
        printf '%s' "$default_value"
    else
        printf '%s' "$current_value"
    fi
}

PROMPT_RESULT=
CHOICE_RESULT=
READ_HEX_RESULT=
READ_KEY_RESULT=
UI_READ_RESULT=

tty_ready() {
    if [ "$INTERACTIVE" -ne 1 ]; then
        return 1
    fi
    if [ ! -e /dev/tty ]; then
        return 1
    fi
    if ( : < /dev/tty > /dev/tty ) 2>/dev/null; then
        return 0
    fi
    return 1
}

ui_printf() {
    if tty_ready; then
        printf "$@" > /dev/tty
    else
        printf "$@"
    fi
}

ui_read_line() {
    if tty_ready; then
        IFS= read -r UI_READ_RESULT < /dev/tty || UI_READ_RESULT=
    else
        IFS= read -r UI_READ_RESULT || UI_READ_RESULT=
    fi
}

prompt_line() {
    PL_LABEL=$1
    PL_DEFAULT=$2

    if [ -n "$PL_DEFAULT" ]; then
        ui_printf '%s [%s]: ' "$PL_LABEL" "$PL_DEFAULT"
    else
        ui_printf '%s: ' "$PL_LABEL"
    fi

    ui_read_line
    PL_ANSWER=$UI_READ_RESULT
    if ! tty_ready && [ ! -t 0 ]; then
        ui_printf '\n'
    fi
    if [ -z "$PL_ANSWER" ]; then
        PL_ANSWER=$PL_DEFAULT
    fi
    PROMPT_RESULT=$PL_ANSWER
}

prompt_choice() {
    PC_LABEL=$1
    PC_DEFAULT=$2
    PC_CHOICES=$3

    if tty_ready; then
        prompt_choice_tty "$PC_LABEL" "$PC_DEFAULT" "$PC_CHOICES"
        return
    fi

    while :; do
        ui_printf '\n%s\n' "$PC_LABEL"
        PC_INDEX=1
        for PC_CHOICE in $PC_CHOICES; do
            if [ "$PC_CHOICE" = "$PC_DEFAULT" ]; then
                PC_MARKER='[x]'
            else
                PC_MARKER='[ ]'
            fi
            ui_printf '  %s %s) %s\n' "$PC_MARKER" "$PC_INDEX" "$PC_CHOICE"
            PC_INDEX=$((PC_INDEX + 1))
        done

        prompt_line "Select number or value" "$PC_DEFAULT"
        PC_ANSWER=$(lower "$PROMPT_RESULT")
        if [ -n "$PC_ANSWER" ] && [ "$PC_ANSWER" -eq "$PC_ANSWER" ] 2>/dev/null; then
            PC_INDEX=1
            for PC_CHOICE in $PC_CHOICES; do
                if [ "$PC_ANSWER" = "$PC_INDEX" ]; then
                    CHOICE_RESULT=$PC_CHOICE
                    return
                fi
                PC_INDEX=$((PC_INDEX + 1))
            done
        fi

        for PC_CHOICE in $PC_CHOICES; do
            if [ "$PC_ANSWER" = "$PC_CHOICE" ]; then
                CHOICE_RESULT=$PC_ANSWER
                return
            fi
        done
        ui_printf 'Choose a listed number or one of: %s\n' "$PC_CHOICES"
    done
}

choice_value_at() {
    CVA_TARGET=$1
    CVA_INDEX=1
    CHOICE_RESULT=
    for CVA_CHOICE in $2; do
        if [ "$CVA_INDEX" = "$CVA_TARGET" ]; then
            CHOICE_RESULT=$CVA_CHOICE
            return
        fi
        CVA_INDEX=$((CVA_INDEX + 1))
    done
}

read_hex_byte() {
    if tty_ready; then
        READ_HEX_RESULT=$(dd bs=1 count=1 2>/dev/null < /dev/tty | od -An -tx1 | tr -d ' \n')
    else
        READ_HEX_RESULT=$(dd bs=1 count=1 2>/dev/null | od -An -tx1 | tr -d ' \n')
    fi
}

read_choice_key() {
    READ_KEY_RESULT=
    read_hex_byte
    case "$READ_HEX_RESULT" in
        1b)
            read_hex_byte
            if [ "$READ_HEX_RESULT" = 5b ]; then
                read_hex_byte
                case "$READ_HEX_RESULT" in
                    41) READ_KEY_RESULT=up ;;
                    42) READ_KEY_RESULT=down ;;
                    43) READ_KEY_RESULT=right ;;
                    44) READ_KEY_RESULT=left ;;
                esac
            elif [ "$READ_HEX_RESULT" = 4f ]; then
                read_hex_byte
                case "$READ_HEX_RESULT" in
                    41) READ_KEY_RESULT=up ;;
                    42) READ_KEY_RESULT=down ;;
                    43) READ_KEY_RESULT=right ;;
                    44) READ_KEY_RESULT=left ;;
                esac
            fi
            ;;
        20) READ_KEY_RESULT=space ;;
        0a|0d|'') READ_KEY_RESULT=enter ;;
    esac
}

render_choice_tty() {
    RCT_LABEL=$1
    RCT_CHOICES=$2
    RCT_CURSOR=$3
    RCT_SELECTED=$4

    ui_printf '%s\n' "$RCT_LABEL"
    RCT_INDEX=1
    for RCT_CHOICE in $RCT_CHOICES; do
        if [ "$RCT_INDEX" = "$RCT_SELECTED" ]; then
            RCT_MARKER='[x]'
        else
            RCT_MARKER='[ ]'
        fi
        if [ "$RCT_INDEX" = "$RCT_CURSOR" ]; then
            RCT_POINTER='>'
        else
            RCT_POINTER=' '
        fi
        ui_printf ' %s %s %s) %s\n' "$RCT_POINTER" "$RCT_MARKER" "$RCT_INDEX" "$RCT_CHOICE"
        RCT_INDEX=$((RCT_INDEX + 1))
    done
    ui_printf 'Use Up/Down to move, Space to check, Enter to continue.\n'
}

prompt_choice_tty() {
    PCT_LABEL=$1
    PCT_DEFAULT=$2
    PCT_CHOICES=$3
    PCT_INDEX=1
    PCT_SELECTED=1

    for PCT_CHOICE in $PCT_CHOICES; do
        if [ "$PCT_CHOICE" = "$PCT_DEFAULT" ]; then
            PCT_SELECTED=$PCT_INDEX
        fi
        PCT_INDEX=$((PCT_INDEX + 1))
    done
    PCT_COUNT=$((PCT_INDEX - 1))
    PCT_CURSOR=$PCT_SELECTED
    PCT_LINES=$((PCT_COUNT + 2))

    TTY_STTY=$(stty -g < /dev/tty 2>/dev/null || true)
    if [ -n "$TTY_STTY" ]; then
        stty -echo -icanon min 1 time 0 < /dev/tty 2>/dev/null || true
    fi

    ui_printf '\n'
    render_choice_tty "$PCT_LABEL" "$PCT_CHOICES" "$PCT_CURSOR" "$PCT_SELECTED"
    while :; do
        read_choice_key
        case "$READ_KEY_RESULT" in
            up)
                if [ "$PCT_CURSOR" -le 1 ]; then
                    PCT_CURSOR=$PCT_COUNT
                else
                    PCT_CURSOR=$((PCT_CURSOR - 1))
                fi
                ;;
            down)
                if [ "$PCT_CURSOR" -ge "$PCT_COUNT" ]; then
                    PCT_CURSOR=1
                else
                    PCT_CURSOR=$((PCT_CURSOR + 1))
                fi
                ;;
            space|right)
                PCT_SELECTED=$PCT_CURSOR
                ;;
            enter)
                if [ -n "$TTY_STTY" ]; then
                    stty "$TTY_STTY" < /dev/tty 2>/dev/null || true
                    TTY_STTY=
                fi
                choice_value_at "$PCT_SELECTED" "$PCT_CHOICES"
                return
                ;;
            *) continue ;;
        esac

        ui_printf '\033[%sA\033[J' "$PCT_LINES"
        render_choice_tty "$PCT_LABEL" "$PCT_CHOICES" "$PCT_CURSOR" "$PCT_SELECTED"
    done
}

prompt_env_value() {
    PEV_KEY=$1
    PEV_LABEL=$2
    PEV_DEFAULT_VALUE=$3
    PEV_FILE=$4
    PEV_PROMPT_DEFAULT=$(current_or_default "$PEV_KEY" "$PEV_DEFAULT_VALUE" "$PEV_FILE")
    prompt_line "$PEV_LABEL" "$PEV_PROMPT_DEFAULT"
    set_env_value "$PEV_KEY" "$PROMPT_RESULT" "$PEV_FILE"
}

prompt_secret_env_value() {
    PSE_KEY=$1
    PSE_LABEL=$2
    PSE_FILE=$3
    PSE_CURRENT_VALUE=$(env_value "$PSE_KEY" "$PSE_FILE")

    if is_empty_or_placeholder "$PSE_CURRENT_VALUE"; then
        ui_printf '%s: ' "$PSE_LABEL"
    else
        ui_printf '%s (press Enter to keep existing): ' "$PSE_LABEL"
    fi

    if tty_ready; then
        PSE_OLD_STTY=$(stty -g < /dev/tty 2>/dev/null || true)
    else
        PSE_OLD_STTY=$(stty -g 2>/dev/null || true)
    fi
    if [ -n "$PSE_OLD_STTY" ]; then
        if tty_ready; then
            stty -echo < /dev/tty 2>/dev/null || true
        else
            stty -echo 2>/dev/null || true
        fi
    fi
    ui_read_line
    PSE_SECRET_ANSWER=$UI_READ_RESULT
    if [ -n "$PSE_OLD_STTY" ]; then
        if tty_ready; then
            stty "$PSE_OLD_STTY" < /dev/tty 2>/dev/null || true
        else
            stty "$PSE_OLD_STTY" 2>/dev/null || true
        fi
        ui_printf '\n'
    elif ! tty_ready && [ ! -t 0 ]; then
        ui_printf '\n'
    fi

    if [ -n "$PSE_SECRET_ANSWER" ]; then
        set_env_value "$PSE_KEY" "$PSE_SECRET_ANSWER" "$PSE_FILE"
    fi
}

secret_state() {
    value=$(env_value "$1" "$2")
    if is_empty_or_placeholder "$value"; then
        printf 'empty'
    else
        printf 'set'
    fi
}

show_onboarding_summary() {
    file=$1
    auth_mode=$(env_value APP_AUTH_MODE "$file")
    llm_provider=$(env_value AWR_LLM_PROVIDER "$file")
    embedding_provider=$(env_value AWR_EMBEDDING_PROVIDER "$file")

    ui_printf '\nReview settings\n'
    ui_printf '  Deployment: %s\n' "$auth_mode"
    if [ "$auth_mode" = external ]; then
        ui_printf '  Google client ID: %s\n' "$(secret_state GOOGLE_CLIENT_ID "$file")"
        ui_printf '  Allowed Google domains: %s\n' "$(current_or_default APP_ALLOWED_GOOGLE_DOMAINS '-' "$file")"
    else
        ui_printf '  Internal email domain: %s\n' "$(current_or_default APP_INTERNAL_AUTH_EMAIL_DOMAIN '-' "$file")"
    fi
    ui_printf '  Admin emails: %s\n' "$(current_or_default APP_ADMIN_EMAILS '-' "$file")"
    ui_printf '  LLM provider: %s\n' "$llm_provider"
    case "$llm_provider" in
        openai)
            ui_printf '  OpenAI API key: %s\n' "$(secret_state OPENAI_API_KEY "$file")"
            ui_printf '  OpenAI chat model: %s\n' "$(current_or_default OPENAI_CHAT_MODEL '-' "$file")"
            ;;
        gemini)
            ui_printf '  Gemini API key: %s\n' "$(secret_state GEMINI_API_KEY "$file")"
            ui_printf '  Gemini chat model: %s\n' "$(current_or_default GEMINI_CHAT_MODEL '-' "$file")"
            ;;
        internal)
            ui_printf '  Internal LLM base URL: %s\n' "$(current_or_default INTERNAL_LLM_BASE_URL '-' "$file")"
            ui_printf '  Internal LLM API key: %s\n' "$(secret_state INTERNAL_LLM_API_KEY "$file")"
            ui_printf '  Internal LLM chat model: %s\n' "$(current_or_default INTERNAL_LLM_CHAT_MODEL '-' "$file")"
            ;;
        ollama)
            ui_printf '  Ollama base URL: %s\n' "$(current_or_default OLLAMA_BASE_URL '-' "$file")"
            ui_printf '  Ollama chat model: %s\n' "$(current_or_default OLLAMA_CHAT_MODEL '-' "$file")"
            ;;
        anthropic)
            ui_printf '  Anthropic API key: %s\n' "$(secret_state ANTHROPIC_API_KEY "$file")"
            ui_printf '  Anthropic chat model: %s\n' "$(current_or_default ANTHROPIC_CHAT_MODEL '-' "$file")"
            ;;
        xai)
            ui_printf '  xAI API key: %s\n' "$(secret_state XAI_API_KEY "$file")"
            ui_printf '  xAI chat model: %s\n' "$(current_or_default XAI_CHAT_MODEL '-' "$file")"
            ;;
    esac

    ui_printf '  Embedding provider: %s\n' "$embedding_provider"
    case "$embedding_provider" in
        openai)
            ui_printf '  OpenAI embedding model: %s\n' "$(current_or_default OPENAI_EMBEDDING_MODEL '-' "$file")"
            ui_printf '  OpenAI embedding dimension: %s\n' "$(current_or_default OPENAI_EMBEDDING_DIMENSION '-' "$file")"
            ;;
        gemini)
            ui_printf '  Gemini embedding model: %s\n' "$(current_or_default GEMINI_EMBEDDING_MODEL '-' "$file")"
            ;;
        internal)
            ui_printf '  Internal embedding base URL: %s\n' "$(current_or_default INTERNAL_EMBEDDING_BASE_URL '-' "$file")"
            ui_printf '  Internal embedding model: %s\n' "$(current_or_default INTERNAL_EMBEDDING_MODEL '-' "$file")"
            ;;
        ollama)
            ui_printf '  Ollama embedding model: %s\n' "$(current_or_default OLLAMA_EMBEDDING_MODEL '-' "$file")"
            ;;
    esac
}

confirm_onboarding() {
    show_onboarding_summary "$1"
    prompt_line "Continue and validate these settings? (y/n)" "y"
    answer=$(lower "$PROMPT_RESULT")
    case "$answer" in
        y|yes) ;;
        *)
            echo "Onboarding cancelled."
            exit 1
            ;;
    esac
}

configure_auth_onboarding() {
    file=$1

    ui_printf '\nDeployment type\n'
    ui_printf '  internal: internal network or company SSO/header login mode\n'
    ui_printf '  external: public/external deployment with Google OAuth\n'

    auth_default=$(current_or_default APP_AUTH_MODE external "$file")
    auth_default=$(lower "$auth_default")
    case "$auth_default" in
        internal|external) ;;
        *) auth_default=external ;;
    esac

    prompt_choice "Deployment type (internal/external)" "$auth_default" "internal external"
    deployment_type=$CHOICE_RESULT
    set_env_value APP_AUTH_ENABLED true "$file"
    set_env_value APP_AUTH_MODE "$deployment_type" "$file"
    set_env_value APP_LOCAL_LOGIN_ENABLED false "$file"

    if [ "$deployment_type" = external ]; then
        prompt_env_value GOOGLE_CLIENT_ID "Google OAuth client ID" "" "$file"
        prompt_env_value APP_ALLOWED_GOOGLE_DOMAINS "Allowed Google domains, comma-separated (optional)" "" "$file"
    else
        prompt_env_value APP_INTERNAL_AUTH_EMAIL_DOMAIN "Internal email domain" "internal.local" "$file"
    fi

    prompt_env_value APP_ADMIN_EMAILS "Admin emails, comma-separated (optional)" "" "$file"
}

configure_llm_provider() {
    file=$1

    ui_printf '\nLLM provider\n'
    llm_default=$(current_or_default AWR_LLM_PROVIDER local "$file")
    llm_default=$(lower "$llm_default")
    case "$llm_default" in
        local|openai|gemini|internal|ollama|anthropic|xai) ;;
        *) llm_default=local ;;
    esac

    prompt_choice "LLM provider (local/openai/gemini/internal/ollama/anthropic/xai)" "$llm_default" "local openai gemini internal ollama anthropic xai"
    llm_provider=$CHOICE_RESULT
    set_env_value AWR_LLM_PROVIDER "$llm_provider" "$file"

    case "$llm_provider" in
        openai)
            prompt_secret_env_value OPENAI_API_KEY "OpenAI API key" "$file"
            prompt_env_value OPENAI_CHAT_MODEL "OpenAI chat model" "gpt-4.1-mini" "$file"
            ;;
        gemini)
            prompt_secret_env_value GEMINI_API_KEY "Gemini API key" "$file"
            prompt_env_value GEMINI_CHAT_MODEL "Gemini chat model" "gemini-3.1-flash-lite" "$file"
            ;;
        internal)
            prompt_env_value INTERNAL_LLM_BASE_URL "Internal LLM base URL" "" "$file"
            prompt_secret_env_value INTERNAL_LLM_API_KEY "Internal LLM API key, if required" "$file"
            prompt_env_value INTERNAL_LLM_CHAT_MODEL "Internal LLM chat model" "gemma4-31b" "$file"
            ;;
        ollama)
            prompt_env_value OLLAMA_BASE_URL "Ollama base URL" "http://host.docker.internal:11434" "$file"
            prompt_env_value OLLAMA_CHAT_MODEL "Ollama chat model" "llama3.1" "$file"
            ;;
        anthropic)
            prompt_secret_env_value ANTHROPIC_API_KEY "Anthropic API key" "$file"
            prompt_env_value ANTHROPIC_CHAT_MODEL "Anthropic chat model" "claude-3-5-sonnet-latest" "$file"
            ;;
        xai)
            prompt_secret_env_value XAI_API_KEY "xAI API key" "$file"
            prompt_env_value XAI_CHAT_MODEL "xAI chat model" "grok-2-latest" "$file"
            ;;
        local)
            ;;
    esac
}

configure_embedding_provider() {
    file=$1

    ui_printf '\nEmbedding provider\n'
    embedding_default=$(current_or_default AWR_EMBEDDING_PROVIDER none "$file")
    embedding_default=$(lower "$embedding_default")
    case "$embedding_default" in
        none|openai|gemini|internal|ollama) ;;
        *) embedding_default=none ;;
    esac

    prompt_choice "Embedding provider (none/openai/gemini/internal/ollama)" "$embedding_default" "none openai gemini internal ollama"
    embedding_provider=$CHOICE_RESULT
    set_env_value AWR_EMBEDDING_PROVIDER "$embedding_provider" "$file"

    case "$embedding_provider" in
        openai)
            prompt_secret_env_value OPENAI_API_KEY "OpenAI API key" "$file"
            prompt_env_value OPENAI_EMBEDDING_MODEL "OpenAI embedding model" "text-embedding-3-small" "$file"
            prompt_env_value OPENAI_EMBEDDING_DIMENSION "OpenAI embedding dimension" "1536" "$file"
            ;;
        gemini)
            prompt_secret_env_value GEMINI_API_KEY "Gemini API key" "$file"
            prompt_env_value GEMINI_EMBEDDING_MODEL "Gemini embedding model" "gemini-embedding-001" "$file"
            ;;
        internal)
            prompt_env_value INTERNAL_EMBEDDING_BASE_URL "Internal embedding base URL" "" "$file"
            prompt_env_value INTERNAL_EMBEDDING_MODEL "Internal embedding model" "genai-bge-m3" "$file"
            ;;
        ollama)
            prompt_env_value OLLAMA_BASE_URL "Ollama base URL" "http://host.docker.internal:11434" "$file"
            prompt_env_value OLLAMA_EMBEDDING_MODEL "Ollama embedding model" "embeddinggemma" "$file"
            ;;
        none)
            ;;
    esac
}

run_onboarding() {
    file=$1

    ui_printf '\nSQLAdvisor environment onboarding\n'
    ui_printf 'Press Enter to keep the value shown in brackets.\n'

    configure_auth_onboarding "$file"
    configure_llm_provider "$file"
    configure_embedding_provider "$file"
    confirm_onboarding "$file"
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

if [ "$INTERACTIVE" -eq 1 ]; then
    run_onboarding "$WORK_FILE"
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
