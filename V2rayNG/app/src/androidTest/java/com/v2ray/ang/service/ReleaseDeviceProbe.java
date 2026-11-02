package com.v2ray.ang.service;

import org.json.JSONObject;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Runs as shell UID against a synthetic echo endpoint, never a real website. */
public final class ReleaseDeviceProbe {
    public static void main(String[] args) throws Exception {
        if (args[0].equals("http")) {
            int port = Integer.parseInt(args[1]);
            if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid audit proxy port");
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 3000);
                socket.setSoTimeout(5000);
                String credentials = Base64.encodeToString("audit-local:audit-test-only".getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
                String request = "CONNECT 198.18.0.9:12345 HTTP/1.1\r\nHost: 198.18.0.9:12345\r\nProxy-Authorization: Basic " + credentials + "\r\n\r\n";
                socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
                ByteArrayOutputStream header = new ByteArrayOutputStream();
                String response;
                do {
                    int next = socket.getInputStream().read();
                    if (next < 0 || header.size() >= 8192) throw new AssertionError("Incomplete proxy reply");
                    header.write(next);
                    response = header.toString("US-ASCII");
                } while (!response.endsWith("\r\n\r\n"));
                if (!response.startsWith("HTTP/1.1 200 ") && !response.startsWith("HTTP/1.0 200 ")) {
                    throw new AssertionError("Audit HTTP CONNECT refused");
                }
                byte[] sent = "tun-smoke".getBytes(StandardCharsets.UTF_8);
                socket.getOutputStream().write(sent);
                byte[] received = new byte[sent.length];
                readFully(socket, received, System.nanoTime() + TimeUnit.SECONDS.toNanos(5));
                if (!Arrays.equals(sent, received)) throw new AssertionError("Proxy payload changed");
                System.out.print("tun-smoke");
            }
            return;
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("198.18.0.9", 12345), 3000);
            socket.setTcpNoDelay(true);
            if (args[0].equals("latency")) {
                byte[] sent = new byte[256];
                for (int i = 0; i < sent.length; i++) sent[i] = (byte) (i * 31);
                byte[] received = new byte[sent.length];
                long[] samples = new long[500];
                for (int i = -20; i < samples.length; i++) {
                    long started = System.nanoTime();
                    socket.getOutputStream().write(sent);
                    readFully(socket, received, started + TimeUnit.SECONDS.toNanos(5));
                    if (!Arrays.equals(sent, received)) throw new AssertionError("Echo payload changed");
                    if (i >= 0) samples[i] = System.nanoTime() - started;
                }
                Arrays.sort(samples);
                JSONObject result = new JSONObject();
                result.put("kind", "latency");
                result.put("samples", samples.length);
                result.put("p50_us", samples[249] / 1000.0);
                result.put("p95_us", samples[474] / 1000.0);
                result.put("p99_us", samples[494] / 1000.0);
                result.put("max_us", samples[499] / 1000.0);
                System.out.print(result);
                return;
            }
            if (!args[0].equals("bulk")) throw new IllegalArgumentException("Unknown probe");
            byte[] sent = new byte[4 * 1024 * 1024];
            for (int i = 0; i < sent.length; i++) sent[i] = (byte) (i * 31 + (i >> 11));
            byte[] received = new byte[sent.length];
            ExecutorService uploader = Executors.newSingleThreadExecutor();
            try {
                transfer(socket, uploader, sent, received);
                long started = System.nanoTime();
                long rounds = 0;
                do {
                    transfer(socket, uploader, sent, received);
                    rounds++;
                } while (System.nanoTime() - started < TimeUnit.SECONDS.toNanos(3));
                long elapsed = System.nanoTime() - started;
                long bytes = rounds * sent.length * 2L;
                JSONObject result = new JSONObject();
                result.put("kind", "bulk");
                result.put("bytes", bytes);
                result.put("elapsed_ms", elapsed / 1e6);
                result.put("duplex_MB_s", bytes * 1000.0 / elapsed);
                System.out.print(result);
            } finally {
                socket.close();
                uploader.shutdownNow();
                uploader.awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    private static void transfer(Socket socket, ExecutorService uploader, byte[] sent, byte[] received) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        Future<?> written = uploader.submit(() -> {
            try {
                socket.getOutputStream().write(sent);
            } catch (java.io.IOException error) {
                throw new java.io.UncheckedIOException(error);
            }
        });
        readFully(socket, received, deadline);
        written.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        if (!Arrays.equals(sent, received)) throw new AssertionError("Bulk payload changed");
    }

    private static void readFully(Socket socket, byte[] bytes, long deadline) throws Exception {
        DataInputStream input = new DataInputStream(socket.getInputStream());
        int offset = 0;
        while (offset < bytes.length) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new java.net.SocketTimeoutException("Probe deadline");
            socket.setSoTimeout((int) Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)));
            int n = input.read(bytes, offset, bytes.length - offset);
            if (n < 0) throw new java.io.EOFException("Incomplete echo");
            offset += n;
        }
    }
}
