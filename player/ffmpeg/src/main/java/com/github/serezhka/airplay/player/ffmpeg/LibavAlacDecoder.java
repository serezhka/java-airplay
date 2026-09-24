package com.github.serezhka.airplay.player.ffmpeg;

import com.github.serezhka.airplay.protocol.media.AudioStreamInfo;
import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avutil.AVChannelLayout;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.swresample.SwrContext;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.PointerPointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_ALAC;
import static org.bytedeco.ffmpeg.global.avcodec.av_new_packet;
import static org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc;
import static org.bytedeco.ffmpeg.global.avcodec.av_packet_free;
import static org.bytedeco.ffmpeg.global.avcodec.av_packet_unref;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_alloc_context3;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_find_decoder;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_free_context;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_open2;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_frame;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_send_packet;
import static org.bytedeco.ffmpeg.global.avutil.AVERROR_EOF;
import static org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_S16;
import static org.bytedeco.ffmpeg.global.avutil.av_frame_alloc;
import static org.bytedeco.ffmpeg.global.avutil.av_frame_free;
import static org.bytedeco.ffmpeg.global.avutil.av_free;
import static org.bytedeco.ffmpeg.global.avutil.av_get_bytes_per_sample;
import static org.bytedeco.ffmpeg.global.avutil.av_malloc;
import static org.bytedeco.ffmpeg.global.avutil.av_sample_fmt_is_planar;
import static org.bytedeco.ffmpeg.global.swresample.swr_alloc_set_opts2;
import static org.bytedeco.ffmpeg.global.swresample.swr_convert;
import static org.bytedeco.ffmpeg.global.swresample.swr_free;
import static org.bytedeco.ffmpeg.global.swresample.swr_init;

/**
 * Decodes AirPlay ALAC packets with bundled libav (FFmpeg 8 removed {@code -f alac} demuxer).
 */
final class LibavAlacDecoder implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LibavAlacDecoder.class);
    private static final int INPUT_PADDING = 64;
    /** POSIX EAGAIN negated — {@code AVERROR(EAGAIN)}. */
    private static final int AVERROR_EAGAIN = -11;

    private final int sampleRate;
    private final int channels;
    private AVCodecContext codecCtx;
    private AVPacket packet;
    private AVFrame frame;
    private SwrContext swr;
    private BytePointer swrOut;
    private int swrOutSamples;

    LibavAlacDecoder(AudioStreamInfo info) {
        this.sampleRate = sampleRate(info);
        this.channels = channels(info);
        int bitDepth = bitDepth(info);
        int framesPerPacket = info != null && info.getSamplesPerFrame() > 0 ? info.getSamplesPerFrame() : 352;
        open(bitDepth, framesPerPacket);
    }

    int sampleRate() {
        return sampleRate;
    }

    int channels() {
        return channels;
    }

    private void open(int bitDepth, int framesPerPacket) {
        AVCodec codec = avcodec_find_decoder(AV_CODEC_ID_ALAC);
        if (codec == null || codec.isNull()) {
            throw new IllegalStateException("libav has no ALAC decoder");
        }
        codecCtx = avcodec_alloc_context3(codec);
        byte[] cookie = magicCookie(framesPerPacket, bitDepth, channels, sampleRate);
        BytePointer extradata = new BytePointer(av_malloc(cookie.length + INPUT_PADDING));
        extradata.limit(cookie.length + INPUT_PADDING);
        extradata.put(cookie);
        for (int i = 0; i < INPUT_PADDING; i++) {
            extradata.put(cookie.length + i, (byte) 0);
        }
        codecCtx.extradata(extradata);
        codecCtx.extradata_size(cookie.length);
        codecCtx.sample_rate(sampleRate);
        AVChannelLayout layout = codecCtx.ch_layout();
        layout.nb_channels(channels);
        if (avcodec_open2(codecCtx, codec, (PointerPointer<?>) null) < 0) {
            throw new IllegalStateException("avcodec_open2(ALAC) failed");
        }
        packet = av_packet_alloc();
        frame = av_frame_alloc();
        log.info("libav ALAC decoder open rate={}Hz channels={} frames/packet={}",
                sampleRate, channels, framesPerPacket);
    }

    synchronized void decode(byte[] alacPacket, FfplayPcmSink sink) throws IOException {
        if (codecCtx == null || sink == null || alacPacket == null || alacPacket.length == 0) {
            return;
        }
        av_packet_unref(packet);
        if (av_new_packet(packet, alacPacket.length) < 0) {
            log.debug("ALAC av_new_packet failed");
            return;
        }
        try {
            packet.data().put(alacPacket);
            int send = avcodec_send_packet(codecCtx, packet);
            if (send < 0 && send != AVERROR_EAGAIN && send != AVERROR_EOF) {
                log.debug("ALAC send_packet err={}", send);
                return;
            }
            while (true) {
                int rec = avcodec_receive_frame(codecCtx, frame);
                if (rec == AVERROR_EAGAIN || rec == AVERROR_EOF) {
                    break;
                }
                if (rec < 0) {
                    log.debug("ALAC receive_frame err={}", rec);
                    break;
                }
                byte[] pcm = toS16Interleaved(frame);
                if (pcm != null && pcm.length > 0) {
                    sink.write(pcm, 0, pcm.length);
                }
            }
        } finally {
            av_packet_unref(packet);
        }
    }

    private byte[] toS16Interleaved(AVFrame src) {
        int srcFmt = src.format();
        int nbSamples = src.nb_samples();
        int ch = src.ch_layout().nb_channels() > 0 ? src.ch_layout().nb_channels() : channels;
        if (srcFmt == AV_SAMPLE_FMT_S16 && av_sample_fmt_is_planar(srcFmt) == 0) {
            int bytes = nbSamples * ch * 2;
            byte[] out = new byte[bytes];
            src.data(0).get(out);
            return out;
        }
        ensureSwr(src, ch);
        int outBytes = nbSamples * ch * av_get_bytes_per_sample(AV_SAMPLE_FMT_S16);
        if (swrOut == null || swrOutSamples < nbSamples) {
            if (swrOut != null) {
                av_free(swrOut);
            }
            swrOut = new BytePointer(av_malloc(outBytes));
            swrOutSamples = nbSamples;
        }
        PointerPointer<BytePointer> outPlanes = new PointerPointer<>(1);
        outPlanes.put(0, swrOut);
        int converted = swr_convert(swr, outPlanes, nbSamples, src.data(), nbSamples);
        outPlanes.deallocate();
        if (converted <= 0) {
            return null;
        }
        byte[] out = new byte[converted * ch * 2];
        swrOut.position(0).limit(out.length).asByteBuffer().get(out);
        return out;
    }

    private void ensureSwr(AVFrame src, int ch) {
        if (swr != null && !swr.isNull()) {
            return;
        }
        swr = new SwrContext();
        AVChannelLayout inLayout = new AVChannelLayout(src.ch_layout());
        AVChannelLayout outLayout = new AVChannelLayout();
        outLayout.nb_channels(ch);
        int err = swr_alloc_set_opts2(swr,
                outLayout, AV_SAMPLE_FMT_S16, sampleRate,
                inLayout, src.format(), src.sample_rate(),
                0, null);
        if (err < 0 || swr_init(swr) < 0) {
            throw new IllegalStateException("swr_init failed for ALAC");
        }
    }

    @Override
    public synchronized void close() {
        if (swr != null) {
            swr_free(swr);
            swr = null;
        }
        if (swrOut != null) {
            av_free(swrOut);
            swrOut = null;
        }
        if (frame != null) {
            av_frame_free(frame);
            frame = null;
        }
        if (packet != null) {
            av_packet_free(packet);
            packet = null;
        }
        if (codecCtx != null) {
            // avcodec_free_context frees extradata — do not av_free it again.
            avcodec_free_context(codecCtx);
            codecCtx = null;
        }
    }

    private static byte[] magicCookie(int frameLength, int bitDepth, int channels, int sampleRate) {
        ByteBuffer cookie = ByteBuffer.allocate(36).order(ByteOrder.BIG_ENDIAN);
        cookie.putInt(36);
        cookie.put("alac".getBytes(StandardCharsets.US_ASCII));
        cookie.putInt(0);
        cookie.putInt(frameLength);
        cookie.put((byte) 0);
        cookie.put((byte) bitDepth);
        cookie.put((byte) 40);
        cookie.put((byte) 10);
        cookie.put((byte) 14);
        cookie.put((byte) channels);
        cookie.putShort((short) 0x00ff);
        cookie.putInt(0);
        cookie.putInt(0);
        cookie.putInt(sampleRate);
        return cookie.array();
    }

    private static int sampleRate(AudioStreamInfo info) {
        String name = formatName(info);
        return name.contains("48000") ? 48_000 : 44_100;
    }

    private static int channels(AudioStreamInfo info) {
        String name = formatName(info);
        return name.endsWith("_1") ? 1 : 2;
    }

    private static int bitDepth(AudioStreamInfo info) {
        String name = formatName(info);
        return name.contains("_24_") ? 24 : 16;
    }

    private static String formatName(AudioStreamInfo info) {
        return info == null || info.getAudioFormat() == null ? "" : info.getAudioFormat().name();
    }
}
