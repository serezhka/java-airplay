package com.github.serezhka.airplay.server.internal.handler.control;

/**
 * When to call {@link com.github.serezhka.airplay.server.Playback#onPresentationEnded()}.
 * <p>
 * Screen mirror RTSP ({@code Active-Remote}) and YouTube HLS ({@code X-Apple-Session-ID})
 * are different sessions. Rules:
 * <ul>
 *   <li>Never end while any HLS session is still live (mirror TEARDOWN during YouTube).</li>
 *   <li>YouTube-only (no prior mirror SETUP): {@code /stop} / reverse-close ends presentation
 *       — there is no RTSP TEARDOWN.</li>
 *   <li>After mirror was seen: keep presentation on {@code /stop} so remirror can reuse the
 *       player; end on VIDEO/full RTSP TEARDOWN when HLS is gone.</li>
 * </ul>
 */
final class PresentationEndPolicy {

    enum Decision {
        KEEP,
        END
    }

    private PresentationEndPolicy() {
    }

    /**
     * After HTTP {@code /stop}, {@code playlistRemove}, or reverse-event close cleared HLS.
     */
    static Decision afterHlsIdle(boolean anyHlsLeft, boolean anyVideoRunning, boolean screenMirrorSeen) {
        if (anyHlsLeft || anyVideoRunning) {
            return Decision.KEEP;
        }
        // Pure YouTube (or audio-only) disconnect — no RTSP TEARDOWN will follow.
        if (!screenMirrorSeen) {
            return Decision.END;
        }
        // Mirror was used: remirror SETUP often follows /stop within a second.
        return Decision.KEEP;
    }

    /** RTSP VIDEO TEARDOWN after this session's video server was stopped. */
    static Decision afterVideoTeardown(boolean anyHlsLeft) {
        return anyHlsLeft ? Decision.KEEP : Decision.END;
    }

    /** RTSP full TEARDOWN (no stream list) after local A/V/HLS cleanup. */
    static Decision afterFullTeardown(boolean anyHlsLeft) {
        return anyHlsLeft ? Decision.KEEP : Decision.END;
    }
}
