package com.github.serezhka.airplay.app.ffmpeg.process;

import com.sun.jna.Native;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Windows stand-in for Unix {@code SIGSTOP}/{@code SIGCONT}: freeze a child {@code ffplay}
 * without destroying its HWND (keeps the last frame on screen across pause / seek replace).
 */
final class WindowsProcessControl {

    private static final Logger log = LoggerFactory.getLogger(WindowsProcessControl.class);
    private static final int PROCESS_SUSPEND_RESUME = 0x0800;
    private static final int PROCESS_QUERY_LIMITED_INFORMATION = 0x1000;

    private WindowsProcessControl() {
    }

    static boolean suspend(long pid) {
        return ntControl(pid, true);
    }

    static boolean resume(long pid) {
        return ntControl(pid, false);
    }

    private static boolean ntControl(long pid, boolean suspend) {
        if (pid <= 0) {
            return false;
        }
        WinNT.HANDLE handle = Kernel32.INSTANCE.OpenProcess(
                PROCESS_SUSPEND_RESUME | PROCESS_QUERY_LIMITED_INFORMATION, false, (int) pid);
        if (handle == null) {
            log.debug("OpenProcess pid={} failed: {}", pid, Kernel32.INSTANCE.GetLastError());
            return false;
        }
        try {
            int status = suspend
                    ? Ntdll.INSTANCE.NtSuspendProcess(handle)
                    : Ntdll.INSTANCE.NtResumeProcess(handle);
            if (status != 0) {
                log.debug("Nt{}Process pid={} status=0x{}",
                        suspend ? "Suspend" : "Resume", pid, Integer.toHexString(status));
                return false;
            }
            return true;
        } catch (UnsatisfiedLinkError e) {
            log.debug("ntdll suspend/resume unavailable: {}", e.toString());
            return false;
        } finally {
            Kernel32.INSTANCE.CloseHandle(handle);
        }
    }

    private interface Ntdll extends StdCallLibrary {
        Ntdll INSTANCE = Native.load("ntdll", Ntdll.class, W32APIOptions.DEFAULT_OPTIONS);

        int NtSuspendProcess(WinNT.HANDLE processHandle);

        int NtResumeProcess(WinNT.HANDLE processHandle);
    }
}
