# Cast Fix

Keeps casting from phones working on a **Chromecast with Google TV** whose setup wizard is disabled so
that a replacement launcher can own Home, as [cleanyourtv](https://github.com/Gathin23/cleanyourtv)'s
debloat lists do. It runs by itself after every restart and needs no computer once it is set up.

Tested on a Chromecast with Google TV HD (boreal), Google TV 14, build UTTC.250917.004. The 4K model
(sabrina) runs the same build and should behave the same, but hasn't been tried.

![Cast Fix on the TV after two restarts: last run "Casting ready", with the log of each run](docs/screenshot.png)

## The problem

With `com.google.android.tungsten.setupwraith` disabled, phones refuse to cast:

> Untrusted device. Living Room TV couldn't be verified. This could be caused by outdated device firmware.

The Cast receiver (`com.google.android.apps.mediashell`) gets its factory device credentials only from
the setup wizard, which hosts `CastInfoProvider`. Without it the receiver logs, once a minute,

```
E/ActivityThread: Failed to find provider info for com.google.android.tvsetup...castsetupinfo.CastInfoProvider
E/cast_ClientAuthCredsFactoryProvisioned: Cannot get device credentials
W/cast_shell: client_auth_signer.cc(180)] No private key, hence empty signature.
```

and answers the phone's authentication challenge unsigned. The wizard can't simply stay on either: its
`RecoveryActivity` claims Home at priority 1, above any third-party launcher, and adb may only disable
whole packages, not single components (`Shell cannot change component state`).

## The fix

Once the receiver has loaded the credentials it keeps them in memory until it restarts. So after every
boot Cast Fix:

1. asks the receiver on `127.0.0.1:8009` to sign the device-auth challenge phones send; if it signs,
   casting works and the run ends here, without touching Wireless debugging
2. switches Wireless debugging on and connects to the TV's own adbd over loopback, with a key that was
   paired once
3. switches the stock launcher on (so a Home press in the meantime lands somewhere safe), then the setup
   wizard
4. gives the receiver 20 seconds to load the credentials by itself; a receiver that doesn't (one that has
   been running for a while, or sometimes the one started at boot) is restarted with `am crash`, and the
   new one signed within 4 to 6 seconds in testing
5. switches the wizard and the stock launcher off again and puts your launcher back as Home, or leaves
   the stock launcher on if that fails, and checks that the receiver still signs
6. switches Wireless debugging off again

A run at boot took about 20 seconds in testing. The **Cast Fix** tile in the app list does the same by hand, in
case casting stops without a restart; when casting already works it only checks and says so.

## Install (once, from a computer with adb)

You need adb on a computer connected to the TV (Developer options > Wireless debugging > Pair device with
pairing code, `adb pair`, `adb connect`). Download `castfix-<version>.apk` from
[Releases](../../releases), or build it (below), then:

```sh
adb install castfix-1.0.apk
adb shell pm grant com.thayford.castfix android.permission.WRITE_SECURE_SETTINGS
adb shell am start -n com.thayford.castfix/.MainActivity    # once, or Android never sends it boot events
```

If `adb install` hangs (Android 14 can wait for an on-screen confirmation), `adb push` the APK to
`/data/local/tmp/` and run `adb shell pm install -r /data/local/tmp/castfix-1.0.apk` instead.

Then pair the app itself. On the TV open Developer options > Wireless debugging > **Pair device with
pairing code**, and while that dialog is still open run, with the port and code it shows:

```sh
adb shell am broadcast -n com.thayford.castfix/.PairReceiver --ei port <pairing port> --es code <code>
# Broadcast completed: result=0, data="paired"
```

The app's screen now reads "Setup: paired · settings permission granted". Leave Developer options on;
Wireless debugging itself can be off.

Android switches Wireless debugging on only on a network it was allowed on for good. The first time it is
switched on, the TV asks "Allow wireless debugging on this network?": tick **Always allow on this
network**, then Allow. Allowed only once, the TV asks again at every boot, nobody answers, and the run at
boot ends with "Wireless debugging would not stay on". The permission belongs to the access point (its
BSSID), so a mesh node, a repeater or the other band of the same router asks once more.

Restart the TV and a "Cast Fix: Casting ready." message appears within a minute or two.

## Security

- The app holds an adb key for your TV, so it can do anything `adb shell` can. The key stays in the app's
  private storage, and Wireless debugging is on only for the seconds a run takes.
- The pairing receiver requires `android.permission.DUMP`, which the adb shell holds and ordinary apps
  don't, so no other app on the TV can feed it a pairing code.
- It sets `adb_allowed_connection_time` to 0 (the "Disable adb authorization timeout" developer option),
  because a paired key otherwise lapses after 7 days without a connection and restarts can be further
  apart than that. This applies to every paired key, including your computer's.
- No network use beyond adb over loopback and an mDNS lookup of the TV's own debugging port. No
  analytics, no internet access.

## Uninstall

```sh
adb uninstall com.thayford.castfix
adb shell settings delete global adb_allowed_connection_time     # back to the 7-day default
```

and remove "CastFix" under Wireless debugging > Paired devices. Casting then stops working again after the
next restart, unless you re-enable the setup wizard (and with it the stock launcher).

## Troubleshooting

The screen shows the last result and the last lines of the log; everything also goes to logcat with tag
`CastFix`. To keep Wireless debugging on after a run so the log can be read from a computer:

```sh
adb shell settings put global castfix_keep_debugging 1     # settings delete global castfix_keep_debugging to undo
```

"Wireless debugging would not stay on" means Android switched it straight back off, almost always because
the network was not allowed for good (Install, above): switch Wireless debugging on once by hand and tick
**Always allow on this network**.

The receiver also loses its credentials when its process restarts without a reboot (it crashed one night in
testing). A watchdog job checks the receiver's signature every 15 minutes and runs the fix when it is empty,
and a run that fails is tried again 2 minutes later; for the job to run on time, exempt the app from battery
optimisation once: `adb shell dumpsys deviceidle whitelist +com.thayford.castfix`.

Every adb command has a 20-second limit and is retried up to three times on a fresh connection.
libadb's `open()` waits for adbd's OKAY without a timeout and can miss it when the answer arrives before
the wait starts; that hung a run once just after a cold boot.

## Build

JDK 17 and Android SDK platform 34:

```sh
./gradlew assembleRelease     # app/build/outputs/apk/release/app-release.apk
```

## Credits

- [libadb-android](https://github.com/MuntashirAkon/libadb-android) by Muntashir Al-Islam (used under
  Apache-2.0), [Conscrypt](https://github.com/google/conscrypt) (Apache-2.0) and
  [Bouncy Castle](https://www.bouncycastle.org/) (MIT-style licence).
- Pairing an app with its own TV's Wireless debugging comes from
  [adb-auto-enable](https://github.com/mouldybread/adb-auto-enable).
- The cast icon is the Material Icons "cast" glyph (Apache-2.0).

Cast Fix is MIT-licensed; see [LICENSE](LICENSE).
