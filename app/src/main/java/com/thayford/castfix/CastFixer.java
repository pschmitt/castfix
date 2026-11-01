package com.thayford.castfix;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.SystemClock;
import android.provider.Settings;

import java.net.Inet4Address;

/**
 * On Chromecast with Google TV the Cast receiver gets its device credentials only from the setup wizard
 * (setupwraith's CastInfoProvider), and the wizard has to stay disabled for a replacement launcher to own
 * Home. The receiver keeps the credentials in memory once loaded, so after each boot: switch the wizard on
 * until the receiver has loaded them, then switch it and the stock launcher off again.
 *
 * Whether the receiver holds its credentials is read from the receiver itself (CastHealth: does it sign a
 * device-auth challenge?), not from logcat, whose buffer the receiver fills within the hour.
 */
final class CastFixer {
    static final String SETUP = "com.google.android.tungsten.setupwraith";
    static final String STOCK_HOME = "com.google.android.apps.tv.launcherx";
    private static final String CAST_RECEIVER = "com.google.android.apps.mediashell";
    private static final String ADB_WIFI = "adb_wifi_enabled";
    private static final String HOME_QUERY =
            "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME";
    /** A receiver that has just started loads the credentials by itself once the wizard answers. */
    private static final long SELF_LOAD_WAIT_MS = 20_000;
    /** One that has been running without them doesn't ask again (cleanyourtv #2615): it is restarted. */
    private static final long RESTART_WAIT_MS = 75_000;
    /** The restarted receiver has to stay up: a copy that comes back after the wizard is off has no credentials. */
    private static final long STABLE_MS = 20_000;

    private final Context context;
    private final FixLog log;

    CastFixer(Context context) {
        this.context = context;
        this.log = new FixLog(context);
    }

    /** Runs the whole fix and returns a one-line result for the screen. */
    String run(String trigger) {
        log.add("--- run (" + trigger + ")");
        if (context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                != PackageManager.PERMISSION_GRANTED) {
            return log.result("Needs WRITE_SECURE_SETTINGS, granted once from a computer (README, Setup).");
        }
        String ip = waitForWifi(90_000);
        if (ip == null) return log.result("No Wi-Fi after 90 s; nothing changed.");
        boolean interrupted = log.hasPending();
        if (interrupted) {
            log.add("the last run was cut short with the setup wizard on; cleaning up first");
        } else {
            // A receiver that signs holds its credentials: nothing to do, and Wireless debugging stays as it is.
            CastHealth.State state = CastHealth.check(5_000);
            log.setLastCheck(state.name());
            log.add("Cast receiver: " + describe(state));
            if (state == CastHealth.State.SIGNED) return log.result("Casting already works.");
        }
        ContentResolver resolver = context.getContentResolver();
        boolean debuggingWasOn = Settings.Global.getInt(resolver, ADB_WIFI, 0) == 1;
        Settings.Global.putInt(resolver, ADB_WIFI, 1);
        // A paired key otherwise lapses after 7 days without a connection, and restarts can be further apart.
        Settings.Global.putLong(resolver, "adb_allowed_connection_time", 0L);
        try (Shell shell = new Shell(context, ip, log)) {
            if (!shell.connect()) return log.result("Could not reach Wireless debugging on this TV.");
            return fix(shell, interrupted);
        } catch (Exception e) {
            log.add("error: " + e);
            return log.result("Failed: " + e.getMessage());
        } finally {
            // Troubleshooting: `settings put global castfix_keep_debugging 1` keeps it on to read the logs.
            if (!debuggingWasOn && Settings.Global.getInt(resolver, "castfix_keep_debugging", 0) != 1) {
                Settings.Global.putInt(resolver, ADB_WIFI, 0);
                log.add("Wireless debugging switched off again");
            }
        }
    }

    private String fix(Shell shell, boolean interrupted) throws Exception {
        String disabled = shell.run("pm list packages -d");
        if (interrupted) {
            recover(shell, disabled);
            disabled = shell.run("pm list packages -d");
            if (CastHealth.check(5_000) == CastHealth.State.SIGNED) return log.result("Cleaned up; casting works.");
        }
        if (!hasPackage(disabled, SETUP)) {
            return log.result("The setup wizard is on, so casting works without a fix.");
        }
        String home = lastLine(shell.run(HOME_QUERY));
        boolean stockHomeWasOff = hasPackage(disabled, STOCK_HOME);
        log.add("Home is " + home);
        // Written before anything changes, so a run that dies in the next minute is undone by the next one.
        log.setPending(home, stockHomeWasOff);
        // Stock launcher first: a Home press in the next minute then lands on it, not on the wizard's
        // RecoveryActivity (seen crash-looping system_server on other Google TV 14 builds).
        if (stockHomeWasOff) shell.run("pm enable --user 0 " + STOCK_HOME);
        shell.run("pm enable --user 0 " + SETUP);
        log.add("setup wizard on, waiting for the Cast receiver");
        boolean signed = waitForSignature(SELF_LOAD_WAIT_MS);
        if (!signed) {
            String pid = shell.run("pidof " + CAST_RECEIVER).trim();
            log.add("the receiver (pid " + pid + ") did not load them; restarting it");
            shell.run("am crash " + CAST_RECEIVER);
            signed = waitForSignature(RESTART_WAIT_MS);
        }
        if (signed) {
            String pid = shell.run("pidof " + CAST_RECEIVER).trim();
            Thread.sleep(STABLE_MS);
            String later = shell.run("pidof " + CAST_RECEIVER).trim();
            if (!pid.equals(later)) {
                log.add("the receiver restarted again (pid " + pid + " -> " + later + "); waiting for it");
                signed = waitForSignature(RESTART_WAIT_MS);
            }
        }
        if (!signed) log.add("Cast credentials did not load in time");
        shell.run("pm disable-user --user 0 " + SETUP);
        if (stockHomeWasOff) restoreHome(shell, home);
        log.clearPending();
        // The receiver keeps them in memory, so this has to hold with the wizard off again.
        CastHealth.State after = CastHealth.check(5_000);
        log.setLastCheck(after.name());
        log.add("with the setup wizard off: " + describe(after));
        if (signed && after == CastHealth.State.SIGNED) return log.result("Casting ready.");
        return log.result("Cast credentials did not load; run it again.");
    }

    /** Undoes a run that was cut short: the wizard off, and the replacement launcher back as Home. */
    private void recover(Shell shell, String disabled) throws Exception {
        if (!hasPackage(disabled, SETUP)) shell.run("pm disable-user --user 0 " + SETUP);
        String home = log.pendingHome();
        if (log.pendingStockHomeWasOff() && home != null) restoreHome(shell, home);
        log.clearPending();
        log.add("setup wizard off again");
    }

    /** Puts the replacement launcher back as Home, or leaves the stock one on if that fails. */
    private void restoreHome(Shell shell, String home) throws Exception {
        boolean replacement = home.contains("/") && !home.startsWith(STOCK_HOME + "/")
                && !home.startsWith(SETUP + "/") && !home.startsWith("android/");
        if (!replacement) {
            log.add("no replacement launcher owned Home; leaving the stock launcher on");
            return;
        }
        // Same order as cleanyourtv's build-and-install: start the new Home, disable the stock one,
        // set the preference last (Android drops it whenever the set of Home apps changes).
        String focus = shell.run("dumpsys window | grep mCurrentFocus");
        if (focus.contains(STOCK_HOME) || focus.contains(SETUP)) shell.run("am start -n " + home);
        shell.run("pm disable-user --user 0 " + STOCK_HOME);
        shell.run("cmd package set-home-activity --user 0 " + home);
        String now = lastLine(shell.run(HOME_QUERY));
        if (now.equals(home)) {
            log.add("Home is " + home + " again");
        } else {
            shell.run("pm enable --user 0 " + STOCK_HOME);
            log.add("Home went to " + now + "; stock launcher switched back on");
        }
    }

    /** Polls the receiver until it signs a device-auth challenge, or the time is up. */
    private boolean waitForSignature(long timeoutMs) throws InterruptedException {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(3_000);
            if (CastHealth.check(4_000) == CastHealth.State.SIGNED) {
                log.add("Cast credentials loaded");
                return true;
            }
        }
        return false;
    }

    private static String describe(CastHealth.State state) {
        switch (state) {
            case SIGNED: return "signs, credentials loaded";
            case UNSIGNED: return "empty signature, no credentials";
            default: return "not answering on port 8009";
        }
    }

    /** The Wi-Fi IPv4 address once Wi-Fi is up, or null on timeout. */
    private String waitForWifi(long timeoutMs) {
        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            Network network = cm.getActiveNetwork();
            NetworkCapabilities caps = network == null ? null : cm.getNetworkCapabilities(network);
            LinkProperties link = network == null ? null : cm.getLinkProperties(network);
            if (caps != null && link != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                for (LinkAddress address : link.getLinkAddresses()) {
                    if (address.getAddress() instanceof Inet4Address) return address.getAddress().getHostAddress();
                }
            }
            SystemClock.sleep(1_000);
        }
        return null;
    }

    private static boolean hasPackage(String packageList, String pkg) {
        for (String line : packageList.split("\n")) {
            if (line.trim().equals("package:" + pkg)) return true;
        }
        return false;
    }

    private static String lastLine(String text) {
        String[] lines = text.trim().split("\n");
        return lines[lines.length - 1].trim();
    }
}
