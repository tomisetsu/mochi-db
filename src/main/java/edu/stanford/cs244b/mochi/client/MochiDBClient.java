package edu.stanford.cs244b.mochi.client;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.stanford.cs244b.mochi.server.Utils;
import edu.stanford.cs244b.mochi.server.messages.MochiProtocol.MultiGrant;
import edu.stanford.cs244b.mochi.server.messages.MochiProtocol.ProtocolMessage;
import edu.stanford.cs244b.mochi.server.messages.MochiProtocol.Transaction;
import edu.stanford.cs244b.mochi.server.messages.MochiProtocol.Write1OkFromServer;
import edu.stanford.cs244b.mochi.server.messages.MochiProtocol.Write1ToServer;
import edu.stanford.cs244b.mochi.server.messages.MochiProtocol.Write2AnsFromServer;
import edu.stanford.cs244b.mochi.server.messages.MochiProtocol.Write2ToServer;
import edu.stanford.cs244b.mochi.server.messages.MochiProtocol.WriteCertificate;
import edu.stanford.cs244b.mochi.server.messaging.MochiMessaging;
import edu.stanford.cs244b.mochi.server.messaging.Server;

public class MochiDBClient implements Closeable {
    final static Logger LOG = LoggerFactory.getLogger(MochiDBClient.class);

    private final AtomicLong operationNumberCounter = new AtomicLong(1);
    private final String mochiDBClientID = Utils.getUUIDwithPref(Utils.UUID_PREFIXES.CLIENT);
    private final MochiMessaging mochiMessaging = new MochiMessaging();
    private final Set<Server> servers = new HashSet<Server>();

    public MochiDBClient() {
    };
    
    public void addServers(final Server... servers) {
        for (final Server s : servers) {
            this.servers.add(s);
        }
    }

    public void addServers(final Collection<Server> servers) {
        for (final Server s : servers) {
            this.servers.add(s);
        }
    }

    public void waitForConnectionToBeEstablishedToServers() {
        for (final Server s : servers) {
            mochiMessaging.waitForConnectionToBeEstablished(s);
            LOG.debug("Connection to server {} was established", s);
        }
    }

    public long getNextOperationNumber() {
        return operationNumberCounter.getAndIncrement();
    }

    public void executeWriteTransaction(final Transaction transactionToExecute) {

        final Write1ToServer.Builder write1toServerBuilder = Write1ToServer.newBuilder();
        write1toServerBuilder.setTransaction(transactionToExecute);

        final List<Future<ProtocolMessage>> write1responseFutures = Utils.sendMessageToServers(write1toServerBuilder,
                servers, mochiMessaging);
        Utils.busyWaitForFutures(write1responseFutures);
        LOG.debug("Resolved write1response futures");
        final List<ProtocolMessage> write1responseProtocalMessages = Utils.getFutures(write1responseFutures);

        final List<Object> messages1FromServers = new ArrayList<Object>(servers.size());
        boolean allWriteOk = true;
        for (ProtocolMessage pm : write1responseProtocalMessages) {
            final Write1OkFromServer writeOkFromServer = pm.getWrite1OkFromServer();
            if (writeOkFromServer != null) {
                messages1FromServers.add(writeOkFromServer);
            } else {
                allWriteOk = false;
                // TODO: handle other responses
                Utils.assertNotNull(writeOkFromServer, "Expected write1ok from the server");
            }
        }

        final Map<String, MultiGrant> write1mutiGrants = new HashMap<String, MultiGrant>();
        for (Object messageFromServer : messages1FromServers) {
            if (messageFromServer instanceof Write1OkFromServer) {
                final MultiGrant mg = ((Write1OkFromServer) messageFromServer).getMultiGrant();
                Utils.assertNotNull(mg, "server1MultiGrant is null");
                Utils.assertNotNull(mg.getServerId(), "serverId is null");
                write1mutiGrants.put(mg.getServerId(), mg);
            } else {
                throw new UnsupportedOperationException();
            }
        }
        LOG.info("Got write gratns servers {}. Progressing to step 2. Multigrants: ", write1mutiGrants.keySet(),
                write1mutiGrants.values());

        // Step 2:

        final WriteCertificate.Builder wcb = WriteCertificate.newBuilder();
        wcb.putAllGrants(write1mutiGrants);

        final Write2ToServer.Builder write2ToServerBuilder = Write2ToServer.newBuilder();
        write2ToServerBuilder.setWriteCertificate(wcb);

        final List<Future<ProtocolMessage>> write2responseFutures = Utils.sendMessageToServers(write2ToServerBuilder,
                servers, mochiMessaging);
        Utils.busyWaitForFutures(write2responseFutures);
        LOG.debug("Resolved write2response futures");
        final List<ProtocolMessage> write2responseProtocolMessages = Utils.getFutures(write2responseFutures);

        final List<Write2AnsFromServer> write2ansFromServers = new ArrayList<Write2AnsFromServer>(servers.size());
        for (final ProtocolMessage pm : write2responseProtocolMessages) {
            final Write2AnsFromServer write2AnsFromServer = pm.getWrite2AnsFromServer();
            Utils.assertNotNull(write2AnsFromServer, "write2AnsFromServer is null");
        }
    }

    private void executePhase2() {
        // TODO
    }

    @Override
    public void close() {
        mochiMessaging.close();
    }

}