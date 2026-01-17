package bgu.spl.net.impl.stomp;

import bgu.spl.net.api.StompMessagingProtocol;
import bgu.spl.net.srv.Connections;
import java.util.Map;
import java.util.HashMap;

import bgu.spl.net.impl.data.LoginStatus;
import bgu.spl.net.impl.data.Database;


public class StompMessagingProtocolImpl implements StompMessagingProtocol<String>{
    private int connectionId;
    private boolean shouldTerminate = false;
    private boolean loggedIn = false;
    private Connections<String> connections; 

    @Override
    public void start(int connectionId, Connections<String> connections){
        this.connectionId = connectionId;
        this.connections = connections;
    }

    @Override
    public void process(String message){

        //Spliting the message to commands strings array
        String[] lines = message.split("\n");
        if (lines.length == 0) {
            sendError("Empty Message", "The received message is empty.");
            return;
        }
         //Saving the first string command and using trim to delete unnecessary spaces
        String command = lines[0].trim();

        //Parsing headers into a map
        Map<String, String> headers = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                break; // End of headers and start of body
            }
            String[] headerParts = line.split(":", 2);
            if (headerParts.length == 2) {
                headers.put(headerParts[0].trim(), headerParts[1].trim());
            }
        }

        // Extracting body
        int bodyStartIndex = -1;
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].trim().isEmpty()) {
                bodyStartIndex = i + 1;
                break;
            }
        }
        String body = "";
        if (bodyStartIndex != -1 && bodyStartIndex < lines.length) {
            StringBuilder bodyBuilder = new StringBuilder();
            for (int i = bodyStartIndex; i < lines.length; i++) {
                //removing null characters from the body lines
                String line = lines[i].replace("\u0000", "");
                bodyBuilder.append(line);
                if (i < lines.length - 1) {
                    bodyBuilder.append("\n"); //Preserving line breaks in the body
                }
            }
            body = bodyBuilder.toString().trim();
        }

        //Handling commands
        switch(command) {
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
            //Returning an error in a case of unknown command
                sendError("Unknown Command", "The command " + command + " is not supported.");
                break;
        }
    }

    @Override
    public boolean shouldTerminate(){
        return shouldTerminate;
    }

    private void Connect(Map<String, String> headers){
        String acceptVersion = headers.get("accept-version");
        String login = headers.get("login");
        String passcode = headers.get("passcode");

        //First we check if the headers are valid
        if (login == null || passcode == null) {
            sendError("Missing Headers", "One or more required headers are missing in the CONNECT frame.");
            return;
        }
        if (acceptVersion == null || acceptVersion.contains("1.2") == false) {
            sendError("Unsupported Version", "The server only supports STOMP version 1.2.");
            return; 
        }

        LoginStatus status = Database.getInstance().login(connectionId, login, passcode);



        if (status == LoginStatus.LOGGED_IN_SUCCESSFULLY || status == LoginStatus.ADDED_NEW_USER) {
            loggedIn = true;
            connections.send(connectionId, "CONNECTED\nsession-id:" + connectionId + "\n\n");
        } else {
            sendError("Login Failed", "Invalid login or passcode.");
        }

        //Unfinished part - need to handle other login status cases!!!!
        




    
    }

    private void Send(Map<String, String> headers, String body){
      if (loggedIn == false) {
        sendError("Not Logged In", "You must be logged in to send messages.");
        return;
      }
      //Checking if the destination header is present
      String destination = headers.get("destination");
      if (destination == null) {
        sendError("Missing Destination", "The destination header is required.");
        return;
      }
      //Checking if the client is subscribed to the destination :)
      ConnectionsImpl<String> impl = (ConnectionsImpl<String>) connections;
      if (!impl.TopicToClient.containsKey(destination)) {
        sendError("Not subscribed", "You are not subscribed to the destination: " + destination);
        return;
      }
      //Sending the message to all subscribers
      connections.send(destination, body);

        //Sending receipt if present
      String receiptId = headers.get("receipt");
      if (receiptId != null) {
        connections.send(connectionId, "RECEIPT\nreceipt-id:" + receiptId + "\n\n");
      }

    }

    private void Subscribe(Map<String, String> headers){
      //Checking if the client is logged in
      if (loggedIn == false) {
        sendError("Not Logged In", "You must be logged in to subscribe to topics.");
        return;
      }
      //Checking if the required headers are present  
      String destination = headers.get("destination");
      String idStr = headers.get("id");
      if (destination == null || idStr == null) {
        sendError("Missing Headers", "Both destination and id headers are required for SUBSCRIBE.");
        return;
      }
      //subscribing the client to the topic
      int subId;
      try {
        subId = Integer.parseInt(idStr);
        connections.subscribe(connectionId, subId, destination);
      } catch (NumberFormatException e) {
        sendError("Invalid Subscription ID", "The subscription id must be an integer.");
        return;
      }
      //Sending receipt if present
      String receiptId = headers.get("receipt");
      if (receiptId != null) {  
        connections.send(connectionId, "RECEIPT\nreceipt-id:" + receiptId + "\n\n");
      
    }

    private void Unsubscribe(Map<String, String> headers){
        //Checking if the client is logged in
      if (loggedIn == false) {
        sendError("Not Logged In", "You must be logged in to unsubscribe from topics.");
        return;         
      }
        //Checking if the required headers are present
        String idStr = headers.get("id");
        if (idStr == null) {
            sendError("Missing Headers", "The id header is required for UNSUBSCRIBE."); 
        }
        //Unsubscribing the client from the topic
        int subId;
        try {
            subId = Integer.parseInt(idStr);
            ConnectionsImpl<String> impl = (ConnectionsImpl<String>) connections;
            String channel = impl.getTopicBySubscriptionId(connectionId, subId);
            if (channel != null) {
                connections.unsubscribe(connectionId, channel);
            }
        } catch (NumberFormatException e) {
            sendError("Invalid Subscription ID", "The subscription id must be an integer.");
            return;
        }
        //Sending receipt if present
        String receiptId = headers.get("receipt");     
        if (receiptId != null) {  
            connections.send(connectionId, "RECEIPT\nreceipt-id:" + receiptId + "\n\n");
        }
    }

    private void Disconnect(Map<String, String> headers){
        //Checking if the client is logged in
      if (loggedIn == false) {
        sendError("Not Logged In", "You must be logged in to disconnect.");
        return;         
      }
        //Sending receipt if present
        String receiptId = headers.get("receipt");     
        if (receiptId != null) {  
            connections.send(connectionId, "RECEIPT\nreceipt-id:" + receiptId + "\n\n");
        }
        //Disconnecting the client
        connections.disconnect(connectionId);
        this.shouldTerminate = true;

    }
    
    //Sends an error message to the client
    private void sendError(String message, String details) {
        StringBuilder sb = new StringBuilder();
        sb.append("ERROR\n");
        sb.append("message:").append(message).append("\n");
        sb.append(details).append("\n");
        sb.append('\0');
        if (connections != null)
            connections.send(connectionId, sb.toString());
        this.shouldTerminate = true;
    }

    
}