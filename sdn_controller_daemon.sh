#!/bin/bash
# ═══════════════════════════════════════════════════════════════
# SDN Controller Daemon — Plano de Control de Radios
# ═══════════════════════════════════════════════════════════════
#
# Este daemon es el componente del controlador SDN que gestiona
# el hardware de radio del dispositivo móvil vía ADB.
#
# Arquitectura:
#   App publica → MQTT topic dispositivo/{MAC}/radio-request
#   Este daemon escucha → ejecuta adb shell svc bluetooth/wifi
#   Este daemon confirma → MQTT topic dispositivo/{MAC}/comando
#   App recibe confirmación → procede con operaciones BLE/WiFi
#
# Uso:
#   ./sdn_controller_daemon.sh              # broker en localhost
#   ./sdn_controller_daemon.sh 192.168.1.100  # broker en IP específica
#
# Requisitos:
#   - mosquitto-clients (mosquitto_sub, mosquitto_pub)
#   - adb conectado al dispositivo
#   - python3 (para parsear JSON)
#
# Tópicos MQTT:
#   Escucha:  dispositivo/+/radio-request
#   Publica:  dispositivo/{MAC}/comando
#
# Acciones soportadas:
#   enable_bt    → adb shell svc bluetooth enable  → BT_READY
#   disable_bt   → adb shell svc bluetooth disable → BT_DISABLED
#   enable_wifi  → adb shell svc wifi enable       → WIFI_READY
#   disable_wifi → adb shell svc wifi disable      → WIFI_DISABLED
# ═══════════════════════════════════════════════════════════════

set -euo pipefail

BROKER="${1:-localhost}"
TOPIC="dispositivo/+/radio-request"
LOG_FILE="sdn_controller_daemon.log"

# Colores
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

timestamp() {
    date '+%H:%M:%S'
}

log() {
    local msg="[$(timestamp)] $1"
    echo -e "$msg"
    echo "$msg" >> "$LOG_FILE"
}

# ─── Verificaciones ──────────────────────────────────────────

check_dependencies() {
    local missing=()
    for cmd in mosquitto_sub mosquitto_pub adb python3; do
        if ! command -v "$cmd" &>/dev/null; then
            missing+=("$cmd")
        fi
    done

    if [ ${#missing[@]} -ne 0 ]; then
        echo -e "${RED}✗ Faltan dependencias: ${missing[*]}${NC}"
        echo ""
        echo "Instalar con:"
        echo "  sudo apt install mosquitto-clients android-tools-adb python3"
        exit 1
    fi
}

check_adb() {
    if ! adb devices 2>/dev/null | grep -q "device$"; then
        echo -e "${YELLOW}⚠ No hay dispositivo ADB conectado${NC}"
        echo "  Conecta el teléfono por USB y ejecuta: adb devices"
        echo ""
        read -p "¿Continuar sin ADB? (s/n): " -n 1 -r
        echo ""
        if [[ ! $REPLY =~ ^[Ss]$ ]]; then
            exit 1
        fi
    else
        local device
        device=$(adb devices | grep "device$" | head -1 | cut -f1)
        echo -e "${GREEN}✓ Dispositivo ADB: $device${NC}"
    fi
}

check_broker() {
    if ! mosquitto_pub -h "$BROKER" -t "test/ping" -m "ping" -q 0 2>/dev/null; then
        echo -e "${RED}✗ No se puede conectar al broker MQTT en $BROKER:1883${NC}"
        exit 1
    fi
    echo -e "${GREEN}✓ Broker MQTT: $BROKER:1883${NC}"
}

# ─── Ejecución de ADB ───────────────────────────────────────

execute_radio_command() {
    local action="$1"
    local mac="$2"

    case "$action" in
        enable_bt)
            log "${CYAN}  → adb shell svc bluetooth enable${NC}"
            if adb shell svc bluetooth enable 2>/dev/null; then
                sleep 2
                # Verificar que efectivamente se encendió
                local bt_state
                bt_state=$(adb shell settings get global bluetooth_on 2>/dev/null || echo "?")
                if [ "$bt_state" = "1" ]; then
                    send_confirmation "$mac" "BT_READY" "BT encendido vía ADB (verificado)"
                    log "${GREEN}  ✓ BT encendido y verificado${NC}"
                else
                    send_confirmation "$mac" "BT_READY" "BT enable ejecutado (estado: $bt_state)"
                    log "${YELLOW}  ⚠ BT enable ejecutado pero estado=$bt_state${NC}"
                fi
            else
                log "${RED}  ✗ Error ejecutando adb shell svc bluetooth enable${NC}"
            fi
            ;;

        disable_bt)
            log "${CYAN}  → adb shell svc bluetooth disable${NC}"
            if adb shell svc bluetooth disable 2>/dev/null; then
                sleep 1
                send_confirmation "$mac" "BT_DISABLED" "BT deshabilitado vía ADB"
                log "${GREEN}  ✓ BT deshabilitado${NC}"
            else
                log "${RED}  ✗ Error ejecutando adb shell svc bluetooth disable${NC}"
            fi
            ;;

        enable_wifi)
            log "${CYAN}  → adb shell svc wifi enable${NC}"
            if adb shell svc wifi enable 2>/dev/null; then
                sleep 2
                send_confirmation "$mac" "WIFI_READY" "WiFi encendido vía ADB"
                log "${GREEN}  ✓ WiFi encendido${NC}"
            else
                log "${RED}  ✗ Error ejecutando adb shell svc wifi enable${NC}"
            fi
            ;;

        disable_wifi)
            log "${CYAN}  → adb shell svc wifi disable${NC}"
            if adb shell svc wifi disable 2>/dev/null; then
                sleep 1
                send_confirmation "$mac" "WIFI_DISABLED" "WiFi deshabilitado vía ADB"
                log "${GREEN}  ✓ WiFi deshabilitado${NC}"
            else
                log "${RED}  ✗ Error ejecutando adb shell svc wifi disable${NC}"
            fi
            ;;

        *)
            log "${YELLOW}  ⚠ Acción desconocida: $action${NC}"
            ;;
    esac
}

# ─── Confirmación MQTT ───────────────────────────────────────

send_confirmation() {
    local mac="$1"
    local command_action="$2"
    local reason="$3"
    local topic="dispositivo/$mac/comando"
    local payload="{\"sessionId\":\"radio-ctrl\",\"action\":\"$command_action\",\"reason\":\"$reason\"}"

    mosquitto_pub -h "$BROKER" -t "$topic" -m "$payload" -q 1 2>/dev/null
    log "${BLUE}  → MQTT confirmación: $command_action → $topic${NC}"
}

# ─── Parseo de JSON ──────────────────────────────────────────

parse_json_field() {
    local json="$1"
    local field="$2"
    echo "$json" | python3 -c "
import sys, json
try:
    data = json.load(sys.stdin)
    print(data.get('$field', ''))
except:
    print('')
" 2>/dev/null
}

# ─── Loop Principal ──────────────────────────────────────────

main() {
    echo ""
    echo -e "${CYAN}═══════════════════════════════════════════════════${NC}"
    echo -e "${CYAN}     SDN Controller Daemon — Radio Control (MQTT)  ${NC}"
    echo -e "${CYAN}     Fallback: usar sdn_ble_bridge.py (BLE GATT)  ${NC}"
    echo -e "${CYAN}═══════════════════════════════════════════════════${NC}"
    echo ""

    check_dependencies
    check_adb
    check_broker

    echo ""
    echo -e "Escuchando: ${YELLOW}$TOPIC${NC}"
    echo -e "Broker:     ${YELLOW}$BROKER${NC}"
    echo ""
    echo -e "${GREEN}Esperando radio-requests del agente móvil...${NC}"
    echo -e "${BLUE}(Los comandos 'bt on/off' desde la app llegarán aquí)${NC}"
    echo ""
    echo "─────────────────────────────────────────────────"

    # Suscribirse y procesar mensajes
    mosquitto_sub -h "$BROKER" -t "$TOPIC" -v 2>/dev/null | while IFS= read -r line; do
        # El formato es: "topic payload"
        # Separar topic del payload
        local topic_name payload mac action reason

        topic_name=$(echo "$line" | awk '{print $1}')
        payload=$(echo "$line" | cut -d' ' -f2-)

        # Extraer MAC del tópico: dispositivo/{MAC}/radio-request
        mac=$(echo "$topic_name" | cut -d'/' -f2)

        # Parsear JSON del payload
        action=$(parse_json_field "$payload" "action")
        reason=$(parse_json_field "$payload" "reason")

        echo ""
        log "${YELLOW}━━━ Radio Request ━━━${NC}"
        log "  MAC:    $mac"
        log "  Acción: $action"
        log "  Razón:  $reason"

        # Ejecutar comando ADB
        execute_radio_command "$action" "$mac"

        echo "─────────────────────────────────────────────────"
    done
}

# ─── Manejo de señales ───────────────────────────────────────

cleanup() {
    echo ""
    log "${YELLOW}Daemon detenido${NC}"
    exit 0
}

trap cleanup SIGINT SIGTERM

# ─── Modo de ejecución ──────────────────────────────────────

case "${1:-}" in
    --help|-h)
        echo "SDN Controller Daemon — Plano de Control de Radios"
        echo ""
        echo "Uso: $0 [broker_ip]"
        echo ""
        echo "Escucha requests MQTT del agente móvil y ejecuta ADB"
        echo "para encender/apagar radios BT/WiFi."
        echo ""
        echo "Opciones:"
        echo "  broker_ip    IP del broker MQTT (default: localhost)"
        echo "  --status     Mostrar estado actual de radios"
        echo "  --test MAC   Enviar test request para una MAC"
        echo ""
        echo "Ejemplos:"
        echo "  $0                    # Iniciar daemon (broker localhost)"
        echo "  $0 192.168.1.100     # Iniciar daemon (broker en IP)"
        echo "  $0 --status           # Ver estado de radios del teléfono"
        echo "  $0 --test A2:57:0E:EF:C6:E5  # Simular request"
        exit 0
        ;;

    --status)
        echo "═══ Estado de Radios (vía ADB) ═══"
        echo ""
        bt=$(adb shell settings get global bluetooth_on 2>/dev/null || echo "?")
        wifi=$(adb shell settings get global wifi_on 2>/dev/null || echo "?")
        echo "Bluetooth: $([ "$bt" = "1" ] && echo "ON ✓" || echo "OFF ✗") (bluetooth_on=$bt)"
        echo "WiFi:      $([ "$wifi" = "1" ] && echo "ON ✓" || echo "OFF ✗") (wifi_on=$wifi)"
        exit 0
        ;;

    --test)
        mac="${2:?Falta MAC. Uso: $0 --test A2:57:0E:EF:C6:E5}"
        broker="${3:-localhost}"
        echo "Enviando test radio-request para $mac..."
        mosquitto_pub -h "$broker" -t "dispositivo/$mac/radio-request" \
            -m "{\"action\":\"enable_bt\",\"reason\":\"Test desde daemon\",\"mac\":\"$mac\"}" -q 1
        echo "✓ Enviado. El daemon debería procesarlo."
        exit 0
        ;;
esac

main
