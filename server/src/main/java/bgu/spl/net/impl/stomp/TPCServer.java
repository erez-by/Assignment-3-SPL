package bgu.spl.net.impl.stomp;

import java.util.function.Supplier;
import bgu.spl.net.api.MessageEncoderDecoder;
import bgu.spl.net.api.StompMessagingProtocol;
import bgu.spl.net.srv.BaseServer;
import bgu.spl.net.srv.BlockingConnectionHandler;

public class TPCServer<T> extends BaseServer<T> {

    public TPCServer(int port, Supplier<StompMessagingProtocol<T>> protocolFactory, Supplier<MessageEncoderDecoder<T>> encdecFactory) {
        super(port, protocolFactory, encdecFactory);
    }

    @Override
    protected void execute(BlockingConnectionHandler<T> handler) {
       // New Thread for every client
        new Thread(handler).start();
    }
}
