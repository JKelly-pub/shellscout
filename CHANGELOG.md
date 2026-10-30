# Changelog

Release APKs, checksums and signing details are on the
[releases page](https://github.com/JKelly-pub/shellscout/releases).

## Unreleased

### New: WireGuard
- A new **WireGuard** tab. Import a config from a `.conf` file, by scanning its QR code, or by
  pasting the text. Start and stop the tunnel there, and see traffic and the latest handshake.
- **Pin a config to hosts**, from the tab (Pin to hosts) or the host editor (WireGuard tunnel).
  Connecting to a pinned host starts its tunnel first. A tunnel started that way stops when the last
  session using it closes; one you start from the tab runs until you stop it.
- Tunnels run as Android's VPN through the official WireGuard library (wireguard-go). Android asks
  once for VPN permission. Only one tunnel can run at a time, and starting another asks first.
- Configs are stored encrypted, like passwords and keys. The notification shows the running tunnel,
  and Disconnect all stops it too.

### New: FTP and FTPS
- Hosts can now use **FTP** or **FTPS** (FTP over TLS, explicit `AUTH TLS`) as well as SSH. Choose
  the protocol when you edit a host, or quick connect with `ftp://user@host` or `ftps://user@host`.
- FTP tabs open straight to the file browser: browse, upload, download with resume, rename, move,
  delete, change permissions (where the server allows `SITE CHMOD`) and edit text files. Copy and
  extract need a shell on the server, so they stay SSH-only.
- FTPS protects both the login and the files. A certificate Android trusts is accepted silently;
  a self-signed one is shown with its SHA-256 fingerprint to check once, and a changed certificate
  gets the same warning as a changed SSH host key.
- Plain FTP hosts are marked with a warning, since the password and files are sent unencrypted.
- Disconnected tabs now offer Reconnect in the file browser too.

## 0.6.0 (2026-10-26)

### New: install a key on a server
- **Settings → Keys → Install on server** adds a key to `~/.ssh/authorized_keys` on a server you're
  connected to, like `ssh-copy-id`. Connect with your password, pick the server, tap Install.
- Ticked by default, "Sign in to this host with the key from now on" switches the saved host to
  key login. Your saved password is kept.
- A key that's already there isn't added twice, existing keys are kept, and a new `~/.ssh` gets the
  permissions the SSH server requires.

## 0.5.1 (2026-10-26)

### Security
- Bouncy Castle updated from 1.84 to 1.85, which fixes a nesting-depth check in ASN.1 parsing
  (used when reading key files) and a certificate name-constraints bypass. SSH connections don't
  use X.509 certificate chains, so the second didn't affect how ShellScout connects.

## 0.5.0 (2026-10-26)

First public release of ShellScout, an SSH terminal and SFTP file manager for Android phones,
foldables and tablets.

### Terminal
- Multiple sessions in tabs, kept alive in the background, with one-tap reconnect
- Full-screen programs such as vim, htop and tmux; extra-keys bar and hardware keyboard shortcuts
- Select and copy text by touch, or with a mouse: drag, double-click a word, right-click for
  Copy and Paste. Hold Shift to select in programs that use the mouse themselves.

### Files
- Server and device folders side by side on tablets, with drag and drop between them
- Upload and download files and folders, with a queue and resume after a dropped connection
- Select several items to cut, copy, paste, download or delete together; moves and copies run on
  the server and never overwrite
- Extract .zip and tar archives on the server, and edit remote text files directly
- Copy the current folder path, ready to paste in the terminal

### Security and privacy
- Passwords and keys encrypted with the Android Keystore, optional app lock, host key checking
- No analytics, no ads and no network traffic except to your own servers
- Settings → About → Open-source licences lists every library and its licence
