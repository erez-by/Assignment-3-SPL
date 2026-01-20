package bgu.spl.net.impl.stomp;
import bgu.spl.net.srv.Reactor;
import bgu.spl.net.api.StompMessagingProtocol;
import bgu.spl.net.impl.stomp.StompMessagingProtocolImpl;
import bgu.spl.net.impl.stomp.StompMessageEncoderDecoder;
import bgu.spl.net.srv.Server;
import bgu.spl.net.impl.stomp.TCPServer;

public class StompServer {

    public static void main(String[] args) {
        // args like {port} ,{server type}
        if (args.length < 2) {
            System.out.println("Usage: StompServer <port> <tpc|reactor>");
            return;
        }
        
        int port;
        try {
            port = Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            System.out.println("Invalid port number: " + args[0]);
            return;
        }
        
        String serverType = args[1];
        Server<String> server;

        if(serverType.equals("tcp")){
        server = new TCPServer<>(port, 
            () -> new StompMessagingProtocolImpl(), 
            () -> new StompMessageEncoderDecoder());
        server.serve();
        }

        else if (serverType.equals("reactor")){
            server = new Reactor<>(
                4,
                port,
                () -> new StompMessagingProtocolImpl(),
                () -> new StompMessageEncoderDecoder());
            server.serve();
        }
        else{
            System.out.println("Invalid server type: " + serverType);
            return;
        }
    }
}
