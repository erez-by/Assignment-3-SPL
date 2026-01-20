#include <stdlib.h>
#include "../include/ConnectionHandler.h"
#include "../include/StompProtocol.h"
#include "../include/event.h"
#include "../include/GameManager.h"
#include <iostream>
#include <fstream>
#include <thread>
#include <vector>
#include <sstream>
#include <map>
#include <mutex> // Added for thread safety

std::mutex mapMutex;

//pending recept map for retiorning massages


std::vector<std::string> split(const std::string &s, char delimiter){
	//function to split a string by a given delimiter
	std::vector<std::string> tokens;
	std::string token;
	std::istringstream tokenStream(s);
	while (std::getline(tokenStream, token, delimiter)) {
		tokens.push_back(token);
	}
	return tokens;
}

std::vector<std::string> inputParser(std::string input, int& subId , int& receptId 
	, std::map<std::string,int>& topicMap, std::map<int,PendingRequest>& receptMap
	, std::string myLogInUserName, GameManager& gameManager){
	//function to parse the input from the keyboard and create a stomp frame string
	//spliting the input by space 
	std::vector<std::string> args = split(input, ' ');
	if(args.size()==0){
		return std::vector<std::string>{};
	}
	std::vector<std::string> framesToSend;
	std::string command = args[0];
	std::stringstream frame;
	// all the cases for comands by the user 
	if(command == "login"){
		if(args.size() < 3) {
            std::cout << "Usage: login {host:port} {user} {password}" << std::endl;
            return framesToSend;
        }
		frame << "CONNECT\n";
		frame << "accept-version:1.2\n";
		frame << "host:stomp.cs.bgu.ac.il\n";
		frame << "login:" << args[1] << "\n";
		frame << "passcode:" << args[2] << "\n\n";
		myLogInUserName = args[1];
		framesToSend.push_back(frame.str());
	}
	if(command == "join"){
		frame << "SUBSCRIBE\n";
		frame << "destination:/" << args[1] << "\n";
		frame << "id:" << subId << "\n";
		frame << "receipt:" << receptId << "\n\n";
		// adding the topic to the map with its subId
		topicMap[args[1]] = subId;
		receptMap[receptId] = PendingRequest{"SUBSCRIBE",args[1]};
		subId++;
		receptId++;
		framesToSend.push_back(frame.str());
	}
	if(command == "exit"){
		frame << "UNSUBSCRIBE\n";
		frame << "id:" << topicMap[args[1]] << "\n";
		frame << "receipt:" << receptId << "\n\n";
		receptMap[receptId] = PendingRequest{"UNSUBSCRIBE",args[1]};
		receptId++;
		framesToSend.push_back(frame.str());
	}
	if(command == "report"){
		std::string json_path = args[1];
		names_and_events data = parseEventsFile(json_path);

		for(Event event : data.events){
			std::stringstream ss;
			ss << "SEND\n";
			ss << "destination:/" << data.team_a_name << "_" << data.team_b_name << "\n\n";
			ss << "user:" << myLogInUserName << "\n";
			ss << "team a:" << event.get_team_a_name() << "\n";
			ss << "team b:" << event.get_team_b_name() << "\n";
			ss << "event name:" << event.get_name() << "\n";
			ss << "time:" << event.get_time() << "\n";
			ss << "general game updates:\n";
			for(auto const& [key, value]: event.get_game_updates()){
				ss << key << ":" << value << "\n";
			}
			ss << "team a updates:\n";
			for(auto const& [key, value]: event.get_team_a_updates()){
				ss << key << ":" << value << "\n";
			}
			ss << "team b updates:\n";
			for(auto const& [key, value]: event.get_team_b_updates()){
				ss << key << ":" << value << "\n";
			}
			ss << "description:" << event.get_discription() << "\n";
			framesToSend.push_back(ss.str());
		}
		
	}
	if(command == "logout"){
		frame << "DISCONNECT\n";
		frame << "receipt:" << receptId << "\n\n";
		receptMap[receptId] = PendingRequest{"DISCONNECT",args[1]};
		receptId++;
		framesToSend.push_back(frame.str());
	}
	if(command =="summary"){
		std::string gameName = args[1];
		std::string userName = args[2];
		std::string filePath = args[3];
		try{
			GameState userSates =  gameManager.getUserStates(gameName,userName);
		std::string team_a_name = userSates.team_a_stats["team a"];
		std::string team_b_name = userSates.team_b_stats["team b"];
		std::ofstream outFile(filePath);
		outFile << team_a_name << "VS" << team_b_name << "\n";
		outFile << "Game statesL:"<< "\n";
		for(const auto& [key,value] : userSates.general_states){
			outFile << key << " : " << value << "\n";
		}
		outFile <<team_a_name<< "states:"<< "\n";
		for(const auto& [key,value] : userSates.team_a_stats){
			outFile << key << " : " << value << "\n";
		}
		outFile <<team_b_name<< "states:"<< "\n";
		for(const auto& [key,value] : userSates.team_b_stats){
			outFile << key << " : " << value << "\n";
		}
		outFile <<"Game event report:"<< "\n";
		for(const auto& event : userSates.events){
			outFile << event.get_time() << " - " << event.get_name() << "\n";
			outFile << event.get_discription()<< "\n";
		}
		outFile.close();
		} catch (const std::exception& e) {
			std::cerr << "Error generating summary: " << e.what() << std::endl;
		}

		std::cout<< "wrote summery to file path: "<< filePath << std::endl;
		}
	return framesToSend;
}





void socketListenerThread(ConnectionHandler& connectionHandler , StompProtocol& stompProtocol , std::map<int,PendingRequest>& receptMap,GameManager& gameManager){
    while(1){
        std::string answer;
        
        if(!connectionHandler.getFrameAscii(answer, '\0')){
            std::cout << "Disconnected. Exiting...\n" << std::endl;
            break;
        }

        // Lock map before processing to prevent race conditions
        {
            std::lock_guard<std::mutex> lock(mapMutex);
            stompProtocol.process(answer, receptMap, gameManager);
        }

        if(stompProtocol.getShouldTerminate()){
            break;
        }
    }
}


int main(int argc, char *argv[]) {
	//cheak for correct number of arguments 
	if(argc<3){
		std::cerr << "Usage: " << argv[0] << " host port" << std::endl << std::endl;
		return -1;
	}
	// get host and port from arg
	std::string host = argv[1];
    short port = atoi(argv[2]);

	ConnectionHandler connectionHandler(host, port);
	// try to connect to the server
	if(!connectionHandler.connect()){
		std::cerr << "Cannot connect to " << host << ":" << port << std::endl;
		return 1;
	}
	//creating the stomp protocol and counts 
	StompProtocol stompProtocol;
	int subId = 1;
	int receptId = 1;
	std::map<int,PendingRequest> receptMap;
	std::map<std::string,int> topicMap;
	std::string myLogInUserName = "";
	GameManager gameManager;

	//creating the socket and keyborad threds
	
	std::thread socketListener(socketListenerThread,std::ref(connectionHandler) , std::ref(stompProtocol), std::ref(receptMap), std::ref(gameManager));

	while(1){
		const short bufsize = 1024;
        char buf[bufsize];
        std::cin.getline(buf, bufsize);
		std::string line(buf);
		if(line==""){
			continue;
		}
		std::lock_guard<std::mutex> lock(mapMutex);
		std::vector<std::string> framesToSend = inputParser(line,subId,receptId,topicMap,receptMap,myLogInUserName,gameManager);
		for(const std::string& frameString : framesToSend){
			if(frameString!=""){
				std::string frameStringWithNull = frameString + '\0';
				if(!connectionHandler.sendBytes(frameStringWithNull.c_str(),frameStringWithNull.length())){
				std::cout << "Disconnected. Exiting...\n" << std::endl;
				break;
			}
		}
		}

		if(stompProtocol.getShouldTerminate()) break;
	}

	if(socketListener.joinable()){
		socketListener.join();
	}

	return 0;

}


