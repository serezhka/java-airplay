package com.github.serezhka.airplay.client.netty;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollIoHandler;
import io.netty.channel.nio.NioIoHandler;

import java.util.concurrent.atomic.AtomicReference;

/** Shared Netty 4.2 event-loop factory for cast clients (control / video / audio). */
public final class NettyEventLoops {

    private static final AtomicReference<EventLoopGroup> SHARED = new AtomicReference<>();

    private NettyEventLoops() {
    }

    /** Process-lifetime worker group shared by cast TCP/UDP clients. */
    public static EventLoopGroup shared() {
        return SHARED.updateAndGet(existing ->
                existing != null && !existing.isShuttingDown() && !existing.isShutdown() && !existing.isTerminated()
                        ? existing
                        : create());
    }

    public static EventLoopGroup create() {
        return Epoll.isAvailable()
                ? new MultiThreadIoEventLoopGroup(EpollIoHandler.newFactory())
                : new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());
    }
}
