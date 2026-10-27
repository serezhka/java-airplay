/* Injected into the cast Chromium tab (top-level only).
 * Placeholders: __VIDEO_WS__ __CONTROL_WS__
 */
(function () {
  if (window !== window.top) return; // never run in iframes (YouTube embeds)
  if (window.__airplayCastBooted) return;
  window.__airplayCastBooted = true;

  var VIDEO_WS = '__VIDEO_WS__';
  var CONTROL_WS = '__CONTROL_WS__';
  var controlWs = null;
  var videoWs = null;
  var captureStream = null;
  var videoTrack = null;
  var encoder = null;
  var captureTimer = null;
  var videoEl = null;
  var canvas = null;
  var ctx = null;
  var spsPpsAnnexB = null;
  var casting = false;
  var frameIndex = 0;
  var encodeSize = { w: 960, h: 540 };

  function emit(obj) {
    try {
      if (controlWs && controlWs.readyState === 1) controlWs.send(JSON.stringify(obj));
    } catch (e) {}
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

  function sendAnnexB(bytes) {
    if (!videoWs || videoWs.readyState !== 1 || !bytes || !bytes.length) return;
    videoWs.send(bytes);
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
    // Cap size — lab displays often report ~1152x720; keep well under level limits.
    encodeSize = fitSize(width, height, 960, 540);
    var codecs = ['avc1.42E01F', 'avc1.4D401F', 'avc1.64001F', 'avc1.640028', 'avc1.42E01E'];
    // Soft first: DISPLAY=:10 / remote sessions often break prefer-hardware.
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
              sendAnnexB(merged);
            } else {
              sendAnnexB(annex);
            }
          },
          error: function (e) { emit({ event: 'error', message: 'encoder: ' + e }); }
        });
        try {
          await tryConfigure(enc, codecs[ci], encodeSize.w, encodeSize.h, hwModes[hi]);
          encoder = enc;
          emit({
            event: 'log',
            message: 'encoder ok ' + codecs[ci] + ' ' + encodeSize.w + 'x' + encodeSize.h + ' ' + hwModes[hi]
          });
          return encodeSize;
        } catch (e) {
          lastErr = e;
          try { enc.close(); } catch (e2) {}
        }
      }
    }
    throw lastErr || new Error('no AVC encoder');
  }

  function stopCapture() {
    casting = false;
    if (captureTimer) { clearInterval(captureTimer); captureTimer = null; }
    if (encoder) { try { encoder.close(); } catch (e) {} encoder = null; }
    if (videoTrack) { try { videoTrack.stop(); } catch (e) {} videoTrack = null; }
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
      emit({ event: 'error', message: 'capture: mediaDevices.getDisplayMedia unavailable' });
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
        audio: false,
        preferCurrentTab: true,
        selfBrowserSurface: 'include',
        surfaceSwitching: 'exclude',
        systemAudio: 'exclude'
      });
      videoTrack = captureStream.getVideoTracks()[0];
      if (!videoTrack) throw new Error('no video track');
      videoTrack.addEventListener('ended', function () {
        stopCapture();
        emit({ event: 'captureEnded' });
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
      canvas = document.createElement('canvas');
      canvas.width = size.w;
      canvas.height = size.h;
      ctx = canvas.getContext('2d', { alpha: false });

      casting = true;
      frameIndex = 0;
      emit({ event: 'captureStarted', width: size.w, height: size.h });

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
            ensureEncoder(next.w, next.h).catch(function (e) {
              emit({ event: 'error', message: 'reconfigure: ' + e });
            });
            return;
          }
          ctx.drawImage(videoEl, 0, 0, canvas.width, canvas.height);
          var frame = new VideoFrame(canvas, { timestamp: performance.now() * 1000 });
          encoder.encode(frame, { keyFrame: (frameIndex++ % 30) === 0 });
          frame.close();
        } catch (e) {
          emit({ event: 'error', message: 'frame: ' + e });
        }
      }, 33);
    } catch (e) {
      stopCapture();
      emit({ event: 'error', message: 'capture: ' + e });
    }
  }

  function connect() {
    controlWs = new WebSocket(CONTROL_WS);
    controlWs.onopen = function () {
      emit({ event: 'ready' });
      videoWs = new WebSocket(VIDEO_WS);
      videoWs.binaryType = 'arraybuffer';
      videoWs.onopen = function () {
        emit({ event: 'videoWsOpen' });
        // Defer until after paint — avoids about:blank / early document races.
        setTimeout(function () { startCapture(); }, 300);
      };
    };
    controlWs.onclose = function () {
      setTimeout(connect, 800);
    };
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', connect);
  } else {
    connect();
  }
})();
