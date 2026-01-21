#pragma once

#include "ConnectionHandler.h"
#include "event.h"

#include <algorithm>
#include <atomic>
#include <condition_variable>
#include <functional>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>

class StompProtocol {
public:
    StompProtocol();
    ~StompProtocol();

    // Main entry point for keyboard input
    void processInput(const std::string& inputLine);

private:
    // --- Inner Types ---
    enum class ReceiptType { SUBSCRIBE, UNSUBSCRIBE, DISCONNECT };
    
    struct ReceiptContext {
        ReceiptType type;
        std::string gameName;
        std::string subscriptionId;
    };

    struct GameEvent {
        std::string eventName;
        int time;
        std::string description;
    };

    struct UserGameStats {
        std::map<std::string, std::string> generalStats;
        std::map<std::string, std::string> teamAStats;
        std::map<std::string, std::string> teamBStats;
        std::vector<GameEvent> events;
    };

    struct GameSession {
        std::string teamA;
        std::string teamB;
        std::unordered_map<std::string, UserGameStats> reporters;
    };

    struct StompFrame {
        std::string command;
        std::map<std::string, std::string> headers;
        std::string body;
    };

    // --- State Variables ---
    std::mutex stateMutex_;
    std::mutex socketMutex_;
    std::condition_variable stateCv_;

    std::shared_ptr<ConnectionHandler> connection_;
    std::thread workerThread_;
    std::atomic<bool> isRunning_{true};

    // Connection State
    bool isConnected_ = false;
    bool isLoggedIn_ = false;
    bool isPendingLogin_ = false;
    bool isPendingLogout_ = false;
    std::string currentUser_;

    // ID Generators
    int subIdCounter_ = 1;
    int receiptIdCounter_ = 1;

    // Maps
    std::unordered_map<std::string, std::string> gameToSubId_; 
    std::unordered_map<std::string, ReceiptContext> pendingReceipts_;
    std::unordered_map<std::string, GameSession> gameStorage_;

    // Command Dispatcher
    std::map<std::string, std::function<void(std::stringstream&)>> commandDispatcher_;

    // --- Helper Functions ---
    bool isAuthenticated();
    void terminateSession();
    
    // Command Handlers
    void handleLoginCommand(std::stringstream& ss);
    void handleJoinCommand(std::stringstream& ss);
    void handleExitCommand(std::stringstream& ss);
    void handleLogoutCommand(std::stringstream& ss);
    void handleReportCommand(std::stringstream& ss);
    void handleSummaryCommand(std::stringstream& ss);

    // Networking
    void networkLoop();
    void dispatchServerFrame(const StompFrame& frame);
    void processReceipt(const std::string& receiptId);
    void handleDisconnect();
    
    // Utilities
    void sendStompFrame(const std::string& cmd, const std::map<std::string, std::string>& headers, const std::string& body = "");
    StompFrame parseStompFrame(const std::string& raw);
    std::string formatEventBody(const std::string& teamA, const std::string& teamB, const Event& ev);
    void updateGameState(const std::string& gameName, const std::string& user, const std::string& body);
    std::string extractUserFromBody(const std::string& body);
};