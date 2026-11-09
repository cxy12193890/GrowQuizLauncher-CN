# Local parent-console security

## Current protections

- The parent console and phone wallpaper-upload endpoint use TLS 1.2 or TLS 1.3.
- The server binds to the TV's active LAN IPv4 address instead of listening on every interface.
- The first-time setup URL contains a random one-time token. The server authorizes setup only for the phone IP that opened the TV-issued QR URL, for ten minutes.
- New parent passwords must contain at least 12 characters.
- Passwords are stored as salted PBKDF2-HMAC-SHA256 hashes with 120,000 iterations. Existing legacy hashes upgrade after a successful login.
- Five failed logins from one LAN IP within the rate-limit window trigger a 15-minute block.
- Session cookies are Secure, HttpOnly and SameSite=Strict. Password changes revoke previous sessions.
- Phone uploads use a 128-bit random URL token and TLS.
- App-wide cleartext traffic is disabled. Weather uses a city selected by the parent and does not infer a city from the public IP address.

## First connection and certificate warning

The TV creates a self-signed certificate for its current LAN IP address and keeps its private key in the app's private storage. The TV shows the certificate SHA-256 fingerprint beside the QR code.

Some phone browsers show a certificate warning because this local certificate is self-signed. Before continuing, compare the browser certificate fingerprint with the fingerprint shown on the TV. Continue only when every hexadecimal pair matches. If it does not match, stop and do not enter the parent password.

The certificate is generated per TV and LAN IP. A network change can create a new certificate and a new fingerprint. Verify the new fingerprint again.

## Remaining security limits

- The certificate is self-signed, not issued by a public certificate authority. Fingerprint verification is required on first use and after certificate changes.
- This is a home-LAN management service. Do not expose its port through router port forwarding or use it on an untrusted public Wi-Fi network.
- Android TV firmware, accessibility and device-owner behavior still require real-device testing. CI compilation does not prove that every TV vendor implements these controls correctly.

## Release status

The application is not approved for paid release. Question-bank commercial rights, cross-age duplicate review, icon provenance and the permanent release-signing key remain release blockers. See `docs/ASSET-LICENSE-REVIEW.md` and `docs/RELEASE-SIGNING.md`.