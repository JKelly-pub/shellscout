# ShellScout

**An SSH terminal and SFTP file manager for Android, built for phones, foldables and tablets.**

ShellScout puts a full terminal and a file manager for your servers in one app. Open several
sessions at once, browse and edit remote files beside the terminal, and move files between your
device and your servers. Sessions and transfers keep running while you switch apps. The layout
adapts to the screen, from phones to tablets with a keyboard, and in Samsung DeX it scales to fit an
external monitor.

## Features

**Terminal**
- Multiple sessions in tabs, each with its own connection, kept alive in the background
- Works with full-screen programs such as `vim`, `htop` and `tmux` (xterm-256color)
- Extra-keys bar for Esc, Ctrl, Alt, Tab, arrows, Home/End, Page Up/Down and F1–F12
- Hardware keyboard support with app shortcuts
- Pinch to zoom, plus light, dark and high-contrast themes
- Select and copy text by touch, or by dragging and double-clicking with a mouse
- Disconnect banner with one-tap reconnect

**Files**
- Browse servers over SFTP and folders on your device side by side
- Upload and download files and whole folders, with a transfer queue and progress
- Interrupted transfers resume where they stopped
- Choose to overwrite, rename, skip or resume when a file already exists
- Select several files and folders to cut, copy, paste, download or delete them together
- Extract .zip and tar archives on the server
- Share files from other apps straight into ShellScout to upload them

**Remote editing**
- Edit text files on the server directly, with syntax highlighting and find and replace
- Warns you if the file was changed on the server since you opened it

**Hosts and keys**
- Saved hosts with groups, plus quick connect (`user@host:port`)
- Password, keyboard-interactive and key authentication
- Generate Ed25519, ECDSA or RSA keys, or import OpenSSH, PEM and PuTTY (`.ppk`) keys
- Install a key on a server in one tap over your password login, or copy or share its public key

**Snippets**
- Save commands you run often and send them to the terminal in two taps
- Use placeholders such as `{{service}}` to be asked for values each time

**Phones, foldables and tablets**
- Phones get a simple one-screen-at-a-time layout
- Larger screens add a navigation rail, a terminal and files split view with a draggable divider,
  and drag and drop between local and remote files
- On wide tablets your hosts stay in a list beside the session
- Handles rotation, folding and split-screen without dropping your sessions

## Security and privacy

- Passwords and private keys are encrypted with the Android Keystore
- Optional app lock with fingerprint, face or device PIN, offered when you first open the app
- Host keys are checked on every connection. A changed key is blocked until you confirm the
  replacement.
- Only modern SSH algorithms by default; older ones can be allowed per host when needed
- Optional screenshot blocking and automatic clipboard clearing
- No analytics, no ads, no cloud backup, and no network traffic except to your own servers

## Install

Requires Android 10 or newer. No Google Play Services needed.

Download the latest `shellscout-*.apk` from the [Releases](../../releases) page on your device, open
it, and allow installs from your browser or file manager when asked.

## Keyboard shortcuts

| Keys | Action |
| --- | --- |
| Ctrl+Shift+T | New session |
| Ctrl+Shift+W | Close session |
| Ctrl+Tab / Ctrl+Shift+Tab | Next / previous session |
| Ctrl+Shift+F | Show files (split view on tablets) |
| Ctrl+Shift+S | Snippets |
| Ctrl+Shift+C / V | Copy selection / paste |

## Building from source

See [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) for build, signing and testing notes.

## Licence

Apache-2.0 (see [LICENSE](LICENSE)). Copyright 2026 James Kelly. The terminal emulator is adapted from
[Termux](https://github.com/termux/termux-app) under Apache-2.0; see [NOTICE.md](NOTICE.md) for
all third-party components.
