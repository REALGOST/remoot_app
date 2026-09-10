#!/usr/bin/env python3
"""
PC Companion Server for Android Wi-Fi Remote Streamer (Target API 34).
Requirements: pip install pyautogui opencv-python (optional for decoding)
"""

import socket
import struct
import threading
import time

DISCOVERY_PORT = 8888
STREAM_PORT = 9999
MAGIC = 0x5354524D # "STRM"
HEADER_SIZE = 18

TYPE_DISCOVERY_PING = 0x01
TYPE_DISCOVERY_PONG = 0x02
TYPE_VIDEO_FRAME    = 0x10
TYPE_AUDIO_FRAME    = 0x11
TYPE_TOUCH_EVENT    = 0x20
TYPE_HEARTBEAT      = 0x40

def udp_discovery_responder():
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind(('', DISCOVERY_PORT))
    print(f"[*] UDP Discovery Beacon listener active on port {DISCOVERY_PORT}")

    while True:
        try:
            data, addr = sock.recvfrom(1024)
            msg = data.decode('utf-8', errors='ignore')
            if "DISCOVER_PC_SERVER" in msg:
                print(f"[+] Discovered Android device at {addr[0]}: {msg}")
                hostname = socket.gethostname()
                response = f"PC_SERVER_OFFER:{hostname}:{STREAM_PORT}".encode('utf-8')
                sock.sendto(response, addr)
        except Exception as e:
            time.sleep(1)

def handle_touch_packet(payload):
    if len(payload) < 18:
        return
    action, pointer_id, norm_x, norm_y, pressure, button_state = struct.unpack(">BBfffi", payload)
    try:
        import pyautogui
        screen_w, screen_h = pyautogui.size()
        target_x = int(norm_x * screen_w)
        target_y = int(norm_y * screen_h)

        if action == 0: # DOWN
            pyautogui.mouseDown(target_x, target_y)
        elif action == 1: # MOVE
            pyautogui.moveTo(target_x, target_y)
        elif action == 2: # UP
            pyautogui.mouseUp(target_x, target_y)
    except ImportError:
        actions = {0: "DOWN", 1: "MOVE", 2: "UP", 3: "CANCEL"}
        print(f"[Touch] {actions.get(action, 'UNKNOWN')} (X: {norm_x:.3f}, Y: {norm_y:.3f}, Pressure: {pressure:.2f})")

def stream_server_loop():
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(('', STREAM_PORT))
    server.listen(1)
    print(f"[*] Stream Socket Server ready on port {STREAM_PORT} (waiting for Android)...")

    while True:
        client_sock, addr = server.accept()
        print(f"[+] Connected to Android at {addr[0]}:{addr[1]}")
        try:
            while True:
                header_bytes = client_sock.recv(HEADER_SIZE)
                if len(header_bytes) < HEADER_SIZE:
                    break
                magic, p_type, flags, seq, timestamp, length = struct.unpack(">IBBIIQ", header_bytes[:18])
                if magic != MAGIC:
                    break

                payload = b""
                while len(payload) < length:
                    chunk = client_sock.recv(length - len(payload))
                    if not chunk:
                        break
                    payload += chunk

                if p_type == TYPE_TOUCH_EVENT:
                    handle_touch_packet(payload)
                elif p_type == TYPE_HEARTBEAT:
                    client_sock.sendall(header_bytes + payload)
        except Exception as e:
            print(f"[-] Disconnected: {e}")
        finally:
            client_sock.close()

if __name__ == "__main__":
    threading.Thread(target=udp_discovery_responder, daemon=True).start()
    stream_server_loop()