# Lurker for Android

A native Android client for [Lurker](https://github.com/amiantos/lurker), an IRC client with a
server that stays connected for you.

> **Status: prototype.** This is a spike, not the app. It exists to prove that Lurker's WebSocket +
> REST contract can be driven from a native client, and it does exactly enough to demonstrate that:
> sign in, list buffers, read a channel, send a message. Nothing else. See
> [Scope](#what-this-does-and-doesnt-do) before you get excited.

## What it proves

Lurker is a true bouncer: the client never touches IRC. The server does all the IRC work — parsing,
TLS, SASL, reconnect, history, highlight matching, ignore filtering — and speaks to clients in
high-level concepts (networks, buffers, messages, members) over one WebSocket plus a small REST
surface.

Until recently, though, the WebSocket authenticated by **cookie only**, so a native client couldn't
open one at all. [lurker#489](https://github.com/amiantos/lurker/issues/489) added bearer-token auth
to the upgrade, and this app is the end-to-end proof that it works:

1. `POST /api/auth/login/token` — password in, session token out, no browser in the loop.
2. `GET /api/networks` with `Authorization: Bearer <token>` — the same token authenticates REST.
3. `GET /ws` with the same bearer header on the **upgrade** — the thing browsers cannot do, and
   precisely why the web client is cookie-bound and native clients don't have to be.

A native session is an ordinary session: the bearer *is* the session token the web client's cookie
already carries, just handed to the app in a response body instead of a `Set-Cookie`.

## What this does (and doesn't) do

**Does:** sign in with a password · open the WebSocket · list buffers · open a channel and read its
backlog · send a message · render live incoming messages.

**Doesn't:** persist anything (state dies with the process, including your token — you sign in every
launch) · reconnect or resume (`?since=`) · sort or group the buffer list · show unread badges,
member lists, DMs-as-first-class, uploads, search, highlights, settings, or push · parse mIRC colors
or link URLs · handle `/commands` · render joins, parts, quits, modes, or topics.

The architecture is throwaway on purpose: no ViewModel, no local store, no repository layer. All the
wire handling is in one file, [`LurkerClient.kt`](app/src/main/java/net/amiantos/lurker/LurkerClient.kt),
and the three screens are in [`MainActivity.kt`](app/src/main/java/net/amiantos/lurker/MainActivity.kt).

The real app is scoped in the **1.0 — Daily driver** milestone. It gets one client with a
configurable base URL + auth strategy, behind a proper internal model (`Network` / `Buffer` /
`Message` / `Member` + an event enum). Note there is deliberately **no transport-adapter seam**: the
original plan called for one, but it was justified almost entirely by a direct-IRC mode that has
since been **dropped permanently**. Self-hosted and hosted are the same client differing only in base
URL and auth, so the seam would abstract over a second transport that will never exist.

## Running it

Requires a Lurker server you can reach, with a **password** set on your account — the token mint
endpoint is password-only, so a passkey-only account can't sign in yet.

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Point it at your server on the sign-in screen. Two things catch people out:

- **Use the API server's port** (`8010` by default), not the Vite client dev port — that one only
  serves the web SPA and has no `/api` or `/ws`.
- **From the emulator, the host machine is `10.0.2.2`**, not `localhost` (which is the emulator
  itself). So a local dev server is `http://10.0.2.2:8010`. From a physical device on the same
  network, use the machine's LAN address instead.

Cleartext HTTP is enabled in the manifest so a plain-HTTP dev server works. That is a prototype
convenience and should not survive into a shipping build.

## A note on the buffer list

On connect, the server ships one `backlog` frame per buffer — but for channels and DMs those frames
are **shells** with no messages in them. Lurker auto-focuses nothing on load, so it doesn't read a
buffer's history until the client actually opens it. The client sends `open-buffer` and the server
replies with a real backlog frame.

Get this wrong and you build a correct-looking buffer list where every channel is empty, which reads
as a bug but is the lazy-hydration design working as intended. It's the one piece of the contract
that isn't obvious from the frame names.

## License

[MPL-2.0](LICENSE), same as Lurker.
