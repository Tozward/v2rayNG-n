package com.v2ray.ang.service;

import java.io.DataInputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** app_process runs this test-only probe as shell UID, which the VPN does capture. */
public final class TunProbe {
    public static void main(String[] args) throws Exception {
        byte[] payload = "tun-smoke".getBytes(StandardCharsets.UTF_8);
        InetSocketAddress destination = new InetSocketAddress("198.18.0.9", 12345);
        byte[] received;
        if (args[0].equals("udp")) {
            try (DatagramSocket socket = new DatagramSocket()) {
                socket.setSoTimeout(1000);
                socket.connect(destination);
                socket.send(new DatagramPacket(payload, payload.length));
                DatagramPacket reply = new DatagramPacket(new byte[payload.length + 1], payload.length + 1);
                socket.receive(reply);
                received = Arrays.copyOf(reply.getData(), reply.getLength());
            }
        } else {
            try (Socket socket = new Socket()) {
                socket.connect(destination, 1000);
                socket.setSoTimeout(1000);
                socket.getOutputStream().write(payload);
                received = new byte[payload.length];
                new DataInputStream(socket.getInputStream()).readFully(received);
            }
        }
        if (!Arrays.equals(payload, received)) throw new AssertionError("Payload changed");
        new FileOutputStream(FileDescriptor.out).write(received);
    }
}
