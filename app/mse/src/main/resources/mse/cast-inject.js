/* Injected on every top-level document in the cast Chromium tab.
 * Placeholders: __BINDING_NAME__ __ENABLE_AUDIO__
 */
(function () {
  if (window !== window.top) return;
  if (window.__airplayCastBooted) return;
  window.__airplayCastBooted = true;

  var BINDING = '__BINDING_NAME__';
  var ENABLE_AUDIO = __ENABLE_AUDIO__;
  var captureStream = null;
  var videoTrack = null;
  var audioTrack = null;
  var encoder = null;
  var audioEncoder = null;
  var captureTimer = null;
  var videoEl = null;
  var canvas = null;
  var ctx = null;
  var spsPpsAnnexB = null;
  var casting = false;
  var frameIndex = 0;
  var encodeSize = { w: 960, h: 540 };
  var audioConfigured = false;
  var audioReader = null;
  var encoderBusy = null;

  function post(obj) {
    try {
      var fn = window[BINDING];
      if (typeof fn === 'function') fn(JSON.stringify(obj));
    } catch (e) {}
  }

  function emit(event, message) {
    post({ t: 'e', event: event, message: message || '' });
  }

  function b64FromU8(u8) {
    var bin = '';
    var step = 0x8000;
    for (var i = 0; i < u8.length; i += step) {
      bin += String.fromCharCode.apply(null, u8.subarray(i, i + step));
    }
    return btoa(bin);
  }

  function sendVideoAnnexB(u8) {
    if (!u8 || !u8.length) return;
    post({ t: 'v', b: b64FromU8(u8) });
  }

  function sendAudioAdts(u8) {
    if (!u8 || !u8.length) return;
    post({ t: 'a', b: b64FromU8(u8) });
  }

  function even(n) { n = n | 0; return n % 2 === 0 ? n : n - 1; }

  function fitSize(w, h, maxW, maxH) {
    w = even(Math.max(2, w | 0));
    h = even(Math.max(2, h | 0));
    var scale = Math.min(1, maxW / w, maxH / h);
    return { w: even(Math.max(2, Math.round(w * scale))), h: even(Math.max(2, Math.round(h * scale))) };
  }

  function avccToAnnexB(data) {
    var u8 = data instanceof Uint8Array ? data : new Uint8Array(data);
    var out = [];
    var i = 0;
    while (i + 4 <= u8.length) {
      var n = (u8[i] << 24) | (u8[i + 1] << 16) | (u8[i + 2] << 8) | u8[i + 3];
      i += 4;
      if (n <= 0 || i + n > u8.length) break;
      out.push(0, 0, 0, 1);
      for (var j = 0; j < n; j++) out.push(u8[i + j]);
      i += n;
    }
    return new Uint8Array(out);
  }

  function extractSpsPps(description) {
    var d = new Uint8Array(description);
    if (d.length < 7) return null;
    var out = [];
    var offset = 5;
    var numSps = d[offset++] & 0x1f;
    for (var s = 0; s < numSps; s++) {
      if (offset + 2 > d.length) return null;
      var len = (d[offset] << 8) | d[offset + 1];
      offset += 2;
      out.push(0, 0, 0, 1);
      for (var i = 0; i < len && offset + i < d.length; i++) out.push(d[offset + i]);
      offset += len;
    }
    if (offset >= d.length) return new Uint8Array(out);
    var numPps = d[offset++];
    for (var p = 0; p < numPps; p++) {
      if (offset + 2 > d.length) break;
      var plen = (d[offset] << 8) | d[offset + 1];
      offset += 2;
      out.push(0, 0, 0, 1);
      for (var j = 0; j < plen && offset + j < d.length; j++) out.push(d[offset + j]);
      offset += plen;
    }
    return new Uint8Array(out);
  }

  function adtsHeader(aacLength, sampleRateIndex, channelConfig) {
    var frameLen = aacLength + 7;
    var hdr = new Uint8Array(7);
    hdr[0] = 0xff;
    hdr[1] = 0xf1;
    hdr[2] = (0x01 << 6) | ((sampleRateIndex & 0x0f) << 2) | ((channelConfig >> 2) & 0x01);
    hdr[3] = ((channelConfig & 0x03) << 6) | ((frameLen >> 11) & 0x03);
    hdr[4] = (frameLen >> 3) & 0xff;
    hdr[5] = ((frameLen & 0x07) << 5) | 0x1f;
    hdr[6] = 0xfc;
    return hdr;
  }

  async function tryConfigure(enc, codec, w, h, hw) {
    await enc.configure({
      codec: codec,
      width: w,
      height: h,
      bitrate: 2_500_000,
      framerate: 30,
      avc: { format: 'avc' },
      latencyMode: 'realtime',
      hardwareAcceleration: hw
    });
  }

  async function ensureEncoder(width, height) {
    if (encoder) {
      try { encoder.close(); } catch (e) {}
      encoder = null;
    }
    spsPpsAnnexB = null;
    if (typeof VideoEncoder === 'undefined') throw new Error('VideoEncoder unsupported');
    encodeSize = fitSize(width, height, 960, 540);
    var codecs = ['avc1.42E01F', 'avc1.4D401F', 'avc1.64001F', 'avc1.640028', 'avc1.42E01E'];
    var hwModes = ['prefer-software', 'no-preference', 'prefer-hardware'];
    var lastErr = null;
    for (var hi = 0; hi < hwModes.length; hi++) {
      for (var ci = 0; ci < codecs.length; ci++) {
        var enc = new VideoEncoder({
          output: function (chunk, meta) {
            if (meta && meta.decoderConfig && meta.decoderConfig.description) {
              spsPpsAnnexB = extractSpsPps(meta.decoderConfig.description);
            }
            var buf = new ArrayBuffer(chunk.byteLength);
            chunk.copyTo(buf);
            var annex = avccToAnnexB(buf);
            if (chunk.type === 'key' && spsPpsAnnexB && spsPpsAnnexB.length) {
              var merged = new Uint8Array(spsPpsAnnexB.length + annex.length);
              merged.set(spsPpsAnnexB, 0);
              merged.set(annex, spsPpsAnnexB.length);
              sendVideoAnnexB(merged);
            } else {
              sendVideoAnnexB(annex);
            }
          },
          error: function (e) { emit('error', 'encoder: ' + e); }
        });
        try {
          await tryConfigure(enc, codecs[ci], encodeSize.w, encodeSize.h, hwModes[hi]);
          encoder = enc;
          emit('log', 'encoder ok ' + codecs[ci] + ' ' + encodeSize.w + 'x' + encodeSize.h + ' ' + hwModes[hi]);
          return encodeSize;
        } catch (e) {
          lastErr = e;
          try { enc.close(); } catch (e2) {}
        }
      }
    }
    throw lastErr || new Error('no AVC encoder');
  }

  async function ensureAudioEncoder() {
    if (audioEncoder || !ENABLE_AUDIO) return;
    if (typeof AudioEncoder === 'undefined') {
      emit('log', 'AudioEncoder unavailable — tab audio off');
      return;
    }
    audioEncoder = new AudioEncoder({
      output: function (chunk) {
        var raw = new Uint8Array(chunk.byteLength);
        chunk.copyTo(raw);
        var hdr = adtsHeader(raw.length, 4, 2);
        var adts = new Uint8Array(hdr.length + raw.length);
        adts.set(hdr, 0);
        adts.set(raw, hdr.length);
        sendAudioAdts(adts);
      },
      error: function (e) { emit('error', 'audio encoder: ' + e); }
    });
    await audioEncoder.configure({
      codec: 'mp4a.40.2',
      sampleRate: 44100,
      numberOfChannels: 2,
      bitrate: 128_000
    });
    audioConfigured = true;
    emit('log', 'audio encoder ok mp4a.40.2');
  }

  async function pumpAudio() {
    if (!audioTrack || !audioConfigured || !audioEncoder) return;
    try {
      var processor = new MediaStreamTrackProcessor({ track: audioTrack });
      audioReader = processor.readable.getReader();
      while (casting) {
        var step = await audioReader.read();
        if (step.done) break;
        var data = step.value;
        if (audioEncoder && audioEncoder.state === 'configured') {
          audioEncoder.encode(data);
        }
        data.close();
      }
    } catch (e) {
      if (casting) emit('error', 'audio pump: ' + e);
    } finally {
      audioReader = null;
    }
  }

  function stopCapture() {
    casting = false;
    if (captureTimer) { clearInterval(captureTimer); captureTimer = null; }
    if (audioReader) {
      try { audioReader.cancel(); } catch (e) {}
      audioReader = null;
    }
    if (encoder) { try { encoder.close(); } catch (e) {} encoder = null; }
    if (audioEncoder) { try { audioEncoder.close(); } catch (e) {} audioEncoder = null; }
    audioConfigured = false;
    encoderBusy = null;
    if (videoTrack) { try { videoTrack.stop(); } catch (e) {} videoTrack = null; }
    if (audioTrack) { try { audioTrack.stop(); } catch (e) {} audioTrack = null; }
    if (captureStream) {
      try { captureStream.getTracks().forEach(function (t) { t.stop(); }); } catch (e) {}
      captureStream = null;
    }
    if (videoEl) { try { videoEl.srcObject = null; } catch (e) {} videoEl = null; }
    canvas = null;
    ctx = null;
  }

  async function startCapture() {
    if (casting) return;
    if (!navigator.mediaDevices || !navigator.mediaDevices.getDisplayMedia) {
      emit('error', 'capture: mediaDevices.getDisplayMedia unavailable');
      return;
    }
    try {
      captureStream = await navigator.mediaDevices.getDisplayMedia({
        video: {
          displaySurface: 'browser',
          frameRate: 30,
          width: { ideal: 1280 },
          height: { ideal: 720 }
        },
        audio: ENABLE_AUDIO,
        preferCurrentTab: true,
        selfBrowserSurface: 'include',
        surfaceSwitching: 'exclude',
        systemAudio: 'exclude'
      });
      videoTrack = captureStream.getVideoTracks()[0];
      if (!videoTrack) throw new Error('no video track');
      if (ENABLE_AUDIO) {
        audioTrack = captureStream.getAudioTracks()[0] || null;
      }
      videoTrack.addEventListener('ended', function () {
        stopCapture();
        emit('captureEnded', '');
      });

      videoEl = document.createElement('video');
      videoEl.muted = true;
      videoEl.playsInline = true;
      videoEl.srcObject = captureStream;
      await videoEl.play();

      var settings = videoTrack.getSettings ? videoTrack.getSettings() : {};
      var rawW = settings.width || videoEl.videoWidth || 1280;
      var rawH = settings.height || videoEl.videoHeight || 720;
      var size = await ensureEncoder(rawW, rawH);
      if (ENABLE_AUDIO && audioTrack) {
        await ensureAudioEncoder();
        pumpAudio();
      }
      canvas = document.createElement('canvas');
      canvas.width = size.w;
      canvas.height = size.h;
      ctx = canvas.getContext('2d', { alpha: false });

      casting = true;
      frameIndex = 0;
      emit('captureStarted', size.w + 'x' + size.h);

      captureTimer = setInterval(function () {
        if (!casting || !encoder || encoder.state !== 'configured') return;
        try {
          var srcW = videoEl.videoWidth || rawW;
          var srcH = videoEl.videoHeight || rawH;
          var next = fitSize(srcW, srcH, 960, 540);
          if (next.w !== canvas.width || next.h !== canvas.height) {
            canvas.width = next.w;
            canvas.height = next.h;
            encodeSize = next;
            if (!encoderBusy) {
              encoderBusy = ensureEncoder(next.w, next.h).catch(function (e) {
                emit('error', 'reconfigure: ' + e);
              }).finally(function () { encoderBusy = null; });
            }
            return;
          }
          if (encoderBusy || !encoder) return;
          ctx.drawImage(videoEl, 0, 0, canvas.width, canvas.height);
          var frame = new VideoFrame(canvas, { timestamp: performance.now() * 1000 });
          encoder.encode(frame, { keyFrame: (frameIndex++ % 30) === 0 });
          frame.close();
        } catch (e) {
          emit('error', 'frame: ' + e);
        }
      }, 33);
    } catch (e) {
      stopCapture();
      emit('error', 'capture: ' + e);
    }
  }

  window.__airplayCastEnableAudio = function () {
    ENABLE_AUDIO = true;
    if (!casting) return;
    if (audioTrack && !audioConfigured) {
      ensureAudioEncoder().then(function () { pumpAudio(); }).catch(function (e) {
        emit('error', 'late audio: ' + e);
      });
      return;
    }
    // Capture started without an audio track — must re-request getDisplayMedia.
    if (!audioTrack) {
      stopCapture();
      startCapture();
    }
  };

  function boot() {
    emit('ready', '');
    setTimeout(function () { startCapture(); }, 400);
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
  } else {
    boot();
  }
})();
