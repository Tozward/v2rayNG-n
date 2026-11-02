// Package singtun adapts sing-box 1.15's new Go TCP/IP stack to the existing
// Xray SOCKS inbound. It does not import sing-box or change routing policy.
package singtun

import (
	"context"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/netip"
	"sync"
	"syscall"
	"time"

	tun "github.com/sagernet/sing-tun"
	"github.com/sagernet/sing/common/bufio"
	"github.com/sagernet/sing/common/logger"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/protocol/socks"
)

type Controller struct {
	mu  sync.Mutex
	run *session
}

func NewController() *Controller { return new(Controller) }

// Start duplicates fd. VpnService remains the sole owner of its original fd.
// All addresses/routes are configured by Android, never by sing-tun.
func (c *Controller) Start(fd, mtu, socksPort int32, ipv4, ipv6, username, password string) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.run != nil {
		return errors.New("Sing TUN already started")
	}
	options, err := deviceOptions(fd, mtu, socksPort, ipv4, ipv6, username, password)
	if err != nil {
		return err
	}
	device, err := openDevice(options)
	if err != nil {
		return fmt.Errorf("open Sing TUN: %w", err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	handler := &forwarder{client: socks.NewClient(
		&localDialer{dialer: net.Dialer{Timeout: 10 * time.Second}},
		M.Socksaddr{Addr: netip.MustParseAddr("127.0.0.1"), Port: uint16(socksPort)},
		socks.Version5, username, password,
	)}
	stack, err := tun.NewGo(tun.StackOptions{
		Context: ctx, Tun: device, TunOptions: options, Handler: handler,
		Logger: stackLogger{Logger: logger.NOP()}, UDPTimeout: 60 * time.Second,
		ICMPTimeout: 10 * time.Second, TCPLocalJumbo: true,
	})
	if err == nil {
		err = device.Start()
	}
	if err == nil {
		err = stack.Start()
	}
	if err != nil {
		handler.stop()
		cancel()
		if stack != nil {
			_ = stack.Close()
		}
		_ = device.Close()
		handler.wg.Wait()
		return fmt.Errorf("start Sing TUN: %w", err)
	}
	c.run = &session{device: device, stack: stack, handler: handler, cancel: cancel}
	return nil
}

// ResetNetwork purges UDP mappings after Xray replaces its SOCKS listener.
// TCP relays observe their old SOCKS connection closing; upstream's reset only
// reclaims TCP buffers and deliberately does not terminate healthy TCP flows.
func (c *Controller) ResetNetwork() {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.run != nil {
		c.run.handler.udpGate.Lock()
		defer c.run.handler.udpGate.Unlock()
		c.run.stack.ResetNetwork()
		// NAT purge schedules socket closure on the engine. Drain old relays
		// before admitting new ones for the same UDP tuple after handover.
		c.run.handler.udpWG.Wait()
	}
}

func (c *Controller) Stop() error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.run == nil {
		return nil
	}
	run := c.run
	c.run = nil
	run.handler.stop()
	run.cancel()
	err := run.stack.Close()
	err = errors.Join(err, run.device.Close())
	run.handler.wg.Wait()
	return err
}

type session struct {
	device  tun.Tun
	stack   tun.Stack
	handler *forwarder
	cancel  context.CancelFunc
}

func deviceOptions(fd, mtu, port int32, ipv4, ipv6, username, password string) (tun.Options, error) {
	options := tun.Options{MTU: uint32(mtu), FileDescriptor: int(fd), DNSMode: tun.DNSModeDisabled,
		// sing-tun 611d83b: external Android configuration must not be undone on close.
		// Reevaluate when sing-tun provides a stable external-device ownership option.
		EXP_ExternalConfiguration: true, Logger: stackLogger{Logger: logger.NOP()}}
	if fd <= 0 || mtu < 576 || mtu > 65535 || port < 1 || port > 65535 {
		return options, errors.New("invalid Sing TUN fd, MTU or local SOCKS port")
	}
	v4, err := netip.ParsePrefix(ipv4)
	if err != nil || !v4.Addr().Is4() {
		return options, errors.New("invalid Sing TUN IPv4 prefix")
	}
	options.Inet4Address = []netip.Prefix{v4}
	if ipv6 != "" {
		v6, err := netip.ParsePrefix(ipv6)
		if err != nil || !v6.Addr().Is6() || mtu < 1280 {
			return options, errors.New("invalid Sing TUN IPv6 prefix or MTU")
		}
		options.Inet6Address = []netip.Prefix{v6}
	}
	if len(username) > 255 || len(password) > 255 {
		return options, errors.New("SOCKS credentials exceed protocol limits")
	}
	return options, nil
}

type forwarder struct {
	client  N.Dialer
	mu      sync.Mutex
	closed  bool
	wg      sync.WaitGroup
	udpGate sync.RWMutex
	udpWG   sync.WaitGroup
}

// Only the Xray listener may be dialed by this adapter. Remote destinations are
// encoded in SOCKS requests and never opened directly by the TUN backend.
type localDialer struct{ dialer net.Dialer }

func (d *localDialer) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	if destination.Addr.IsUnspecified() {
		destination.Addr = netip.MustParseAddr("127.0.0.1")
	}
	if !destination.Addr.IsLoopback() {
		return nil, errors.New("Sing TUN only dials the local Xray proxy")
	}
	return d.dialer.DialContext(ctx, network, destination.String())
}

func (*localDialer) ListenPacket(ctx context.Context, _ M.Socksaddr) (net.PacketConn, error) {
	return (&net.ListenConfig{}).ListenPacket(ctx, "udp4", "127.0.0.1:0")
}

func (h *forwarder) begin() bool {
	h.mu.Lock()
	defer h.mu.Unlock()
	if h.closed {
		return false
	}
	h.wg.Add(1)
	return true
}

func (h *forwarder) stop() {
	h.mu.Lock()
	h.closed = true
	h.mu.Unlock()
}

func (h *forwarder) JudgeFlow(protocol uint8, _, _ netip.AddrPort, _ []byte) tun.FlowVerdict {
	if protocol == 6 || protocol == 17 {
		return tun.FlowVerdict{Action: tun.ActionAccept}
	}
	// SOCKS5 carries TCP/UDP only, matching the existing HEV path.
	return tun.FlowVerdict{Action: tun.ActionReject}
}

func (h *forwarder) NewDNSPacket(_ []byte, _, _ M.Socksaddr, _ N.PacketWriter) {
	// No ActionHijackDNS verdict is emitted. DNS follows the existing Xray rule.
}

func (h *forwarder) NewConnectionEx(ctx context.Context, conn net.Conn, _, destination M.Socksaddr, onClose N.CloseHandlerFunc) {
	if !h.begin() {
		_ = conn.Close()
		if onClose != nil {
			onClose(net.ErrClosed)
		}
		return
	}
	spliced := false
	defer func() {
		if !spliced {
			h.wg.Done()
		}
	}()
	dialCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
	remote, err := h.client.DialContext(dialCtx, N.NetworkTCP, destination)
	cancel()
	if err == nil {
		// GoConn defers SYN-ACK until the outbound handshake succeeds. Splice
		// requires the established phase, just as sing-box's connection manager.
		err = N.ReportHandshakeSuccess(conn)
		if err != nil {
			_ = remote.Close()
		}
	}
	if err == nil {
		if source, ok := conn.(*tun.GoConn); ok {
			if socket, ok := remote.(*net.TCPConn); ok {
				spliced = source.Splice(&spliceSocket{Conn: socket, original: socket, closer: socket}, tun.SpliceOptions{
					OnClose: h.finishSplice(conn, onClose, h.wg.Done),
				})
				if spliced {
					return
				}
			}
		}
		err = bufio.CopyConn(ctx, conn, remote)
	} else {
		_ = conn.Close()
	}
	if onClose != nil {
		onClose(err)
	}
}

func (h *forwarder) NewPacketConnectionEx(ctx context.Context, conn N.PacketConn, _, destination M.Socksaddr, onClose N.CloseHandlerFunc) {
	h.udpGate.RLock()
	if !h.begin() {
		h.udpGate.RUnlock()
		_ = conn.Close()
		if onClose != nil {
			onClose(net.ErrClosed)
		}
		return
	}
	h.udpWG.Add(1)
	h.udpGate.RUnlock()
	done := func() { h.udpWG.Done(); h.wg.Done() }
	spliced := false
	defer func() {
		if !spliced {
			done()
		}
	}()
	dialCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
	remote, err := h.client.ListenPacket(dialCtx, destination)
	cancel()
	if err == nil {
		if source, ok := conn.(*tun.UDPNatConn); ok {
			upstream, offload := N.UnwrapPacketOffload(remote)
			socket, isSocket := upstream.(syscall.Conn)
			original, isCloser := upstream.(io.Closer)
			if isSocket && isCloser && offload != nil {
				spliced = source.Splice(&spliceSocket{Conn: socket, original: original, closer: remote}, tun.SplicePacketOptions{
					SpliceOptions: tun.SpliceOptions{OnClose: h.finishSplice(conn, onClose, done)},
					Timeout:       60 * time.Second, Offload: offload,
					FrontHeadroom: N.CalculateFrontHeadroom(remote), RearHeadroom: N.CalculateRearHeadroom(remote),
				})
				if spliced {
					return
				}
			}
		}
		err = bufio.CopyPacketConn(ctx, conn, bufio.NewPacketConn(remote))
	} else {
		_ = conn.Close()
	}
	if onClose != nil {
		onClose(err)
	}
}

func (h *forwarder) finishSplice(conn io.Closer, onClose N.CloseHandlerFunc, done func()) N.CloseHandlerFunc {
	return func(err error) {
		defer done()
		_ = conn.Close()
		if onClose != nil {
			onClose(err)
		}
	}
}

// sing-tun takes ownership of a duplicate socket when Splice succeeds. Route
// Close to that owner until Detach, so shutdown never leaves its duplicate alive.
type spliceSocket struct {
	syscall.Conn
	original   io.Closer
	closer     io.Closer
	mu         sync.Mutex
	attachment io.Closer
	closed     bool
}

func (s *spliceSocket) Attach(owner io.Closer) (io.Closer, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed || s.attachment != nil {
		return nil, false
	}
	s.attachment = owner
	// Only the raw UDP socket is replaced. Its SOCKS TCP association must remain
	// alive until the entire flow closes, when closer releases both resources.
	return s.original, true
}

func (s *spliceSocket) Detach() {
	s.mu.Lock()
	s.attachment = nil
	closed := s.closed
	s.mu.Unlock()
	if closed {
		_ = s.closer.Close()
	}
}

func (s *spliceSocket) Close() error {
	s.mu.Lock()
	s.closed = true
	owner := s.attachment
	s.mu.Unlock()
	if owner != nil {
		return owner.Close()
	}
	return s.closer.Close()
}

// Keep the packet hot path free of Java callbacks and per-connection logging.
// Fatal/Panic are reported as errors; a library must never terminate the app.
type stackLogger struct{ logger.Logger }

func (stackLogger) Warn(args ...any)    { log.Print("Sing TUN warning: ", fmt.Sprint(args...)) }
func (stackLogger) Error(args ...any)   { log.Print("Sing TUN error: ", fmt.Sprint(args...)) }
func (l stackLogger) Fatal(args ...any) { l.Error(args...) }
func (l stackLogger) Panic(args ...any) { l.Error(args...) }
