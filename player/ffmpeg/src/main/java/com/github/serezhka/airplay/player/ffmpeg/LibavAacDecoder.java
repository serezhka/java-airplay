package com.github.serezhka.airplay.player.ffmpeg;

import com.github.serezhka.airplay.lib.AudioStreamInfo;
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

import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_AAC;
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
 * Decodes AirPlay AAC-LC / AAC-ELD raw frames with bundled libav
 * (system {@code ffplay -f aac} does not accept raw ELD and FFmpeg 8 broke {@code -c:a aac}).
 */
final class LibavAacDecoder implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LibavAacDecoder.class);
    private static final int INPUT_PADDING = 64;
    private static final int AVERROR_EAGAIN = -11;

    /** Same ASC as GStreamer aac-eld appsrc ({@code codec_data=f8e85000}). */
    private static final byte[] ASC_ELD_44100_2 = hex("f8e85000");
    private static final byte[] ASC_LC_44100_2 = hex("1210");

    private final int sampleRate;
    private final int channels;
    private AVCodecContext codecCtx;
    private AVPacket packet;
    private AVFrame frame;
    private SwrContext swr;
    private BytePointer swrOut;
    private int swrOutSamples;

    LibavAacDecoder(AudioStreamInfo info) {
        this.sampleRate = sampleRate(info);
        this.channels = channels(info);
        boolean eld = info != null && info.getCompressionType() == AudioStreamInfo.CompressionType.AAC_ELD;
        open(eld ? ASC_ELD_44100_2 : ASC_LC_44100_2, eld ? "AAC-ELD" : "AAC-LC");
    }

    int sampleRate() {
        return sampleRate;
    }

    int channels() {
        return channels;
    }

    private void open(byte[] asc, String label) {
        AVCodec codec = avcodec_find_decoder(AV_CODEC_ID_AAC);
        if (codec == null || codec.isNull()) {
            throw new IllegalStateException("libav has no AAC decoder");
        }
        codecCtx = avcodec_alloc_context3(codec);
        BytePointer extradata = new BytePointer(av_malloc(asc.length + INPUT_PADDING));
        extradata.limit(asc.length + INPUT_PADDING);
        extradata.put(asc);
        for (int i = 0; i < INPUT_PADDING; i++) {
            extradata.put(asc.length + i, (byte) 0);
        }
        codecCtx.extradata(extradata);
        codecCtx.extradata_size(asc.length);
        codecCtx.sample_rate(sampleRate);
        codecCtx.ch_layout().nb_channels(channels);
        if (avcodec_open2(codecCtx, codec, (PointerPointer<?>) null) < 0) {
            throw new IllegalStateException("avcodec_open2(" + label + ") failed");
        }
        packet = av_packet_alloc();
        frame = av_frame_alloc();
        log.info("libav {} decoder open rate={}Hz channels={}", label, sampleRate, channels);
    }

    synchronized void decode(byte[] aacPacket, FfplayPcmSink sink) throws IOException {
        if (codecCtx == null || sink == null || aacPacket == null || aacPacket.length == 0) {
            return;
        }
        av_packet_unref(packet);
        if (av_new_packet(packet, aacPacket.length) < 0) {
            return;
        }
        try {
            packet.data().put(aacPacket);
            int send = avcodec_send_packet(codecCtx, packet);
            if (send < 0 && send != AVERROR_EAGAIN && send != AVERROR_EOF) {
                log.debug("AAC send_packet err={}", send);
                return;
            }
            while (true) {
                int rec = avcodec_receive_frame(codecCtx, frame);
                if (rec == AVERROR_EAGAIN || rec == AVERROR_EOF) {
                    break;
                }
                if (rec < 0) {
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
            byte[] out = new byte[nbSamples * ch * 2];
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
            throw new IllegalStateException("swr_init failed for AAC");
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
            avcodec_free_context(codecCtx);
            codecCtx = null;
        }
    }

    private static int sampleRate(AudioStreamInfo info) {
        String name = formatName(info);
        if (name.contains("48000")) {
            return 48_000;
        }
        if (name.contains("24000")) {
            return 24_000;
        }
        if (name.contains("16000")) {
            return 16_000;
        }
        return 44_100;
    }

    private static int channels(AudioStreamInfo info) {
        String name = formatName(info);
        return name.endsWith("_1") ? 1 : 2;
    }

    private static String formatName(AudioStreamInfo info) {
        return info == null || info.getAudioFormat() == null ? "" : info.getAudioFormat().name();
    }

    private static byte[] hex(String s) {
        int n = s.length() / 2;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
