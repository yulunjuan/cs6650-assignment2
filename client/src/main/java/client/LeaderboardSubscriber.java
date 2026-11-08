package client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Instant;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

public class LeaderboardSubscriber implements WebSocket.Listener {
    private final HttpClient client;
    private final URI uri;
    private final int resortID;

    private volatile WebSocket webSocket;
    private volatile boolean running = true;
    private volatile long firstConnectMs = -1;
    private volatile String lastMessage;

    private final AtomicInteger updatesReceived = new AtomicInteger();
    private final AtomicInteger reconnections = new AtomicInteger();
    private final ConcurrentLinkedQueue<Long> pushLatenciesMs = new ConcurrentLinkedQueue<>();

    // Listener callbacks for one WebSocket are invoked sequentially, so a plain StringBuilder is safe
    private final StringBuilder buffer = new StringBuilder();

    public LeaderboardSubscriber(HttpClient client, String wsBaseUrl, int resortID) {
        this.client = client;
        this.resortID = resortID;
        this.uri = URI.create(wsBaseUrl + "/leaderboard/" + resortID);
    }

    public void connect() {
        long start = System.currentTimeMillis();
        webSocket = client.newWebSocketBuilder().buildAsync(uri, this).join();
        if (firstConnectMs < 0) {
            firstConnectMs = System.currentTimeMillis() - start;
        }
    }

    @Override
    public void onOpen(WebSocket ws) {
        ws.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
        buffer.append(data);
        if (last) {
            handleMessage(buffer.toString(), System.currentTimeMillis());
            buffer.setLength(0);
        }
        // Must request the next message explicitly, otherwise no more messages are delivered
        ws.request(1);
        return null;
    }

    private void handleMessage(String json, long receiveTimeMs) {
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            long serverTimeMs = Instant.parse(obj.get("serverTimestamp").getAsString()).toEpochMilli();
            pushLatenciesMs.add(receiveTimeMs - serverTimeMs);
            updatesReceived.incrementAndGet();
            lastMessage = json;
        } catch (Exception e) {
            System.out.println("Resort " + resortID + ": malformed leaderboard message");
        }
    }

    @Override
    public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
        scheduleReconnect();
        return null;
    }

    @Override
    public void onError(WebSocket ws, Throwable error) {
        System.out.println("Resort " + resortID + " WebSocket error: " + error.getMessage());
        scheduleReconnect();
    }

    private void scheduleReconnect() {
        if (!running) {
            return;
        }
        reconnections.incrementAndGet();
        new Thread(() -> {
            for (int attempt = 0; running && attempt < Config.MAX_RETRIES; attempt++) {
                try {
                    Thread.sleep(500L << attempt);
                    connect();
                    return;
                } catch (Exception e) {
                    System.out.println("Resort " + resortID + " reconnect attempt " + (attempt + 1) + " failed");
                }
            }
        }).start();
    }

    public void close() {
        running = false;  // set first so onClose does not trigger a reconnect
        try {
            if (webSocket != null) {
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
            }
        } catch (Exception ignored) {
        }
    }

    public int getResortID() { return resortID; }
    public int getUpdatesReceived() { return updatesReceived.get(); }
    public int getReconnections() { return reconnections.get(); }
    public long getFirstConnectMs() { return firstConnectMs; }
    public String getLastMessage() { return lastMessage; }
    public ConcurrentLinkedQueue<Long> getPushLatenciesMs() { return pushLatenciesMs; }
}