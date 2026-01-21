package bgu.spl.net.impl.stomp;

import bgu.spl.net.api.StompMessagingProtocol;
import bgu.spl.net.impl.data.Database;
import bgu.spl.net.impl.data.LoginStatus;
import bgu.spl.net.srv.Connections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class StompMessagingProtocolImpl implements StompMessagingProtocol<String> {

    private int ownerId;
    private Connections<String> connections;
    private boolean shouldTerminate = false;
    private boolean isAuthenticated = false;
    private String currentUser = null;
    
    // Map to track subscriptions: SubscriptionID -> Channel Name
    private final Map<Integer, String> activeSubscriptions = new ConcurrentHashMap<>();
    
    // Dispatcher map for commands
    private final Map<String, ProtocolAction> commandDispatch = new HashMap<>();

    public StompMessagingProtocolImpl() {
        // Initialize Command Dispatcher
        commandDispatch.put("CONNECT", this::handleConnect);
        commandDispatch.put("DISCONNECT", this::handleDisconnect);
        commandDispatch.put("SEND", this::handleSend);
        commandDispatch.put("SUBSCRIBE", this::handleSubscribe);
        commandDispatch.put("UNSUBSCRIBE", this::handleUnsubscribe);
    }

    @Override
    public void start(int connectionId, Connections<String> connections) {
        this.ownerId = connectionId;
        this.connections = connections;
    }

    @Override
    public void process(String rawMessage) {
        if (shouldTerminate) return;

        try {
            // 1. Parse the raw string into a structured object
            StompFrame frame = StompFrame.fromString(rawMessage);
            
            // 2. Dispatch to the correct handler
            ProtocolAction action = commandDispatch.get(frame.getCommand());
            if (action != null) {
                action.execute(frame);
            } else {
                sendErrorFrame("Unknown STOMP command: " + frame.getCommand(), frame.getHeaders());
            }

        } catch (Exception e) {
            sendErrorFrame("Malformed frame received", null);
        }
    }

    @Override
    public boolean shouldTerminate() {
        return shouldTerminate;
    }

    // --- Command Handlers ---

    private void handleConnect(StompFrame frame) {
        if (isAuthenticated) {
            sendErrorFrame("User already logged in", frame.getHeaders());
            return;
        }

        String login = frame.getHeader("login");
        String passcode = frame.getHeader("passcode");
        String version = frame.getHeader("accept-version");

        if (version == null || !version.contains("1.2")) {
            sendErrorFrame("Unsupported protocol version", frame.getHeaders());
            return;
        }

        LoginStatus status = Database.getInstance().login(ownerId, login, passcode);

        if (status == LoginStatus.LOGGED_IN_SUCCESSFULLY || status == LoginStatus.ADDED_NEW_USER) {
            isAuthenticated = true;
            currentUser = login;
            sendMessage("CONNECTED\nversion:1.2\n\n");
            System.out.println("Client " + ownerId + " connected as " + login);
        } else {
            // Map the specific DB error to a response
            String errorMsg = (status == LoginStatus.WRONG_PASSWORD) ? "Wrong password" : 
                              (status == LoginStatus.ALREADY_LOGGED_IN) ? "User already active" : 
                              "Login failed";
            sendErrorFrame(errorMsg, frame.getHeaders());
        }
    }

    private void handleDisconnect(StompFrame frame) {
        if (!validateSession(frame)) return;

        String receipt = frame.getHeader("receipt");
        if (receipt != null) {
            sendMessage("RECEIPT\nreceipt-id:" + receipt + "\n\n");
        }
        
        Database.getInstance().logout(ownerId);
        terminate();
    }

    private void handleSend(StompFrame frame) {
        if (!validateSession(frame)) return;

        String destination = frame.getHeader("destination");
        if (destination == null) {
            sendErrorFrame("Missing 'destination' header", frame.getHeaders());
            return;
        }

        // Verify we are subscribed before sending
        if (!activeSubscriptions.containsValue(destination)) {
            sendErrorFrame("User is not subscribed to " + destination, frame.getHeaders());
            return;
        }

        String body = frame.getBody();
        
        // Forward message to channel
        connections.send(destination, body);
        
        // Optional: File tracking logic
        String fileName = frame.getHeader("file name");
        if (fileName != null) {
             Database.getInstance().logFile(currentUser, fileName, destination);
        }

        sendReceiptIfRequested(frame);
    }

    private void handleSubscribe(StompFrame frame) {
        if (!validateSession(frame)) return;

        String dest = frame.getHeader("destination");
        String idStr = frame.getHeader("id");

        if (dest == null || idStr == null) {
            sendErrorFrame("Malformed SUBSCRIBE frame (missing id or destination)", frame.getHeaders());
            return;
        }

        try {
            int subId = Integer.parseInt(idStr);
            activeSubscriptions.put(subId, dest);
            connections.subscribe(dest, ownerId, subId);
            sendReceiptIfRequested(frame);
        } catch (NumberFormatException e) {
            sendErrorFrame("Invalid subscription ID", frame.getHeaders());
        }
    }

    private void handleUnsubscribe(StompFrame frame) {
        if (!validateSession(frame)) return;

        String idStr = frame.getHeader("id");
        if (idStr == null) {
            sendErrorFrame("Missing subscription ID", frame.getHeaders());
            return;
        }

        try {
            int subId = Integer.parseInt(idStr);
            String channel = activeSubscriptions.remove(subId);
            
            if (channel != null) {
                connections.unsubscribe(channel, ownerId);
                sendReceiptIfRequested(frame);
            } else {
                // Subscription not found - non-fatal error usually, but strict STOMP might warn
                sendReceiptIfRequested(frame); 
            }
        } catch (NumberFormatException e) {
            sendErrorFrame("Invalid ID format", frame.getHeaders());
        }
    }

    // --- Helpers ---

    private boolean validateSession(StompFrame frame) {
        if (!isAuthenticated) {
            sendErrorFrame("Client is not authenticated", frame.getHeaders());
            return false;
        }
        return true;
    }

    private void sendReceiptIfRequested(StompFrame frame) {
        String receipt = frame.getHeader("receipt");
        if (receipt != null) {
            sendMessage("RECEIPT\nreceipt-id:" + receipt + "\n\n");
        }
    }

    private void sendErrorFrame(String msg, Map<String, String> originalHeaders) {
        StringBuilder sb = new StringBuilder();
        sb.append("ERROR\n");
        sb.append("message:").append(msg).append("\n");
        
        if (originalHeaders != null && originalHeaders.containsKey("receipt")) {
            sb.append("receipt-id:").append(originalHeaders.get("receipt")).append("\n");
        }
        
        sb.append("\n"); // Empty line
        // Optional: echo body? No need for this assignment usually.
        sendMessage(sb.toString());
        
        terminate(); // Errors in STOMP 1.2 often result in disconnect
    }

    private void sendMessage(String content) {
        connections.send(ownerId, content);
    }
    
    private void terminate() {
        shouldTerminate = true;
        connections.disconnect(ownerId);
    }

    // --- Functional Interface for Dispatcher ---
    @FunctionalInterface
    private interface ProtocolAction {
        void execute(StompFrame frame);
    }

    // --- Private Inner Class for Parsing (Encapsulation) ---
    private static class StompFrame {
        private final String command;
        private final Map<String, String> headers;
        private final String body;

        private StompFrame(String command, Map<String, String> headers, String body) {
            this.command = command;
            this.headers = headers;
            this.body = body;
        }

        public String getCommand() { return command; }
        
        public String getHeader(String key) { return headers.get(key); }
        
        public Map<String, String> getHeaders() { return headers; }
        
        public String getBody() { return body; }

        public static StompFrame fromString(String msg) {
            if (msg == null || msg.isEmpty()) return new StompFrame("", new HashMap<>(), "");

            String[] lines = msg.split("\n");
            String command = lines[0].trim();
            Map<String, String> headers = new HashMap<>();
            StringBuilder body = new StringBuilder();

            boolean isBody = false;
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i];
                if (!isBody) {
                    if (line.trim().isEmpty()) {
                        isBody = true; // Empty line marks start of body
                    } else {
                        String[] parts = line.split(":", 2);
                        if (parts.length == 2) {
                            headers.put(parts[0].trim(), parts[1].trim());
                        }
                    }
                } else {
                    body.append(line).append("\n");
                }
            }
            // Trim the last newline added by the loop if necessary
            if (body.length() > 0 && body.charAt(body.length() - 1) == '\n') {
                body.setLength(body.length() - 1);
            }

            return new StompFrame(command, headers, body.toString());
        }
    }
}