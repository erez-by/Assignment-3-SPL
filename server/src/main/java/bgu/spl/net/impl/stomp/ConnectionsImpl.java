
package bgu.spl.net.impl.stomp;

import bgu.spl.net.srv.Connections;
import bgu.spl.net.srv.ConnectionHandler;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class ConnectionsImpl<T> implements Connections<T> {
    private final ConcurrentHashMap<Integer, ConnectionHandler<T>> ClientHandler = new ConcurrentHashMap<>();
    // using nested hash map for connection id -> subcrcription id
    public final ConcurrentHashMap<String, ConcurrentHashMap<Integer, Integer>> TopicToClient = new ConcurrentHashMap<>();
    // client to topic map for disconnecting
    public final ConcurrentHashMap<Integer, ConcurrentHashMap<String, Integer>> ClinetToTopic = new ConcurrentHashMap<>();
    // message id counter
    private static AtomicInteger messageIdCounter = new AtomicInteger(0);

    @Override
    public boolean send(int connectionId, T msg) {
        ConnectionHandler<T> handler = ClientHandler.get(connectionId);
        if (handler != null) {
            handler.send(msg);
            return true;
        }
        return false;

    }

    @Override
    public void send(String channel, T msg) {
        ConcurrentHashMap<Integer, Integer> subscribers = TopicToClient.get(channel);
        if (subscribers != null) {
            int msgId = messageIdCounter.getAndIncrement();
            // for each subscriber send the message with the subscription id and message id
            for (Integer Id : subscribers.keySet()) {
                // getting the subscription id
                Integer subId = subscribers.get(Id);
                // creating the message string
                String msgString = "MESSAGE\nsubscription:" + subId + "\nmessage-id:" + msgId + "\ndestination:"
                        + channel + "\n\n" + msg + "\u0000";
                send(Id, (T) msgString);
            }
        }

    }

    @Override
    public void disconnect(int connectionId) {
        // getting the nested hash map of the topics the client is subscribe to
        ConcurrentHashMap<String, Integer> Topics = ClinetToTopic.get(connectionId);
        // removes the cliewnt from the handler map
        ClientHandler.remove(connectionId);
        // for every topic removes the client from the topics
        if (Topics != null) {
            for (String topic : Topics.keySet()) {
                ConcurrentHashMap<Integer, Integer> topics = TopicToClient.get(topic);
                if (topics != null) {
                    topics.remove(connectionId);
                }

            }
        }
        // removes from the clientTopic list
        ClinetToTopic.remove(connectionId);

    }

    public void disconnectAll() {
        for (Integer id : ClientHandler.keySet()) {
            disconnect(id);
        }
    }

    public void connect(int connectionId, ConnectionHandler<T> handler) {
        if (handler != null) {
            ClientHandler.put(connectionId, handler);
        }
    }

    public void subscribe(int connectionId, int subId, String channel) {
        TopicToClient.computeIfAbsent(channel, a -> new ConcurrentHashMap<>())
                .put(connectionId, subId);
        ClinetToTopic.computeIfAbsent(connectionId, a -> new ConcurrentHashMap<>())
                .put(channel, subId);
    }

    public void unsubscribe(int connectionId, String channel) {
        if (channel != null) {
            ConcurrentHashMap<Integer, Integer> clients = TopicToClient.get(channel);
            ConcurrentHashMap<String, Integer> topics = ClinetToTopic.get(connectionId);
            if (clients != null) {
                clients.remove(connectionId);
            }
            if (topics != null) {
                topics.remove(channel);
            }

        }
    }

    // method to get topic by subscription id for a specific client
    public String getTopicBySubscriptionId(int connectionId, int subId) {
        ConcurrentHashMap<String, Integer> clientSubscriptions = ClinetToTopic.get(connectionId);
        if (clientSubscriptions != null) {
            for (Map.Entry<String, Integer> entry : clientSubscriptions.entrySet()) {
                if (entry.getValue().equals(subId)) {
                    return entry.getKey();
                }
            }
        }
        return null;
    }

}