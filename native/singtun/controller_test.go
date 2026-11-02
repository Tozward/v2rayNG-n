package singtun

import (
	stdbufio "bufio"
	"bytes"
	"context"
	"io"
	"net"
	"net/netip"
	"strings"
	"sync"
	"testing"
	"time"

	tun "github.com/sagernet/sing-tun"
	"github.com/sagernet/sing/common/auth"
	"github.com/sagernet/sing/common/buf"
	"github.com/sagernet/sing/common/logger"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/protocol/socks"
)

func TestDeviceOptionsValidateBeforeOwningFD(t *testing.T) {
	for _, change := range []func(*[3]int32, *[4]string){
		func(n *[3]int32, s *[4]string) { n[0] = -1 },
		func(n *[3]int32, s *[4]string) { n[1] = 0 },
		func(n *[3]int32, s *[4]string) { n[2] = 65536 },
		func(n *[3]int32, s *[4]string) { s[0] = "::1/128" },
		func(n *[3]int32, s *[4]string) { s[1] = "bad prefix" },
		func(n *[3]int32, s *[4]string) { s[2] = strings.Repeat("x", 256) },
	} {
		n, s := [3]int32{7, 1500, 10808}, [4]string{"10.10.14.1/30", "fc00::1/126", "user", "password"}
		change(&n, &s)
		if _, err := deviceOptions(n[0], n[1], n[2], s[0], s[1], s[2], s[3]); err == nil {
			t.Fatal("accepted invalid options")
		}
	}
	options, err := deviceOptions(7, 1500, 10808, "10.10.14.1/30", "", "", "")
	if err != nil || len(options.Inet6Address) != 0 || !options.EXP_ExternalConfiguration || options.AutoRoute || options.GSO {
		t.Fatalf("Android ownership/offload options: %+v: %v", options, err)
	}
	c := NewController()
	c.ResetNetwork()
	if err := c.Stop(); err != nil {
		t.Fatal(err)
	}
	if err := c.Start(-1, 1500, 10808, "10.10.14.1/30", "", "", ""); err == nil {
		t.Fatal("accepted invalid fd")
	}
}

type echoHandler struct{ destinations chan M.Socksaddr }

func (h echoHandler) NewConnectionEx(ctx context.Context, conn net.Conn, _, dst M.Socksaddr, onClose N.CloseHandlerFunc) {
	h.destinations <- dst
	defer conn.Close()
	_, err := io.Copy(conn, conn)
	if onClose != nil {
		onClose(err)
	}
}

func (h echoHandler) NewPacketConnectionEx(ctx context.Context, conn N.PacketConn, _, dst M.Socksaddr, onClose N.CloseHandlerFunc) {
	h.destinations <- dst
	defer conn.Close()
	for {
		packet := buf.NewPacket()
		packet.Resize(256, 0)
		destination, err := conn.ReadPacket(packet)
		if err != nil {
			packet.Release()
			if onClose != nil {
				onClose(err)
			}
			return
		}
		if err = conn.WritePacket(packet, destination); err != nil {
			if onClose != nil {
				onClose(err)
			}
			return
		}
	}
}

func echoSOCKS(t *testing.T) (N.Dialer, <-chan M.Socksaddr, int32, func()) {
	t.Helper()
	listener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	destinations := make(chan M.Socksaddr, 32)
	var mu sync.Mutex
	var connections []net.Conn
	go func() {
		for {
			conn, err := listener.Accept()
			if err != nil {
				return
			}
			mu.Lock()
			connections = append(connections, conn)
			mu.Unlock()
			go func() {
				defer conn.Close()
				_ = socks.HandleConnectionEx(ctx, conn, stdbufio.NewReader(conn),
					auth.NewAuthenticator([]auth.User{{Username: "user", Password: "secret"}}),
					echoHandler{destinations}, nil, time.Second*5, M.SocksaddrFromNet(conn.RemoteAddr()), nil)
			}()
		}
	}()
	dropConnections := func() {
		mu.Lock()
		defer mu.Unlock()
		for _, conn := range connections {
			_ = conn.Close()
		}
		connections = nil
	}
	t.Cleanup(func() {
		cancel()
		_ = listener.Close()
		dropConnections()
	})
	return socks.NewClient(&localDialer{dialer: net.Dialer{Timeout: time.Second * 5}}, M.SocksaddrFromNet(listener.Addr()), socks.Version5, "user", "secret"), destinations, int32(listener.Addr().(*net.TCPAddr).Port), dropConnections
}

func memoryLink(t *testing.T, dialer N.Dialer) (*tun.Go, *Controller) {
	t.Helper()
	ctx, cancel := context.WithCancel(context.Background())
	var clientDevice, vpnDevice *tun.MemoryTun
	link := func(peer **tun.MemoryTun) func([]*buf.Buffer) {
		return func(packets []*buf.Buffer) {
			for _, packet := range packets {
				_, _ = (*peer).Write(packet.Bytes())
				packet.Release()
			}
		}
	}
	clientDevice = tun.NewMemoryTun(tun.MemoryTunOptions{MTU: 1500, Outbound: link(&vpnDevice)})
	vpnDevice = tun.NewMemoryTun(tun.MemoryTunOptions{MTU: 1500, Outbound: link(&clientDevice)})
	options, _ := deviceOptions(7, 1500, 10808, "10.10.14.1/30", "fc00::1/126", "", "")
	handler := &forwarder{client: dialer}
	stack, err := tun.NewGo(tun.StackOptions{Context: ctx, Tun: vpnDevice, TunOptions: options, Handler: handler,
		Logger: logger.NOP(), UDPTimeout: time.Second * 5})
	if err != nil {
		t.Fatal(err)
	}
	if err = stack.Start(); err != nil {
		t.Fatal(err)
	}
	client, err := tun.NewGo(tun.StackOptions{Context: ctx, Tun: clientDevice, TunOptions: options, Logger: logger.NOP()})
	if err != nil {
		t.Fatal(err)
	}
	if err = client.Start(); err != nil {
		t.Fatal(err)
	}
	controller := &Controller{run: &session{device: vpnDevice, stack: stack, handler: handler, cancel: cancel}}
	t.Cleanup(func() { _ = controller.Stop(); _ = client.Close(); _ = clientDevice.Close() })
	return client, controller
}

func TestTCPAndUDPThroughAuthenticatedSOCKS(t *testing.T) {
	dialer, destinations, _, _ := echoSOCKS(t)
	client, controller := memoryLink(t, dialer)
	for _, pair := range [][2]string{{"10.10.14.1", "192.0.2.10:443"}, {"fc00::1", "[2001:db8::10]:53"}} {
		source, destination := netip.MustParseAddr(pair[0]), netip.MustParseAddrPort(pair[1])
		ctx, cancel := context.WithTimeout(context.Background(), time.Second*10)
		conn, err := client.DialTCP(ctx, source, destination)
		cancel()
		if err != nil {
			t.Fatal(err)
		}
		_ = conn.SetDeadline(time.Now().Add(time.Second * 10))
		payload := bytes.Repeat([]byte("tcp-payload"), 16384)
		writeDone := make(chan error, 1)
		go func() { _, err := conn.Write(payload); writeDone <- err }()
		actual := make([]byte, len(payload))
		if _, err = io.ReadFull(conn, actual); err != nil {
			t.Fatal(err)
		}
		if err = <-writeDone; err != nil || !bytes.Equal(payload, actual) {
			t.Fatalf("TCP transfer: %v", err)
		}
		_ = conn.Close()
		if dst := <-destinations; dst.AddrPort() != destination {
			t.Fatalf("TCP destination changed: %v", dst)
		}

		udp, err := client.DialUDP(netip.AddrPortFrom(source, 0), destination)
		if err != nil {
			t.Fatal(err)
		}
		_ = udp.SetDeadline(time.Now().Add(time.Second * 5))
		for _, size := range []int{32, 1200, 4096} {
			payload := bytes.Repeat([]byte{byte(size)}, size)
			if _, err = udp.Write(payload); err != nil {
				t.Fatal(err)
			}
			actual := make([]byte, size+1)
			n, err := udp.Read(actual)
			if err != nil || !bytes.Equal(payload, actual[:n]) {
				t.Fatalf("UDP %d: n=%d err=%v", size, n, err)
			}
		}
		_ = udp.Close()
		if dst := <-destinations; dst.AddrPort() != destination {
			t.Fatalf("UDP destination changed: %v", dst)
		}
	}
	controller.ResetNetwork()
	if err := controller.Stop(); err != nil {
		t.Fatal(err)
	}
	if err := controller.Stop(); err != nil {
		t.Fatal(err)
	}
	controller.ResetNetwork()
}

func TestStopCancelsStalledHandshake(t *testing.T) {
	listener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	accepted := make(chan net.Conn, 1)
	go func() {
		conn, err := listener.Accept()
		if err == nil {
			accepted <- conn
		}
	}()
	dialer := socks.NewClient(N.SystemDialer, M.SocksaddrFromNet(listener.Addr()), socks.Version5, "", "")
	client, controller := memoryLink(t, dialer)
	ctx, cancel := context.WithTimeout(context.Background(), time.Second*5)
	defer cancel()
	dialDone := make(chan error, 1)
	go func() {
		conn, err := client.DialTCP(ctx, netip.MustParseAddr("10.10.14.1"), netip.MustParseAddrPort("192.0.2.1:443"))
		if conn != nil {
			_ = conn.Close()
		}
		dialDone <- err
	}()
	select {
	case server := <-accepted:
		defer server.Close()
	case <-ctx.Done():
		t.Fatal(ctx.Err())
	}
	stopped := make(chan error, 1)
	go func() { stopped <- controller.Stop() }()
	select {
	case err := <-stopped:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(time.Second * 2):
		t.Fatal("stop waited for SOCKS timeout")
	}
	_ = client.Close()
	select {
	case <-dialDone:
	case <-ctx.Done():
		t.Fatal("client handshake leaked")
	}
}

func TestAdapterNeverDialsRemoteDestinationsDirectly(t *testing.T) {
	dialer := &localDialer{}
	for _, destination := range []string{"1.1.1.1:53", "[2001:db8::1]:443", "example.com:443"} {
		conn, err := dialer.DialContext(context.Background(), "udp", M.ParseSocksaddr(destination))
		if err == nil || conn != nil {
			t.Fatalf("accepted direct destination %s", destination)
		}
	}
}
