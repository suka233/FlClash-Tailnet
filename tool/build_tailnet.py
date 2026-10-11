#!/usr/bin/env python3
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
NATIVE = ROOT / "native/tailnet"
LOCK = json.loads((NATIVE / "backend.lock.json").read_text())


def run(args, **kwargs):
    return subprocess.run(args, check=True, text=True, **kwargs)


def replace_once(path, old, new):
    text = path.read_text()
    if text.count(old) != 1:
        raise RuntimeError(f"Upstream contract changed: {path.name}: {old!r}")
    path.write_text(text.replace(old, new))


def prepare(env):
    cache = NATIVE / ".build"
    source = cache / "tailscale"
    fingerprint = hashlib.sha256(
        (NATIVE / "backend.lock.json").read_bytes()
        + (NATIVE / "platform_android.go").read_bytes()
        + Path(__file__).read_bytes()
    ).hexdigest()
    stamp = cache / "source.stamp"
    if stamp.exists() and stamp.read_text() == fingerprint:
        return source
    metadata = json.loads(run(
        ["go", "mod", "download", "-json", f"tailscale.com@{LOCK['version']}"],
        env=env, capture_output=True,
    ).stdout)
    if metadata["Sum"] != LOCK["sum"]:
        raise RuntimeError("Tailscale module checksum differs from backend.lock.json")
    if source.exists():
        shutil.rmtree(source)
    shutil.copytree(metadata["Dir"], source)
    source.chmod(0o755)
    for path in source.rglob("*"):
        if path.is_file():
            path.chmod(0o644)
        elif path.is_dir():
            path.chmod(0o755)
    replace_once(source / "feature/taildrop/fileops_fs.go", "//go:build !android\n", "")
    replace_once(source / "cmd/tailscaled/tailscaled.go", "netns.SetEnabled(!onlyNetstack)",
                 'netns.SetEnabled(runtime.GOOS == "android" || !onlyNetstack)')
    replace_once(source / "cmd/tailscaled/tailscaled.go", "sys.Set(netMon)",
                 'sys.Set(netMon)\n\t\tif runtime.GOOS == "android" { registerFlclashNetMon(netMon) }')
    proxy = source / "cmd/tailscaled/proxy.go"
    replace_once(proxy, '\t"net/http/httputil"', '\t"net/http/httputil"\n\t"os"')
    replace_once(proxy, '\t\t\t\tDialer: dialer.UserDial,',
                 '\t\t\t\tDialer: dialer.UserDial,\n'
                 '\t\t\t\tUsername: os.Getenv("FLCLASH_SOCKS_USER"),\n'
                 '\t\t\t\tPassword: os.Getenv("FLCLASH_SOCKS_PASSWORD"),')
    shutil.copyfile(NATIVE / "platform_android.go", source / "cmd/tailscaled/flclash_android.go")
    (source / "cmd/tailscaled/flclash_other.go").write_text(
        '//go:build !android\n\npackage main\n\nimport "tailscale.com/net/netmon"\n'
        '\nfunc registerFlclashNetMon(*netmon.Monitor) {}\n')
    with (source / "cmd/tailscaled/flclash_android.go").open("a") as output:
        output.write("\nfunc registerFlclashNetMon(m *netmon.Monitor) { flclashNetMon.Store(m) }\n")
    run(["gofmt", "-w", "cmd/tailscaled/flclash_android.go", "cmd/tailscaled/flclash_other.go",
         "cmd/tailscaled/tailscaled.go", "cmd/tailscaled/proxy.go"], cwd=source, env=env)
    stamp.write_text(fingerprint)
    return source


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--abi", default="arm64-v8a", choices=["arm64-v8a", "armeabi-v7a", "x86_64"])
    parser.add_argument("--prepare-only", action="store_true")
    args = parser.parse_args()
    env = dict(os.environ, GOTOOLCHAIN=LOCK["go"])
    source = prepare(env)
    if args.prepare_only:
        return
    sdk = Path(env.get("ANDROID_HOME", env.get("ANDROID_SDK_ROOT", Path.home() / "Library/Android/sdk")))
    ndk = Path(env.get("ANDROID_NDK_HOME", sdk / "ndk/28.2.13676358"))
    host = "darwin-x86_64" if platform.system() == "Darwin" else "linux-x86_64"
    arch, target = {"arm64-v8a": ("arm64", "aarch64-linux-android24"),
                    "armeabi-v7a": ("arm", "armv7a-linux-androideabi24"),
                    "x86_64": ("amd64", "x86_64-linux-android24")}[args.abi]
    env.update(GOOS="android", GOARCH=arch, CGO_ENABLED="1",
               CC=str(ndk / f"toolchains/llvm/prebuilt/{host}/bin/{target}-clang"))
    if arch == "arm":
        env["GOARM"] = "7"
    dest = ROOT / f"android/tailnet/src/main/jniLibs/{args.abi}/libtailnet.so"
    dest.parent.mkdir(parents=True, exist_ok=True)
    tags = "ts_omit_systray,ts_omit_kube,ts_omit_aws,ts_omit_bird,ts_omit_qrcodes,ts_omit_desktop_sessions,ts_omit_dbus,ts_omit_networkmanager,ts_omit_resolved,ts_omit_sdnotify,ts_omit_tpm,ts_omit_logtail,ts_omit_synology,ts_omit_syspolicy,ts_omit_ssh,ts_omit_iptables,ts_omit_tap,ts_omit_linuxdnsfight,ts_omit_captiveportal,ts_omit_appconnectors,ts_omit_syslog,ts_omit_clientupdate,ts_omit_portlist,ts_omit_androidbin,ts_omit_androiddns"
    run(["go", "build", "-buildmode=pie", "-trimpath", "-buildvcs=false", "-tags", tags,
         "-ldflags", f"-s -w -X tailscale.com/version.shortStamp={LOCK['version'][1:]}",
         "-o", str(dest), "./cmd/tailscaled"], cwd=source, env=env)
    print(f"Built {dest.relative_to(ROOT)} ({dest.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
