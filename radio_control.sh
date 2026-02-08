#!/bin/bash
# ═══════════════════════════════════════════════════════════════
# radio_control.sh — Control de radios del teléfono desde la laptop
#
# Combina ADB (toggle hardware) + MQTT (notificar a la app).
# Este es el mecanismo GARANTIZADO — funciona siempre que
# el teléfono esté conectado por ADB (USB o WiFi).
#
# Uso:
#   ./radio_control.sh bt on          Encender Bluetooth
#   ./radio_control.sh bt off         Apagar Bluetooth
#   ./radio_control.sh wifi on        Encender WiFi
#   ./radio_control.sh wifi off       Apagar WiFi
#   ./radio_control.sh prepare <MAC>  ADB BT on + MQTT PREPARE_BT
#   ./radio_control.sh release <MAC>  ADB BT off + MQTT RELEASE_RADIO
#   ./radio_control.sh status         Estado actual de radios
#   ./radio_control.sh full-test <MAC> Ciclo completo: on→prepare→release→off
#
# Variables de entorno opcionales:
#   BROKER_IP   (default: localhost)
#   BROKER_PORT (default: 1883)
# ═══════════════════════════════════════════════════════════════

set -euo pipefail

BROKER_IP="${BROKER_IP:-localhost}"
BROKER_PORT="${BROKER_PORT:-1883}"

RADIO="${1:-}"
ACTION="${2:-}"
MAC="${3:-}"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

usage() {
    echo -e "${CYAN}═══ SDN Radio Control ═══${NC}"
    echo ""
    echo "Uso: $0 <comando> [args]"
    echo ""
    echo "  bt on              Encender Bluetooth (ADB)"
    echo "  bt off             Apagar Bluetooth (ADB)"
    echo "  wifi on            Encender WiFi (ADB)"
    echo "  wifi off           Apagar WiFi (ADB)"
    echo "  prepare <MAC>      BT on + enviar PREPARE_BT por MQTT"
    echo "  release <MAC>      BLE stop + BT off + enviar RELEASE_RADIO"
    echo "  status             Estado actual de radios"
    echo "  full-test <MAC>    Ciclo completo de prueba"
    echo ""
    echo "Env: BROKER_IP=$BROKER_IP  BROKER_PORT=$BROKER_PORT"
    exit 1
}

check_adb() {
    if ! command -v adb &>/dev/null; then
        echo -e "${RED}Error: adb no encontrado${NC}"
        exit 1
    fi
    local count
    count=$(adb devices | grep -c "device$" || true)
    if [[ "$count" -eq 0 ]]; then
        echo -e "${RED}Error: No hay dispositivos ADB conectados${NC}"
        exit 1
    fi
}

check_mqtt() {
    if ! command -v mosquitto_pub &>/dev/null; then
        echo -e "${YELLOW}Aviso: mosquitto_pub no encontrado — no se enviará MQTT${NC}"
        return 1
    fi
    return 0
}

adb_radio() {
    local svc_name="$1"
    local action="$2"
    echo -ne "  ADB: svc $svc_name $action ... "
    local output
    output=$(adb shell "svc $svc_name $action" 2>&1)
    if echo "$output" | grep -qi "success\|^$"; then
        echo -e "${GREEN}OK${NC}"
        return 0
    else
        echo -e "${RED}FAIL${NC} ($output)"
        return 1
    fi
}

mqtt_command() {
    local mac="$1"
    local action="$2"
    local reason="${3:-Control remoto desde laptop}"

    if ! check_mqtt; then return 1; fi

    local topic="dispositivo/${mac}/comando"
    local payload="{\"sessionId\":\"adb-$(date +%s)\",\"action\":\"${action}\",\"reason\":\"${reason}\"}"

    echo -ne "  MQTT: $topic → $action ... "
    if mosquitto_pub -h "$BROKER_IP" -p "$BROKER_PORT" -t "$topic" -m "$payload" 2>/dev/null; then
        echo -e "${GREEN}OK${NC}"
        return 0
    else
        echo -e "${RED}FAIL${NC}"
        return 1
    fi
}

show_status() {
    echo -e "${CYAN}═══ Estado de Radios ═══${NC}"
    local bt wifi
    bt=$(adb shell "settings get global bluetooth_on" 2>/dev/null | tr -d '\r')
    wifi=$(adb shell "settings get global wifi_on" 2>/dev/null | tr -d '\r')
    local ip
    ip=$(adb shell "ip addr show wlan0 2>/dev/null | grep 'inet ' | awk '{print \$2}'" 2>/dev/null | tr -d '\r')
    local model
    model=$(adb shell "getprop ro.product.model" 2>/dev/null | tr -d '\r')

    echo -e "  Dispositivo: ${CYAN}$model${NC}"
    echo -e "  Bluetooth:   $([ "$bt" = "1" ] && echo -e "${GREEN}ON ✓${NC}" || echo -e "${RED}OFF ✗${NC}")"
    echo -e "  WiFi:        $([ "$wifi" = "1" ] && echo -e "${GREEN}ON ✓${NC}" || echo -e "${RED}OFF ✗${NC}")"
    echo -e "  IP:          ${ip:-N/A}"
}

do_prepare() {
    local mac="$1"
    echo -e "${CYAN}═══ PREPARE: Activar BT ═══${NC}"
    echo -e "  MAC: $mac"
    echo ""

    # 1. Encender BT via ADB
    adb_radio bluetooth enable
    sleep 2

    # 2. Notificar a la app vía MQTT → activa BLE scan+advertising
    mqtt_command "$mac" "PREPARE_BT" "Controller: activar BLE para sesión"
    echo ""
    show_status
}

do_release() {
    local mac="$1"
    echo -e "${CYAN}═══ RELEASE: Liberar radios ═══${NC}"
    echo -e "  MAC: $mac"
    echo ""

    # 1. Notificar a la app vía MQTT → detiene BLE ops
    mqtt_command "$mac" "RELEASE_RADIO" "Controller: liberar radios"
    sleep 1

    # 2. Apagar BT via ADB
    adb_radio bluetooth disable
    echo ""
    show_status
}

do_full_test() {
    local mac="$1"
    echo -e "${CYAN}═══════════════════════════════════════════${NC}"
    echo -e "${CYAN}  PRUEBA COMPLETA — Ciclo SDN Radio       ${NC}"
    echo -e "${CYAN}═══════════════════════════════════════════${NC}"
    echo ""

    echo -e "${YELLOW}[1/5] Estado inicial${NC}"
    show_status
    echo ""

    echo -e "${YELLOW}[2/5] Encendiendo BT (ADB)${NC}"
    adb_radio bluetooth enable
    sleep 2
    echo ""

    echo -e "${YELLOW}[3/5] Enviando PREPARE_BT (MQTT)${NC}"
    mqtt_command "$mac" "PREPARE_BT" "Test: activar BLE scan+advertising"
    echo "  ⏳ Esperando 5s para que la app active BLE..."
    sleep 5
    show_status
    echo ""

    echo -e "${YELLOW}[4/5] Enviando RELEASE_RADIO (MQTT)${NC}"
    mqtt_command "$mac" "RELEASE_RADIO" "Test: liberar radios"
    echo "  ⏳ Esperando 3s para que la app detenga BLE..."
    sleep 3
    echo ""

    echo -e "${YELLOW}[5/5] Apagando BT (ADB)${NC}"
    adb_radio bluetooth disable
    sleep 2
    echo ""

    echo -e "${CYAN}═══ Resultado Final ═══${NC}"
    show_status
    echo ""
    echo -e "${GREEN}✓ Ciclo completo terminado${NC}"
}

# ─── Main ────────────────────────────────────────────────────

check_adb

case "$RADIO" in
    bt|bluetooth)
        if [[ "$ACTION" != "on" && "$ACTION" != "off" ]]; then usage; fi
        local_action=$([ "$ACTION" = "on" ] && echo "enable" || echo "disable")
        adb_radio bluetooth "$local_action"
        ;;
    wifi)
        if [[ "$ACTION" != "on" && "$ACTION" != "off" ]]; then usage; fi
        local_action=$([ "$ACTION" = "on" ] && echo "enable" || echo "disable")
        adb_radio wifi "$local_action"
        ;;
    prepare)
        if [[ -z "$ACTION" ]]; then echo "Falta MAC. Uso: $0 prepare <MAC>"; exit 1; fi
        do_prepare "$ACTION"
        ;;
    release)
        if [[ -z "$ACTION" ]]; then echo "Falta MAC. Uso: $0 release <MAC>"; exit 1; fi
        do_release "$ACTION"
        ;;
    status)
        show_status
        ;;
    full-test)
        if [[ -z "$ACTION" ]]; then echo "Falta MAC. Uso: $0 full-test <MAC>"; exit 1; fi
        do_full_test "$ACTION"
        ;;
    *)
        usage
        ;;
esac
