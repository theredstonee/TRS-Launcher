# Hosted worlds with mods and a resource pack

How a host shares **mods** and a **resource pack** with the guests of a hosted world, and how guests get them.
The TRS API only stores the **list** (metadata, `api/API.md` §21.10). Files never go through TRS servers: store mods
come from their official source (Modrinth, CurseForge), everything else goes **directly from the host's game to the
guest** over the hosting connection (relay or P2P).

Everything is **off by default**. The host turns on "Share mods" and/or "Share resource pack" per world (remembered in
`config/trsclient/hosting-share.json`); only then the choice appears. "Send mods directly from the host" is a separate,
also default-off option – without it, mods that are not in a store are only listed as "get it yourself".

## 1. Who does what

| Piece | Where | Job |
|---|---|---|
| Mod list of the host | game (`core/hosting/share/ModScan`, `ShareModel`) | reads `<game>/mods/*.jar`: name, version, environment, dependencies, "adds blocks/items" (blockstates / recipes / loot tables / worldgen of an own namespace); SHA-1, SHA-512, SHA-256 |
| Store detection | **launcher** (`hosting.mods` over the TRS Link, `docs/hosting-link.md` §6) – without launcher the game asks Modrinth itself | Modrinth by SHA-512 (both SHA-1 and SHA-512 must match), CurseForge by Murmur2 fingerprint (the API key only lives in the launcher) |
| Announce | game → `PUT /v1/hosting/rooms/{id}/content` | list + pack info, nothing else |
| Guest dialog | launcher (`HostingModsDialog`) | on **every** join: list, "new instance" / "add to a copy" / "without mods", host-file warning + "I trust this host" |
| Store downloads | launcher (`hosting_mods::install_into`) | official URL from Modrinth/CurseForge, SHA-1 while loading, SHA-512 afterwards (Modrinth), size |
| Host files | launcher fetches, host game serves | own relay connection + file channel (§2), SHA-256 **and** SHA-1 against the announcement |
| Resource pack | host game serves, guest game fetches | vanilla server-pack prompt, local per-connection endpoint (§4) |

**Why the launcher fetches the mods:** mods must be on disk *before* the game starts, and only the launcher creates
instances. It speaks the small relay handshake itself (TCP, `relay/PROTOCOL.md` §2.4) – no P2P/ICE in Rust, no second
Minecraft session. The relay token comes from the API (`POST /v1/hosting/rooms/{id}/connect`, accepted members only) and
never reaches the web view. The pack is different: it is only needed while playing, so the guest's game fetches it
through its own hosting code (direct first, relay as fallback).

## 2. File channel (`TRSF` v1)

A **new** stream between guest and host – a second relay connection of the same guest (the relay allows 3 per guest)
or a second P2P link (new `offer` with a new `sid`). The host looks at the first byte of every new guest stream:
Minecraft never starts with `0x00` (a packet length ≥ 1, or `0xFE` for the legacy ping), the file channel always does.
Minecraft streams are handed to the integrated server unchanged (the sniffed bytes go first); public-link (e4mc) streams
that start with the magic are closed.

```
guest → host  00 54 52 53 46 01                    "\0TRSF" + version 1
frame         type u8 | length u32 (big endian) | payload

GET    0x01   guest → host   kind u8 (1 = mod, 2 = resource pack) + SHA-256 (32 bytes)
BYE    0x02   guest → host   –
FILE   0x81   host → guest   size u64
DATA   0x82   host → guest   ≤ 65 536 bytes
END    0x83   host → guest   SHA-256 (32) of the sent bytes
ERROR  0x8F   host → guest   ASCII code: not_shared, not_found, changed, rate_limited, busy, bad_request, not_allowed
```

- One request at a time. After `ERROR` the channel stays usable; after a client-side abort (size/hash) the client
  closes it.
- Frames from the guest are at most 64 bytes; `DATA` at most 64 KiB.

### Host rules (`FileServer`)

- **Only accepted members:** the guest's UUID comes from the relay (`GUEST_OPEN`, token checked by the relay) or the
  signalling (P2P); it must be `accepted` and not banned in the room – checked when the stream opens **and** before every
  request and every data block.
- **Allowlist:** only the files that are shared right now – mods with source `host` (only with "send directly") and the
  pack – keyed by SHA-256. A guest can only ask for a hash, never a path, so no other file of the host (and no path
  traversal) is reachable. Turning sharing off empties the list at once; running transfers stop.
- **Unchanged files only:** size and modification time must equal the values at the time of sharing; the host hashes
  while sending and answers `changed` if the file changed.
- **Limits per guest:** 2 channels at a time, 60 requests per 10 minutes, at most `3 × shared size + 64 MB` in total;
  60 s idle → closed. Speed per channel 1.5 MB/s over the relay (the relay has 20 Mbit/s per world for everybody),
  8 MB/s direct; sending waits while more than 512 KiB are queued (back pressure).
- **Sizes:** mods from the host ≤ 64 MB each and ≤ 512 MB together, pack ≤ 250 MB (also enforced by the API).

### Guest rules (launcher `hosting_mods::relay`, game `FileClient`)

- Expected SHA-256, SHA-1 and size come from the **API** (what the host announced), not from the channel.
- Size must equal the announcement and stay below the limit, otherwise abort; the partial file is deleted.
- Files are written to a temporary `.trs-hosting-*.part` in the target `mods` folder and renamed only after all checks.
- File names: only `[A-Za-z0-9._+-]` (anything else → `_`), must end with `.jar`, never a path; a name that exists with
  other content gets a `-<sha1 prefix>` suffix.
- Timeouts: 6 s connect, 14 s relay handshake, 45 s per read.

## 3. Guest dialog (launcher)

Shown on every join of a world whose `content.mods > 0` – from the list, a world card, an invite toast and from the
game ("Open in launcher", `hosting.open`, `docs/hosting-link.md` §7):

- each mod with required/optional, source ("Modrinth", "CurseForge", "From the host (not verified)", "Get it yourself"),
  size and present/missing for the chosen instance;
- **New instance** (version + loader of the world, TRS Client), **Add to an existing instance as a copy** (the original
  stays untouched – `duplicate_instance`, then only the missing mods), **Join without mods** – only if the chosen instance
  lacks no required mod; otherwise the dialog explains why not;
- optional mods can be deselected, required ones not; "get it yourself" mods are listed as a hint;
- **mods directly from the host:** a red warning that appears on every join – *"These mods come directly from the host
  and were not checked. A mod is executable code – only install them if you trust the host."* – and the checkbox
  *"I trust this host"* (always unticked). Without it, nothing is installed (the core refuses too:
  `hosting.trustRequired`).
- If anything fails, the new instance (or copy) is deleted again; the original instance is never changed.

## 4. Resource pack (Minecraft 1.20.3+)

- **Host:** during `ServerConfigurationPacketListenerImpl#startConfiguration` of a player, the game remembers the name;
  `MinecraftServer#getServerResourcePack` then returns the shared pack – but only for accepted TRS guests (not the host,
  not public-link guests): `ServerResourcePackInfo(id, "http://trs-pack.invalid/<sha1>.zip", sha1, required = false,
  prompt = null)`. The `.invalid` address never resolves; it is only a marker.
- **Guest:** `ClientCommonPacketListenerImpl#parseResourcePackUrl` rewrites the marker to a local endpoint that is
  only valid for this connection: `http://127.0.0.1:<random port>/<128-bit random token>/pack.zip`. It accepts only
  loopback, only that path, at most 4 requests, and stops when the guest leaves. Each request opens a file channel to
  the host, streams the pack and checks SHA-1 and SHA-256 on the way (a mismatch aborts the response, Minecraft rejects
  the pack; Minecraft also checks the SHA-1 itself).
- The player sees Minecraft's normal question "Use server resource pack?"; declining is fine (not required).
- **Versions:** Fabric, NeoForge and Forge from 1.20.3 (the mixins are only listed there). Older versions (1.14.4–1.20.2,
  1.8.9–1.12.2, 1.7.10, 1.13.2) hide "Share resource pack" and say why: before the configuration phase the pack is sent
  from different places per version, and the URL check differs – no clean hook without version-specific packet code.

## 5. Tests

- Game (`:common:test`, `ShareTest`): list rules like the API, parsing drops foreign junk (paths, sizes, formatting
  codes), scan/defaults/dependencies/build, per-world settings, file channel (allowlist, `not_shared` for other files,
  wrong kind, size/hash abort, not-accepted guest, changed file, sharing off, rate limit), Minecraft routing keeps bytes,
  pack endpoint (token path, 404, SHA-1 mismatch).
- Launcher (`cargo test -p trs-core hosting_mods link::`): list cleaning, file names, trust duty, file channel against a
  fake relay (hash/size/errors/desync), copy instance keeps the original, failed download leaves no file, store matches
  need exact hashes, Murmur2, `hosting.mods`/`hosting.open` limits.
- Web view (`tests/hosting-mods.test.ts`): dialog logic and store flow.
- End to end: `scratchpad/hmods/e2e` – two games (1.21.11), host with a Modrinth mod, an own test mod and a test pack;
  the launcher tool `cargo run -p trs-core --example hosting_mods` fetches the list, downloads and checks everything.
