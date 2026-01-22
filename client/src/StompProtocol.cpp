#include "../include/ConnectionHandler.h"
#include "../include/StompProtocol.h"
#include "../include/GameManager.h"
#include <iostream>
#include <sstream>

//creating the StompFrame 
StompFrame::StompFrame(std::string msg): command(""), headers(), body(""){
    std::stringstream ss(msg);
    std::string line;
    //get the command
    if (std::getline(ss, command)) {
        if (!command.empty() && command.back() == '\r') command.pop_back();
    }
    //get the header lines
    while(std::getline(ss, line) && !line.empty()){
        if (line.back() == '\r') line.pop_back(); // Fix CRLF issue
        if (line.empty()) break; 

        size_t pos = line.find(':');
        if(pos != std::string::npos){
            std::string key = line.substr(0, pos);
            std::string value = line.substr(pos + 1);
            headers[key] = value;
        }
    }

    //get the body 
    char c;
    while(ss.get(c)){
        if(c != '\0') body += c;
    }
}
    //the protocol 
    StompProtocol::StompProtocol():shouldTerminate(false),isConnected(false){}

    void StompProtocol::process(std::string input,std::map<int,PendingRequest>& reciptMap,GameManager& gameManager){
        //
        StompFrame frame(input);
        if(frame.command == "CONNECTED"){

            processConnectedFrame(frame );
        }
        else if(frame.command == "MESSAGE"){
            processMessageFrame(frame, gameManager);
        }
        else if(frame.command == "RECEIPT"){
            processReceiptFrame(frame , reciptMap);
        }
        else if(frame.command == "ERROR"){
            processErrorFrame(frame );
        }
    }

    bool StompProtocol::getShouldTerminate(){
        return shouldTerminate;
    }

    void StompProtocol::setShouldTerminate(bool terminate){
        shouldTerminate = terminate;
    }

    bool StompProtocol::getIsConnected(){
        return isConnected;
    }

    void StompProtocol::processConnectedFrame(StompFrame& frame){
        if(isConnected){
            std::cout << "alredy connected to server." << std::endl;
            return;
        }
        isConnected = true;
        std::cout << "login successful." << std::endl;
    }

    void StompProtocol::processMessageFrame(StompFrame& frame , GameManager& gameManager){
        //exstract data 
        std::string topic = frame.headers["destination"]; 
        std::string gameName = topic.substr(7); 
        std::string user = frame.headers["user"];
        Event event(frame.body);
        //updating the game staes accordingly
        gameManager.updateGameState(gameName, user, event);
        //printing we gpt a massage - also for cheaking later it works
        std::cout << "Received update for " << gameName << " from " << user << std::endl;
    }

    
    void StompProtocol::processReceiptFrame(StompFrame& frame,std::map<int,PendingRequest>& reciptMap){
        std::string receiptId = frame.headers["receipt-id"];
        int id = std::stoi(receiptId);
        PendingRequest pendingRequest = reciptMap[id];
        if(pendingRequest.command == "DISCONNECT"){
            shouldTerminate = true;
            std::cout << "disconnected from server." << std::endl;
        }
        else if(pendingRequest.command == "SUBSCRIBE"){
            std::cout << "subscribed to topic " << pendingRequest.content << std::endl;
        }
        else if(pendingRequest.command == "UNSUBSCRIBE"){
            std::cout << "unsubscribed from topic " << pendingRequest.content << std::endl;
        }

        
    }
    void StompProtocol::processErrorFrame(StompFrame& frame){
        std::cout << "Error: " << frame.body << std::endl;
    }
