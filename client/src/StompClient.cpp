#include <iostream>
#include <string>

#include "../include/StompProtocol.h"

int main(int argc, char *argv[]) {
    (void)argc; (void)argv;

    StompProtocol protocol;

    std::string line;
    while (std::getline(std::cin, line)) {
        protocol.processInput(line);
    }
    return 0;
}