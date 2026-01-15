#pragma once
#include "../include/ConnectionHandler.h"
#include <iostream>
#include <sstream>

//creating the StmopFrame 
StompFrame::StmopFrame(std::string msg){
    std::stringstream ss(msg);
    std::string line;
    //get the command
    std::getline(ss,command);
    //get the header lines
    while(std::getline(ss,line) && !line.empty()){
        size_t pos = line.find(':');
        if(pos != std::string::npos){
            std::string key = line.substr(0,pos);
            std::string value = line.substr(pos+1);
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

    void StompProtocol::process(std::string input,std::map<int,PendingRequest>& reciptMap){
        StompFrame frame(input);
        if(frame.command == "CONNECTED"){
            processConnectedFrame(frame );
        }
        else if(frame.command == "MESSAGE"){
            processMessageFrame(frame );
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

    bool StompProtocol::getIsConnected(){
        return isConnected;
    }

    void StompProtocol::processConnectedFrame(StmopFrame& frame){
        if(isConnected){
            std::cout << "alredy connected to server." << std::endl;
            return;
        }
        isConnected = true;
        std::cout << "log in secsseful." << std::endl;
    }

    void StompProtocol::processMessageFrame(StompFrame& frame){
        // to do later 
    }
    void StompProtocol::processReceiptFrame(StompFrame& frame,std::map<int,PendingRequest>& reciptMap){
        std::string receiptId = frame.headers["receipt-id"];
        int id = std::stoi(receiptId);
        PendingRequest pendingRequest = reciptMap[id];
        if(pendingRequest.command == 'DISCONNECT'){
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
