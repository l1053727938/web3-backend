package com.ray.web3.util;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * HTTP CONNECT 隧道工具：为 wss 连接穿透 HTTP 代理（如 Clash 127.0.0.1:7890）
 * <p>
 * 之所以要手写隧道：web3j 的 WebSocket 底层是 Java-WebSocket，其 setProxy 内部只做
 * {@code new Socket(new Proxy(HTTP, addr)).connect(目标:443)}，而 JDK 的高层 Socket 对 HTTP 代理
 * 不会发送 CONNECT 请求（等价于把 TLS 字节直接怼给代理端口），握手必被对端中断；
 * SOCKS 类型同样不被 Clash 的 mixed 端口按预期处理。因此这里自行完成 CONNECT 握手，
 * 再把建立好的 SSLSocket 通过 WebSocketClient#setSocket 交给 Java-WebSocket 复用
 */
public final class ProxyTunnel {

    /** CONNECT 响应读取的兜底上限，防止代理返回异常数据时无限循环 */
    private static final int MAX_HEADER_BYTES = 8192;

    private ProxyTunnel() {
    }

    /**
     * 通过 HTTP 代理打开到 targetHost:targetPort 的 TLS 隧道
     *
     * @param proxyHost   代理地址
     * @param proxyPort   代理端口
     * @param targetHost  目标主机（不含协议前缀）
     * @param targetPort  目标端口
     * @param timeoutMs   连接与等待代理响应的超时（毫秒）
     * @return 已完成 TLS 握手的 SSLSocket，可直接交给 WebSocketClient#setSocket
     */
    public static SSLSocket open(String proxyHost, int proxyPort,
                                 String targetHost, int targetPort, int timeoutMs) throws IOException {
        Socket plain = new Socket();
        try {
            plain.connect(new InetSocketAddress(proxyHost, proxyPort), timeoutMs);
            sendConnectRequest(plain.getOutputStream(), targetHost, targetPort);
            awaitTunnelEstablished(plain, timeoutMs);

            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            // autoClose=true：关闭这个 SSLSocket 时底层隧道 socket 一并释放
            SSLSocket ssl = (SSLSocket) factory.createSocket(plain, targetHost, targetPort, true);
            try {
                SSLParameters parameters = ssl.getSSLParameters();
                // 显式指定 HTTPS 以启用 SNI 与证书校验
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                ssl.setSSLParameters(parameters);
                ssl.startHandshake();
            } catch (IOException | RuntimeException e) {
                ssl.close();
                throw e;
            }
            return ssl;
        } catch (IOException | RuntimeException e) {
            plain.close();
            throw e;
        }
    }

    private static void sendConnectRequest(OutputStream out, String targetHost, int targetPort)
            throws IOException {
        String request = "CONNECT " + targetHost + ":" + targetPort + " HTTP/1.1\r\n"
                + "Host: " + targetHost + ":" + targetPort + "\r\n\r\n";
        out.write(request.getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    /**
     * 读取代理返回的 CONNECT 响应；逐字节读到空行为止，
     * 不能用带缓冲的流，否则会吞掉紧随其后的 TLS 字节导致握手失败
     */
    private static void awaitTunnelEstablished(Socket socket, int timeoutMs) throws IOException {
        socket.setSoTimeout(timeoutMs);
        StringBuilder sb = new StringBuilder();
        int read = 0;
        int b;
        // 注意：不能对 socket 的流做 try-with-resources，关流会连带关掉隧道 socket
        InputStream in = socket.getInputStream();
        try {
            while (read++ < MAX_HEADER_BYTES && (b = in.read()) != -1) {
                sb.append((char) b);
                if (sb.toString().endsWith("\r\n\r\n")) {
                    break;
                }
            }
        } finally {
            socket.setSoTimeout(0);
        }
        String response = sb.toString();
        String statusLine = response.isEmpty() ? "" : response.split("\r\n")[0];
        // 兼容 HTTP/1.0 代理：状态行形如 "HTTP/1.0 200 Connection established"
        if (!statusLine.matches("^HTTP/1\\.\\d 200\\b.*")) {
            throw new IOException("CONNECT 隧道建立失败，代理响应: "
                    + (statusLine.isEmpty() ? "<空，代理无响应>" : statusLine));
        }
    }
}
