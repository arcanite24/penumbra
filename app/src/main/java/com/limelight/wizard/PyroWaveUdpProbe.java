package com.limelight.wizard;

import android.os.Process;
import android.os.SystemClock;

import com.limelight.LimeLog;
import com.limelight.nvstream.http.NvHTTP;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * One run of Vibepollo's /pyrowave-udp-probe (PEN-10; protocol in Vibepollo's
 * docs/pyrowave-protocol.md). The client binds a UDP socket and asks over paired HTTPS for
 * kbps, port, packet size and a fresh 32-hex-digit token. With handshake=1 the host flushes the
 * response headers with X-PyroWave-Udp-Port and waits up to 1.5 s for the token from our socket,
 * which opens the path from both ends; it then sends 2 s of packets (token at bytes 0-31,
 * big-endian sequence at 32-35) and finishes the XML body with expected, sent and elapsedMs.
 * The host refuses while a stream is running.
 * <p>
 * Arrival times are taken in Java right after each receive, so the receiving thread's own
 * scheduling shows up as transit variation; the grade is therefore a little stricter than the
 * kernel-timestamped Mac client's.
 */
public final class PyroWaveUdpProbe implements PyroWaveUdpGrade.Probe {
    public static final int PACKET_SIZE = 1392;
    private static final int HANDSHAKE_MS = 1500;
    private static final int DRAIN_MS = 100;

    private final NvHTTP http;
    private final boolean handshake;
    private final SecureRandom random = new SecureRandom();

    public PyroWaveUdpProbe(NvHTTP http, boolean handshake) {
        this.http = http;
        this.handshake = handshake;
    }

    @Override
    public PyroWaveUdpGrade.Result run(int kbps, int fps, int paceKbps) throws InterruptedException {
        PyroWaveUdpGrade.Result failed = new PyroWaveUdpGrade.Result();
        failed.kbps = kbps;
        failed.fps = fps;
        failed.paceKbps = paceKbps;

        final int expected = PyroWaveUdpGrade.expectedPackets(kbps, PACKET_SIZE);
        final long[] arrivals = new long[expected];
        Arrays.fill(arrivals, -1);
        byte[] tokenBytes = new byte[16];
        random.nextBytes(tokenBytes);
        StringBuilder hex = new StringBuilder();
        for (byte b : tokenBytes) hex.append(String.format("%02x", b & 255));
        final String token = hex.toString();
        final byte[] tokenAscii = token.getBytes(StandardCharsets.US_ASCII);

        final InetAddress host;
        DatagramSocket socket = null;
        Thread receiver = null;
        final CountDownLatch firstPacket = new CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicBoolean running = new java.util.concurrent.atomic.AtomicBoolean(true);
        try {
            host = InetAddress.getByName(http.getHostAddress());
            socket = new DatagramSocket(null);
            socket.setReuseAddress(false);
            try {
                socket.setReceiveBufferSize(8 * 1024 * 1024);
            } catch (SocketException ignored) {
            }
            socket.bind(new InetSocketAddress(0));
            socket.setSoTimeout(50);
            final DatagramSocket sock = socket;
            final long startNs = System.nanoTime();
            receiver = new Thread(() -> {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);
                byte[] buf = new byte[2048];
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                int wanted = PACKET_SIZE + 48;
                while (running.get()) {
                    try {
                        packet.setLength(buf.length);
                        sock.receive(packet);
                    } catch (SocketTimeoutException e) {
                        continue;
                    } catch (Exception e) {
                        break;
                    }
                    long now = System.nanoTime();
                    if (packet.getLength() != wanted || !host.equals(packet.getAddress())) continue;
                    boolean match = true;
                    for (int i = 0; i < 32; i++) {
                        if (buf[i] != tokenAscii[i]) { match = false; break; }
                    }
                    if (!match) continue;
                    int seq = ((buf[32] & 255) << 24) | ((buf[33] & 255) << 16) | ((buf[34] & 255) << 8) | (buf[35] & 255);
                    if (seq >= 0 && seq < arrivals.length && arrivals[seq] < 0) {
                        arrivals[seq] = (now - startNs) / 1000;
                    }
                    firstPacket.countDown();
                }
            }, "penumbra-udp-probe");
            receiver.start();

            String query = "kbps=" + kbps + "&port=" + socket.getLocalPort() + "&packetsize=" + PACKET_SIZE
                    + "&token=" + token + (handshake ? "&handshake=1" : "")
                    + (fps > 0 ? "&fps=" + fps + "&pacekbps=" + paceKbps : "");
            String body;
            try (Response response = http.openHttpsStreaming("pyrowave-udp-probe", query, 6000)) {
                if (handshake) {
                    String portHeader = response.header("X-PyroWave-Udp-Port");
                    int port = 0;
                    try {
                        port = portHeader != null ? Integer.parseInt(portHeader.trim()) : 0;
                    } catch (NumberFormatException ignored) {
                    }
                    if (port < 1 || port > 65535) {
                        failed.error = "The host didn't start the UDP handshake";
                        return failed;
                    }
                    // Send the token from our receiving socket until the host's packets arrive
                    DatagramPacket hello = new DatagramPacket(tokenAscii, tokenAscii.length, host, port);
                    long deadline = SystemClock.uptimeMillis() + HANDSHAKE_MS;
                    while (SystemClock.uptimeMillis() < deadline) {
                        socket.send(hello);
                        if (firstPacket.await(100, TimeUnit.MILLISECONDS)) break;
                    }
                }
                ResponseBody rb = response.body();
                body = rb != null ? rb.string() : "";
            }
            // A short drain for reordered tail packets; their delay still counts
            Thread.sleep(DRAIN_MS);
            running.set(false);
            receiver.join(500);

            String status = NvHTTP.getXmlField(body, "expected"); // also checks status_code
            int hostExpected = status != null ? Integer.parseInt(status.trim()) : -1;
            String sentStr = NvHTTP.getXmlField(body, "sent");
            String elapsedStr = NvHTTP.getXmlField(body, "elapsedMs");
            if (hostExpected != expected || sentStr == null || elapsedStr == null) {
                failed.error = "Unexpected probe reply (expected " + hostExpected + ", planned " + expected + ")";
                return failed;
            }
            PyroWaveUdpGrade.Result r = PyroWaveUdpGrade.grade(kbps, fps, paceKbps, expected,
                    Integer.parseInt(sentStr.trim()), Double.parseDouble(elapsedStr.trim()), arrivals);
            LimeLog.info("PyroWave UDP probe: " + r);
            return r;
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            failed.error = e.getMessage() != null ? e.getMessage() : e.toString();
            LimeLog.warning("PyroWave UDP probe at " + kbps + " kbps failed: " + failed.error);
            return failed;
        } finally {
            running.set(false);
            if (socket != null) socket.close();
            if (receiver != null) {
                try {
                    receiver.join(200);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
