package bgu.spl.net.impl.stomp;

import java.util.function.Supplier;
import bgu.spl.net.api.MessageEncoderDecoder;
import bgu.spl.net.api.StompMessagingProtocol;
import bgu.spl.net.srv.BaseServer;
import bgu.spl.net.srv.BlockingConnectionHandler;

public class TCPServer<T> extends BaseServer<T> {

    public TCPServer(
            int port,
            Supplier<StompMessagingProtocol<T>> protocolFactory,
            Supplier<MessageEncoderDecoder<T>> readerFactory) {
        super(port, protocolFactory, readerFactory);
    }

    @Override
    protected void execute(BlockingConnectionHandler<T> handler) {
        new Thread(handler).start();
    }
    
}
