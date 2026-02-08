#!/usr/bin/env python3
"""
SDN BLE Control Plane Bridge
═════════════════════════════

Puente entre el GATT Server SDN del teléfono y el controlador.
Corre en la laptop (mismo equipo que tiene ADB conectado).

Arquitectura:
─────────────
  Teléfono (GATT Server SDN)  ←──BLE──→  Laptop (este script)  ──→  ADB
       ↑                                       ↓
   App recibe comandos              Ejecuta adb shell svc ...
   App envía radio-requests         Bridge local MQTT (opcional)

UUIDs del servicio SDN:
  Service:       a1b2c3d4-e5f6-7890-abcd-ef1234567890
  CMD_WRITE:     a1b2c3d4-0001-7890-abcd-ef1234567890  (Write)
  RESPONSE:      a1b2c3d4-0002-7890-abcd-ef1234567890  (Notify)
  RADIO_REQUEST: a1b2c3d4-0003-7890-abcd-ef1234567890  (Notify)

Dependencias:
  pip install bleak

Uso:
  python3 sdn_ble_bridge.py                  # Busca y conecta al teléfono
  python3 sdn_ble_bridge.py --scan           # Solo escanea dispositivos BLE
  python3 sdn_ble_bridge.py --mac XX:XX:...  # Conecta a MAC específica
  python3 sdn_ble_bridge.py --send '{"action":"PREPARE_BT"}'  # Envía comando
"""

import asyncio
import json
import logging
import os
import signal
import subprocess
import sys
import time
from typing import Optional

try:
    from bleak import BleakClient, BleakScanner
    from bleak.backends.characteristic import BleakGATTCharacteristic
except ImportError:
    print("ERROR: Instala bleak primero:")
    print("  pip install bleak")
    sys.exit(1)

# ─── Configuración ───────────────────────────────────────────

SDN_SERVICE_UUID = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
SDN_CMD_WRITE_UUID = "a1b2c3d4-0001-7890-abcd-ef1234567890"
SDN_RESPONSE_UUID = "a1b2c3d4-0002-7890-abcd-ef1234567890"
SDN_RADIO_REQ_UUID = "a1b2c3d4-0003-7890-abcd-ef1234567890"

# ADB
ADB_CMD = "adb"

# Logging
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    datefmt="%H:%M:%S",
)
log = logging.getLogger("ble-bridge")

# Estado global
running = True


def signal_handler(sig, frame):
    global running
    log.info("Señal recibida, cerrando...")
    running = False


signal.signal(signal.SIGINT, signal_handler)
signal.signal(signal.SIGTERM, signal_handler)


# ─── ADB Helpers ─────────────────────────────────────────────

def adb_exec(command: str) -> tuple[bool, str]:
    """Ejecuta un comando ADB y retorna (success, output)."""
    full_cmd = f"{ADB_CMD} shell {command}"
    log.info(f"ADB exec: {full_cmd}")
    try:
        result = subprocess.run(
            full_cmd.split(),
            capture_output=True,
            text=True,
            timeout=10,
        )
        output = result.stdout.strip()
        success = result.returncode == 0
        if output:
            log.info(f"ADB output: {output}")
        return success, output
    except subprocess.TimeoutExpired:
        log.error("ADB timeout")
        return False, "timeout"
    except Exception as e:
        log.error(f"ADB error: {e}")
        return False, str(e)


def handle_radio_request(action: str, reason: str) -> dict:
    """
    Procesa un radio-request del teléfono y ejecuta ADB.
    Retorna el comando de confirmación para enviar al teléfono.
    """
    log.info(f"Radio request: action={action}, reason={reason}")

    if action == "enable_bt":
        success, _ = adb_exec("svc bluetooth enable")
        if success:
            # Esperar a que el radio se encienda
            time.sleep(2)
            return {"action": "BT_READY", "sessionId": "ble-bridge", "reason": "ADB enable_bt OK"}
        else:
            return {"action": "BT_READY", "sessionId": "ble-bridge", "reason": "ADB enable_bt FAILED"}

    elif action == "disable_bt":
        success, _ = adb_exec("svc bluetooth disable")
        return {"action": "BT_DISABLED", "sessionId": "ble-bridge",
                "reason": f"ADB disable_bt {'OK' if success else 'FAILED'}"}

    elif action == "enable_wifi":
        success, _ = adb_exec("svc wifi enable")
        if success:
            time.sleep(3)  # WiFi tarda más en estabilizarse
            return {"action": "WIFI_READY", "sessionId": "ble-bridge", "reason": "ADB enable_wifi OK"}
        else:
            return {"action": "WIFI_READY", "sessionId": "ble-bridge", "reason": "ADB enable_wifi FAILED"}

    elif action == "disable_wifi":
        success, _ = adb_exec("svc wifi disable")
        return {"action": "WIFI_DISABLED", "sessionId": "ble-bridge",
                "reason": f"ADB disable_wifi {'OK' if success else 'FAILED'}"}

    else:
        log.warning(f"Radio request desconocido: {action}")
        return {"action": "UNKNOWN", "sessionId": "ble-bridge", "reason": f"Unknown action: {action}"}


# ─── BLE GATT Client ────────────────────────────────────────

class SdnBleController:
    """
    Cliente BLE GATT que se conecta al GATT Server SDN del teléfono.

    Funciones:
    - Recibe radio-requests del teléfono → ejecuta ADB
    - Envía comandos SDN al teléfono (PREPARE_BT, RELEASE, etc.)
    - Recibe responses/telemetría del teléfono
    """

    def __init__(self):
        self.client: Optional[BleakClient] = None
        self.connected = False
        self.pending_commands: list[dict] = []

    async def scan_for_sdn_devices(self, timeout: float = 10.0) -> list:
        """Escanea dispositivos BLE que anuncian el servicio SDN."""
        log.info(f"Escaneando dispositivos SDN (timeout={timeout}s)...")

        devices = []
        discovered = await BleakScanner.discover(timeout=timeout)

        for d in discovered:
            uuids = d.metadata.get("uuids", [])
            # Normalizar UUIDs a minúsculas
            uuids_lower = [u.lower() for u in uuids]

            if SDN_SERVICE_UUID.lower() in uuids_lower:
                devices.append(d)
                log.info(f"  ✓ SDN Device: {d.name or 'Unknown'} ({d.address}) RSSI={d.rssi}")
            else:
                # También buscar por nombre parcial como fallback
                name = (d.name or "").lower()
                if "sdn" in name or "xiaomi" in name:
                    log.info(f"  ? Posible: {d.name or 'Unknown'} ({d.address}) RSSI={d.rssi}")

        if not devices:
            log.warning("No se encontraron dispositivos SDN")

        return devices

    def _on_response_notify(self, sender: BleakGATTCharacteristic, data: bytearray):
        """Callback cuando el teléfono envía una response/telemetría."""
        try:
            json_str = data.decode("utf-8")
            log.info(f"📱 Response: {json_str[:200]}")
            # Aquí se podría publicar a MQTT local o procesar
        except Exception as e:
            log.error(f"Error parseando response: {e}")

    def _on_radio_request_notify(self, sender: BleakGATTCharacteristic, data: bytearray):
        """
        Callback cuando el teléfono solicita un toggle de radio.
        Ejecuta ADB y envía confirmación de vuelta.
        """
        try:
            json_str = data.decode("utf-8")
            log.info(f"📱 Radio Request: {json_str}")

            request = json.loads(json_str)
            action = request.get("action", "unknown")
            reason = request.get("reason", "")

            # Ejecutar ADB
            confirmation = handle_radio_request(action, reason)

            # Enviar confirmación al teléfono
            self.pending_commands.append(confirmation)

        except Exception as e:
            log.error(f"Error procesando radio request: {e}")

    async def connect(self, address: str, timeout: float = 15.0):
        """Conecta al GATT Server SDN del teléfono."""
        log.info(f"Conectando a {address}...")

        self.client = BleakClient(address, timeout=timeout)
        await self.client.connect()

        if not self.client.is_connected:
            raise ConnectionError(f"No se pudo conectar a {address}")

        self.connected = True
        log.info(f"✓ Conectado a {address}")

        # Listar servicios
        for service in self.client.services:
            log.info(f"  Service: {service.uuid}")
            for char in service.characteristics:
                props = ",".join(char.properties)
                log.info(f"    Char: {char.uuid} [{props}]")

        # Suscribirse a notificaciones
        try:
            await self.client.start_notify(SDN_RESPONSE_UUID, self._on_response_notify)
            log.info("✓ Suscrito a RESPONSE notifications")
        except Exception as e:
            log.warning(f"No se pudo suscribir a RESPONSE: {e}")

        try:
            await self.client.start_notify(SDN_RADIO_REQ_UUID, self._on_radio_request_notify)
            log.info("✓ Suscrito a RADIO_REQUEST notifications")
        except Exception as e:
            log.warning(f"No se pudo suscribir a RADIO_REQUEST: {e}")

    async def send_command(self, command: dict):
        """Envía un comando SDN al teléfono vía GATT write."""
        if not self.client or not self.client.is_connected:
            log.error("No conectado — no se puede enviar comando")
            return

        json_bytes = json.dumps(command).encode("utf-8")

        try:
            await self.client.write_gatt_char(
                SDN_CMD_WRITE_UUID,
                json_bytes,
                response=True  # With response para confirmar
            )
            log.info(f"→ Comando enviado: {command.get('action', 'unknown')}")
        except Exception as e:
            log.error(f"Error enviando comando: {e}")

    async def run_loop(self):
        """Loop principal: mantiene la conexión y procesa pending commands."""
        log.info("═══ BLE Bridge activo — esperando radio-requests del teléfono ═══")
        log.info("  Ctrl+C para salir")

        while running and self.client and self.client.is_connected:
            # Enviar pending commands (confirmaciones de radio-requests)
            while self.pending_commands:
                cmd = self.pending_commands.pop(0)
                await self.send_command(cmd)

            await asyncio.sleep(0.5)

        log.info("Loop terminado")

    async def disconnect(self):
        """Desconecta del GATT Server."""
        if self.client and self.client.is_connected:
            try:
                await self.client.disconnect()
            except Exception:
                pass
            log.info("Desconectado del GATT Server")
        self.connected = False


# ─── Main ────────────────────────────────────────────────────

async def main():
    import argparse

    parser = argparse.ArgumentParser(
        description="SDN BLE Control Plane Bridge",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Ejemplos:
  %(prog)s                              Busca y conecta automáticamente
  %(prog)s --scan                       Solo escanea dispositivos BLE
  %(prog)s --mac AA:BB:CC:DD:EE:FF      Conecta a MAC específica
  %(prog)s --send '{"action":"PREPARE_BT","sessionId":"test"}'
        """,
    )
    parser.add_argument("--scan", action="store_true", help="Solo escanear dispositivos BLE")
    parser.add_argument("--mac", type=str, help="MAC del teléfono (evita scan)")
    parser.add_argument("--send", type=str, help="Envía un comando JSON y sale")
    parser.add_argument("--timeout", type=float, default=10.0, help="Timeout de scan (default: 10s)")

    args = parser.parse_args()

    controller = SdnBleController()

    # ── Modo SCAN ──
    if args.scan:
        devices = await controller.scan_for_sdn_devices(timeout=args.timeout)
        if devices:
            print(f"\n{len(devices)} dispositivo(s) SDN encontrado(s):")
            for d in devices:
                print(f"  {d.name or 'Unknown':20s} {d.address}  RSSI={d.rssi}")
        else:
            print("\nNo se encontraron dispositivos SDN.")
            print("Asegúrate de que:")
            print("  1. BT está encendido en el teléfono")
            print("  2. La app SDN está corriendo con GATT Server activo")
            print("  3. 'ble start' ejecutado en la consola de la app")
        return

    # ── Buscar o usar MAC ──
    target_address = args.mac

    if not target_address:
        log.info("Buscando teléfono SDN...")
        devices = await controller.scan_for_sdn_devices(timeout=args.timeout)
        if not devices:
            log.error("No se encontró el teléfono SDN. ¿Está advertiseando?")
            log.info("Tip: Ejecuta 'ble start' en la consola de la app")
            return
        target_address = devices[0].address
        log.info(f"Usando primer dispositivo: {devices[0].name} ({target_address})")

    # ── Conectar ──
    try:
        await controller.connect(target_address)
    except Exception as e:
        log.error(f"Error conectando: {e}")
        return

    # ── Modo SEND ──
    if args.send:
        try:
            command = json.loads(args.send)
            await controller.send_command(command)
            log.info("Comando enviado, esperando 3s para response...")
            await asyncio.sleep(3)
        except json.JSONDecodeError as e:
            log.error(f"JSON inválido: {e}")
        finally:
            await controller.disconnect()
        return

    # ── Modo BRIDGE (principal) ──
    try:
        await controller.run_loop()
    except KeyboardInterrupt:
        log.info("Interrumpido por usuario")
    except Exception as e:
        log.error(f"Error en loop: {e}")
    finally:
        await controller.disconnect()
        log.info("Bridge cerrado")


if __name__ == "__main__":
    asyncio.run(main())
