#include "../include/StompProtocol.h"
#include <iostream>
#include <sstream>
#include <fstream>
#include <vector>
#include <algorithm>


namespace {
    // Helper to safely trim whitespace
    std::string trim(const std::string& str) {
        const auto strBegin = str.find_first_not_of(" \t\r\n");
        if (strBegin == std::string::npos) return "";
        const auto strEnd = str.find_last_not_of(" \t\r\n");
        const auto range = strEnd - strBegin + 1;
        return str.substr(strBegin, range);
    }

    // Helper to split string by delimiter
    std::vector<std::string> split(const std::string& s, char delimiter) {
        std::vector<std::string> tokens;
        std::string token;
        std::istringstream tokenStream(s);
        while (std::getline(tokenStream, token, delimiter)) {
            tokens.push_back(token);
        }
        return tokens;
    }

    // New Logic: Parse Key-Value pairs from a block of text
    std::map<std::string, std::string> parseBlockToMap(const std::vector<std::string>& lines) {
        std::map<std::string, std::string> result;
        for (const auto& line : lines) {
            size_t colon = line.find(':');
            if (colon != std::string::npos) {
                std::string key = trim(line.substr(0, colon));
                std::string val = trim(line.substr(colon + 1));
                result[key] = val;
            }
        }
        return result;
    }
}


StompProtocol::StompProtocol() {
    // Command Pattern: Map string commands to member functions
    commandDispatcher_["login"] = [this](std::stringstream& ss) { handleLoginCommand(ss); };
    commandDispatcher_["join"] = [this](std::stringstream& ss) { handleJoinCommand(ss); };
    commandDispatcher_["exit"] = [this](std::stringstream& ss) { handleExitCommand(ss); };
    commandDispatcher_["report"] = [this](std::stringstream& ss) { handleReportCommand(ss); };
    commandDispatcher_["summary"] = [this](std::stringstream& ss) { handleSummaryCommand(ss); };
    commandDispatcher_["logout"] = [this](std::stringstream& ss) { handleLogoutCommand(ss); };
}

StompProtocol::~StompProtocol() {
    terminateSession();
    if (workerThread_.joinable()) {
        workerThread_.join();
    }
}

void StompProtocol::processInput(const std::string& inputLine) {
    // Logic Change: Manual string splitting instead of stream extraction for command parsing
    std::string trimmedInput = trim(inputLine);
    if (trimmedInput.empty()) return;

    size_t firstSpace = trimmedInput.find(' ');
    std::string command = (firstSpace == std::string::npos) ? trimmedInput : trimmedInput.substr(0, firstSpace);
    std::string args = (firstSpace == std::string::npos) ? "" : trimmedInput.substr(firstSpace + 1);

    std::stringstream ss(args); // Pass only arguments to handlers

    if (commandDispatcher_.count(command)) {
        if (command != "login" && !isAuthenticated()) {
            std::cout << "You must login first" << std::endl;
        } else {
            commandDispatcher_[command](ss);
        }
    } else {
        std::cout << "Unknown command" << std::endl;
    }
}

bool StompProtocol::isAuthenticated() {
    std::lock_guard<std::mutex> lock(stateMutex_);
    return isLoggedIn_;
}

void StompProtocol::terminateSession() {
    isRunning_ = false;
    // Local copy to avoid holding mutex while closing
    std::shared_ptr<ConnectionHandler> conn;
    {
        std::lock_guard<std::mutex> lock(stateMutex_);
        conn = connection_;
    }
    if (conn) conn->close();
}


void StompProtocol::handleLoginCommand(std::stringstream& ss) {
    std::string hostPort, user, pass;
    ss >> hostPort >> user >> pass;
    
    if (hostPort.empty() || user.empty() || pass.empty()) {
        std::cout << "Usage: login {host:port} {user} {password}" << std::endl;
        return;
    }

    {
        std::lock_guard<std::mutex> lock(stateMutex_);
        if (isLoggedIn_ || isPendingLogin_) {
            std::cout << "The client is already logged in, log out before trying again" << std::endl;
            return;
        }
    }

    // persing port and host
    size_t colon = hostPort.find(':');
    if (colon == std::string::npos) {
        std::cout << "Could not connect to server" << std::endl;
        return;
    }

    std::string host = hostPort.substr(0, colon);
    short port = 0;
    try {
        port = static_cast<short>(std::stoi(hostPort.substr(colon + 1)));
    } catch (...) {
        std::cout << "Invalid port number" << std::endl;
        return;
    }

    auto newConn = std::make_shared<ConnectionHandler>(host, port);
    if (!newConn->connect()) {
        std::cout << "Could not connect to server" << std::endl;
        return;
    }

    // Update connection state
    {
        std::lock_guard<std::mutex> lock(stateMutex_);
        connection_ = newConn;
        isConnected_ = true;
        isPendingLogin_ = true;
        currentUser_ = user;
        isRunning_ = true;
    }

    workerThread_ = std::thread(&StompProtocol::networkLoop, this);

    std::map<std::string, std::string> headers;
    headers["accept-version"] = "1.2";
    headers["host"] = "stomp.cs.bgu.ac.il";
    headers["login"] = user;
    headers["passcode"] = pass;

    sendStompFrame("CONNECT", headers);

    // Block until login result
    std::unique_lock<std::mutex> lock(stateMutex_);
    stateCv_.wait(lock, [this] { return !isPendingLogin_ || !isConnected_; });
}

void StompProtocol::handleJoinCommand(std::stringstream& ss) {
    std::string gameName;
    ss >> gameName;
    if (gameName.empty()) return;

    std::string subId, receiptId;
    {
        std::lock_guard<std::mutex> lock(stateMutex_);
        subId = std::to_string(subIdCounter_++);
        receiptId = std::to_string(receiptIdCounter_++);
        
        gameToSubId_[gameName] = subId;
        pendingReceipts_[receiptId] = {ReceiptType::SUBSCRIBE, gameName, subId};
    }

    sendStompFrame("SUBSCRIBE", {
        {"destination", "/" + gameName},
        {"id", subId},
        {"receipt", receiptId}
    });
}

void StompProtocol::handleExitCommand(std::stringstream& ss) {
    std::string gameName;
    ss >> gameName;
    if (gameName.empty()) return;

    std::string subId;
    {
        std::lock_guard<std::mutex> lock(stateMutex_);
        auto it = gameToSubId_.find(gameName);
        if (it == gameToSubId_.end()) return; // Not subscribed
        subId = it->second;
    }

    std::string receiptId;
    {
        std::lock_guard<std::mutex> lock(stateMutex_);
        receiptId = std::to_string(receiptIdCounter_++);
        pendingReceipts_[receiptId] = {ReceiptType::UNSUBSCRIBE, gameName, subId};
    }

    sendStompFrame("UNSUBSCRIBE", {
        {"id", subId},
        {"receipt", receiptId}
    });
}

void StompProtocol::handleLogoutCommand(std::stringstream& ss) {
    std::string receiptId;
    {
        std::lock_guard<std::mutex> lock(stateMutex_);
        if (isPendingLogout_) return;
        isPendingLogout_ = true;
        receiptId = std::to_string(receiptIdCounter_++);
        pendingReceipts_[receiptId] = {ReceiptType::DISCONNECT, "", ""};
    }

    sendStompFrame("DISCONNECT", {{"receipt", receiptId}});
    
    std::unique_lock<std::mutex> lock(stateMutex_);
    stateCv_.wait(lock, [this] { return !isPendingLogout_; });
}

void StompProtocol::handleReportCommand(std::stringstream& ss) {
    std::string file;
    ss >> file;
    if (file.empty()) return;

    names_and_events data = parseEventsFile(file);
    std::string gameName = data.team_a_name + "_" + data.team_b_name;

    {
        std::lock_guard<std::mutex> lock(stateMutex_);
        if (gameToSubId_.find(gameName) == gameToSubId_.end()) {
            std::cout << "You must join " << gameName << " before reporting" << std::endl;
            return;
        }
    }

    for (const auto& event : data.events) {
        std::string body = formatEventBody(data.team_a_name, data.team_b_name, event);
        
        // Optimistic local update
        updateGameState(gameName, currentUser_, body);

        sendStompFrame("SEND", {
            {"destination", "/" + gameName}
        }, body);
    }
}

void StompProtocol::handleSummaryCommand(std::stringstream& ss) {
    std::string gameName, user, path;
    ss >> gameName >> user >> path;
    
    GameSession sessionSnapshot;
    UserGameStats statsSnapshot;
    bool hasData = false;

    {
        std::lock_guard<std::mutex> lock(stateMutex_);
        if (gameStorage_.count(gameName) && gameStorage_[gameName].reporters.count(user)) {
            sessionSnapshot = gameStorage_[gameName];
            statsSnapshot = sessionSnapshot.reporters[user];
            hasData = true;
        }
    }

    if (!hasData) {
        std::cout << "No stats found for user " << user << " in game " << gameName << std::endl;
        return;
    }

    std::ofstream fs(path);
    if (!fs.is_open()) {
        std::cout << "Could not open file: " << path << std::endl;
        return;
    }

    fs << sessionSnapshot.teamA << " vs " << sessionSnapshot.teamB << "\n";
    fs << "Game stats:\n";
    
    auto printMap = [&](const std::string& title, const std::map<std::string, std::string>& m) {
        fs << title << "\n";
        for (const auto& p : m) fs << p.first << ": " << p.second << "\n";
    };

    printMap("General stats:", statsSnapshot.generalStats);
    printMap(sessionSnapshot.teamA + " stats:", statsSnapshot.teamAStats);
    printMap(sessionSnapshot.teamB + " stats:", statsSnapshot.teamBStats);

    fs << "Game event reports:\n";
    for (const auto& ev : statsSnapshot.events) {
        fs << ev.time << " - " << ev.eventName << ":\n";
        fs << ev.description << "\n\n";
    }
    fs.close();
}

// networking loop
void StompProtocol::networkLoop() {
    while (isRunning_) {
        std::shared_ptr<ConnectionHandler> conn;
        {
            std::lock_guard<std::mutex> lock(stateMutex_);
            conn = connection_;
        }
        
        if (!conn) break;

        std::string packet;
        // Read until null terminator
        if (!conn->getFrameAscii(packet, '\0')) {
            handleDisconnect();
            break;
        }

        StompFrame frame = parseStompFrame(packet);
        dispatchServerFrame(frame);
    }
}

void StompProtocol::dispatchServerFrame(const StompFrame& frame) {
    if (frame.command == "CONNECTED") {
        std::lock_guard<std::mutex> lock(stateMutex_);
        isLoggedIn_ = true;
        isPendingLogin_ = false;
        stateCv_.notify_all();
        std::cout << "Login successful" << std::endl;
    } 
    else if (frame.command == "MESSAGE") {
        if (frame.headers.count("destination")) {
            std::string game = frame.headers.at("destination");
            // Remove '/topic/' or '/' prefix if present
            size_t lastSlash = game.rfind('/');
            if (lastSlash != std::string::npos) game = game.substr(lastSlash + 1);
            
            std::string bodyUser = extractUserFromBody(frame.body);
            updateGameState(game, bodyUser, frame.body);
        }
    }
    else if (frame.command == "RECEIPT") {
        if (frame.headers.count("receipt-id")) {
            processReceipt(frame.headers.at("receipt-id"));
        }
    }
    else if (frame.command == "ERROR") {
        std::cout << frame.body << std::endl;
        handleDisconnect();
    }
}

void StompProtocol::processReceipt(const std::string& receiptId) {
    std::lock_guard<std::mutex> lock(stateMutex_);
    auto it = pendingReceipts_.find(receiptId);
    if (it == pendingReceipts_.end()) return;

    ReceiptContext ctx = it->second;
    pendingReceipts_.erase(it);

    switch (ctx.type) {
        case ReceiptType::SUBSCRIBE:
            std::cout << "Joined channel " << ctx.gameName << std::endl;
            break;
        case ReceiptType::UNSUBSCRIBE:
            std::cout << "Exited channel " << ctx.gameName << std::endl;
            gameToSubId_.erase(ctx.gameName);
            break;
        case ReceiptType::DISCONNECT:
            isPendingLogout_ = false;
            isLoggedIn_ = false;
            currentUser_ = "";
            stateCv_.notify_all();
            terminateSession();
            std::cout << "Disconnected" << std::endl;
            break;
    }
}

void StompProtocol::handleDisconnect() {
    std::lock_guard<std::mutex> lock(stateMutex_);
    isConnected_ = false;
    isLoggedIn_ = false;
    isPendingLogin_ = false;
    stateCv_.notify_all();
    connection_.reset(); 
}

// helper functions
void StompProtocol::sendStompFrame(const std::string& cmd, const std::map<std::string, std::string>& headers, const std::string& body) {
    std::stringstream frame;
    frame << cmd << "\n";
    for (const auto& pair : headers) {
        frame << pair.first << ":" << pair.second << "\n";
    }
    frame << "\n" << body;
    
    std::lock_guard<std::mutex> lock(socketMutex_);
    if (connection_) {
        // Send frame + null char
        connection_->sendFrameAscii(frame.str(), '\0');
    }
}

StompProtocol::StompFrame StompProtocol::parseStompFrame(const std::string& raw) {
    StompFrame frame;
    std::vector<std::string> lines = split(raw, '\n');
    
    if (lines.empty()) return frame;
    frame.command = trim(lines[0]);

    bool inBody = false;
    for (size_t i = 1; i < lines.size(); ++i) {
        std::string line = trim(lines[i]);
        if (!inBody) {
            if (line.empty()) {
                inBody = true;
                continue;
            }
            size_t c = line.find(':');
            if (c != std::string::npos) {
                frame.headers[line.substr(0, c)] = line.substr(c + 1);
            }
        } else {
            frame.body += lines[i] + "\n";
        }
    }
    return frame;
}

std::string StompProtocol::formatEventBody(const std::string& teamA, const std::string& teamB, const Event& ev) {
    std::ostringstream oss;
    oss << "user: " << currentUser_ << "\n"
        << "team a: " << teamA << "\n"
        << "team b: " << teamB << "\n"
        << "event name: " << ev.get_name() << "\n"
        << "time: " << ev.get_time() << "\n"
        << "general game updates:\n";
    
    for (const auto& p : ev.get_game_updates()) oss << p.first << ": " << p.second << "\n";
    
    oss << "team a updates:\n";
    for (const auto& p : ev.get_team_a_updates()) oss << p.first << ": " << p.second << "\n";
    
    oss << "team b updates:\n";
    for (const auto& p : ev.get_team_b_updates()) oss << p.first << ": " << p.second << "\n";
    
    oss << "description:\n" << ev.get_discription();
    return oss.str();
}

std::string StompProtocol::extractUserFromBody(const std::string& body) {
    size_t pos = body.find("user:");
    if (pos == std::string::npos) return "";
    
    size_t endLine = body.find('\n', pos);
    return trim(body.substr(pos + 5, endLine - (pos + 5)));
}


void StompProtocol::updateGameState(const std::string& gameName, const std::string& user, const std::string& body) {
    if (user.empty()) return;

    std::lock_guard<std::mutex> lock(stateMutex_);
    GameSession& session = gameStorage_[gameName];
    UserGameStats& stats = session.reporters[user];

    // Split body into lines
    std::vector<std::string> lines = split(body, '\n');

    //  Identify sections
    std::vector<std::string> currentBlock;
    std::string currentSection = "header";
    
    for (const auto& rawLine : lines) {
        std::string line = trim(rawLine);
        if (line.empty()) continue;

        if (line == "general game updates:") {
            currentSection = "gen"; continue;
        } else if (line == "team a updates:") {
            currentSection = "teamA"; continue;
        } else if (line == "team b updates:") {
            currentSection = "teamB"; continue;
        } else if (line == "description:") {
            currentSection = "desc"; continue;
        }

        // Processing based on current section
        if (currentSection == "header") {
            if (line.rfind("team a:", 0) == 0) session.teamA = trim(line.substr(7));
            else if (line.rfind("team b:", 0) == 0) session.teamB = trim(line.substr(7));
        }
        else if (currentSection == "gen") {
            size_t c = line.find(':');
            if (c != std::string::npos) stats.generalStats[trim(line.substr(0, c))] = trim(line.substr(c+1));
        }
        else if (currentSection == "teamA") {
            size_t c = line.find(':');
            if (c != std::string::npos) stats.teamAStats[trim(line.substr(0, c))] = trim(line.substr(c+1));
        }
        else if (currentSection == "teamB") {
            size_t c = line.find(':');
            if (c != std::string::npos) stats.teamBStats[trim(line.substr(0, c))] = trim(line.substr(c+1));
        }
        else if (currentSection == "desc") {
            currentBlock.push_back(rawLine);
        }
    }
    
    // Extract metadata for the event struct
    GameEvent newEvent;
    for(const auto& l : lines) {
         if(l.rfind("event name:", 0) == 0) newEvent.eventName = trim(l.substr(11));
         if(l.rfind("time:", 0) == 0) try { newEvent.time = std::stoi(trim(l.substr(5))); } catch(...) {}
    }
    
    // Extract description block manually (multi-line support)
    size_t descPos = body.find("description:");
    if (descPos != std::string::npos) {
        newEvent.description = trim(body.substr(descPos + 12));
    }
    
    stats.events.push_back(newEvent);
}