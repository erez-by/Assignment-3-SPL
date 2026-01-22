package bgu.spl.net.impl.stomp;

import java.util.function.Supplier;
import bgu.spl.net.api.MessageEncoderDecoder;
import bgu.spl.net.api.StompMessagingProtocol;
import bgu.spl.net.srv.BaseServer;
import bgu.spl.net.srv.BlockingConnectionHandler;

public class TPCServer<T> extends BaseServer<T> {

<<<<<<< HEAD
    public TPCServer(int port, Supplier<StompMessagingProtocol<T>> protocolFactory, Supplier<MessageEncoderDecoder<T>> encdecFactory) {
        super(port, protocolFactory, encdecFactory);
=======
    public TPCServer(
            int port,
            Supplier<StompMessagingProtocol<T>> protocolFactory,
            Supplier<MessageEncoderDecoder<T>> readerFactory) {
        super(port, protocolFactory, readerFactory);
>>>>>>> 6b6ccabaf60d8bd57247e5edcc4cacbf1778a9f6
    }

    @Override
    protected void execute(BlockingConnectionHandler<T> handler) {
<<<<<<< HEAD
       // New Thread for every client
        new Thread(handler).start();
    }
=======
        new Thread(handler).start();
    }
    
>>>>>>> 6b6ccabaf60d8bd57247e5edcc4cacbf1778a9f6
}
