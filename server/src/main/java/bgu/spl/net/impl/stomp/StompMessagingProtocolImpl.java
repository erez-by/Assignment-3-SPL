package bgu.spl.net.impl.stomp;

import bgu.spl.net.api.StompMessagingProtocol;
import bgu.spl.net.srv.Connections;

public class StompMessagingProtocolImpl implements StompMessagingProtocol<String>{
    private int connectionId;
    private boolean shouldTerminate = false;
    private Connections<String> connections; 
    @Override
    public void start(int connectionId, Connections<String> connections){
        this.connectionId = connectionId;
        this.connections = connections;
    }
    @Override
    public void process(String message){
        String[] lines = message.split("\n");
        String header = lines[0];

        switch(header){
            case "CONNECT":
                handleConnect(lines);
                break;
        }

    }
	
	
    public boolean shouldTerminate(){
        return true;
    }

    private void handleConnect(String[] lines){
      

    }
    

    
}