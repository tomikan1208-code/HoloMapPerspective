"""Minecraft status handshake and ping, using only the Python standard library."""
import argparse
import json
import socket
import struct
import time


def varint(value):
    value &= 0xFFFFFFFF
    result = bytearray()
    while True:
        part = value & 0x7F
        value >>= 7
        result.append(part | (0x80 if value else 0))
        if not value:
            return bytes(result)


def exact(stream, count):
    result = bytearray()
    while len(result) < count:
        part = stream.recv(count - len(result))
        if not part:
            raise ConnectionError("Server closed the connection")
        result.extend(part)
    return bytes(result)


def read_varint(stream):
    value = 0
    for position in range(5):
        part = exact(stream, 1)[0]
        value |= (part & 0x7F) << (position * 7)
        if not part & 0x80:
            return value
    raise ValueError("Invalid VarInt")


def send_packet(stream, payload):
    stream.sendall(varint(len(payload)) + payload)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=25565)
    arguments = parser.parse_args()
    with socket.create_connection((arguments.host, arguments.port), timeout=5) as stream:
        host = arguments.host.encode("utf-8")
        handshake = b"\x00" + varint(-1) + varint(len(host)) + host + struct.pack(">H", arguments.port) + b"\x01"
        send_packet(stream, handshake)
        send_packet(stream, b"\x00")
        packet_length = read_varint(stream)
        if not 0 < packet_length < 1_000_000 or read_varint(stream) != 0:
            raise ValueError("Invalid status packet")
        json_length = read_varint(stream)
        status = json.loads(exact(stream, json_length))
        started = time.monotonic()
        nonce = struct.pack(">q", time.time_ns() // 1_000_000)
        send_packet(stream, b"\x01" + nonce)
        if read_varint(stream) != 9 or exact(stream, 9) != b"\x01" + nonce:
            raise ValueError("Invalid ping response")
        if "26.3" not in status["version"]["name"]:
            raise AssertionError(f"Unexpected server: {status['version']}")
        print(json.dumps({"result": "PASS", "version": status["version"],
                          "players": status["players"], "ping_ms": round((time.monotonic()-started)*1000, 2)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
