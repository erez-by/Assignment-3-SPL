package bgu.spl.net.srv;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ConnectionsImpl<T> implements Connections<T> {

    private final ConcurrentHashMap<Integer, ConnectionHandler<T>> handlersById;
    private final ConcurrentHashMap<String, ConcurrentHashMap<Integer, Integer>> subsByTopic;

    public ConnectionsImpl() {
        handlersById = new ConcurrentHashMap<>();
        subsByTopic = new ConcurrentHashMap<>();
    }

    // send
    @Override
    public boolean send(int connectionId, T msg) {
        ConnectionHandler<T> handler = handlersById.get(connectionId);
        if (handler == null)
            return false;

        handler.send(msg);
        return true;
    }

    @Override
    public void send(String destination, T msg) {
        ConcurrentHashMap<Integer, Integer> topicMap = subsByTopic.get(destination);

        log("Broadcast request for '" + destination + "'");

        if (topicMap == null || topicMap.isEmpty())
            return;

        String frameHeader = buildHeader(destination);
        deliverToSubscribers(topicMap, frameHeader, msg);
    }

    private void deliverToSubscribers(ConcurrentHashMap<Integer, Integer> topicMap,
                                      String header,
                                      T body) {

        for (Integer connId : new HashSet<>(topicMap.keySet())) {
            Integer subId = topicMap.get(connId);
            if (subId == null)
                continue;

            String fullFrame = String.format(header, subId) + body;
            send(connId, (T) fullFrame);
        }
    }

    private String buildHeader(String destination) {
        return "MESSAGE\n" +
               "subscription:%d\n" +
               "message-id:" + UUID.randomUUID() + "\n" +
               "destination:" + destination + "\n\n";
    }

// add connection
    public void addConnection(int connectionId, ConnectionHandler<T> handler) {
        handlersById.put(connectionId, handler);
    }

    @Override
    public void disconnect(int connectionId) {
        if (handlersById.remove(connectionId) == null) {
            logWarn("Disconnect ignored for unknown client " + connectionId);
            return;
        }

        int removed = clearClientSubscriptions(connectionId);
        log("Client " + connectionId + " removed from " + removed + " topic(s)");
    }

    private int clearClientSubscriptions(int connectionId) {
        int count = 0;

        for (String topic : subsByTopic.keySet()) {
            ConcurrentHashMap<Integer, Integer> map = subsByTopic.get(topic);
            if (map != null && map.remove(connectionId) != null) {
                count++;
            }
        }
        return count;
    }

// subscribe 
    @Override
    public void subscribe(String topic, int connectionId, int subscriptionId) {
        subsByTopic
                .computeIfAbsent(topic, t -> new ConcurrentHashMap<>())
                .put(connectionId, subscriptionId);
    }

    @Override
    public void unsubscribe(String topic, int connectionId) {
        ConcurrentHashMap<Integer, Integer> topicMap = subsByTopic.get(topic);

        if (topicMap == null) {
            logWarn("Unsubscribe failed – topic not found: " + topic);
            return;
        }

        if (topicMap.remove(connectionId) == null) {
            logWarn("Client " + connectionId + " was not subscribed to " + topic);
        }
    }
    // login
    private void log(String msg) {
        System.out.println("[Connections] " + msg);
    }

    private void logWarn(String msg) {
        System.out.println("[Connections][WARN] " + msg);
    }
}