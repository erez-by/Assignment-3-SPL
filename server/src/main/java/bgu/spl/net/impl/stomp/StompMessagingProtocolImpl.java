package bgu.spl.net.impl.stomp;

import bgu.spl.net.api.StompMessagingProtocol;
import bgu.spl.net.srv.Connections;
import bgu.spl.net.impl.stomp.ConnectionsImpl;
import java.util.Map;
import java.util.HashMap;
import bgu.spl.net.impl.data.LoginStatus;
import bgu.spl.net.impl.data.Database;

public class StompMessagingProtocolImpl implements StompMessagingProtocol<String> {
    private int connectionId;
    private boolean shouldTerminate = false;
    private boolean loggedIn = false;
    private Connections<String> connections;
    private Database database;
    String username;

    @Override
    public void start(int connectionId, Connections<String> connections) {
        this.connectionId = connectionId;
        this.connections = connections;
        this.database = Database.getInstance();
    }

    @Override
    public void process(String message) {

        // Spliting the message to commands strings array
        String[] parts = message.split("\n\n", 2);
        String headerPart = parts[0];
        String body = parts.length > 1 ? parts[1] : "";
        // further spliting the header part to lines
        String[] lines = headerPart.split("\n");
        if (lines.length == 0) {
            sendError("Empty Message", "The received message is empty.", new HashMap<>()); 
            return;
        }
        // Saving the first string command and using trim to delete unnecessary spaces
        String command = lines[0].trim();

        // Parsing headers into a map
        Map<String, String> headers = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) {
                break; // End of headers and start of body
            }
            String[] headerParts = line.split(":", 2);
            if (headerParts.length == 2) {
                headers.put(headerParts[0].trim(), headerParts[1].trim());
            }
        }

        // Handling commands
        switch (command) {
            case "CONNECT":
                Connect(headers);
                break;
            case "SEND":
                Send(headers, body);
                break;
            case "SUBSCRIBE":
                Subscribe(headers);
                break;
            case "UNSUBSCRIBE":
                Unsubscribe(headers);
                break;
            case "DISCONNECT":
                Disconnect(headers);
                break;
            default:
                // Returning an error in a case of unknown command
                sendError("Unknown Command", "The command " + command + " is not supported.", headers);
                break;
        }
    }

    @Override
    public boolean shouldTerminate() {
        return shouldTerminate;
    }

    private void Connect(Map<String, String> headers) {
        String acceptVersion = headers.get("accept-version");
        String login = headers.get("login");
        String passcode = headers.get("passcode");
        username = login;

        // First we check if the headers are valid
        if (login == null || passcode == null) {
            sendError("Missing Headers", "One or more required headers are missing in the CONNECT frame.", headers);
            return;
        }
        if (acceptVersion == null || acceptVersion.contains("1.2") == false) {
            sendError("Unsupported Version", "The server only supports STOMP version 1.2.", headers);
            return;
        }
        // Checking login status from the database and acting accordingly
        LoginStatus status = Database.getInstance().login(connectionId, login, passcode);
        if (status == LoginStatus.CLIENT_ALREADY_CONNECTED || status == LoginStatus.ALREADY_LOGGED_IN) {
            sendError("Already Connected", "User already logged in.", headers);
            return;
        } 
        if (status == LoginStatus.WRONG_PASSWORD) {
             sendError("Wrong Password", "Password does not match.", headers);
             return;
        }
        if (status == LoginStatus.LOGGED_IN_SUCCESSFULLY || status == LoginStatus.ADDED_NEW_USER) {
            this.loggedIn = true;
            this.username = login;
            connections.send(connectionId, "CONNECTED\nversion:1.2\nsession-id:" + connectionId + "\n\n");
            return;
        }
    }

    private void Disconnect(Map<String, String> headers) {
        // Checking if the client is logged in
        if (loggedIn == false) {
            sendError("Not Logged In", "You must be logged in to disconnect.", headers);
            return;
        }
        // Sending receipt if present
        String receiptId = headers.get("receipt");
        if (receiptId != null) {
            connections.send(connectionId, "RECEIPT\nreceipt-id:" + receiptId + "\n\n");
        }
        // Logging out from the database
        Database.getInstance().logout(connectionId);

        connections.disconnect(connectionId);
        this.shouldTerminate = true;

    }

    private void Send(Map<String, String> headers, String body) {
        // Checking if the client is logged in
        if (loggedIn == false) {
            sendError("Not Logged In", "You must be logged in to send messages.", headers);
            return;
        }
        // Checking if the destination header is present
        String destination = headers.get("destination");
        if (destination == null) {
            sendError("Missing Destination", "The destination header is required.", headers);
            return;
        }
        // Checking if the client is subscribed to the destination 
        ConnectionsImpl<String> impl = (ConnectionsImpl<String>) connections;
        if (!impl.TopicToClient.containsKey(destination) || !impl.TopicToClient.get(destination).containsKey(connectionId)) {
            sendError("Not subscribed", "You are not subscribed to the destination: " + destination, headers);
            return;
        }
        // Sending the message to all subscribers
        connections.send(destination, body);

        // Sending receipt if present
        String receiptId = headers.get("receipt");
        if (receiptId != null) {
            connections.send(connectionId, "RECEIPT\nreceipt-id:" + receiptId + "\n\n");
        }

        String filename = headers.get("file name");
        // Tracking file upload in the database if applicable
        if (filename != null) {
            database.trackFileUpload(username, filename, destination);
        }


    }

    private void Subscribe(Map<String, String> headers){
      //Checking if the client is logged in
      if (loggedIn == false) {
        sendError("Not Logged In", "You must be logged in to subscribe to topics.", headers);
        return;
      }
      //Checking if the required headers are present  
      String destination = headers.get("destination");
      String idStr = headers.get("id");
      if (destination == null || idStr == null) {
        sendError("Missing Headers", "Both destination and id headers are required for SUBSCRIBE.", headers);
        return;
      }
      //subscribing the client to the topic
      int subId;
      try {
        subId = Integer.parseInt(idStr);
        connections.subscribe(connectionId, subId, destination);
      } catch (NumberFormatException e) {
        sendError("Invalid Subscription ID", "The subscription id must be an integer.", headers);
        return;
      }
      //Sending receipt if present
      String receiptId = headers.get("receipt");
      if (receiptId != null) {  
        connections.send(connectionId, "RECEIPT\nreceipt-id:" + receiptId + "\n\n");
      }
    }

    private void Unsubscribe(Map<String, String> headers) {
        // Checking if the client is logged in
        if (loggedIn == false) {
            sendError("Not Logged In", "You must be logged in to unsubscribe from topics.", headers);
            return;
        }
        // Checking if the required headers are present
        String idStr = headers.get("id");
        if (idStr == null) {
            sendError("Missing Headers", "The id header is required for UNSUBSCRIBE.", headers);
        }
        // Unsubscribing the client from the topic
        int subId;
        try {
            subId = Integer.parseInt(idStr);
            ConnectionsImpl<String> impl = (ConnectionsImpl<String>) connections;
            String channel = impl.getTopicBySubscriptionId(connectionId, subId);
            if (channel != null) {
                connections.unsubscribe(connectionId, channel);
            } else {
                sendError("Not Subscribed", "You are not subscribed with subscription id: " + subId, headers);
                return;
            }
        } catch (NumberFormatException e) {
            sendError("Invalid Subscription ID", "The subscription id must be an integer.", headers);
            return;
        }
        // Sending receipt if present
        String receiptId = headers.get("receipt");
        if (receiptId != null) {
            connections.send(connectionId, "RECEIPT\nreceipt-id:" + receiptId + "\n\n");
        }
    }

    // Sends an error message to the client
    private void sendError(String message, String details, Map<String, String> headers) {
        // Building the error message
        StringBuilder sb = new StringBuilder();
        sb.append("ERROR\n");
        // Adding receipt id if present
        String receiptId = headers.get("receipt");
        if (receiptId != null) {
            sb.append("receipt-id:").append(receiptId).append("\n");
        }
        // Adding message and details
        sb.append("message:").append(message).append("\n");
        sb.append("\n");
        sb.append(details).append("\n");

        // Sending the error message
        if (connections != null)
            connections.send(connectionId, sb.toString());
        // Terminating the connection
        this.shouldTerminate = true;
    }

}