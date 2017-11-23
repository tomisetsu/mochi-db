package edu.stanford.cs244b.mochi.server;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;
import org.testng.Assert;
import org.testng.annotations.Test;

import edu.stanford.cs244b.mochi.client.MochiDBClient;
import edu.stanford.cs244b.mochi.server.messages.MochiProtocol.HelloFromServer;
import edu.stanford.cs244b.mochi.server.messages.MochiProtocol.HelloFromServer2;
import edu.stanford.cs244b.mochi.server.messages.MochiProtocol.Operation;
import edu.stanford.cs244b.mochi.server.messages.MochiProtocol.OperationAction;
import edu.stanford.cs244b.mochi.server.messages.MochiProtocol.ProtocolMessage;
import edu.stanford.cs244b.mochi.server.messages.MochiProtocol.ReadToServer;
import edu.stanford.cs244b.mochi.server.messages.MochiProtocol.ReadFromServer;
import edu.stanford.cs244b.mochi.server.messages.MochiProtocol.Transaction;
import edu.stanford.cs244b.mochi.server.messaging.ConnectionNotReadyException;
import edu.stanford.cs244b.mochi.server.messaging.MochiMessaging;
import edu.stanford.cs244b.mochi.server.messaging.MochiServer;
import edu.stanford.cs244b.mochi.server.messaging.Server;
import edu.stanford.cs244b.mochi.server.requesthandlers.HelloToServer2RequestHandler;
import edu.stanford.cs244b.mochi.server.requesthandlers.HelloToServerRequestHandler;
import edu.stanford.cs244b.mochi.testingframework.InMemoryDSMochiContextImpl;
import edu.stanford.cs244b.mochi.testingframework.MochiVirtualCluster;

public class MochiClientServerCommunicationTest {
    final static Logger LOG = LoggerFactory.getLogger(MochiClientServerCommunicationTest.class);

    @Test
    public void testHelloToFromServer() throws InterruptedException {
        MochiServer ms = newMochiServer();
        ms.start();
        LOG.info("Mochi server started");

        final MochiMessaging mm = new MochiMessaging();

        HelloFromServer hfs = null;
        for (int i = 0; i < 10; i++) {
            LOG.debug("Trying to send helloToServer {}th time", i);
            try {
                hfs = mm.sayHelloToServer(ms.toServer());
                break;
            } catch (ConnectionNotReadyException cnrEx) {
                Thread.sleep(200);
            }
        }
        if (hfs == null) {
            Assert.fail("Connection did not get established");
        }
        String messageFromClient = hfs.getClientMsg();
        Assert.assertEquals(messageFromClient, MochiMessaging.CLIENT_HELLO_MESSAGE);

        mm.close();
    }

    @Test(expectedExceptions = { ConnectionNotReadyException.class, java.net.UnknownHostException.class })
    public void testConnectionNotKnownServer() {
        final MochiMessaging mm = new MochiMessaging();
        final HelloFromServer hfs = mm.sayHelloToServer(new Server("some_unknown_host", MochiServer.DEFAULT_PORT));

        mm.close();
    }

    @Test
    public void testHelloToFromServerMultiple() throws InterruptedException {
        final int serverPort1 = 8001;
        final int serverPort2 = 8002;
        MochiServer ms1 = newMochiServer(serverPort1);
        ms1.start();
        MochiServer ms2 = newMochiServer(serverPort2);
        ms2.start();
        LOG.info("Mochi servers started");

        final MochiMessaging mm = new MochiMessaging();
        mm.waitForConnectionToBeEstablished(ms1.toServer());
        mm.waitForConnectionToBeEstablished(ms2.toServer());

        for (int i = 0; i < 2; i++) {
            LOG.info("testHelloToFromServerMultiple iteration {}", i);
            HelloFromServer hfs1 = mm.sayHelloToServer(ms1.toServer());
            HelloFromServer hfs2 = mm.sayHelloToServer(ms2.toServer());
            Assert.assertTrue(hfs1 != null);
            Assert.assertTrue(hfs2 != null);
            HelloFromServer2 hfs21 = mm.sayHelloToServer2(ms1.toServer());
            HelloFromServer2 hfs22 = mm.sayHelloToServer2(ms2.toServer());
            Assert.assertTrue(hfs21 != null);
            Assert.assertTrue(hfs22 != null);

            Assert.assertEquals(hfs1.getClientMsg(), MochiMessaging.CLIENT_HELLO_MESSAGE);
            Assert.assertEquals(hfs2.getClientMsg(), MochiMessaging.CLIENT_HELLO_MESSAGE);
            Assert.assertEquals(hfs1.getMsg(), HelloToServerRequestHandler.HELLO_RESPONSE);
            Assert.assertEquals(hfs2.getMsg(), HelloToServerRequestHandler.HELLO_RESPONSE);

            Assert.assertEquals(hfs21.getClientMsg(), MochiMessaging.CLIENT_HELLO2_MESSAGE);
            Assert.assertEquals(hfs22.getClientMsg(), MochiMessaging.CLIENT_HELLO2_MESSAGE);
            Assert.assertEquals(hfs21.getMsg(), HelloToServer2RequestHandler.HELLO_RESPONSE);
            Assert.assertEquals(hfs22.getMsg(), HelloToServer2RequestHandler.HELLO_RESPONSE);
        }

        mm.close();
        ms1.close();
        ms2.close();
    }

    @Test
    public void testHelloToFromServerAsync() throws InterruptedException, ExecutionException {
        final int serverPort1 = 8001;
        MochiServer ms1 = newMochiServer(serverPort1);
        ms1.start();

        final MochiMessaging mm = new MochiMessaging();
        mm.waitForConnectionToBeEstablished(ms1.toServer());

        for (int i = 0; i < 2; i++) {
            LOG.info("testHelloToFromServerMultipleAsync iteration {}", i);
            Future<ProtocolMessage> hfsFuture1 = mm.sayHelloToServerAsync(getServerToTestAgainst(ms1.toServer()));
            Future<ProtocolMessage> hfsFuture2 = mm.sayHelloToServerAsync(getServerToTestAgainst(ms1.toServer()));
            Future<ProtocolMessage> hfsFuture3 = mm.sayHelloToServerAsync(getServerToTestAgainst(ms1.toServer()));
            Assert.assertTrue(hfsFuture1 != null);
            Assert.assertTrue(hfsFuture2 != null);
            Assert.assertTrue(hfsFuture3 != null);

            long waitStart = System.currentTimeMillis();
            while (true) {
                if (futureCancelledOrDone(hfsFuture1) && futureCancelledOrDone(hfsFuture2)
                        && futureCancelledOrDone(hfsFuture3)) {
                    break;
                }
                Thread.sleep(3);
            }
            long waitEnd = System.currentTimeMillis();
            long getStart = System.currentTimeMillis();
            // Those should be fast since futures were already resolved
            ProtocolMessage pm1 = hfsFuture1.get();
            ProtocolMessage pm2 = hfsFuture2.get();
            ProtocolMessage pm3 = hfsFuture3.get();
            long getEnd = System.currentTimeMillis();

            HelloFromServer hfs1 = pm1.getHelloFromServer();
            HelloFromServer hfs2 = pm2.getHelloFromServer();
            HelloFromServer hfs3 = pm3.getHelloFromServer();

            LOG.info("Got requests from the server. Took {} ms to wait for response and {} ms to get them",
                    (waitEnd - waitStart), (getEnd - getStart));
            Assert.assertEquals(hfs1.getClientMsg(), MochiMessaging.CLIENT_HELLO_MESSAGE);
            Assert.assertEquals(hfs2.getClientMsg(), MochiMessaging.CLIENT_HELLO_MESSAGE);
            Assert.assertEquals(hfs3.getClientMsg(), MochiMessaging.CLIENT_HELLO_MESSAGE);
        }

        mm.close();
        ms1.close();
    }

    @Test
    public void testReadOperation() throws InterruptedException, ExecutionException {
        // TODO: Use Mochi Testing Framework
        final int serverPort1 = 8001;
        MochiServer ms1 = newMochiServer(serverPort1);
        ms1.start();

        final MochiMessaging mm = new MochiMessaging();
        mm.waitForConnectionToBeEstablished(ms1.toServer());

        final ReadToServer.Builder rbuilder = ReadToServer.newBuilder();
        rbuilder.setClientId(Utils.getUUID());
        rbuilder.setNonce(Utils.getUUID());

        final Operation.Builder orBuilder = Operation.newBuilder();
        orBuilder.setAction(OperationAction.READ);
        orBuilder.setOperand1("DEMO_KEY_1");

        final Transaction.Builder trBuilder = Transaction.newBuilder();
        trBuilder.addOperations(orBuilder);

        rbuilder.setTransaction(trBuilder);
        final Future<ProtocolMessage> readResponseFutureServer1 = mm.sendAndReceive(ms1.toServer(), rbuilder);
        ProtocolMessage readResponsePMFromServer1 = readResponseFutureServer1.get();
        ReadFromServer readFromServer1 = readResponsePMFromServer1.getReadFromServer();
        Assert.assertNotNull(readFromServer1);
        ms1.close();
        mm.close();
    }

    @Test
    public void testWriteOperation() throws InterruptedException, ExecutionException {
        final int numberOfServersToTest = 2;
        final MochiVirtualCluster mochiVirtualCluster = new MochiVirtualCluster(numberOfServersToTest);
        mochiVirtualCluster.startAllServers();

        final MochiDBClient mochiDBclient = new MochiDBClient();
        mochiDBclient.addServers(mochiVirtualCluster.getAllServers());
        mochiDBclient.waitForConnectionToBeEstablishedToServers();

        final Operation.Builder oBuilder1 = Operation.newBuilder();
        oBuilder1.setAction(OperationAction.WRITE);
        oBuilder1.setOperand1("DEMO_KEY_1");
        oBuilder1.setOperand2("NEW_VALUE_FOR_KEY_1");
        oBuilder1.setOperationNumber(1); // First operation

        final Operation.Builder oBuilder2 = Operation.newBuilder();
        oBuilder2.setAction(OperationAction.WRITE);
        oBuilder2.setOperand1("DEMO_KEY_2");
        oBuilder2.setOperand2("NEW_VALUE_FOR_KEY_2");
        oBuilder2.setOperationNumber(1); // Second operation

        final Transaction.Builder tBuilder = Transaction.newBuilder();
        tBuilder.addOperations(oBuilder1);
        tBuilder.addOperations(oBuilder2);

        mochiDBclient.executeWriteTransaction(tBuilder.build());

        mochiVirtualCluster.close();
        mochiDBclient.close();
    }

    /*
     * Specify the remote server, port via command line arguments
     * -DremoteServer=###### -DremotePort=####
     */
    private Server getServerToTestAgainst(final Server server) {
        final String remoteServer = System.getProperty("remoteServer", null);
        final String remotePort = System.getProperty("remotePort", null);
        if (StringUtils.isEmpty(remoteServer) && StringUtils.isEmpty(remotePort)) {
            LOG.info("Using default server and port");
            return server;
        } else if (StringUtils.isEmpty(remotePort)) {
            LOG.info("Using remote server: {}", remoteServer);
            LOG.info("Using default port 8081");
            return new Server(remoteServer, 8081);
        } else {
            LOG.info("Using remote server: {}", remoteServer);
            LOG.info("Using remote port: {}", remotePort);
            return new Server(remoteServer, Integer.valueOf(remotePort));
        }
    }

    private boolean futureCancelledOrDone(Future<?> f) {
        return (f.isCancelled() || f.isDone());
    }

    private MochiServer newMochiServer() {
        return new MochiServer(new InMemoryDSMochiContextImpl());
    }

    private MochiServer newMochiServer(final int serverPort) {
        return new MochiServer(serverPort, new InMemoryDSMochiContextImpl());
    }
}
