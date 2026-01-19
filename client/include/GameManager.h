#pragma once

#include <string>
#include <iostream>
#include <map>
#include <vector>
#include <mutex>
#include "event.h"


struct GameState{
    std::map<std::string,std::string> general_states;
    std::map<std::string,std::string> team_a_stats;
    std::map<std::string,std::string> team_b_stats;
    std::vector<Event> events;

    GameState():general_states(),team_a_stats(),team_b_stats(),events(){};
};

class GameManager{
    private:
    // 2 maps one from game name to another map of user - updates
        std::map<std::string,std::map<std::string,GameState>> gameUpdates;
        std::mutex _mutex;
    public:
        GameManager() = default;
        
        //updating the staes after one event
        void updateGameState(std::string game_name,std::string user_name,Event event){
            std::lock_guard<std::mutex> lock(_mutex);
            GameState& stats = gameUpdates[game_name][user_name];
            //adding event
            stats.events.push_back(event);
            //updating stats field 
            for(auto const& [key,value]:event.get_game_updates()){
                stats.general_states[key]=value;
            }
            for(auto const& [key,value]:event.get_team_a_updates()){
                stats.team_a_stats[key]=value;
            }
            for(auto const& [key,value]:event.get_team_b_updates()){
                stats.team_b_stats[key]=value;
            }
        }

        // getting a report from 1 user
        GameState getUserStates(std::string game_name,std::string user_name){
            std::lock_guard<std::mutex> lock(_mutex);
            if (gameUpdates.count(game_name) && gameUpdates[game_name].count(user_name)) {
                return gameUpdates[game_name][user_name]; 
            }   
            return GameState();
        }
};



    









