package bgu.spl.net.impl.data;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.concurrent.ConcurrentHashMap;
import java.util.ArrayList;
import java.util.List;

/**
 * Refactored Database implementation.
 * Handles synchronization between the Java Server (Memory) and Python Server (SQL Persistence).
 */
public class Database {

    private final ConcurrentHashMap<String, User> usersCache;
    private final ConcurrentHashMap<Integer, User> activeSessions;
    private final DbNetworkHandler networkHandler;

    // Singleton Holder
    private static class SingletonHolder {
        private static final Database INSTANCE = new Database();
    }

    public static Database getInstance() {
        return SingletonHolder.INSTANCE;
    }

    private Database() {
        this.usersCache = new ConcurrentHashMap<>();
        this.activeSessions = new ConcurrentHashMap<>();
        // Network handler encapsulates the socket logic
        this.networkHandler = new DbNetworkHandler("127.0.0.1", 7778);

        initializeDatabase();
    }

    private void initializeDatabase() {
        resetStaleSessions();
        synchronizeUsers();
    }

    /**
     * Fixes inconsistent states if the server crashed previously without logging users out.
     */
    private void resetStaleSessions() {
        System.out.println("[DB] Resetting stale sessions...");
        String response = networkHandler.sendQuery("UPDATE login_history SET logout_time=datetime('now') WHERE logout_time IS NULL");
        if (response.startsWith("SUCCESS")) {
            System.out.println("[DB] Stale sessions cleared.");
        }
    }

    /**
     * Pulls user data from SQL to populate the in-memory cache.
     */
    private void synchronizeUsers() {
        System.out.println("[DB] Synchronizing users...");
        String response = networkHandler.sendQuery("SELECT username, password FROM users");
        
        if (response.startsWith("ERROR")) {
            System.err.println("[DB] Failed to load users: " + response);
            return;
        }

        List<String[]> rows = parseResultSet(response);
        for (String[] cols : rows) {
            if (cols.length >= 2) {
                String u = cols[0];
                String p = cols[1];
                usersCache.put(u, new User(-1, u, p));
            }
        }
        System.out.println("[DB] Users loaded: " + usersCache.size());
    }

    public LoginStatus login(int connectionId, String username, String password) {
        // check memory state for active connection ID
        if (activeSessions.containsKey(connectionId)) {
            return LoginStatus.CLIENT_ALREADY_CONNECTED;
        }

        // query SQL to check if user exists
        String existingUserQuery = "SELECT username, password FROM users WHERE username='" + escape(username) + "'";
        String lookupResult = networkHandler.sendQuery(existingUserQuery);
        List<String[]> lookupRows = parseResultSet(lookupResult);

        if (lookupRows.isEmpty()) {
            //  User does not exist -> Register new user ---
            return registerNewUser(connectionId, username, password);
        } else {
            // User exists -> Validate credentials ---
            String[] userData = lookupRows.get(0);
            String storedPass = userData[1];

            if (!storedPass.equals(password)) {
                return LoginStatus.WRONG_PASSWORD;
            }

            // Check if currently logged in (SQL Truth)
            String activeCheck = "SELECT username FROM login_history WHERE username='" + escape(username) + "' AND logout_time IS NULL";
            String activeResult = networkHandler.sendQuery(activeCheck);
            if (!parseResultSet(activeResult).isEmpty()) {
                return LoginStatus.ALREADY_LOGGED_IN;
            }

            // Perform Login
            finalizeLogin(connectionId, username, password);
            return LoginStatus.LOGGED_IN_SUCCESSFULLY;
        }
    }

    private LoginStatus registerNewUser(int connectionId, String username, String password) {
        System.out.println("[DB] Registering new user: " + username);
        String insert = String.format("INSERT INTO users (username, password, registration_date) VALUES ('%s', '%s', datetime('now'))", 
                                      escape(username), escape(password));
        
        String result = networkHandler.sendQuery(insert);
        if (result.startsWith("ERROR")) {
            System.err.println("[DB] Registration failed: " + result);
            return LoginStatus.WRONG_PASSWORD; // Fallback error
        }

        finalizeLogin(connectionId, username, password);
        return LoginStatus.ADDED_NEW_USER;
    }

    private void finalizeLogin(int connId, String username, String password) {
        // Update SQL History
        String logSql = String.format("INSERT INTO login_history (username, login_time) VALUES ('%s', datetime('now'))", escape(username));
        networkHandler.sendQuery(logSql);

        // Update Memory
        User user = usersCache.computeIfAbsent(username, k -> new User(connId, username, password));
        user.setConnectionId(connId);
        user.login();
        activeSessions.put(connId, user);
    }

    public void logout(int connectionId) {
        User u = activeSessions.remove(connectionId);
        if (u != null) {
            u.logout();
            // Update SQL
            String sql = String.format("UPDATE login_history SET logout_time=datetime('now') WHERE username='%s' AND logout_time IS NULL", escape(u.name));
            networkHandler.sendQuery(sql);
            System.out.println("[DB] User " + u.name + " logged out.");
        }
    }

    public void logFile(String username, String filename, String channel) {
        String sql = String.format("INSERT INTO file_tracking (username, filename, upload_time, game_channel) VALUES ('%s', '%s', datetime('now'), '%s')",
                escape(username), escape(filename), escape(channel));
        networkHandler.sendQuery(sql);
    }

    // --- Reporting ---

    public void printReport() {
        System.out.println("\n--- Server Statistics Report ---");
        printUserList();
        printLoginLogs();
        printFileStats();
        System.out.println("--------------------------------\n");
    }

    private void printUserList() {
        System.out.println("1. Users:");
        String res = networkHandler.sendQuery("SELECT username, registration_date FROM users ORDER BY registration_date");
        List<String[]> rows = parseResultSet(res);
        if (rows.isEmpty()) System.out.println("   (none)");
        for (String[] r : rows) {
            if(r.length >= 2) System.out.printf("   - %s (Registered: %s)%n", r[0], r[1]);
        }
    }

    private void printLoginLogs() {
        System.out.println("2. Login History:");
        String res = networkHandler.sendQuery("SELECT username, login_time, logout_time FROM login_history ORDER BY login_time DESC");
        List<String[]> rows = parseResultSet(res);
        if (rows.isEmpty()) System.out.println("   (none)");
        for (String[] r : rows) {
            if(r.length >= 3) {
                String logout = r[2].isEmpty() ? "Active" : r[2];
                System.out.printf("   - %s [%s -> %s]%n", r[0], r[1], logout);
            }
        }
    }

    private void printFileStats() {
        System.out.println("3. Files Uploaded:");
        String res = networkHandler.sendQuery("SELECT username, filename, game_channel FROM file_tracking ORDER BY upload_time DESC");
        List<String[]> rows = parseResultSet(res);
        if (rows.isEmpty()) System.out.println("   (none)");
        for (String[] r : rows) {
            if(r.length >= 3) System.out.printf("   - %s posted '%s' to %s%n", r[0], r[1], r[2]);
        }
    }

    // --- Helpers ---

    private String escape(String input) {
        return input == null ? "" : input.replace("'", "''");
    }

    private List<String[]> parseResultSet(String rawProtocolString) {
        List<String[]> results = new ArrayList<>();
        if (rawProtocolString == null || !rawProtocolString.startsWith("SUCCESS")) return results;

        String[] lines = rawProtocolString.split("\\|");
        // Skip index 0 (the SUCCESS message)
        for (int i = 1; i < lines.length; i++) {
            results.add(lines[i].split(","));
        }
        return results;
    }

    // halper clases for handling DB network communication
    private static class DbNetworkHandler {
        private final String host;
        private final int port;

        public DbNetworkHandler(String host, int port) {
            this.host = host;
            this.port = port;
        }

        public synchronized String sendQuery(String sql) {
            try (Socket s = new Socket(host, port);
                 PrintWriter out = new PrintWriter(s.getOutputStream(), true);
                 BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()))) {
                
                // Send with null char suffix
                out.print(sql + '\0');
                out.flush();

                // Read until null char
                StringBuilder sb = new StringBuilder();
                int c;
                while ((c = in.read()) != -1) {
                    if (c == '\0') break;
                    sb.append((char) c);
                }
                return sb.toString();

            } catch (IOException e) {
                System.err.println("[DB-Net] Error: " + e.getMessage());
                return "ERROR:Connection Failed";
            }
        }
    }
}