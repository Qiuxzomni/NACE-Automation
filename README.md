# NACE Automation for Android

NACE Automation is an Android companion app for scheduled copy trading on the NACE exchange. Choose **BTC/USDT** or **ETH/USDT**, set your trading windows, and let the app submit eligible copy orders using the amount already filled in by the exchange. The automation runs on your phone, including while the screen is locked, when Android allows the background service and scheduled wake-ups.

[Download the signed Android APK](https://github.com/Qiuxzomni/NACE-Automation/releases/download/v0.2.22/NACE-Automation-0.2.22.apk) · [Release history](https://github.com/Qiuxzomni/NACE-Automation/releases)

## Get started

1. Download the APK and open it on an Android phone running Android 8.0 or later.
2. Choose **English**, **Română**, or **Español** in the first setup popup. Follow the prompts to allow scheduled wake-ups and notifications.
3. Enter your NACE phone number and password. If the exchange asks for a verification code, Control opens with your sign-in details prefilled and the code field in view. Enter the code on the exchange page and tap **Login**.
4. Select your trading pair and review the scheduled windows shown in your phone's time zone. Tap **Add time** to create another window.
5. Check the dashboard for **Automation Online** and **Connected**. Use **Settings** to change the language or theme, adjust alert sounds, or stop and restart automation.

## Watch, Control, and Run History

- **Watch** opens the selected pair's **Copy Trading List** in a read-only viewer. You can follow the page while automation runs.
- **Control** lets you interact with the exchange page and pauses scheduled automation. Close Control when you're done to let automation resume.
- **Run History** shows verified, unverified, failed, and skipped results. After submitting an order, NACE checks the exchange's **Order** tab. An unclear confirmation is marked unverified and automation avoids submitting the same signal again.
- **Balance** shows the available USDT balance when it can be verified. The raised value beside it shows the change in balance for the day.

## Checks and recovery

| When something needs attention | What NACE does |
| --- | --- |
| A scheduled window is approaching | Checks readiness **10 minutes** and **60 seconds** before the start, including internet access, the session, and the selected pair. |
| A condition blocks trading | Shows a notification describing the issue. Tap **Acknowledge** to stop repeating alert beeps. Alert sounds can be turned off in Settings while notifications stay available. |
| The session expires | Tries to reuse credentials for the same exchange domain. Credentials are saved encrypted on the device after a successful sign-in. Exchange verification still requires you to complete the challenge. |
| The exchange page repeatedly fails to load | Tests internet access independently. If the internet works and the exchange page fails five times, the app offers a popup to enter a replacement address. A different domain requires a fresh sign-in. |
| The phone restarts | Unlock it once so Android can resume the background service. |

A verified result means the exchange showed an order in its Order tab. Trading outcomes and any bonus eligibility depend on the exchange. Android battery management, connectivity, and changes to the exchange website can affect a scheduled run.

## Support

For help, use **Contact Support** in Settings or email [VibeCodeDrop@pm.me](mailto:VibeCodeDrop@pm.me). The app's EULA is available in Settings.

## Build from source

The Android app lives in `app/src/main`. To build on Windows, install JDK 17 or later, Android SDK Platform 36, and Android SDK Build Tools 36.0.0. Set `JAVA_HOME` and `ANDROID_HOME` to your installed JDK and SDK directories.

```powershell
.\build.ps1
```

The APK is written to `build/NACE Automation.apk`. The build creates a local development key on first use. Android accepts an in-place update only when both APKs use the same signing key.
