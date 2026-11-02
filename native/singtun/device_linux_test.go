package singtun

import (
	"bytes"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"testing"
	"time"

	"github.com/sagernet/netlink"
	"golang.org/x/sys/unix"
)

// Runs as root on the disposable Linux CI runner, without changing its default route.
func TestKernelFDTransferResetAndOwnership(t *testing.T) {
	if os.Geteuid() != 0 {
		t.Skip("requires a disposable Linux runner with CAP_NET_ADMIN")
	}
	fd, err := unix.Open("/dev/net/tun", unix.O_RDWR|unix.O_NONBLOCK|unix.O_CLOEXEC, 0)
	if err != nil {
		t.Fatal(err)
	}
	defer unix.Close(fd)
	ifr, err := unix.NewIfreq(fmt.Sprintf("sg%d", os.Getpid()))
	if err != nil {
		t.Fatal(err)
	}
	ifr.SetUint16(unix.IFF_TUN | unix.IFF_NO_PI)
	if err = unix.IoctlIfreq(fd, unix.TUNSETIFF, ifr); err != nil {
		t.Fatal(err)
	}
	link, err := netlink.LinkByName(ifr.Name())
	if err != nil {
		t.Fatal(err)
	}
	address, err := netlink.ParseAddr("198.19.254.1/30")
	if err != nil {
		t.Fatal(err)
	}
	if err = netlink.AddrAdd(link, address); err != nil {
		t.Fatal(err)
	}
	if err = netlink.LinkSetMTU(link, 1500); err != nil {
		t.Fatal(err)
	}
	if err = netlink.LinkSetUp(link); err != nil {
		t.Fatal(err)
	}
	_, _, port, dropConnections := echoSOCKS(t)
	controller := NewController()
	defer controller.Stop()
	start := func() {
		t.Helper()
		if err := controller.Start(int32(fd), 1500, port, "198.19.254.1/30", "", "user", "secret"); err != nil {
			t.Fatal(err)
		}
	}
	transfer := func() net.Conn {
		t.Helper()
		conn, err := net.DialTimeout("tcp4", "198.19.254.2:443", time.Second*5)
		if err != nil {
			t.Fatal(err)
		}
		_ = conn.SetDeadline(time.Now().Add(time.Second * 5))
		payload := bytes.Repeat([]byte("kernel-tun"), 2048)
		go func() { _, _ = conn.Write(payload) }()
		actual := make([]byte, len(payload))
		if _, err := io.ReadFull(conn, actual); err != nil || !bytes.Equal(actual, payload) {
			t.Fatalf("kernel TCP: %v", err)
		}
		return conn
	}
	start()
	if err := controller.Start(int32(fd), 1500, port, "198.19.254.1/30", "", "user", "secret"); err == nil {
		t.Fatal("duplicate native start accepted")
	}
	conn := transfer()
	// Simulate Xray replacing its listener/connections before resetting the backend.
	dropConnections()
	controller.ResetNetwork()
	_ = conn.SetReadDeadline(time.Now().Add(time.Second))
	if _, err := conn.Read(make([]byte, 1)); err == nil {
		t.Fatal("stale flow survived reset")
	} else {
		var networkError net.Error
		if errors.As(err, &networkError) && networkError.Timeout() {
			t.Fatalf("reset did not close stale TCP flow: %v", err)
		}
	}
	_ = conn.Close()
	_ = transfer().Close()
	udp, err := net.DialTimeout("udp4", "198.19.254.2:53", time.Second*5)
	if err != nil {
		t.Fatal(err)
	}
	payload := bytes.Repeat([]byte("dns"), 400)
	for attempt := 0; attempt < 2; attempt++ {
		_ = udp.SetDeadline(time.Now().Add(time.Second * 5))
		if _, err = udp.Write(payload); err != nil {
			t.Fatal(err)
		}
		actual := make([]byte, len(payload)+1)
		n, err := udp.Read(actual)
		if err != nil || !bytes.Equal(actual[:n], payload) {
			t.Fatalf("kernel UDP after %d resets: %v", attempt, err)
		}
		dropConnections()
		controller.ResetNetwork()
	}
	_ = udp.Close()
	if err = controller.Stop(); err != nil {
		t.Fatal(err)
	}
	if _, err = unix.FcntlInt(uintptr(fd), unix.F_GETFD, 0); err != nil {
		t.Fatalf("caller TUN fd closed: %v", err)
	}
	start()
	_ = transfer().Close()
}
