/*
 * Copyright 2015-present Open Networking Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.onosproject.store.cluster.messaging.impl;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManagerFactory;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.net.ConnectException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;

import com.google.common.base.Throwables;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.MoreExecutors;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import org.apache.commons.math3.stat.descriptive.SynchronizedDescriptiveStatistics;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.apache.felix.scr.annotations.Service;
import org.onosproject.cluster.ClusterMetadataService;
import org.onosproject.cluster.ControllerNode;
import org.onosproject.core.HybridLogicalClockService;
import org.onosproject.store.cluster.messaging.Endpoint;
import org.onosproject.store.cluster.messaging.MessagingException;
import org.onosproject.store.cluster.messaging.MessagingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.onlab.util.Tools.groupedThreads;
import static org.onosproject.security.AppGuard.checkPermission;
import static org.onosproject.security.AppPermission.Type.CLUSTER_WRITE;

/**
 * Netty based MessagingService.
 */
@Component(enabled = false)
@Service
public class NettyMessagingManager implements MessagingService {
    private static final long HISTORY_EXPIRE_MILLIS = Duration.ofMinutes(1).toMillis();
    private static final long TIMEOUT_INTERVAL = 50;
    private static final int WINDOW_SIZE = 60;
    private static final int WINDOW_UPDATE_SAMPLE_SIZE = 100;
    private static final long WINDOW_UPDATE_MILLIS = 10000;
    private static final int MIN_SAMPLES = 25;
    private static final int MIN_STANDARD_DEVIATION = 100;
    private static final int PHI_FAILURE_THRESHOLD = 12;
    private static final long MIN_TIMEOUT_MILLIS = 100;
    private static final long MAX_TIMEOUT_MILLIS = 5000;
    private static final int CHANNEL_POOL_SIZE = 8;

    private static final byte[] EMPTY_PAYLOAD = new byte[0];

    private final Logger log = LoggerFactory.getLogger(getClass());

    private final LocalClientConnection localClientConnection = new LocalClientConnection();
    private final LocalServerConnection localServerConnection = new LocalServerConnection(null);

    //TODO CONFIG_DIR is duplicated from ConfigFileBasedClusterMetadataProvider
    private static final String CONFIG_DIR = "../config";
    private static final String KS_FILE_NAME = "onos.jks";
    private static final File DEFAULT_KS_FILE = new File(CONFIG_DIR, KS_FILE_NAME);
    private static final String DEFAULT_KS_PASSWORD = "changeit";

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected HybridLogicalClockService clockService;

    private Endpoint localEndpoint;
    private int preamble;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final Map<String, BiConsumer<InternalRequest, ServerConnection>> handlers = new ConcurrentHashMap<>();
    private final Map<Channel, RemoteClientConnection> clientConnections = Maps.newConcurrentMap();
    private final Map<Channel, RemoteServerConnection> serverConnections = Maps.newConcurrentMap();
    private final AtomicLong messageIdGenerator = new AtomicLong(0);

    private ScheduledFuture<?> timeoutFuture;

    private final Map<Endpoint, List<CompletableFuture<Channel>>> channels = Maps.newConcurrentMap();

    private EventLoopGroup serverGroup;
    private EventLoopGroup clientGroup;
    private Class<? extends ServerChannel> serverChannelClass;
    private Class<? extends Channel> clientChannelClass;
    private ScheduledExecutorService timeoutExecutor;

    protected static final boolean TLS_ENABLED = true;
    protected static final boolean TLS_DISABLED = false;
    protected boolean enableNettyTls = TLS_ENABLED;

    protected TrustManagerFactory trustManager;
    protected KeyManagerFactory keyManager;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected ClusterMetadataService clusterMetadataService;

    @Activate
    public void activate() throws InterruptedException {
        ControllerNode localNode = clusterMetadataService.getLocalNode();
        getTlsParameters();

        if (started.get()) {
            log.warn("Already running at local endpoint: {}", localEndpoint);
            return;
        }
        this.preamble = clusterMetadataService.getClusterMetadata().getName().hashCode();
        this.localEndpoint = new Endpoint(localNode.ip(), localNode.tcpPort());
        initEventLoopGroup();
        startAcceptingConnections();
        timeoutExecutor = Executors.newSingleThreadScheduledExecutor(
                groupedThreads("NettyMessagingEvt", "timeout", log));
        timeoutFuture = timeoutExecutor.scheduleAtFixedRate(
                this::timeoutAllCallbacks, TIMEOUT_INTERVAL, TIMEOUT_INTERVAL, TimeUnit.MILLISECONDS);
        started.set(true);
        log.info("Started");
    }

    @Deactivate
    public void deactivate() {
        if (started.get()) {
            serverGroup.shutdownGracefully();
            clientGroup.shutdownGracefully();
            timeoutFuture.cancel(false);
            timeoutExecutor.shutdown();
            started.set(false);
        }
        log.info("Stopped");
    }

    private void getTlsParameters() {
        // default is TLS enabled unless key stores cannot be loaded
        enableNettyTls = Boolean.parseBoolean(System.getProperty("enableNettyTLS", Boolean.toString(TLS_ENABLED)));

        if (enableNettyTls) {
            enableNettyTls = loadKeyStores();
        }
    }

    private boolean loadKeyStores() {
        // Maintain a local copy of the trust and key managers in case anything goes wrong
        TrustManagerFactory tmf;
        KeyManagerFactory kmf;
        try {
            String ksLocation = System.getProperty("javax.net.ssl.keyStore", DEFAULT_KS_FILE.toString());
            String tsLocation = System.getProperty("javax.net.ssl.trustStore", DEFAULT_KS_FILE.toString());
            char[] ksPwd = System.getProperty("javax.net.ssl.keyStorePassword", DEFAULT_KS_PASSWORD).toCharArray();
            char[] tsPwd = System.getProperty("javax.net.ssl.trustStorePassword", DEFAULT_KS_PASSWORD).toCharArray();

            tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            KeyStore ts = KeyStore.getInstance("JKS");
            ts.load(new FileInputStream(tsLocation), tsPwd);
            tmf.init(ts);

            kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            KeyStore ks = KeyStore.getInstance("JKS");
            ks.load(new FileInputStream(ksLocation), ksPwd);
            kmf.init(ks, ksPwd);
            if (log.isInfoEnabled()) {
                logKeyStore(ks, ksLocation, ksPwd);
            }
        } catch (FileNotFoundException e) {
            log.warn("Disabling TLS for intra-cluster messaging; Could not load cluster key store: {}", e.getMessage());
            return TLS_DISABLED;
        } catch (Exception e) {
            //TODO we might want to catch exceptions more specifically
            log.error("Error loading key store; disabling TLS for intra-cluster messaging", e);
            return TLS_DISABLED;
        }
        this.trustManager = tmf;
        this.keyManager = kmf;
        return TLS_ENABLED;
    }

    private void logKeyStore(KeyStore ks, String ksLocation, char[] ksPwd) {
        if (log.isInfoEnabled()) {
            log.info("Loaded cluster key store from: {}", ksLocation);
            try {
                for (Enumeration<String> e = ks.aliases(); e.hasMoreElements();) {
                    String alias = e.nextElement();
                    Certificate cert = ks.getCertificate(alias);
                    if (cert == null) {
                        log.info("No certificate for alias {}", alias);
                        continue;
                    }
                    PublicKey key = cert.getPublicKey();
                    // Compute the certificate's fingerprint (use the key if certificate cannot be found)
                    MessageDigest digest = MessageDigest.getInstance("SHA1");
                    digest.update(key.getEncoded());
                    StringJoiner fingerprint = new StringJoiner(":");
                    for (byte b : digest.digest()) {
                        fingerprint.add(String.format("%02X", b));
                    }
                    log.info("{} -> {}", alias, fingerprint);
                }
            } catch (Exception e) {
                log.warn("Unable to print contents of key store: {}", ksLocation, e);
            }
        }
    }

    private void initEventLoopGroup() {
        // try Epoll first and if that does work, use nio.
        try {
            clientGroup = new EpollEventLoopGroup(0, groupedThreads("NettyMessagingEvt", "epollC-%d", log));
            serverGroup = new EpollEventLoopGroup(0, groupedThreads("NettyMessagingEvt", "epollS-%d", log));
            serverChannelClass = EpollServerSocketChannel.class;
            clientChannelClass = EpollSocketChannel.class;
            return;
        } catch (Throwable e) {
            log.debug("Failed to initialize native (epoll) transport. "
                    + "Reason: {}. Proceeding with nio.", e.getMessage());
        }
        clientGroup = new NioEventLoopGroup(0, groupedThreads("NettyMessagingEvt", "nioC-%d", log));
        serverGroup = new NioEventLoopGroup(0, groupedThreads("NettyMessagingEvt", "nioS-%d", log));
        serverChannelClass = NioServerSocketChannel.class;
        clientChannelClass = NioSocketChannel.class;
    }

    /**
     * Times out response callbacks.
     */
    private void timeoutAllCallbacks() {
        // Iterate through all connections and time out callbacks.
        localClientConnection.timeoutCallbacks();
        for (RemoteClientConnection connection : clientConnections.values()) {
            connection.timeoutCallbacks();
        }
    }

    @Override
    public CompletableFuture<Void> sendAsync(Endpoint ep, String type, byte[] payload) {
        checkPermission(CLUSTER_WRITE);
        InternalRequest message = new InternalRequest(preamble,
                clockService.timeNow(),
                messageIdGenerator.incrementAndGet(),
                localEndpoint,
                type,
                payload);
        return executeOnPooledConnection(ep, type, c -> c.sendAsync(message), MoreExecutors.directExecutor());
    }

    @Override
    public CompletableFuture<byte[]> sendAndReceive(Endpoint ep, String type, byte[] payload) {
        checkPermission(CLUSTER_WRITE);
        return sendAndReceive(ep, type, payload, MoreExecutors.directExecutor());
    }

    @Override
    public CompletableFuture<byte[]> sendAndReceive(Endpoint ep, String type, byte[] payload, Executor executor) {
        checkPermission(CLUSTER_WRITE);
        long messageId = messageIdGenerator.incrementAndGet();
        InternalRequest message = new InternalRequest(preamble,
                clockService.timeNow(),
                messageId,
                localEndpoint,
                type,
                payload);
        return executeOnPooledConnection(ep, type, c -> c.sendAndReceive(message), executor);
    }

    private List<CompletableFuture<Channel>> getChannelPool(Endpoint endpoint) {
        return channels.computeIfAbsent(endpoint, e -> {
            List<CompletableFuture<Channel>> defaultList = new ArrayList<>(CHANNEL_POOL_SIZE);
            for (int i = 0; i < CHANNEL_POOL_SIZE; i++) {
                defaultList.add(null);
            }
            return Lists.newCopyOnWriteArrayList(defaultList);
        });
    }

    private int getChannelOffset(String messageType) {
        return Math.abs(messageType.hashCode() % CHANNEL_POOL_SIZE);
    }

    private CompletableFuture<Channel> getChannel(Endpoint endpoint, String messageType) {
        List<CompletableFuture<Channel>> channelPool = getChannelPool(endpoint);
        int offset = getChannelOffset(messageType);

        CompletableFuture<Channel> channelFuture = channelPool.get(offset);
        if (channelFuture == null || channelFuture.isCompletedExceptionally()) {
            synchronized (channelPool) {
                channelFuture = channelPool.get(offset);
                if (channelFuture == null || channelFuture.isCompletedExceptionally()) {
                    channelFuture = openChannel(endpoint);
                    channelPool.set(offset, channelFuture);
                }
            }
        }

        CompletableFuture<Channel> future = new CompletableFuture<>();
        final CompletableFuture<Channel> finalFuture = channelFuture;
        finalFuture.whenComplete((channel, error) -> {
            if (error == null) {
                if (!channel.isActive()) {
                    CompletableFuture<Channel> currentFuture;
                    synchronized (channelPool) {
                        currentFuture = channelPool.get(offset);
                        if (currentFuture == finalFuture) {
                            channelPool.set(offset, null);
                        }
                    }

                    ClientConnection connection = clientConnections.remove(channel);
                    if (connection != null) {
                        connection.close();
                    }

                    if (currentFuture == finalFuture) {
                        getChannel(endpoint, messageType).whenComplete((recursiveResult, recursiveError) -> {
                            if (recursiveError == null) {
                                future.complete(recursiveResult);
                            } else {
                                future.completeExceptionally(recursiveError);
                            }
                        });
                    } else {
                        currentFuture.whenComplete((recursiveResult, recursiveError) -> {
                            if (recursiveError == null) {
                                future.complete(recursiveResult);
                            } else {
                                future.completeExceptionally(recursiveError);
                            }
                        });
                    }
                } else {
                    future.complete(channel);
                }
            } else {
                future.completeExceptionally(error);
            }
        });
        return future;
    }

    private <T> CompletableFuture<T> executeOnPooledConnection(
            Endpoint endpoint,
            String type,
            Function<ClientConnection, CompletableFuture<T>> callback,
            Executor executor) {
        CompletableFuture<T> future = new CompletableFuture<T>();
        executeOnPooledConnection(endpoint, type, callback, executor, future);
        return future;
    }

    private <T> void executeOnPooledConnection(
            Endpoint endpoint,
            String type,
            Function<ClientConnection, CompletableFuture<T>> callback,
            Executor executor,
            CompletableFuture<T> future) {
        if (endpoint.equals(localEndpoint)) {
            callback.apply(localClientConnection).whenComplete((result, error) -> {
                if (error == null) {
                    executor.execute(() -> future.complete(result));
                } else {
                    executor.execute(() -> future.completeExceptionally(error));
                }
            });
            return;
        }

        getChannel(endpoint, type).whenComplete((channel, channelError) -> {
            if (channelError == null) {
                ClientConnection connection = clientConnections.computeIfAbsent(channel, RemoteClientConnection::new);
                callback.apply(connection).whenComplete((result, sendError) -> {
                    if (sendError == null) {
                        executor.execute(() -> future.complete(result));
                    } else {
                        Throwable cause = Throwables.getRootCause(sendError);
                        if (!(cause instanceof TimeoutException) && !(cause instanceof MessagingException)) {
                            channel.close().addListener(f -> {
                                connection.close();
                                clientConnections.remove(channel);
                            });
                        }
                        executor.execute(() -> future.completeExceptionally(sendError));
                    }
                });
            } else {
                executor.execute(() -> future.completeExceptionally(channelError));
            }
        });
    }

    @Override
    public void registerHandler(String type, BiConsumer<Endpoint, byte[]> handler, Executor executor) {
        checkPermission(CLUSTER_WRITE);
        handlers.put(type, (message, connection) -> executor.execute(() ->
                handler.accept(message.sender(), message.payload())));
    }

    @Override
    public void registerHandler(String type, BiFunction<Endpoint, byte[], byte[]> handler, Executor executor) {
        checkPermission(CLUSTER_WRITE);
        handlers.put(type, (message, connection) -> executor.execute(() -> {
            byte[] responsePayload = null;
            InternalReply.Status status = InternalReply.Status.OK;
            try {
                responsePayload = handler.apply(message.sender(), message.payload());
            } catch (Exception e) {
                log.debug("An error occurred in a message handler: {}", e);
                status = InternalReply.Status.ERROR_HANDLER_EXCEPTION;
            }
            connection.reply(message, status, Optional.ofNullable(responsePayload));
        }));
    }

    @Override
    public void registerHandler(String type, BiFunction<Endpoint, byte[], CompletableFuture<byte[]>> handler) {
        checkPermission(CLUSTER_WRITE);
        handlers.put(type, (message, connection) -> {
            handler.apply(message.sender(), message.payload()).whenComplete((result, error) -> {
                InternalReply.Status status;
                if (error == null) {
                    status = InternalReply.Status.OK;
                } else {
                    log.debug("An error occurred in a message handler: {}", error);
                    status = InternalReply.Status.ERROR_HANDLER_EXCEPTION;
                }
                connection.reply(message, status, Optional.ofNullable(result));
            });
        });
    }

    @Override
    public void unregisterHandler(String type) {
        checkPermission(CLUSTER_WRITE);
        handlers.remove(type);
    }

    private Bootstrap bootstrapClient(Endpoint endpoint) {
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT);
        bootstrap.option(ChannelOption.WRITE_BUFFER_WATER_MARK,
                new WriteBufferWaterMark(10 * 32 * 1024, 10 * 64 * 1024));
        bootstrap.option(ChannelOption.SO_SNDBUF, 1048576);
        bootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 1000);
        bootstrap.group(clientGroup);
        bootstrap.channel(clientChannelClass);
        bootstrap.option(ChannelOption.SO_KEEPALIVE, true);
        bootstrap.remoteAddress(endpoint.host().toInetAddress(), endpoint.port());
        if (enableNettyTls) {
            bootstrap.handler(new SslClientCommunicationChannelInitializer());
        } else {
            bootstrap.handler(new BasicChannelInitializer());
        }
        return bootstrap;
    }

    private void startAcceptingConnections() throws InterruptedException {
        ServerBootstrap b = new ServerBootstrap();
        b.childOption(ChannelOption.WRITE_BUFFER_WATER_MARK,
                new WriteBufferWaterMark(8 * 1024, 32 * 1024));
        b.option(ChannelOption.SO_RCVBUF, 1048576);
        b.childOption(ChannelOption.SO_KEEPALIVE, true);
        b.childOption(ChannelOption.TCP_NODELAY, true);
        b.childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT);
        b.group(serverGroup, clientGroup);
        b.channel(serverChannelClass);
        if (enableNettyTls) {
            b.childHandler(new SslServerCommunicationChannelInitializer());
        } else {
            b.childHandler(new BasicChannelInitializer());
        }
        b.option(ChannelOption.SO_BACKLOG, 128);
        b.childOption(ChannelOption.SO_KEEPALIVE, true);

        // Bind and start to accept incoming connections.
        b.bind(localEndpoint.port()).sync().addListener(future -> {
            if (future.isSuccess()) {
                log.info("{} accepting incoming connections on port {}",
                        localEndpoint.host(), localEndpoint.port());
            } else {
                log.warn("{} failed to bind to port {} due to {}",
                        localEndpoint.host(), localEndpoint.port(), future.cause());
            }
        });
    }

    private CompletableFuture<Channel> openChannel(Endpoint ep) {
        Bootstrap bootstrap = bootstrapClient(ep);
        CompletableFuture<Channel> retFuture = new CompletableFuture<>();
        ChannelFuture f = bootstrap.connect();

        f.addListener(future -> {
            if (future.isSuccess()) {
                retFuture.complete(f.channel());
            } else {
                retFuture.completeExceptionally(future.cause());
            }
        });
        log.debug("Established a new connection to {}", ep);
        return retFuture;
    }

    /**
     * Channel initializer for TLS servers.
     */
    private class SslServerCommunicationChannelInitializer extends ChannelInitializer<SocketChannel> {
        private final ChannelHandler dispatcher = new InboundMessageDispatcher();

        @Override
        protected void initChannel(SocketChannel channel) throws Exception {
            SSLContext serverContext = SSLContext.getInstance("TLS");
            serverContext.init(keyManager.getKeyManagers(), trustManager.getTrustManagers(), null);

            SSLEngine serverSslEngine = serverContext.createSSLEngine();

            serverSslEngine.setNeedClientAuth(true);
            serverSslEngine.setUseClientMode(false);
            serverSslEngine.setEnabledProtocols(serverSslEngine.getSupportedProtocols());
            serverSslEngine.setEnabledCipherSuites(serverSslEngine.getSupportedCipherSuites());
            serverSslEngine.setEnableSessionCreation(true);

            channel.pipeline().addLast("ssl", new io.netty.handler.ssl.SslHandler(serverSslEngine))
                    .addLast("encoder", new MessageEncoder(localEndpoint, preamble))
                    .addLast("decoder", new MessageDecoder())
                    .addLast("handler", dispatcher);
        }
    }

    /**
     * Channel initializer for TLS clients.
     */
    private class SslClientCommunicationChannelInitializer extends ChannelInitializer<SocketChannel> {
        private final ChannelHandler dispatcher = new InboundMessageDispatcher();

        @Override
        protected void initChannel(SocketChannel channel) throws Exception {
            SSLContext clientContext = SSLContext.getInstance("TLS");
            clientContext.init(keyManager.getKeyManagers(), trustManager.getTrustManagers(), null);

            SSLEngine clientSslEngine = clientContext.createSSLEngine();

            clientSslEngine.setUseClientMode(true);
            clientSslEngine.setEnabledProtocols(clientSslEngine.getSupportedProtocols());
            clientSslEngine.setEnabledCipherSuites(clientSslEngine.getSupportedCipherSuites());
            clientSslEngine.setEnableSessionCreation(true);

            channel.pipeline().addLast("ssl", new io.netty.handler.ssl.SslHandler(clientSslEngine))
                    .addLast("encoder", new MessageEncoder(localEndpoint, preamble))
                    .addLast("decoder", new MessageDecoder())
                    .addLast("handler", dispatcher);
        }
    }

    /**
     * Channel initializer for basic connections.
     */
    private class BasicChannelInitializer extends ChannelInitializer<SocketChannel> {
        private final ChannelHandler dispatcher = new InboundMessageDispatcher();

        @Override
        protected void initChannel(SocketChannel channel) throws Exception {
            channel.pipeline()
                    .addLast("encoder", new MessageEncoder(localEndpoint, preamble))
                    .addLast("decoder", new MessageDecoder())
                    .addLast("handler", dispatcher);
        }
    }

    /**
     * Channel inbound handler that dispatches messages to the appropriate handler.
     */
    @ChannelHandler.Sharable
    private class InboundMessageDispatcher extends SimpleChannelInboundHandler<Object> {
        // Effectively SimpleChannelInboundHandler<InternalMessage>,
        // had to specify <Object> to avoid Class Loader not being able to find some classes.

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Object rawMessage) throws Exception {
            InternalMessage message = (InternalMessage) rawMessage;
            try {
                if (message.isRequest()) {
                    RemoteServerConnection connection =
                            serverConnections.computeIfAbsent(ctx.channel(), RemoteServerConnection::new);
                    connection.dispatch((InternalRequest) message);
                } else {
                    RemoteClientConnection connection =
                            clientConnections.computeIfAbsent(ctx.channel(), RemoteClientConnection::new);
                    connection.dispatch((InternalReply) message);
                }
            } catch (RejectedExecutionException e) {
                log.warn("Unable to dispatch message due to {}", e.getMessage());
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
            log.error("Exception inside channel handling pipeline.", cause);

            RemoteClientConnection clientConnection = clientConnections.remove(context.channel());
            if (clientConnection != null) {
                clientConnection.close();
            }

            RemoteServerConnection serverConnection = serverConnections.remove(context.channel());
            if (serverConnection != null) {
                serverConnection.close();
            }
            context.close();
        }

        @Override
        public void channelInactive(ChannelHandlerContext context) throws Exception {
            RemoteClientConnection clientConnection = clientConnections.remove(context.channel());
            if (clientConnection != null) {
                clientConnection.close();
            }

            RemoteServerConnection serverConnection = serverConnections.remove(context.channel());
            if (serverConnection != null) {
                serverConnection.close();
            }
            context.close();
        }

        /**
         * Returns true if the given message should be handled.
         *
         * @param msg inbound message
         * @return true if {@code msg} is {@link InternalMessage} instance.
         * @see SimpleChannelInboundHandler#acceptInboundMessage(Object)
         */
        @Override
        public final boolean acceptInboundMessage(Object msg) {
            return msg instanceof InternalMessage;
        }
    }

    /**
     * Wraps a {@link CompletableFuture} and tracks its type and creation time.
     */
    private final class Callback {
        private final String type;
        private final CompletableFuture<byte[]> future;
        private final long time = System.currentTimeMillis();

        Callback(String type, CompletableFuture<byte[]> future) {
            this.type = type;
            this.future = future;
        }

        public void complete(byte[] value) {
            future.complete(value);
        }

        public void completeExceptionally(Throwable error) {
            future.completeExceptionally(error);
        }
    }

    /**
     * Represents the client side of a connection to a local or remote server.
     */
    private interface ClientConnection {

        /**
         * Sends a message to the other side of the connection.
         *
         * @param message the message to send
         * @return a completable future to be completed once the message has been sent
         */
        CompletableFuture<Void> sendAsync(InternalRequest message);

        /**
         * Sends a message to the other side of the connection, awaiting a reply.
         *
         * @param message the message to send
         * @return a completable future to be completed once a reply is received or the request times out
         */
        CompletableFuture<byte[]> sendAndReceive(InternalRequest message);

        /**
         * Closes the connection.
         */
        default void close() {
        }
    }

    /**
     * Represents the server side of a connection.
     */
    private interface ServerConnection {

        /**
         * Sends a reply to the other side of the connection.
         *
         * @param message the message to which to reply
         * @param status  the reply status
         * @param payload the response payload
         */
        void reply(InternalRequest message, InternalReply.Status status, Optional<byte[]> payload);

        /**
         * Closes the connection.
         */
        default void close() {
        }
    }

    /**
     * Remote connection implementation.
     */
    private abstract class AbstractClientConnection implements ClientConnection {
        private final Map<Long, Callback> futures = Maps.newConcurrentMap();
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final Cache<String, RequestMonitor> requestMonitors = CacheBuilder.newBuilder()
                .expireAfterAccess(HISTORY_EXPIRE_MILLIS, TimeUnit.MILLISECONDS)
                .build();

        /**
         * Times out callbacks for this connection.
         */
        void timeoutCallbacks() {
            // Store the current time.
            long currentTime = System.currentTimeMillis();

            // Iterate through future callbacks and time out callbacks that have been alive
            // longer than the current timeout according to the message type.
            Iterator<Map.Entry<Long, Callback>> iterator = futures.entrySet().iterator();
            while (iterator.hasNext()) {
                Callback callback = iterator.next().getValue();
                try {
                    RequestMonitor requestMonitor = requestMonitors.get(callback.type, RequestMonitor::new);
                    long elapsedTime = currentTime - callback.time;
                    if (elapsedTime > MAX_TIMEOUT_MILLIS ||
                        (elapsedTime > MIN_TIMEOUT_MILLIS && requestMonitor.isTimedOut(elapsedTime))) {
                        iterator.remove();
                        requestMonitor.addReplyTime(elapsedTime);
                        callback.completeExceptionally(
                                new TimeoutException("Request timed out in " + elapsedTime + " milliseconds"));
                    }
                } catch (ExecutionException e) {
                    throw new AssertionError();
                }
            }
        }

        protected void registerCallback(long id, String subject, CompletableFuture<byte[]> future) {
            futures.put(id, new Callback(subject, future));
        }

        protected Callback completeCallback(long id) {
            Callback callback = futures.remove(id);
            if (callback != null) {
                try {
                    RequestMonitor requestMonitor = requestMonitors.get(callback.type, RequestMonitor::new);
                    requestMonitor.addReplyTime(System.currentTimeMillis() - callback.time);
                } catch (ExecutionException e) {
                    throw new AssertionError();
                }
            }
            return callback;
        }

        protected Callback failCallback(long id) {
            return futures.remove(id);
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                for (Callback callback : futures.values()) {
                    callback.completeExceptionally(new ConnectException());
                }
            }
        }
    }

    /**
     * Local connection implementation.
     */
    private final class LocalClientConnection extends AbstractClientConnection {
        @Override
        public CompletableFuture<Void> sendAsync(InternalRequest message) {
            BiConsumer<InternalRequest, ServerConnection> handler = handlers.get(message.subject());
            if (handler != null) {
                handler.accept(message, localServerConnection);
            } else {
                log.debug("No handler for message type {} from {}", message.type(), message.sender());
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<byte[]> sendAndReceive(InternalRequest message) {
            CompletableFuture<byte[]> future = new CompletableFuture<>();
            future.whenComplete((r, e) -> completeCallback(message.id()));
            registerCallback(message.id(), message.subject(), future);
            BiConsumer<InternalRequest, ServerConnection> handler = handlers.get(message.subject());
            if (handler != null) {
                handler.accept(message, new LocalServerConnection(future));
            } else {
                log.debug("No handler for message type {} from {}", message.type(), message.sender());
                new LocalServerConnection(future)
                        .reply(message, InternalReply.Status.ERROR_NO_HANDLER, Optional.empty());
            }
            return future;
        }
    }

    /**
     * Local server connection.
     */
    private final class LocalServerConnection implements ServerConnection {
        private final CompletableFuture<byte[]> future;

        LocalServerConnection(CompletableFuture<byte[]> future) {
            this.future = future;
        }

        @Override
        public void reply(InternalRequest message, InternalReply.Status status, Optional<byte[]> payload) {
            if (future != null) {
                if (status == InternalReply.Status.OK) {
                    future.complete(payload.orElse(EMPTY_PAYLOAD));
                } else if (status == InternalReply.Status.ERROR_NO_HANDLER) {
                    future.completeExceptionally(new MessagingException.NoRemoteHandler());
                } else if (status == InternalReply.Status.ERROR_HANDLER_EXCEPTION) {
                    future.completeExceptionally(new MessagingException.RemoteHandlerFailure());
                } else if (status == InternalReply.Status.PROTOCOL_EXCEPTION) {
                    future.completeExceptionally(new MessagingException.ProtocolException());
                }
            }
        }
    }

    /**
     * Remote connection implementation.
     */
    private final class RemoteClientConnection extends AbstractClientConnection {
        private final Channel channel;

        RemoteClientConnection(Channel channel) {
            this.channel = channel;
        }

        @Override
        public CompletableFuture<Void> sendAsync(InternalRequest message) {
            CompletableFuture<Void> future = new CompletableFuture<>();
            channel.writeAndFlush(message).addListener(channelFuture -> {
                if (!channelFuture.isSuccess()) {
                    future.completeExceptionally(channelFuture.cause());
                } else {
                    future.complete(null);
                }
            });
            return future;
        }

        @Override
        public CompletableFuture<byte[]> sendAndReceive(InternalRequest message) {
            CompletableFuture<byte[]> future = new CompletableFuture<>();
            registerCallback(message.id(), message.subject(), future);
            channel.writeAndFlush(message).addListener(channelFuture -> {
                if (!channelFuture.isSuccess()) {
                    Callback callback = failCallback(message.id());
                    if (callback != null) {
                        callback.completeExceptionally(channelFuture.cause());
                    }
                }
            });
            return future;
        }

        /**
         * Dispatches a message to a local handler.
         *
         * @param message the message to dispatch
         */
        private void dispatch(InternalReply message) {
            if (message.preamble() != preamble) {
                log.debug("Received {} with invalid preamble", message.type());
                return;
            }

            clockService.recordEventTime(message.time());

            Callback callback = completeCallback(message.id());
            if (callback != null) {
                if (message.status() == InternalReply.Status.OK) {
                    callback.complete(message.payload());
                } else if (message.status() == InternalReply.Status.ERROR_NO_HANDLER) {
                    callback.completeExceptionally(new MessagingException.NoRemoteHandler());
                } else if (message.status() == InternalReply.Status.ERROR_HANDLER_EXCEPTION) {
                    callback.completeExceptionally(new MessagingException.RemoteHandlerFailure());
                } else if (message.status() == InternalReply.Status.PROTOCOL_EXCEPTION) {
                    callback.completeExceptionally(new MessagingException.ProtocolException());
                }
            } else {
                log.debug("Received a reply for message id:[{}] "
                        + "but was unable to locate the"
                        + " request handle", message.id());
            }
        }
    }

    /**
     * Remote server connection.
     */
    private final class RemoteServerConnection implements ServerConnection {
        private final Channel channel;

        RemoteServerConnection(Channel channel) {
            this.channel = channel;
        }

        /**
         * Dispatches a message to a local handler.
         *
         * @param message the message to dispatch
         */
        private void dispatch(InternalRequest message) {
            if (message.preamble() != preamble) {
                log.debug("Received {} with invalid preamble from {}", message.type(), message.sender());
                reply(message, InternalReply.Status.PROTOCOL_EXCEPTION, Optional.empty());
                return;
            }

            clockService.recordEventTime(message.time());

            BiConsumer<InternalRequest, ServerConnection> handler = handlers.get(message.subject());
            if (handler != null) {
                handler.accept(message, this);
            } else {
                log.debug("No handler for message type {} from {}", message.type(), message.sender());
                reply(message, InternalReply.Status.ERROR_NO_HANDLER, Optional.empty());
            }
        }

        @Override
        public void reply(InternalRequest message, InternalReply.Status status, Optional<byte[]> payload) {
            InternalReply response = new InternalReply(preamble,
                    clockService.timeNow(),
                    message.id(),
                    payload.orElse(EMPTY_PAYLOAD),
                    status);
            channel.writeAndFlush(response);
        }
    }

    /**
     * Request-reply timeout history tracker.
     */
    private static final class RequestMonitor {
        private final DescriptiveStatistics samples = new SynchronizedDescriptiveStatistics(WINDOW_SIZE);
        private final AtomicLong max = new AtomicLong();
        private volatile int replyCount;
        private volatile long lastUpdate = System.currentTimeMillis();

        /**
         * Adds a reply time to the history.
         *
         * @param replyTime the reply time to add to the history
         */
        void addReplyTime(long replyTime) {
            max.accumulateAndGet(replyTime, Math::max);

            // If at least WINDOW_UPDATE_SAMPLE_SIZE response times have been recorded, and at least
            // WINDOW_UPDATE_MILLIS have passed since the last update, record the maximum response time in the samples.
            int replyCount = ++this.replyCount;
            if (replyCount >= WINDOW_UPDATE_SAMPLE_SIZE
                && System.currentTimeMillis() - lastUpdate > WINDOW_UPDATE_MILLIS) {
                synchronized (this) {
                    if (System.currentTimeMillis() - lastUpdate > WINDOW_UPDATE_MILLIS) {
                        long lastMax = max.get();
                        if (lastMax > 0) {
                            samples.addValue(lastMax);
                            lastUpdate = System.currentTimeMillis();
                            this.replyCount = 0;
                            max.set(0);
                        }
                    }
                }
            }
        }

        /**
         * Returns a boolean indicating whether the given request should be timed out according to the elapsed time.
         *
         * @param elapsedTime the elapsed request time
         * @return indicates whether the request should be timed out
         */
        boolean isTimedOut(long elapsedTime) {
            return samples.getN() == WINDOW_SIZE && phi(elapsedTime) >= PHI_FAILURE_THRESHOLD;
        }

        /**
         * Compute phi for the specified node id.
         *
         * @param elapsedTime the duration since the request was sent
         * @return phi value
         */
        private double phi(long elapsedTime) {
            if (samples.getN() < MIN_SAMPLES) {
                return 0.0;
            }
            return computePhi(samples, elapsedTime);
        }

        /**
         * Computes the phi value from the given samples.
         *
         * @param samples     the samples from which to compute phi
         * @param elapsedTime the duration since the request was sent
         * @return phi
         */
        private double computePhi(DescriptiveStatistics samples, long elapsedTime) {
            double meanMillis = samples.getMean();
            double y = (elapsedTime - meanMillis) / Math.max(samples.getStandardDeviation(), MIN_STANDARD_DEVIATION);
            double e = Math.exp(-y * (1.5976 + 0.070566 * y * y));
            if (elapsedTime > meanMillis) {
                return -Math.log10(e / (1.0 + e));
            } else {
                return -Math.log10(1.0 - 1.0 / (1.0 + e));
            }
        }
    }
}
