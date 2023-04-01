package com.github.serezhka.airplay.lib.internal;

import com.dd.plist.BinaryPropertyListParser;
import com.dd.plist.NSDictionary;
import com.github.serezhka.airplay.lib.AudioStreamInfo;
import com.github.serezhka.airplay.lib.MediaStreamInfo;
import com.github.serezhka.airplay.lib.VideoStreamInfo;
import net.i2p.crypto.eddsa.Utils;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Optional;

import android.util.Log;

public class RTSP {
    private static String TAG = "RTSP";

    private byte[] ekey;
    private byte[] eiv;

    private String streamConnectionID;

    public Optional<MediaStreamInfo> setup(InputStream rtspSetupPayload) throws Exception {
        var setup = (NSDictionary) BinaryPropertyListParser.parse(rtspSetupPayload);
        if (setup.containsKey("ekey") || setup.containsKey("eiv")) {
            ekey = (byte[]) setup.get("ekey").toJavaObject();
            eiv = (byte[]) setup.get("eiv").toJavaObject();
            Log.i(TAG, String.format("Encrypted AES key: %s, iv: %s", Utils.bytesToHex(ekey), Utils.bytesToHex(eiv)));
            return Optional.empty();
        } else if (setup.containsKey("streams")) {
            Log.d(TAG, String.format("RTSP SETUP streams:\n%s", setup.toXMLPropertyList()));
            return Optional.ofNullable(getMediaStreamInfo(setup));
        } else {
            Log.e(TAG, String.format("Unknown RTSP setup content\n%s", setup.toXMLPropertyList()));
            return Optional.empty();
        }
    }

    public Optional<MediaStreamInfo> teardown(InputStream rtspTeardownPayload) throws Exception {
        var teardown = (NSDictionary) BinaryPropertyListParser.parse(rtspTeardownPayload);
        Log.d(TAG, String.format("RTSP TEARDOWN streams:\n%s", teardown.toXMLPropertyList()));
        if (teardown.containsKey("streams")) {
            return Optional.ofNullable(getMediaStreamInfo(teardown));
        }
        return Optional.empty();
    }

    private MediaStreamInfo getMediaStreamInfo(NSDictionary request) {
        var streams = ((Object[]) request.get("streams").toJavaObject());
        if (streams.length > 1) {
            Log.w(TAG, "Request contains more than one stream info");
        }

        @SuppressWarnings("rawtypes")
        HashMap stream = (HashMap) streams[0];
        int type = (int) stream.get("type");
        switch (type) {

            // video stream
            case 110:
                if (stream.containsKey("streamConnectionID")) {
                    streamConnectionID = Long.toUnsignedString((long) stream.get("streamConnectionID"));
                }
                return new VideoStreamInfo(streamConnectionID);

                // audio stream
            case 96:
                AudioStreamInfo.AudioStreamInfoBuilder builder = new AudioStreamInfo.AudioStreamInfoBuilder();
                if (stream.containsKey("ct")) {
                    int compressionType = (int) stream.get("ct");
                    builder.compressionType(AudioStreamInfo.CompressionType.fromCode(compressionType));
                }
                if (stream.containsKey("audioFormat")) {
                    long audioFormatCode = (int) stream.get("audioFormat"); // int or long ?!
                    builder.audioFormat(AudioStreamInfo.AudioFormat.fromCode(audioFormatCode));
                }
                if (stream.containsKey("spf")) {
                    int samplesPerFrame = (int) stream.get("spf");
                    builder.samplesPerFrame(samplesPerFrame);
                }
                return builder.build();

            default:
                Log.e(TAG, String.format("Unknown stream type: %d", type));
                return null;
        }
    }

    public String getStreamConnectionID() {
        return streamConnectionID;
    }

    public byte[] getEkey() {
        return ekey;
    }

    public byte[] getEiv() {
        return eiv;
    }
}
