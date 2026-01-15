#pragma once

#include "../include/ConnectionHandler.h"
#include <string>
#include <vector>
#include <map>
#include <sstream>

//creating the stracture of stomp frames 

struct StompFrame{
    std::string command;
    std::map<std::string,std::string> headers;
    std::string body;

    // perser function to convert a string to stomp frame

    StompFrame(std::string frameString);
};

class StompProtocol
{
private:
    bool shouldTerminate = false;
    bool isConnected = false;
public:

    StompProtocol();
    void process(std::string input,std::map<int,PendingRequest>& reciptMap);
    bool getShouldTerminate();
    bool getIsConnected();

private:
    void processConnectedFrame(StompFrame& frame);
    void processMessageFrame(StompFrame& frame);
    void processReceiptFrame(StompFrame& frame);
    void processErrorFrame(StompFrame& frame);
};
