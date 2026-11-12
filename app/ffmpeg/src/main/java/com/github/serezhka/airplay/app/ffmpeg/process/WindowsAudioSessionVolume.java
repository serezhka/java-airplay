package com.github.serezhka.airplay.app.ffmpeg.process;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Per-process Windows audio-session volume (mixer slider) so HLS {@code ffplay} gain can change
 * without relaunching the process. Uses a small PowerShell + Core Audio helper.
 */
final class WindowsAudioSessionVolume {

    private static final Logger log = LoggerFactory.getLogger(WindowsAudioSessionVolume.class);
    private static final AtomicReference<Path> helperScript = new AtomicReference<>();

    private WindowsAudioSessionVolume() {
    }

    /**
     * @param pid    ffplay pid
     * @param linear 0.0–1.0
     * @return true when the session volume was applied (or pid not yet audible); false on hard failure
     */
    static boolean setByPid(long pid, double linear) {
        if (pid <= 0) {
            return true;
        }
        int percent = Math.max(0, Math.min(100, (int) Math.round(linear * 100.0)));
        try {
            Path script = ensureHelper();
            Process p = new ProcessBuilder(
                    "powershell", "-NoProfile", "-ExecutionPolicy", "Bypass",
                    "-File", script.toAbsolutePath().toString(),
                    Long.toString(pid), Integer.toString(percent))
                    .redirectErrorStream(true)
                    .start();
            String out;
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) {
                    if (!sb.isEmpty()) {
                        sb.append('\n');
                    }
                    sb.append(line);
                }
                out = sb.toString().trim();
            }
            if (!p.waitFor(4, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                log.debug("Windows session volume timed out for pid={}", pid);
                return false;
            }
            if (p.exitValue() != 0) {
                log.debug("Windows session volume pid={} exit={} out={}", pid, p.exitValue(), out);
                return false;
            }
            // "NONE" = process has no session yet (same as Pulse empty list) — treat as soft OK.
            if ("NONE".equalsIgnoreCase(out) || out.isBlank()) {
                log.debug("No audio session for pid={} yet; volume={}%% deferred", pid, percent);
                return true;
            }
            log.debug("Windows session volume pid={} -> {}% ({})", pid, percent, out);
            return true;
        } catch (Exception e) {
            log.debug("Windows session volume failed: {}", e.toString());
            return false;
        }
    }

    private static Path ensureHelper() throws Exception {
        Path existing = helperScript.get();
        if (existing != null && Files.isRegularFile(existing)) {
            return existing;
        }
        Path dir = Path.of(System.getProperty("java.io.tmpdir"), "java-airplay-ffmpeg");
        Files.createDirectories(dir);
        Path script = dir.resolve("set-session-volume.ps1");
        String body = """
                param([Parameter(Mandatory=$true)][int]$ProcessId, [Parameter(Mandatory=$true)][int]$Percent)
                $ErrorActionPreference = 'Stop'
                $code = @'
                using System;
                using System.Runtime.InteropServices;
                public static class AudioSessionVolume {
                  [ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")]
                  class MMDeviceEnumeratorComObject { }
                  [Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
                  interface IMMDeviceEnumerator {
                    int NotImpl1();
                    [PreserveSig] int GetDefaultAudioEndpoint(int dataFlow, int role, out IMMDevice endpoint);
                  }
                  [Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
                  interface IMMDevice {
                    [PreserveSig] int Activate(ref Guid iid, int dwClsCtx, IntPtr pActivationParams,
                        [MarshalAs(UnmanagedType.IUnknown)] out object ppInterface);
                  }
                  // IAudioSessionManager (2) then IAudioSessionManager2::GetSessionEnumerator
                  [Guid("77AA99A0-1BD6-484F-8BC7-2C654C9A9B6F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
                  interface IAudioSessionManager2 {
                    int NotImpl1();
                    int NotImpl2();
                    [PreserveSig] int GetSessionEnumerator(out IAudioSessionEnumerator sessionEnum);
                  }
                  [Guid("E2F5BB11-0570-40CA-ACDD-3AA01277DEE8"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
                  interface IAudioSessionEnumerator {
                    [PreserveSig] int GetCount(out int sessionCount);
                    [PreserveSig] int GetSession(int index, [MarshalAs(UnmanagedType.IUnknown)] out object session);
                  }
                  // IAudioSessionControl (9) + GetSessionIdentifier + GetSessionInstanceIdentifier + GetProcessId
                  [Guid("BFB7FF88-7239-4FC9-8FA2-07C950BE9C6D"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
                  interface IAudioSessionControl2 {
                    int N1(); int N2(); int N3(); int N4(); int N5(); int N6(); int N7(); int N8(); int N9();
                    int N10(); int N11();
                    [PreserveSig] int GetProcessId(out uint processId);
                  }
                  [Guid("87CE5498-68D6-44E5-9215-6DA47EF883D8"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
                  interface ISimpleAudioVolume {
                    [PreserveSig] int SetMasterVolume(float fLevel, ref Guid eventContext);
                    [PreserveSig] int GetMasterVolume(out float pfLevel);
                    [PreserveSig] int SetMute(bool bMute, ref Guid eventContext);
                    [PreserveSig] int GetMute(out bool pbMute);
                  }
                  public static string Set(int pid, float level) {
                    var enumerator = (IMMDeviceEnumerator)(object)new MMDeviceEnumeratorComObject();
                    IMMDevice device;
                    Marshal.ThrowExceptionForHR(enumerator.GetDefaultAudioEndpoint(0, 0, out device));
                    Guid iidMgr = typeof(IAudioSessionManager2).GUID;
                    object mgrObj;
                    Marshal.ThrowExceptionForHR(device.Activate(ref iidMgr, 23, IntPtr.Zero, out mgrObj));
                    var mgr = (IAudioSessionManager2)mgrObj;
                    IAudioSessionEnumerator sessions;
                    Marshal.ThrowExceptionForHR(mgr.GetSessionEnumerator(out sessions));
                    int count;
                    Marshal.ThrowExceptionForHR(sessions.GetCount(out count));
                    int matched = 0;
                    Guid ctx = Guid.Empty;
                    for (int i = 0; i < count; i++) {
                      object session;
                      Marshal.ThrowExceptionForHR(sessions.GetSession(i, out session));
                      var ctl2 = (IAudioSessionControl2)session;
                      uint spid;
                      if (ctl2.GetProcessId(out spid) != 0 || spid != (uint)pid) continue;
                      var vol = (ISimpleAudioVolume)session;
                      Marshal.ThrowExceptionForHR(vol.SetMasterVolume(level, ref ctx));
                      matched++;
                    }
                    return matched == 0 ? "NONE" : ("OK:" + matched);
                  }
                }
                '@
                $typeName = 'AudioSessionVolume'
                if (-not ($typeName -as [type])) {
                  Add-Type -TypeDefinition $code -Language CSharp | Out-Null
                }
                $level = [Math]::Max(0.0, [Math]::Min(1.0, $Percent / 100.0))
                Write-Output ([AudioSessionVolume]::Set($ProcessId, [float]$level))
                """;
        // Always rewrite — COM helper layout has been corrected in place.
        Files.writeString(script, body);
        helperScript.set(script);
        return script;
    }
}
