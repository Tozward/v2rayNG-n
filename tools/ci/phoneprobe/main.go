// A shell-UID probe for the isolated audit APK. Targets are fixed synthetic
// endpoints; this tool never discovers or connects to user-configured servers.
package main

import (
	"bufio"
	"bytes"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"sort"
	"strconv"
	"strings"
	"time"
)

const endpoint = "198.18.0.9:12345"

func main() {
	if err := run(os.Args[1:]); err != nil {
		fmt.Fprintln(os.Stderr, "audit probe:", err)
		os.Exit(1)
	}
}

func run(args []string) error {
	if os.Getuid() != 2000 {
		return errors.New("requires the allowlisted shell UID 2000")
	}
	if len(args) == 0 {
		return errors.New("expected tcp, udp, tcp-bulk, latency, bulk, or http PORT")
	}
	mode := args[0]
	if mode == "http" {
		if len(args) != 2 {
			return errors.New("http requires one audit proxy port")
		}
		return httpProbe(args[1])
	}
	if len(args) != 1 {
		return errors.New("unexpected probe arguments")
	}
	if mode == "udp" {
		return udpProbe()
	}
	if mode != "tcp" && mode != "tcp-bulk" && mode != "latency" && mode != "bulk" {
		return errors.New("unknown probe mode")
	}
	timeout := 3 * time.Second
	if mode == "tcp" || mode == "tcp-bulk" {
		timeout = time.Second
	}
	conn, err := net.DialTimeout("tcp4", endpoint, timeout)
	if err != nil {
		return err
	}
	defer conn.Close()
	if err := conn.(*net.TCPConn).SetNoDelay(mode == "latency" || mode == "bulk"); err != nil {
		return err
	}
	switch mode {
	case "tcp", "tcp-bulk":
		sent := []byte("tun-smoke")
		if mode == "tcp-bulk" {
			sent = payload(256 * 1024)
		}
		if err := exchange(conn, sent, make([]byte, len(sent)), 5*time.Second); err != nil {
			return err
		}
		_, err := fmt.Print("tun-smoke")
		return err
	case "latency":
		sent := make([]byte, 256)
		for index := range sent {
			sent[index] = byte(index * 31)
		}
		received := make([]byte, len(sent))
		samples := make([]float64, 500)
		for index := -20; index < len(samples); index++ {
			started := time.Now()
			if err := conn.SetDeadline(time.Now().Add(5 * time.Second)); err != nil {
				return err
			}
			if err := writeAll(conn, sent); err != nil {
				return err
			}
			if _, err := io.ReadFull(conn, received); err != nil {
				return err
			}
			if !bytes.Equal(sent, received) {
				return errors.New("latency echo payload changed")
			}
			if index >= 0 {
				samples[index] = float64(time.Since(started).Nanoseconds()) / 1000
			}
		}
		sort.Float64s(samples)
		return json.NewEncoder(os.Stdout).Encode(map[string]any{
			"kind": "latency", "samples": len(samples), "p50_us": samples[249],
			"p95_us": samples[474], "p99_us": samples[494], "max_us": samples[499],
		})
	case "bulk":
		sent := payload(4 * 1024 * 1024)
		received := make([]byte, len(sent))
		if err := exchange(conn, sent, received, 10*time.Second); err != nil {
			return err
		}
		started := time.Now()
		var rounds int64
		for {
			if err := exchange(conn, sent, received, 10*time.Second); err != nil {
				return err
			}
			rounds++
			if time.Since(started) >= 3*time.Second {
				break
			}
		}
		elapsed := time.Since(started)
		transferred := rounds * int64(len(sent)) * 2
		return json.NewEncoder(os.Stdout).Encode(map[string]any{
			"kind": "bulk", "bytes": transferred,
			"elapsed_ms":  float64(elapsed.Nanoseconds()) / 1e6,
			"duplex_MB_s": float64(transferred) / elapsed.Seconds() / 1e6,
		})
	}
	return nil
}

func payload(size int) []byte {
	buffer := make([]byte, size)
	for index := range buffer {
		buffer[index] = byte(index*31 + (index >> 11))
	}
	return buffer
}

func writeAll(writer io.Writer, data []byte) error {
	for len(data) > 0 {
		n, err := writer.Write(data)
		if err != nil {
			return err
		}
		if n == 0 {
			return io.ErrNoProgress
		}
		data = data[n:]
	}
	return nil
}

// Both directions drain together; a full-duplex echo must not depend on kernel
// socket buffers being large enough to hold the entire bulk payload.
func exchange(conn net.Conn, sent, received []byte, timeout time.Duration) error {
	if err := conn.SetDeadline(time.Now().Add(timeout)); err != nil {
		return err
	}
	written := make(chan error, 1)
	go func() { written <- writeAll(conn, sent) }()
	_, readErr := io.ReadFull(conn, received)
	if readErr != nil {
		_ = conn.Close()
	}
	writeErr := <-written
	if readErr != nil {
		return readErr
	}
	if writeErr != nil {
		return writeErr
	}
	if !bytes.Equal(sent, received) {
		return errors.New("echo payload changed")
	}
	return nil
}

func udpProbe() error {
	conn, err := net.DialTimeout("udp4", endpoint, time.Second)
	if err != nil {
		return err
	}
	defer conn.Close()
	if err := conn.SetDeadline(time.Now().Add(time.Second)); err != nil {
		return err
	}
	sent := []byte("tun-smoke")
	if _, err := conn.Write(sent); err != nil {
		return err
	}
	received := make([]byte, len(sent)+1)
	n, err := conn.Read(received)
	if err != nil {
		return err
	}
	if !bytes.Equal(sent, received[:n]) {
		return errors.New("UDP echo payload changed")
	}
	_, err = fmt.Print("tun-smoke")
	return err
}

func httpProbe(argument string) error {
	port, err := strconv.Atoi(argument)
	if err != nil || port < 1 || port > 65535 {
		return errors.New("invalid audit HTTP proxy port")
	}
	conn, err := net.DialTimeout("tcp4", net.JoinHostPort("127.0.0.1", strconv.Itoa(port)), 3*time.Second)
	if err != nil {
		return err
	}
	defer conn.Close()
	if err := conn.(*net.TCPConn).SetNoDelay(false); err != nil {
		return err
	}
	if err := conn.SetDeadline(time.Now().Add(5 * time.Second)); err != nil {
		return err
	}
	credentials := base64.StdEncoding.EncodeToString([]byte("audit-local:audit-test-only"))
	request := "CONNECT " + endpoint + " HTTP/1.1\r\nHost: " + endpoint + "\r\nProxy-Authorization: Basic " + credentials + "\r\n\r\n"
	if err := writeAll(conn, []byte(request)); err != nil {
		return err
	}
	reader := bufio.NewReader(conn)
	header := make([]byte, 0, 128)
	for !bytes.HasSuffix(header, []byte("\r\n\r\n")) {
		if len(header) >= 8192 {
			return errors.New("audit HTTP reply header too large")
		}
		next, err := reader.ReadByte()
		if err != nil {
			return err
		}
		header = append(header, next)
	}
	if !strings.HasPrefix(string(header), "HTTP/1.1 200 ") && !strings.HasPrefix(string(header), "HTTP/1.0 200 ") {
		return errors.New("audit HTTP CONNECT refused")
	}
	if err := conn.SetDeadline(time.Now().Add(5 * time.Second)); err != nil {
		return err
	}
	sent := []byte("tun-smoke")
	if err := writeAll(conn, sent); err != nil {
		return err
	}
	received := make([]byte, len(sent))
	if _, err := io.ReadFull(reader, received); err != nil {
		return err
	}
	if !bytes.Equal(sent, received) {
		return errors.New("audit HTTP echo payload changed")
	}
	_, err = fmt.Print("tun-smoke")
	return err
}
