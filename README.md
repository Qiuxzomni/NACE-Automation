# NACE Automation for Android

NACE Automation is an Android companion app for scheduled copy trading on the NACE exchange. Choose **BTC/USDT** or **ETH/USDT**, set your trading windows, and let the app submit eligible copy orders using the amount already filled in by the exchange. The automation runs on your phone, including while the screen is locked. It is built to be easy for everyone, including older users: it tells you out loud and with notifications whether it is ready, and helps you fix anything that is not.

**[Download NACE Automation 1.7 (APK)](https://github.com/Qiuxzomni/NACE-Automation/releases/download/v1.7/NACE-Automation-1.7.apk)** · [Release history](https://github.com/Qiuxzomni/NACE-Automation/releases)

## Get started

1. Download the APK and open it on an Android phone running Android 8.0 or later.
2. Choose **English**, **Română**, or **Español**, then complete the setup steps in order:
   - **Step 1 · Scheduled wake-ups**, so the app can wake up for your trades.
   - **Step 2 · Notifications**, so the app can warn you.
   - **Step 3 · Background running**, so Android does not stop the app in the middle of a trade.
   - **Step 4 · Sign in to NACE.** Sign-in unlocks only after steps 1–3 are done.
3. Enter your NACE phone number and password. If the exchange asks for a security code, the exchange page opens with your details filled in and both the code field and the **Login** button under it in view. Enter the code and tap that **Login** button.
4. After sign-in, a short **How NACE Automation works** guide explains the app. You can open it again from **Settings**.
5. Select your trading pair. Switching between BTC and ETH always asks for confirmation. Tap **Add time** to add another trading window.

## Always knowing that it works

- **Next trade countdown.** The dashboard shows **NEXT TRADE IN h:mm:ss**, and **TRADE WINDOW OPEN NOW** while a window runs.
- **Spoken status.** About 5 minutes before each trade the app says *"NACE automation online. Waiting for the trade."* After the trade it says the result.
- **Warnings.** If something can stop a trade (no internet, login expired, NACE website unreachable, wrong pair), the app shows a red **⚠ WARNING** notification with the reason, vibrates, beeps until acknowledged, and says *"Warning!"* with the reason. When it is fixed it says *"Problem solved."*
- **Early health check.** About one hour before each window the app checks internet, the NACE website and the login, so there is time to fix a problem.
- **Help when you open the app.** If something needs attention, a popup explains it and offers the fix: **Check internet** (turns green when the connection works), **Sign in**, or **Enter new link** when the NACE address changed. Required settings that were turned off (notifications, wake-ups, background running) must be turned back on.

## Watch, Control, and Run History

- **Watch** opens the pair selected in the app and its **Copy Trading List** in a read-only viewer. It shows which pair it is opening and never interrupts the automation.
- **Control** lets you use the NACE website yourself, as in a browser, and pauses scheduled automation. It closes by itself after 5 minutes without use or when the screen turns off, so automation always comes back.
- **Run History** shows verified, unverified, failed, and skipped results. After submitting an order, the app checks the exchange's **Order** tab. An unclear confirmation is marked unverified and the same signal is never submitted twice.
- **Balance** shows the available USDT balance when it can be verified, with the change for the day beside it.

## Checks and recovery

| When | What NACE Automation does |
| --- | --- |
| 1 hour before a window | Health check of internet, the NACE website and the login. Warns early if something is wrong. |
| 12 minutes to 60 seconds before a window | Readiness checks: internet, session, and that the NACE page shows the selected pair. The page is switched to the selected pair if needed. |
| A condition blocks trading | Red WARNING notification, beeps and spoken warning with the reason. Tap **Acknowledge** to stop the beeps. Alert sounds can be turned off in Settings while notifications stay on. |
| The session expires | Reuses the credentials saved encrypted on the device for the same exchange address. A security code still has to be entered by you. |
| The NACE address stops working | Offers to enter the new address, then sign in. |
| The phone restarts or the app is updated | The scheduled wake-ups are restored automatically. |

A verified result means the exchange showed an order in its Order tab. Trading outcomes and any bonus eligibility depend on the exchange. Connectivity and changes to the exchange website can affect a scheduled run.

## Privacy

Once a day the app sends an anonymous usage signal (a random install ID, the app version, the Android version and the app language) to count installs and active users. It never includes your phone number, sign-in details, balances, trades or pairs. You can turn it off in Settings under **Anonymous usage stats**. A diagnostic log stays on the phone; you can send it to support from **Settings → Share diagnostic log**.

## Support

For help, use **Contact Support** in Settings or email [VibeCodeDrop@pm.me](mailto:VibeCodeDrop@pm.me). The app's EULA is available in Settings.

## Build from source

The Android app lives in `app/src/main`. To build on Windows, install JDK 17 or later, Android SDK Platform 36, and Android SDK Build Tools 36.0.0. Set `JAVA_HOME` and `ANDROID_HOME` to your installed JDK and SDK directories.

```powershell
.\build.ps1
```

The APK is written to `build/NACE Automation.apk`. The build creates a local development key on first use. Android accepts an in-place update only when both APKs use the same signing key.
