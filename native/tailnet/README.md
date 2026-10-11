# FlClash Tailnet Android prototype

FlClash keeps sole ownership of Android VpnService and its TUN descriptor. Mihomo handles ordinary proxy traffic. Official tailscaled runs with userspace networking in a separate process, exposes an authenticated loopback SOCKS endpoint, and retains official control-plane, WireGuard, DERP and Taildrop behavior. No control-plane credentials are embedded in subscriptions or the source tree. A private random SOCKS credential is injected only into the effective runtime profile.

The final profile adapter prepends Tailnet address and advertised subnet rules, adds the private SOCKS outbound, and integrates MagicDNS through a loopback resolver and host entries. Tailnet traffic fails into the private backend when it is unavailable; it does not fall through to the internet proxy. Rule mode is enforced while enabled. Default exit routes are deliberately excluded.

A same-UID abstract Unix socket broker accepts SCM_RIGHTS descriptors from tailscaled. It binds each connection to a physical Android network and protects it through the existing VpnService. A persistent owner connection terminates the child when the Android app process dies. Network callbacks publish interface snapshots atomically and signal netmon. The backend state and received files are inside the app private directory.

Physical network selection follows Android's active network, with validated Wi-Fi preferred when the active network is the VPN. The standalone Tailscale child also binds its Bionic DNS resolver to that physical network through the Android NDK process binding API; the Flutter/Mihomo parent stays unbound. This keeps control-plane DNS and sockets on the same network, including when a router supplies Fake-IP answers or the proxy VPN is already running. Changes in network capabilities refresh the binding.

Sign-in requests wait for the asynchronous control-server AuthURL and open it once in the external browser. The page shows backend health warnings, a 30-second waiting timeout, and a selectable link with manual reopening if browser launch fails. Opening the settings page alone does not launch authentication. Headscale may require a server-side registration command before the new node becomes Running.

Taildrop uses the official LocalAPI and stable node IDs. Sending stages only the system-picker selected file in app cache and removes the staging copy afterward. Receiving streams into an ACTION_CREATE_DOCUMENT destination; the inbox original remains. The prototype does not automatically delete incoming files.

## Upstream boundaries

- Mihomo submodule remains unchanged, at the upstream FlClash pinned commit.
- Tailscale dependency is pinned with a Go module checksum in native/tailnet/backend.lock.json.
- tool/build_tailnet.py copies verified official source into an ignored build directory, applies small exact-match patches, and fails if upstream contracts change.
- Android integration lives in the tailnet module and a single Flutter method channel.
- FlClash modifications are limited to service socket protection, final profile injection, mode enforcement, navigation, localization and fork branding.

## Platform limits

The APK requires Android 8+ and this deliverable targets ARM64. Userspace forwarding provides TCP/UDP connectivity; it is not a full kernel Tailscale TUN. General ICMP/ping, exit nodes, route advertisement, Funnel and Serve configuration are not exposed by this prototype. Advertised subnet IP routes are read from status; custom split DNS beyond MagicDNS has not yet been integrated.

Hotspot clients configure an HTTP/SOCKS proxy manually, using the hotspot gateway and the FlClash mixed port, with Allow LAN and proxy authentication enabled. Android without root does not provide this app transparent control of all tethered traffic. App-specific clients that ignore proxy settings cannot be forced through it.

## Build and maintain

Use Flutter 3.47.4, JDK17, NDK28.2.13676358, Python3, Go (auto downloads pinned Tailscale toolchain) and Rust. Run flutter pub get, then flutter build apk --release --target-platform android-arm64. Keep android/app/keystore.jks and the signing entries in android/local.properties private and backed up securely; these files are ignored. The release APK uses this persistent local key. CI artifacts use the .dev package and runner debug signing, separately from the installed release prototype.

The weekly upstream-sync workflow prepares sync/upstream from next and merges official upstream/main without rewriting source history. Conflicts stop the run. The reusable CI workflow validates the exact merge commit and builds an APK; only success advances next with a fast-forward, and a concurrent branch change prevents an unsafe push. main is promoted only after device validation. A manual workflow dispatch checks the schedule setup even when upstream has no new commits. CI cannot prove Android VPN routing, Taildrop interoperability or MIUI background survival; those remain device release gates.
