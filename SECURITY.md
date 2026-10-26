# Security policy

ShellScout holds SSH passwords and private keys and connects to your servers, so security reports are
welcome and taken seriously.

## Reporting a vulnerability

Please report it privately, not in a public issue or pull request:

1. Open the repository's **Security** tab and choose **Report a vulnerability**
   (GitHub private vulnerability reporting).
2. Describe the problem, the ShellScout version (Settings, or the release you installed), your
   Android version and device, and steps to reproduce it. A proof of concept helps but isn't required.

You should get an acknowledgement within 7 days. Once the problem is confirmed, a fix is released
as soon as practical and the advisory is published with it, crediting you unless you'd rather not
be named. Please give a reasonable time for a fix before disclosing it publicly.

## Supported versions

Only the latest release gets security fixes. Install updates from the
[releases page](https://github.com/JKelly-pub/shellscout/releases).

## What's in scope

For example:

- Stored passwords, private keys or passphrases being readable outside the app, or leaving it
  unencrypted (logs, backups, the clipboard beyond the configured clear time, shared files)
- Host key checking that can be bypassed, or a changed host key being accepted without confirmation
- Weak SSH algorithms being used when the host's settings don't allow them
- App lock being bypassed
- Another app on the device being able to read ShellScout's data or drive its sessions
- Commands or file operations running on a server other than the one the user chose, or with
  input the user didn't intend (for example shell injection through a file name)

Out of scope: problems in the SSH servers you connect to, attacks that need a rooted or already
compromised device, and missing hardening with no practical attack.

## Verifying releases

Official APKs are signed with the certificate whose SHA-256 is
`76994532acda2e50b2d07d3130fea2bd579359a20e819c384048922bf5bf7714`. Check a download with
`apksigner verify --print-certs shellscout-vX.Y.Z.apk`. An APK signed with any other key is not an
official release.
