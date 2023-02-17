package com.github.serezhka.airplay.server;

import com.dd.plist.BinaryPropertyListParser;
import com.dd.plist.NSDictionary;
import com.dd.plist.PropertyListParser;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.rtsp.RtspDecoder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class RTSPUtil extends RtspDecoder /*HttpResponseDecoder*/ {

    public static void main(String[] args) throws Exception {
        RTSPUtil util = new RTSPUtil();
        Path resource = Paths.get(RTSPUtil.class.getResource("/rtsp/get_playback_info_response.bin").toURI());
        byte[] bytes = Files.readAllBytes(resource);
        ByteBuf byteBuf = Unpooled.wrappedBuffer(bytes);

        List<Object> result = new ArrayList<>();
        util.decode(null, byteBuf, result);
        util.decode(null, byteBuf, result);

        HttpMessage message = (HttpMessage) result.get(0);
        System.out.println(message);

        // HttpContent content = (HttpContent) result.get(1);
        // NSDictionary parsedContent = (NSDictionary) BinaryPropertyListParser.parse(new ByteBufInputStream(content.content()));
        // NSDictionary parsedContent = (NSDictionary) PropertyListParser.parse(new ByteBufInputStream(content.content()));
        // System.out.println(parsedContent.toXMLPropertyList());

        // x-dmap-tagged
        // ByteBuf buf = content.content();
        // System.out.println("Tag: " + buf.readCharSequence(4, StandardCharsets.UTF_8));
        // int size = buf.readInt();
        // System.out.println("Size: " + size);
        // System.out.println("Bytes left: " + buf.readableBytes());
    }
}
