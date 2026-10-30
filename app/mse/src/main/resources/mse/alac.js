/* Vendored from @audio/decode-aac alac.js (Apache-2.0, Apple ALAC reference port).
 * https://github.com/audiojs/decode/tree/master/packages/decode-aac
 * Wrapped as a browser global for the MSE player page (no bundler).
 */
(function (global) {
/**
 * ALAC (Apple Lossless) decoder — pure JS.
 * Ported from Apple's ALAC reference decoder (Apache License 2.0):
 *   ALACDecoder.cpp, ag_dec.c, dp_dec.c, matrix_dec.c, ALACBitUtilities.c
 *   Copyright (c) 2011 Apple Inc. https://github.com/macosforge/alac
 *
 * createALAC(cookie) → { config, decodeFrame(frameBytes) → { channelData: Float32Array[], numSamples } }
 */

// ── constants (aglib.h) ──────────────────────────────────────────────
const QBSHIFT = 9, QB = 1 << QBSHIFT
const MMULSHIFT = 2, MDENSHIFT = QBSHIFT - MMULSHIFT - 1, MOFF = 1 << (MDENSHIFT - 2)
const BITOFF = 24
const MAX_PREFIX_16 = 9, MAX_PREFIX_32 = 9, MAX_DATATYPE_BITS_16 = 16
const N_MAX_MEAN_CLAMP = 0xffff, N_MEAN_CLAMP_VAL = 0xffff
// MPEG element ids (ALACBitUtilities.h)
const ID_SCE = 0, ID_CPE = 1, ID_LFE = 3, ID_DSE = 4, ID_FIL = 6, ID_END = 7

// ── bit reader (ALACBitUtilities.c) ──────────────────────────────────
// pos = absolute bit index into data; data is padded with 8 trailing zeros so
// 32-bit window reads near the end are always in-bounds.
function bits(data, realLen) { return { data, pos: 0, realLen } }
function read(bb, n) { // BitBufferRead — up to 16 bits, MSB first
  let p = bb.pos, o = p >> 3, d = bb.data
  let v = (((d[o] << 16) | (d[o + 1] << 8) | d[o + 2]) << (p & 7)) & 0xFFFFFF
  bb.pos = p + n
  return v >>> (24 - n)
}
function readOne(bb) { let p = bb.pos; bb.pos = p + 1; return (bb.data[p >> 3] >> (7 - (p & 7))) & 1 }
function byteAlign(bb) { bb.pos = (bb.pos + 7) & ~7 }
function read32(d, o) { return (((d[o] << 24) | (d[o + 1] << 16) | (d[o + 2] << 8) | d[o + 3]) >>> 0) }

function getstreambits(d, bitoffset, numbits) {
  let byteoffset = bitoffset >>> 3
  let load1 = read32(d, byteoffset), result
  if ((numbits + (bitoffset & 7)) > 32) {
    result = (load1 << (bitoffset & 7)) >>> 0
    let load2 = d[byteoffset + 4]
    load2 = load2 >>> (8 - (numbits + (bitoffset & 7) - 32))
    result = result >>> (32 - numbits)
    result = (result | load2) >>> 0
  } else {
    result = load1 >>> (32 - numbits - (bitoffset & 7))
  }
  if (numbits !== 32) result = (result & (((1 << numbits) >>> 0) - 1)) >>> 0
  return result >>> 0
}

// ── adaptive Golomb decode (ag_dec.c) ────────────────────────────────
function lg3a(x) { return 31 - Math.clz32(x + 3) }

// st = { p } carries the local bit position
function dynGet32(d, st, m, k, maxbits) {
  let tempbits = st.p
  let streamlong = (read32(d, tempbits >> 3) << (tempbits & 7)) >>> 0
  let result = Math.clz32((~streamlong) >>> 0)
  if (result >= MAX_PREFIX_32) {
    result = getstreambits(d, tempbits + MAX_PREFIX_32, maxbits)
    tempbits += MAX_PREFIX_32 + maxbits
  } else {
    tempbits += result + 1
    if (k !== 1) {
      streamlong = (streamlong << (result + 1)) >>> 0
      let v = streamlong >>> (32 - k)
      tempbits += k - 1
      result = Math.imul(result, m)
      if (v >= 2) { result += v - 1; tempbits += 1 }
    }
  }
  st.p = tempbits
  return result
}

function dynGet(d, st, m, k) {
  let tempbits = st.p
  let streamlong = (read32(d, tempbits >> 3) << (tempbits & 7)) >>> 0
  let pre = Math.clz32((~streamlong) >>> 0), result
  if (pre >= MAX_PREFIX_16) {
    pre = MAX_PREFIX_16
    tempbits += pre
    streamlong = (streamlong << pre) >>> 0
    result = streamlong >>> (32 - MAX_DATATYPE_BITS_16)
    tempbits += MAX_DATATYPE_BITS_16
  } else {
    tempbits += pre + 1
    streamlong = (streamlong << (pre + 1)) >>> 0
    let v = streamlong >>> (32 - k)
    tempbits += k
    result = Math.imul(pre, m) + v - 1
    if (v < 2) { result -= (v - 1); tempbits -= 1 }
  }
  st.p = tempbits
  return result
}

// fills pc[0..numSamples) with residuals; advances bb.pos
function dynDecomp(p, bb, pc, numSamples, maxSize) {
  let d = bb.data, st = { p: bb.pos }, maxPos = bb.realLen * 8
  let mb = p.mb0, pb = p.pb, kb = p.kb, wb = p.wb
  let zmode = 0, c = 0, o = 0
  while (c < numSamples) {
    if (st.p >= maxPos) throw Error('ALAC: ran past frame')
    let m = mb >>> QBSHIFT
    let k = Math.min(lg3a(m), kb)
    m = (1 << k) - 1
    let n = dynGet32(d, st, m, k, maxSize)
    let ndecode = n + zmode
    let mult = (-(ndecode & 1)) | 1
    pc[o++] = ((ndecode + 1) >> 1) * mult
    c++
    mb = pb * (n + zmode) + mb - ((pb * mb) >> QBSHIFT)
    if (n > N_MAX_MEAN_CLAMP) mb = N_MEAN_CLAMP_VAL
    zmode = 0
    if (((mb << MMULSHIFT) < QB) && (c < numSamples)) {
      zmode = 1
      k = Math.clz32(mb) - BITOFF + ((mb + MOFF) >> MDENSHIFT)
      let mz = ((1 << k) - 1) & wb
      n = dynGet(d, st, mz, k)
      if (c + n > numSamples) throw Error('ALAC: zero-run overflow')
      for (let j = 0; j < n; j++) { pc[o++] = 0; c++ }
      if (n >= 65535) zmode = 0
      mb = 0
    }
  }
  bb.pos = st.p
}

// ── dynamic predictor (dp_dec.c) ─────────────────────────────────────
function signOf(i) { return (((-i) >>> 31) | (i >> 31)) }

function unpcBlock(pc1, out, num, coefs, numactive, chanbits, denshift) {
  let chanshift = 32 - chanbits, denhalf = 1 << (denshift - 1)
  out[0] = pc1[0]
  if (numactive === 0) { for (let i = 1; i < num; i++) out[i] = pc1[i]; return }
  if (numactive === 31) {
    let prev = out[0]
    for (let j = 1; j < num; j++) { let del = pc1[j] + prev; prev = (del << chanshift) >> chanshift; out[j] = prev }
    return
  }
  for (let j = 1; j <= numactive; j++) { let del = pc1[j] + out[j - 1]; out[j] = (del << chanshift) >> chanshift }
  let lim = numactive + 1
  for (let j = lim; j < num; j++) {
    let sum1 = 0, pb = j - 1, top = out[j - lim]
    for (let kk = 0; kk < numactive; kk++) sum1 = (sum1 + Math.imul(coefs[kk], out[pb - kk] - top)) | 0
    let del = pc1[j], del0 = del, sg = signOf(del)
    del = (del + top + ((sum1 + denhalf) >> denshift)) | 0
    out[j] = (del << chanshift) >> chanshift
    if (sg > 0) {
      for (let kk = numactive - 1; kk >= 0; kk--) {
        let dd = top - out[pb - kk], sgn = signOf(dd)
        coefs[kk] -= sgn
        del0 -= Math.imul(numactive - kk, (Math.imul(sgn, dd) >> denshift))
        if (del0 <= 0) break
      }
    } else if (sg < 0) {
      for (let kk = numactive - 1; kk >= 0; kk--) {
        let dd = top - out[pb - kk], sgn = signOf(dd)
        coefs[kk] += sgn
        del0 -= Math.imul(numactive - kk, (Math.imul(-sgn, dd) >> denshift))
        if (del0 >= 0) break
      }
    }
  }
}

// ── magic cookie (ALACSpecificConfig) ────────────────────────────────
function parseConfig(c) {
  let u32 = (o) => (c[o] * 0x1000000) + (c[o + 1] << 16) + (c[o + 2] << 8) + c[o + 3]
  return {
    frameLength: u32(0), bitDepth: c[5], pb: c[6], mb: c[7], kb: c[8],
    numChannels: c[9], maxRun: (c[10] << 8) | c[11],
    sampleRate: u32(20),
  }
}

function createALAC(cookie) {
  let cfg = parseConfig(cookie)
  let N = cfg.frameLength
  let mixU = new Int32Array(N), mixV = new Int32Array(N), pred = new Int32Array(N)
  let shiftBuf = new Int32Array(N * 2)
  let scale = 1 / Math.pow(2, cfg.bitDepth - 1)

  function agParams(pbFactor) {
    let pb = ((cfg.pb * pbFactor) / 4) | 0
    return { mb0: cfg.mb, pb, kb: cfg.kb, wb: (1 << cfg.kb) - 1 }
  }

  // decode one ALAC frame → per-channel Int32 samples; returns numSamples
  function decodeElements(bb, chans, requested) {
    let chanIndex = 0, numSamples = requested
    while (true) {
      if (bb.pos >> 3 >= bb.realLen) break
      let tag = read(bb, 3)
      if (tag === ID_SCE || tag === ID_LFE) {
        read(bb, 4) // element instance tag
        if (read(bb, 12) !== 0) throw Error('ALAC: bad header')
        let hb = read(bb, 4)
        let partial = hb >> 3, bytesShifted = (hb >> 1) & 3, escape = hb & 1
        if (bytesShifted === 3) throw Error('ALAC: bad shift')
        let shift = bytesShifted * 8
        let chanBits = cfg.bitDepth - shift
        if (partial) numSamples = ((read(bb, 16) << 16) | read(bb, 16)) >>> 0
        if (!escape) {
          read(bb, 8); read(bb, 8) // mixBits, mixRes (unused for mono)
          let hu = read(bb, 8), modeU = hu >> 4, denShiftU = hu & 0xf
          let pu = read(bb, 8), pbFactorU = pu >> 5, numU = pu & 0x1f
          let coefsU = new Int16Array(32)
          for (let i = 0; i < numU; i++) coefsU[i] = read(bb, 16)
          let shiftPos = bb.pos
          if (bytesShifted) bb.pos += shift * numSamples
          dynDecomp(agParams(pbFactorU), bb, pred, numSamples, chanBits)
          if (modeU === 0) unpcBlock(pred, mixU, numSamples, coefsU, numU, chanBits, denShiftU)
          else { unpcBlock(pred, pred, numSamples, null, 31, chanBits, 0); unpcBlock(pred, mixU, numSamples, coefsU, numU, chanBits, denShiftU) }
          readShift(bb, shiftPos, shift, numSamples, 1)
        } else {
          let s = 32 - chanBits
          if (chanBits <= 16) {
            for (let i = 0; i < numSamples; i++) mixU[i] = (read(bb, chanBits) << s) >> s
          } else {
            let extra = chanBits - 16
            for (let i = 0; i < numSamples; i++) { let v = (read(bb, 16) << 16) >> s; mixU[i] = v | read(bb, extra) }
          }
          bytesShifted = 0
        }
        let out = new Int32Array(numSamples)
        for (let i = 0; i < numSamples; i++) out[i] = bytesShifted ? ((mixU[i] << shift) | shiftBuf[i]) : mixU[i]
        chans[chanIndex++] = out
      } else if (tag === ID_CPE) {
        read(bb, 4)
        if (read(bb, 12) !== 0) throw Error('ALAC: bad header')
        let hb = read(bb, 4)
        let partial = hb >> 3, bytesShifted = (hb >> 1) & 3, escape = hb & 1
        if (bytesShifted === 3) throw Error('ALAC: bad shift')
        let shift = bytesShifted * 8
        let chanBits = cfg.bitDepth - shift + 1
        if (partial) numSamples = ((read(bb, 16) << 16) | read(bb, 16)) >>> 0
        let mixBits = 0, mixRes = 0
        if (!escape) {
          mixBits = read(bb, 8); mixRes = (read(bb, 8) << 24) >> 24 // int8
          let hu = read(bb, 8), modeU = hu >> 4, denShiftU = hu & 0xf
          let pu = read(bb, 8), pbFactorU = pu >> 5, numU = pu & 0x1f
          let coefsU = new Int16Array(32)
          for (let i = 0; i < numU; i++) coefsU[i] = read(bb, 16)
          let hv = read(bb, 8), modeV = hv >> 4, denShiftV = hv & 0xf
          let pv = read(bb, 8), pbFactorV = pv >> 5, numV = pv & 0x1f
          let coefsV = new Int16Array(32)
          for (let i = 0; i < numV; i++) coefsV[i] = read(bb, 16)
          let shiftPos = bb.pos
          if (bytesShifted) bb.pos += shift * 2 * numSamples
          dynDecomp(agParams(pbFactorU), bb, pred, numSamples, chanBits)
          if (modeU === 0) unpcBlock(pred, mixU, numSamples, coefsU, numU, chanBits, denShiftU)
          else { unpcBlock(pred, pred, numSamples, null, 31, chanBits, 0); unpcBlock(pred, mixU, numSamples, coefsU, numU, chanBits, denShiftU) }
          dynDecomp(agParams(pbFactorV), bb, pred, numSamples, chanBits)
          if (modeV === 0) unpcBlock(pred, mixV, numSamples, coefsV, numV, chanBits, denShiftV)
          else { unpcBlock(pred, pred, numSamples, null, 31, chanBits, 0); unpcBlock(pred, mixV, numSamples, coefsV, numV, chanBits, denShiftV) }
          readShift(bb, shiftPos, shift, numSamples, 2)
        } else {
          chanBits = cfg.bitDepth
          let s = 32 - chanBits
          if (chanBits <= 16) {
            for (let i = 0; i < numSamples; i++) {
              mixU[i] = (read(bb, chanBits) << s) >> s
              mixV[i] = (read(bb, chanBits) << s) >> s
            }
          } else {
            let extra = chanBits - 16
            for (let i = 0; i < numSamples; i++) {
              let v = (read(bb, 16) << 16) >> s; mixU[i] = v | read(bb, extra)
              let w = (read(bb, 16) << 16) >> s; mixV[i] = w | read(bb, extra)
            }
          }
          bytesShifted = 0
        }
        // un-mix
        let l = new Int32Array(numSamples), r = new Int32Array(numSamples)
        for (let j = 0; j < numSamples; j++) {
          let a, b
          if (mixRes !== 0) { a = mixU[j] + mixV[j] - ((mixRes * mixV[j]) >> mixBits); b = a - mixV[j] }
          else { a = mixU[j]; b = mixV[j] }
          if (bytesShifted) { a = (a << shift) | shiftBuf[2 * j]; b = (b << shift) | shiftBuf[2 * j + 1] }
          l[j] = a; r[j] = b
        }
        chans[chanIndex++] = l; chans[chanIndex++] = r
      } else if (tag === ID_DSE) { skipDSE(bb) }
      else if (tag === ID_FIL) { skipFIL(bb) }
      else if (tag === ID_END) { byteAlign(bb); break }
      else throw Error('ALAC: unsupported element ' + tag)
      if (chanIndex >= chans.length) break
    }
    return numSamples
  }

  function readShift(bb, shiftPos, shift, numSamples, nch) {
    if (!shift) return
    let sb = { data: bb.data, pos: shiftPos, realLen: bb.realLen }
    let count = numSamples * nch
    for (let i = 0; i < count; i++) shiftBuf[i] = read(sb, shift)
  }

  function skipFIL(bb) { let count = read(bb, 4); if (count === 15) count += read(bb, 8) - 1; bb.pos += count * 8 }
  function skipDSE(bb) {
    read(bb, 4); let align = readOne(bb)
    let count = read(bb, 8); if (count === 255) count += read(bb, 8)
    if (align) byteAlign(bb)
    bb.pos += count * 8
  }

  return {
    config: cfg,
    decodeFrame(frame) {
      let data = new Uint8Array(frame.length + 8)
      data.set(frame)
      let bb = bits(data, frame.length)
      let chans = new Array(cfg.numChannels)
      let numSamples = decodeElements(bb, chans, cfg.frameLength)
      let channelData = []
      for (let c = 0; c < cfg.numChannels; c++) {
        let src = chans[c] || new Int32Array(numSamples)
        let f = new Float32Array(numSamples)
        for (let i = 0; i < numSamples; i++) f[i] = src[i] * scale
        channelData.push(f)
      }
      return { channelData, numSamples }
    },
  }
}

global.createALAC = createALAC;
})(typeof globalThis !== 'undefined' ? globalThis : window);
