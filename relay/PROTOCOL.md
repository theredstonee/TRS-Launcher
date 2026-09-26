# TRS Relay wire protocol (v1)

Binding contract for the game side (TRS Client mod) of **TRS World Hosting**. The TRS API
(`api/API.md`, section "World hosting", branch `website`) hands out the relay address and
short-lived **relay tokens**; this document describes what happens on the wire.

- **TCP** (default `25503`): reliable fallback path. The host keeps one *control* connection per
  room; every guest connection is paired with a fresh *host data* connection, after which the
  relay pipes raw bytes (the Minecraft protocol) between the two.
- **UDP** (default `25504`): a standard **STUN Binding** responder (RFC 5389, for ICE candidate
  discovery / NAT type) and an optional **datagram relay** between a host and its guests.

All integers are big-endian (network order). UUIDs on the wire are **16 raw bytes**
(the 32 hex digits of the undashed UUID). Strings (tokens, error codes, JSON) are ASCII/UTF-8.

---

## 1. Relay token

Issued by the API, verified by the relay with the shared secret `RELAY_SECRET`.

```
trsr1.<payload>.<signature>
payload   = base64url(JSON), no padding
signature = base64url(HMAC-SHA256(RELAY_SECRET, "trsr1." + payload)), 43 chars, no padding
```

Payload (exactly these keys):

| key | type | meaning |
|---|---|---|
| `v` | `1` | version |
| `r` | string `^h[0-9a-f]{20}$` | room id |
| `u` | string, 32 hex | token holder |
| `h` | string, 32 hex | room host |
| `role` | `"host"` \| `"guest"` | `host` ⇒ `u == h`, `guest` ⇒ `u != h` |
| `m` | int 2–10 | max players incl. host |
| `iat` / `exp` | int (unix seconds) | `exp - iat ≤ 130`, token valid until `exp` (+5 s skew) |
| `n` | string `^[0-9a-f]{16}$` | nonce |

Tokens only have to be valid **when connecting** (TCP hello / UDP BIND); established connections
stay up after `exp`. A token may be used several times until it expires (Minecraft may open a
status connection and a login connection). Clients treat tokens as secrets and never log them.
Maximum token length: 512 characters.

---

## 2. TCP

### 2.1 Framing

Every connection starts with a 5-byte **preamble** sent by the client:

```
54 52 53 52 01        "TRSR" + protocol version 1
```

Then **frames** (both directions, until a connection switches to raw mode):

```
+--------+-----------------+-------------------+
| type u8| length u16 (BE) | payload (length)  |
+--------+-----------------+-------------------+
```

Payload length ≤ 1024 in the handshake and on the control connection (larger → `bad_frame`).
The relay does not send a preamble; its first bytes are always a frame.

### 2.2 Frame types

| type | name | direction | payload |
|---|---|---|---|
| `0x01` | HOST_HELLO | client → relay | host token (ASCII) |
| `0x02` | GUEST_HELLO | client → relay | guest token (ASCII) |
| `0x03` | PAIR | client → relay | pairId (16 bytes) |
| `0x20` | GUEST_OPEN | relay → host control | pairId (16) + guest UUID (16) |
| `0x21` | GUEST_CLOSED | relay → host control | pairId (16) |
| `0x22` | CLOSE_GUEST | host control → relay | pairId (16) |
| `0x23` | KICK | host control → relay | guest UUID (16) |
| `0x30` | PING | either way on control | 0–8 bytes (echoed) |
| `0x31` | PONG | either way on control | same bytes as the PING |
| `0x81` | WELCOME | relay → client | JSON (see below) |
| `0x8F` | ERROR | relay → client | error code (ASCII); the relay closes afterwards |

The first frame must arrive within **10 s** of connecting (`idle_timeout`).

### 2.3 Host control connection

```
host → relay : "TRSR" 01 | 01 len <host token>
relay → host : 81 len {"room":"h…","maxGuests":9,"pairTimeoutMs":10000,"idleTimeoutMs":45000}
```

- `maxGuests = min(m, MAX_ROOM_PLAYERS) - 1` distinct guest UUIDs (`MAX_ROOM_PLAYERS` = 10).
- The host must send a frame (use `PING`) at least every **45 s**, else `idle_timeout`.
  Recommended: `PING` every 15 s.
- A second HOST_HELLO for the same room (e.g. after a reconnect) **replaces** the old control
  connection: the old one gets `ERROR replaced`, all its guests are closed.
- Closing the control connection closes every pending and paired guest of the room.
- `KICK <uuid>`: closes all TCP connections and the UDP binding of that guest and refuses the UUID
  (`kicked`) as long as this control connection lives. `CLOSE_GUEST <pairId>`: closes one guest
  connection (a pending guest gets `ERROR kicked`, a paired one is simply closed).

### 2.4 Guest connection and pairing

```
guest → relay : "TRSR" 01 | 02 len <guest token> [Minecraft bytes may follow immediately]
relay → host  : 20 00 20 <pairId 16> <guest uuid 16>            (on the control connection)
host  → relay : NEW TCP connection: "TRSR" 01 | 03 00 10 <pairId 16>
relay → host data conn : 81 len {"room":"h…"}   → raw from here on
relay → guest          : 81 len {"room":"h…"}   → raw from here on
```

- The relay checks: a live control connection for `r` whose host is `h` (`host_offline`), UUID
  not kicked (`kicked`), room size (`room_full`), ≤ 3 connections per guest UUID
  (`too_many_connections`).
- The host must PAIR within **10 s** (`host_timeout` to the guest, `GUEST_CLOSED` to the host).
- Bytes the guest sends before its WELCOME are buffered (≤ 64 KiB, else `buffer_overflow`) and
  delivered to the host data connection right after pairing. Bytes after the PAIR frame on the host
  data connection go to the guest after its WELCOME.
- The pairId is a random 128-bit value only sent to the host control connection; it is the only
  credential of the host data connection.
- **Raw mode:** no framing anymore. The host side feeds the host data connection into its
  integrated server as a normal client connection (the guest's real UUID arrives in GUEST_OPEN;
  the relay never tells the host the guest's IP address). When either side closes, the relay
  closes the other one and sends `GUEST_CLOSED` to the control connection.
- Raw connections idle for **5 min** (no byte in either direction) are closed.

### 2.5 Error codes

`bad_preamble`, `bad_frame`, `bad_token`, `expired`, `host_offline`, `host_timeout`,
`unknown_pair`, `room_full`, `too_many_connections`, `rate_limited`, `replaced`, `kicked`,
`server_full`, `shutting_down`, `idle_timeout`, `buffer_overflow`.

Client reactions: `expired`/`bad_token` → fetch a fresh token from the API
(`POST /v1/hosting/rooms/{id}/connect`) and retry once; `host_offline`/`host_timeout` → show
"host not reachable"; `rate_limited`/`server_full`/`shutting_down` → back off (5 s, 15 s, 60 s);
`kicked`/`room_full` → give up and show the reason.

### 2.6 Limits

| Limit | Default |
|---|---|
| Players per room (incl. host) | 10 (and never more than the token's `m`) |
| Connections per guest UUID and room | 3 |
| Connections total | 1000 (`server_full`) |
| Concurrent connections per IP | 20 (`too_many_connections`) |
| New connections per IP | 60 / min (`rate_limited`) |
| Failed handshakes per IP | 30 / 10 min, then every new connection gets `rate_limited` |
| Bandwidth per room | 20 Mbit/s shared by all TCP pipes (both directions) and UDP datagrams; TCP is slowed down, UDP over budget is dropped |

---

## 3. UDP

One socket, two protocols, told apart by the first bytes.

### 3.1 STUN Binding (RFC 5389)

Standard Binding Request (type `0x0001`, magic cookie `0x2112A442`, 12-byte transaction id,
attributes ignored, ≤ 548 bytes, the length field must match). Answer: Binding Success Response
(`0x0101`, 52 bytes) with

- `XOR-MAPPED-ADDRESS` (`0x0020`) – your public IPv4 address and port as seen by the relay,
- `MAPPED-ADDRESS` (`0x0001`) – the same, un-XORed (for old clients),
- `FINGERPRINT` (`0x8028`) – CRC-32 XOR `0x5354554E`.

No authentication, no other methods, IPv4 only. At most 20 answers per second per IP (excess is
silently dropped). Any STUN library (e.g. ice4j) can use `stun:<relay host>:25504`.

### 3.2 TRS datagram relay

Packets start with `54 52 53 55` ("TRSU") + one type byte:

| type | name | direction | body |
|---|---|---|---|
| `0x01` | BIND | client → relay | token (ASCII) |
| `0x81` | BOUND | relay → client | sessionKey (8 bytes) |
| `0x02` | DATA | guest → relay | sessionKey (8) + payload (≤ 1200) |
| `0x02` | DATA | host → relay | sessionKey (8) + target guest UUID (16) + payload (≤ 1200) |
| `0x82` | DATA_FROM | relay → peer | sender UUID (16) + payload |
| `0x03` | PING | client → relay | sessionKey (8) |
| `0x83` | PONG | relay → client | sessionKey (8) |
| `0x04` | UNBIND | client → relay | sessionKey (8) |
| `0x8F` | ERROR | relay → client | error code (ASCII), at most 1 / s per address |

- **BIND** with a host token binds the host of room `r`; with a guest token a guest (the room
  must exist: host TCP control or host UDP binding, else `ERROR host_offline`; room size and
  kicks are shared with TCP). Re-binding replaces the previous binding of that UUID (new key).
  BINDs are limited to 30 / min per IP.
- A binding is tied to the **source address and port** that sent BIND: DATA/PING/UNBIND from
  another address, or with an unknown key, are dropped silently.
- A guest's DATA goes to the host (`DATA_FROM <guest uuid>`); a host's DATA goes to the given
  guest (`DATA_FROM <host uuid>`). Guest DATA while no host is bound → `ERROR host_offline`.
- Bindings expire **30 s** after their last packet: send PING every ~10 s when idle.
- Payloads larger than 1200 bytes and datagrams over the room's bandwidth budget are dropped.
  Reliability, ordering and encryption are up to the stream protocol the clients run on top.

---

## 4. What the relay stores and logs

Only in memory: rooms (id, host UUID, max players), connections, UDP bindings (address, port,
UUID, role) and per-IP rate-limit counters. Nothing is written to disk. It never logs payloads,
tokens or IP addresses – only aggregate counters (rooms, connections, bytes) every 10 minutes.
