//go:build android

package main

import (
	"encoding/json"
	"fmt"
	"net"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"sync/atomic"
	"syscall"
	"time"

	"tailscale.com/hostinfo"
	"tailscale.com/net/netmon"
	"tailscale.com/net/netns"
	"tailscale.com/tailcfg"
)

var flclashNetMon atomic.Pointer[netmon.Monitor]

func init() {
	go watchFlclashOwner()
	if path := os.Getenv("FLCLASH_PID_FILE"); path != "" {
		if err := os.WriteFile(path, []byte(strconv.Itoa(os.Getpid())), 0600); err != nil {
			panic(err)
		}
	}
	netns.SetAndroidProtectFunc(protectFlclashSocket)
	netmon.RegisterInterfaceGetter(flclashInterfaces)
	hostinfo.RegisterHostinfoNewHook(func(hi *tailcfg.Hostinfo) {
		hi.OSVersion = os.Getenv("FLCLASH_OS_VERSION")
		hi.DeviceModel = os.Getenv("FLCLASH_DEVICE_MODEL")
		hi.Package = os.Getenv("FLCLASH_PACKAGE")
	})
	wake := make(chan os.Signal, 1)
	signal.Notify(wake, syscall.SIGUSR1)
	go func() {
		for range wake {
			if m := flclashNetMon.Load(); m != nil {
				m.InjectEvent()
			}
		}
	}()
}

func watchFlclashOwner() {
	c, err := net.DialUnix("unix", nil, &net.UnixAddr{Name: os.Getenv("FLCLASH_PROTECT_SOCKET"), Net: "unix"})
	if err != nil {
		os.Exit(1)
	}
	defer c.Close()
	if err := c.SetDeadline(time.Now().Add(5 * time.Second)); err != nil {
		os.Exit(1)
	}
	if _, err := c.Write([]byte{2}); err != nil {
		os.Exit(1)
	}
	var reply [1]byte
	if _, err := c.Read(reply[:]); err != nil || reply[0] != 1 {
		os.Exit(1)
	}
	if err := c.SetDeadline(time.Time{}); err != nil {
		os.Exit(1)
	}
	_, _ = c.Read(reply[:])
	// Android killing the owner must not leave an orphan using its node state.
	os.Exit(0)
}

func protectFlclashSocket(fd int) error {
	path := os.Getenv("FLCLASH_PROTECT_SOCKET")
	if path == "" {
		return fmt.Errorf("socket protection broker is not configured")
	}
	c, err := net.DialUnix("unix", nil, &net.UnixAddr{Name: path, Net: "unix"})
	if err != nil {
		return fmt.Errorf("socket protection broker: %w", err)
	}
	defer c.Close()
	if err := c.SetDeadline(time.Now().Add(5 * time.Second)); err != nil {
		return err
	}
	if _, _, err := c.WriteMsgUnix([]byte{1}, syscall.UnixRights(fd), nil); err != nil {
		return err
	}
	var reply [1]byte
	if _, err := c.Read(reply[:]); err != nil {
		return err
	}
	if reply[0] != 1 {
		return fmt.Errorf("Android refused socket protection")
	}
	return nil
}

func flclashInterfaces() ([]netmon.Interface, error) {
	b, err := os.ReadFile(os.Getenv("FLCLASH_NETWORK_STATE"))
	if err != nil {
		return nil, err
	}
	var state struct {
		Default    string `json:"default"`
		Interfaces []struct {
			Name      string   `json:"name"`
			Index     int      `json:"index"`
			MTU       int      `json:"mtu"`
			Addresses []string `json:"addresses"`
		} `json:"interfaces"`
	}
	if err := json.Unmarshal(b, &state); err != nil {
		return nil, err
	}
	netmon.UpdateLastKnownDefaultRouteInterface(state.Default)
	ret := make([]netmon.Interface, 0, len(state.Interfaces))
	for _, iface := range state.Interfaces {
		if strings.HasPrefix(iface.Name, "tun") {
			continue
		}
		ni := netmon.Interface{Interface: &net.Interface{Name: iface.Name, Index: iface.Index, MTU: iface.MTU, Flags: net.FlagUp}}
		for _, address := range iface.Addresses {
			ip, prefix, err := net.ParseCIDR(address)
			if err == nil {
				prefix.IP = ip
				ni.AltAddrs = append(ni.AltAddrs, prefix)
			}
		}
		ret = append(ret, ni)
	}
	return ret, nil
}
