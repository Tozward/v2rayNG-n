// A loopback-only SOCKS echo fixture for the isolated .audit APK. It never dials
// a requested destination and accepts only the reserved synthetic test endpoint.
package main

import (
	"encoding/binary"
	"io"
	"log"
	"net"
	"time"
)

func main() {
	listener, err := net.Listen("tcp4", "127.0.0.1:47183")
	if err != nil {
		log.Fatal(err)
	}
	log.Print("isolated SOCKS echo ready")
	for {
		conn, err := listener.Accept()
		if err != nil {
			log.Fatal(err)
		}
		go handle(conn)
	}
}

func handle(conn net.Conn) {
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(20 * time.Minute))
	var greeting [2]byte
	if _, err := io.ReadFull(conn, greeting[:]); err != nil || greeting[0] != 5 {
		return
	}
	methods := make([]byte, int(greeting[1]))
	if _, err := io.ReadFull(conn, methods); err != nil {
		return
	}
	noAuth := false
	for _, method := range methods {
		noAuth = noAuth || method == 0
	}
	if !noAuth {
		_, _ = conn.Write([]byte{5, 255})
		return
	}
	if _, err := conn.Write([]byte{5, 0}); err != nil {
		return
	}
	var request [10]byte
	if _, err := io.ReadFull(conn, request[:]); err != nil || request[0] != 5 || request[2] != 0 || request[3] != 1 {
		return
	}
	if request[1] == 1 {
		if request[4] != 198 || request[5] != 18 || request[6] != 0 || request[7] != 9 || binary.BigEndian.Uint16(request[8:]) != 12345 {
			return
		}
		if _, err := conn.Write([]byte{5, 0, 0, 1, 127, 0, 0, 1, 0, 0}); err != nil {
			return
		}
		_, _ = io.Copy(conn, conn)
		return
	}
	if request[1] != 3 {
		return
	}
	udp, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		return
	}
	defer udp.Close()
	port := udp.LocalAddr().(*net.UDPAddr).Port
	if _, err = conn.Write([]byte{5, 0, 0, 1, 127, 0, 0, 1, byte(port >> 8), byte(port)}); err != nil {
		return
	}
	done := make(chan struct{})
	go func() {
		defer close(done)
		packet := make([]byte, 65535)
		for {
			n, peer, err := udp.ReadFromUDP(packet)
			if err != nil {
				return
			}
			if n < 10 || packet[0] != 0 || packet[1] != 0 || packet[2] != 0 || packet[3] != 1 ||
				packet[4] != 198 || packet[5] != 18 || packet[6] != 0 || packet[7] != 9 || binary.BigEndian.Uint16(packet[8:10]) != 12345 {
				continue
			}
			_, _ = udp.WriteToUDP(packet[:n], peer)
		}
	}()
	_, _ = io.Copy(io.Discard, conn)
	_ = udp.Close()
	<-done
}
