package com.github.serezhka.airplay.client.netty;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollIoHandler;
import io.netty.channel.nio.NioIoHandler;

/** Netty 4.2 event-loop factory (transport-specific {@code *EventLoopGroup} is deprecated). */
public final class NettyEventLoops {

    private NettyEventLoops() {
    }

    public static EventLoopGroup create() {
        return Epoll.isAvailable()
                ? new MultiThreadIoEventLoopGroup(EpollIoHandler.newFactory())
                : new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());
    }
}
