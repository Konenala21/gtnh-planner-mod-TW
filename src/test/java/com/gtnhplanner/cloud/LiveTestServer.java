package com.gtnhplanner.cloud;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nullable;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.websocketx.ContinuationWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshakerFactory;
import io.netty.handler.ssl.SslHandler;

/**
 * A live service stand-in on netty's server side: plain or TLS, it checks the session cookie on the upgrade and answers
 * the protocol the way the real one does for what the tests drive (hello, join, ops, presence, ping), recording every
 * frame it is sent.
 */
final class LiveTestServer implements AutoCloseable {

    final List<String> received = new CopyOnWriteArrayList<>();
    final List<String> cookies = new CopyOnWriteArrayList<>();
    final List<Channel> clients = new CopyOnWriteArrayList<>();
    final AtomicInteger connections = new AtomicInteger(), pings = new AtomicInteger();
    /** Upgrades are refused with 401 while this is set. */
    volatile boolean refuseAuth;
    /** Pings are answered with a pong frame (the real service does); off to look like a dead peer. */
    volatile boolean answerPings = true;
    /** The doc's plan in the snapshot a join gets. */
    volatile JsonObject plan = new JsonObject();

    private final NioEventLoopGroup group = new NioEventLoopGroup(2);
    private final Channel server;
    private final AtomicInteger seq = new AtomicInteger();

    LiveTestServer(@Nullable final SSLContext ssl) throws InterruptedException {
        server = new ServerBootstrap().group(group)
            .channel(NioServerSocketChannel.class)
            .childHandler(new ChannelInitializer<SocketChannel>() {

                @Override
                protected void initChannel(final SocketChannel ch) {
                    if (ssl != null) {
                        final SSLEngine engine = ssl.createSSLEngine();
                        engine.setUseClientMode(false);
                        ch.pipeline()
                            .addLast(new SslHandler(engine));
                    }
                    ch.pipeline()
                        .addLast(new HttpServerCodec(), new HttpObjectAggregator(65536), new Handler());
                }
            })
            .bind(new InetSocketAddress("127.0.0.1", 0))
            .sync()
            .channel();
    }

    int port() {
        return ((InetSocketAddress) server.localAddress()).getPort();
    }

    /** Sends a frame to every connected client. */
    void send(final String text) {
        for (final Channel c : clients) c.writeAndFlush(new TextWebSocketFrame(text));
    }

    /** Sends one message in two fragments. */
    void sendFragmented(final String first, final String second) {
        for (final Channel c : clients) {
            c.write(new TextWebSocketFrame(false, 0, first));
            c.writeAndFlush(new ContinuationWebSocketFrame(true, 0, second));
        }
    }

    /** Closes every client's connection without a word, as a network that went away does. */
    void dropAll() {
        for (final Channel c : clients) c.close();
        clients.clear();
    }

    List<JsonObject> framesOf(final String type) {
        final List<JsonObject> out = new java.util.ArrayList<>();
        for (final String s : received) {
            final JsonObject o = new JsonParser().parse(s)
                .getAsJsonObject();
            if (type.equals(
                o.get("t")
                    .getAsString()))
                out.add(o);
        }
        return out;
    }

    @Override
    public void close() {
        server.close();
        group.shutdownGracefully(0, 1, java.util.concurrent.TimeUnit.SECONDS);
    }

    private final class Handler extends SimpleChannelInboundHandler<Object> {

        private WebSocketServerHandshaker handshaker;

        @Override
        protected void channelRead0(final ChannelHandlerContext ctx, final Object msg) {
            if (msg instanceof FullHttpRequest) {
                final FullHttpRequest req = (FullHttpRequest) msg;
                final String cookie = req.headers()
                    .get(HttpHeaders.Names.COOKIE);
                cookies.add(cookie == null ? "" : cookie);
                if (refuseAuth || cookie == null || !cookie.startsWith("gtnh_session=")) {
                    ctx.writeAndFlush(
                        new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.UNAUTHORIZED))
                        .addListener(ChannelFutureListener.CLOSE);
                    return;
                }
                handshaker = new WebSocketServerHandshakerFactory(
                    "ws://127.0.0.1:" + port() + "/live",
                    null,
                    false,
                    1 << 25).newHandshaker(req);
                handshaker.handshake(ctx.channel(), req);
                connections.incrementAndGet();
                clients.add(ctx.channel());
            } else if (msg instanceof TextWebSocketFrame) {
                final String text = ((TextWebSocketFrame) msg).text();
                received.add(text);
                respond(
                    ctx,
                    new JsonParser().parse(text)
                        .getAsJsonObject());
            } else if (msg instanceof WebSocketFrame) {
                // Pings and closes of the transport: nothing to say.
            }
        }

        private void respond(final ChannelHandlerContext ctx, final JsonObject f) {
            final String t = f.get("t")
                .getAsString();
            switch (t) {
                case "ping" -> {
                    pings.incrementAndGet();
                    if (answerPings) reply(ctx, "{\"t\":\"pong\"}");
                }
                case "hello" -> reply(
                    ctx,
                    "{\"t\":\"welcome\",\"v\":1,\"you\":{\"id\":\"u1\",\"username\":\"ada\",\"color\":\"#fa0\"}}");
                case "join" -> {
                    final JsonObject s = new JsonObject();
                    s.addProperty("t", "snapshot");
                    s.addProperty(
                        "doc",
                        f.get("doc")
                            .getAsString());
                    s.addProperty("seq", seq.get());
                    s.add("plan", plan);
                    s.addProperty("role", "owner");
                    final JsonArray members = new JsonArray();
                    final JsonObject m = new JsonObject();
                    m.addProperty("id", "u2");
                    m.addProperty("username", "bob");
                    members.add(m);
                    s.add("members", members);
                    s.add("present", new JsonArray());
                    reply(ctx, s.toString());
                }
                case "ops" -> {
                    final JsonObject o = new JsonObject();
                    o.addProperty("t", "ops");
                    o.addProperty(
                        "doc",
                        f.get("doc")
                            .getAsString());
                    o.addProperty("seq", seq.incrementAndGet());
                    o.addProperty("by", "u1");
                    o.add("cid", f.get("cid"));
                    o.add("ops", f.get("ops"));
                    reply(ctx, o.toString());
                }
                default -> {}
            }
        }

        private void reply(final ChannelHandlerContext ctx, final String text) {
            ctx.writeAndFlush(new TextWebSocketFrame(text));
        }

        @Override
        public void channelInactive(final ChannelHandlerContext ctx) {
            clients.remove(ctx.channel());
        }

        @Override
        public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) {
            ctx.close();
        }
    }

}
