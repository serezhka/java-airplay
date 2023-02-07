package com.github.serezhka.airplay.server.internal.handler.control;

import com.github.serezhka.airplay.lib.AudioStreamInfo;
import com.github.serezhka.airplay.lib.MediaStreamInfo;
import com.github.serezhka.airplay.lib.VideoStreamInfo;
import com.github.serezhka.airplay.server.AirPlayConsumer;
import com.github.serezhka.airplay.server.internal.AudioControlServer;
import com.github.serezhka.airplay.server.internal.AudioReceiver;
import com.github.serezhka.airplay.server.internal.VideoReceiver;
import com.github.serezhka.airplay.server.internal.handler.audio.AudioHandler;
import com.github.serezhka.airplay.server.internal.handler.session.Session;
import com.github.serezhka.airplay.server.internal.handler.session.SessionManager;
import com.github.serezhka.airplay.server.internal.handler.video.VideoHandler;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.rtsp.RtspMethods;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;

@Slf4j
@ChannelHandler.Sharable
public class RTSPHandler extends ControlHandler {

    private final AirPlayConsumer airPlayConsumer;
    private final int airTunesPort;

    public RTSPHandler(int airTunesPort, SessionManager sessionManager, AirPlayConsumer airPlayConsumer) {
        super(sessionManager);
        this.airPlayConsumer = airPlayConsumer;
        this.airTunesPort = airTunesPort;
    }

    @Override
    protected boolean handleRequest(ChannelHandlerContext ctx, Session session, FullHttpRequest request) throws Exception {
        var response = createResponseForRequest(request);
        if (RtspMethods.SETUP.equals(request.method())) {

            MediaStreamInfo mediaStreamInfo = session.getAirPlay().rtspGetMediaStreamInfo(new ByteBufInputStream(request.content()));
            if (mediaStreamInfo == null) {
                request.content().resetReaderIndex();
                session.getAirPlay().rtspSetupEncryption(new ByteBufInputStream(request.content()));
            } else {
                switch (mediaStreamInfo.getStreamType()) {
                    case AUDIO:
                        AudioStreamInfo audioStreamInfo = (AudioStreamInfo) mediaStreamInfo;

                        log.info("Audio format is: {}", audioStreamInfo.getAudioFormat());
                        log.info("Audio compression type is: {}", audioStreamInfo.getCompressionType());
                        log.info("Audio samples per frame is: {}", audioStreamInfo.getSamplesPerFrame());

                        airPlayConsumer.onAudioFormat(audioStreamInfo);

                        var audioHandler = new AudioHandler(session.getAirPlay(), airPlayConsumer);
                        var audioReceiver = new AudioReceiver(audioHandler, this);
                        var audioReceiverThread = new Thread(audioReceiver);
                        session.setAudioReceiverThread(audioReceiverThread);
                        audioReceiverThread.start();
                        synchronized (this) {
                            wait();
                        }

                        var audioControlServer = new AudioControlServer(this);
                        var audioControlServerThread = new Thread(audioControlServer);
                        session.setAudioControlServerThread(audioControlServerThread);
                        audioControlServerThread.start();
                        synchronized (this) {
                            wait();
                        }

                        session.getAirPlay().rtspSetupAudio(new ByteBufOutputStream(response.content()),
                                audioReceiver.getPort(), audioControlServer.getPort());

                        break;

                    case VIDEO:
                        VideoStreamInfo videoStreamInfo = (VideoStreamInfo) mediaStreamInfo;

                        airPlayConsumer.onVideoFormat(videoStreamInfo);

                        var videoHandler = new VideoHandler(session.getAirPlay(), airPlayConsumer);
                        var videoReceiver = new VideoReceiver(videoHandler, this);
                        var videoReceiverThread = new Thread(videoReceiver);
                        session.setVideoReceiverThread(videoReceiverThread);
                        videoReceiverThread.start();
                        synchronized (this) {
                            wait();
                        }

                        session.getAirPlay().rtspSetupVideo(new ByteBufOutputStream(response.content()), videoReceiver.getPort(), airTunesPort, 7011);
                        break;
                }
            }
            return sendResponse(ctx, request, response);
        } else if (RtspMethods.GET_PARAMETER.equals(request.method())) {
            byte[] content = "volume: 1.000000\r\n".getBytes(StandardCharsets.US_ASCII);
            response.content().writeBytes(content);
            return sendResponse(ctx, request, response);
        } else if (RtspMethods.RECORD.equals(request.method())) {
            response.headers().add("Audio-Latency", "11025");
            response.headers().add("Audio-Jack-Status", "connected; type=analog");
            return sendResponse(ctx, request, response);
        } else if (RtspMethods.SET_PARAMETER.equals(request.method())) {
            return sendResponse(ctx, request, response);
        } else if ("FLUSH".equals(request.method().toString())) {
            return sendResponse(ctx, request, response);
        } else if (RtspMethods.TEARDOWN.equals(request.method())) {
            MediaStreamInfo mediaStreamInfo = session.getAirPlay().rtspGetMediaStreamInfo(new ByteBufInputStream(request.content()));
            if (mediaStreamInfo != null) {
                switch (mediaStreamInfo.getStreamType()) {
                    case AUDIO:
                        session.stopAudio();
                        airPlayConsumer.onAudioSrcDisconnect();
                        break;
                    case VIDEO:
                        session.stopVideo();
                        airPlayConsumer.onVideoSrcDisconnect();
                        break;
                }
            } else {
                session.stopAudio();
                session.stopVideo();
                airPlayConsumer.onAudioSrcDisconnect();
                airPlayConsumer.onVideoSrcDisconnect();
            }
            return sendResponse(ctx, request, response);
        } else if ("POST".equals(request.method().toString()) && request.uri().equals("/audioMode")) {
            return sendResponse(ctx, request, response);
        }
        return false;
    }
}
