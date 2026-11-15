package com.github.serezhka.airplay.server.internal;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollDatagramChannel;
import io.netty.channel.epoll.EpollIoHandler;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Shared Netty 4.2 event-loop groups for control / video / audio servers.
 * Process-lifetime groups avoid creating a new MultiThreadIoEventLoopGroup per media port.
 */
final class NettyEventLoops {

    private static final AtomicReference<EventLoopGroup> BOSS = new AtomicReference<>();
    private static final AtomicReference<EventLoopGroup> WORKER = new AtomicReference<>();

    private NettyEventLoops() {
    }

    static EventLoopGroup sharedBoss() {
        return BOSS.updateAndGet(existing -> alive(existing) ? existing : create(1));
    }

    static EventLoopGroup sharedWorker() {
        int threads = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
        return WORKER.updateAndGet(existing -> alive(existing) ? existing : create(threads));
    }

    /** Prefer {@link #sharedBoss()}/{@link #sharedWorker()}; kept for tests that need an isolated group. */
    static EventLoopGroup create() {
        return create(0);
    }

    static EventLoopGroup create(int nThreads) {
        if (Epoll.isAvailable()) {
            return nThreads > 0
                    ? new MultiThreadIoEventLoopGroup(nThreads, EpollIoHandler.newFactory())
                    : new MultiThreadIoEventLoopGroup(EpollIoHandler.newFactory());
        }
        return nThreads > 0
                ? new MultiThreadIoEventLoopGroup(nThreads, NioIoHandler.newFactory())
                : new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());
    }

    static Class<? extends ServerSocketChannel> serverSocketChannelClass() {
        return Epoll.isAvailable() ? EpollServerSocketChannel.class : NioServerSocketChannel.class;
    }

    static Class<? extends DatagramChannel> datagramChannelClass() {
        return Epoll.isAvailable() ? EpollDatagramChannel.class : NioDatagramChannel.class;
    }

    private static boolean alive(EventLoopGroup group) {
        return group != null && !group.isShuttingDown() && !group.isShutdown() && !group.isTerminated();
    }
}
