package bgu.spl.net.impl.stomp;

import bgu.spl.net.srv.Reactor;
import bgu.spl.net.srv.Server;


public class StompServer {

    public static void main(String[] args) {
        if (!validArgs(args)) {
            printUsage();
            return;
        }

        int port = parsePort(args[0]);
        if (port == -1) {
            return;
        }

        Server<String> server = buildServer(args[1], port);

        if (server != null) {
            server.serve();
        }
    }

    private static boolean validArgs(String[] args) {
        return args != null && args.length >= 2;
    }

    private static int parsePort(String portArg) {
        try {
            return Integer.parseInt(portArg);
        } catch (NumberFormatException e) {
            System.out.println("Invalid port number: " + portArg);
            return -1;
        }
    }

    private static Server<String> buildServer(String type, int port) {
        switch (type) {
            case "tpc":
                return new TPCServer<>(
                        port,
                        StompMessagingProtocolImpl::new,
                        StompMessageEncoderDecoder::new
                );

            case "reactor":
                return new Reactor<>(
                        2,
                        port,
                        StompMessagingProtocolImpl::new,
                        StompMessageEncoderDecoder::new
                );

            default:
                System.out.println("Unknown server type: " + type);
                return null;
        }
    }

    private static void printUsage() {
        System.out.println("Usage: StompServer <port> <tpc|reactor>");
    }
}
