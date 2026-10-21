# Assistant: personal voice-controlled phone assistant

Say or type commands in Hindi + English. The app picks the fastest way to run them: instant fast path, then Gemini AI routing, then an agent that reads and taps the screen. Taught skills, triggers, notifications and system toggles included.

## 1. Build the APK (no Android Studio)
1. Create a private repo on github.com (New > Private).
2. Unzip assistant-app.zip. Upload everything inside the folder (app, gradle, .github, build.gradle.kts, settings.gradle.kts, gradle.properties, .gitignore, README.md) to the repo root. Drag files AND folders into "Add file > Upload files". The hidden .github folder must go in.
3. Open the Actions tab. If asked, click "I understand my workflows, enable them".
4. Pick "Build debug APK" > Run workflow > Run (it also runs on every push to main).
5. Wait 5 to 10 minutes. Open the finished run. Under Artifacts download assistant-debug-apk.
6. Unzip it. Copy app-debug.apk to your phone and tap it. Allow "install unknown apps" when asked.

## 2. First run on the phone
1. Open the app and follow the 4 onboarding screens.
2. Settings > AI: paste your Gemini API key (aistudio.google.com/apikey). Save. Model default is gemini-2.5-flash.
3. Settings > Contacts and aliases: add people with phone, email, Telegram username, Instagram handle.
4. Settings > Nicknames: e.g. cmp -> https://your-site.

## 3. Manual phone settings (numbered)
1. Accessibility service: Android 13+: Settings > Apps > Assistant > three dots > Allow restricted settings. Then Settings > Accessibility > Assistant > ON > Allow. (If the three dots menu is missing, first try turning the service on once so Android blocks it, then retry.)
2. Notification access: Settings > Notifications > Device and app notifications (or Special app access > Notification access) > Assistant > ON.
3. Display over other apps (helps chained app launches from the background): Settings > Apps > Assistant > Display over other apps > allow.
4. Battery: Settings > Apps > Assistant > Battery > Unrestricted (or "No restrictions").
5. Location: allow "All the time" for place triggers (Settings > Apps > Assistant > Permissions > Location).
6. Exact alarms: Settings > Apps > Special access > Alarms & reminders > Assistant > allow (for on-time triggers).
7. Do Not Disturb access and Modify system settings: the app opens these screens the first time you use DND or brightness commands.
8. Shizuku (optional): install Shizuku, Settings > Developer options > turn on Wireless debugging, open Shizuku > Pairing / Start via wireless debugging, then in Assistant Settings tap Fix next to Shizuku and allow.
9. Xiaomi/Oppo/Vivo/Realme: Settings > Apps > Assistant > Autostart ON; Battery saver > No restrictions; lock the app in Recents.

## 4. Commands to try
- call <alias>, open chrome, open cmp (nickname), "open chrome, open cmp"
- text <alias> on whatsapp I'm coming / message <alias> on instagram hi / send <alias> a telegram hello
- email <alias> on gmail subject meeting body I will be late
- open claude, start a new chat, type what is the weather in Chaibasa today, press enter
- turn on wifi, turn off bluetooth, airplane mode on, dnd on, volume up, volume 40, brightness 60, force stop instagram, timer 5 minutes, set an alarm for 6 am
- learn this as ask claude ... (do it) ... tap green DONE; then "ask claude <question>"
- read my notifications, reply I'll call you back
- stop (cancels everything). Red STOP bubble floats while a task runs.

## 5. Triggers
Triggers tab > Add trigger: Time (HH:mm, optional days 1=Sun..7=Sat), Place (arrive/leave, use my current location), or Notification (app + text contains). Each runs any command or skill, e.g. "turn on wifi, open x". Give Location "All the time", Exact alarms and Battery unrestricted for reliability. If Android won't let the background service start, you get a notification: tap it to run.

## 6. Test checklist (easy to hard)
1. call <alias> (no AI key needed)
2. open cmp (nickname URL)
3. text <alias> on whatsapp hello (auto-sends)
4. email on gmail (auto-sends)
5. message <alias> on instagram hi
6. open claude ... press enter (agent loop)
7. learn this as test ... then run it by voice
8. turn on wifi / volume 30 / timer 1 minute
9. read my notifications; reply ok
10. time trigger 2 minutes from now running "open chrome"
11. place trigger, notification trigger

## 7. Known limitations
- Secure screens (banking, passwords, some Android settings) block screen reading and screenshots.
- Anti-bot or heavily custom apps may hide labels; vision fallback helps (Android 11+ only) but is slower.
- Wi-Fi/Bluetooth/airplane without Shizuku tap the Quick Settings tile, which differs by brand and may fail.
- Background launches can be blocked by Android; allow Display over other apps.
- Android 14+ blocks a microphone service from starting in the background: triggers then post a tap-to-run notification when the service isn't already running.
- Speech needs internet (or an offline language pack). Hindi script recognition may not match Latin aliases: keep speech language en-IN.
- Unknown apps run through the agent each time (not auto-saved as profiles); teach a skill instead.
- Screen content and commands are sent to Google's Gemini API when AI is used.
- MediaProjection screenshot fallback for Android 10 and older is not included.
- Not Play Store safe: broad permissions by design.

## 8. Troubleshooting and what to paste back
- Build fails: Actions > failed run > failed step. Paste the red lines that start with "e:" or "error:".
- App crashes or a command misbehaves: Activity tab > export (share icon) and paste the entries. For crashes also paste Logcat lines containing "AndroidRuntime" (via Shizuku/adb: adb logcat -d | grep -A 20 AndroidRuntime).
- Always include the exact command you said or typed.
- AI errors show the Gemini code in Activity (429 = wait and retry; 400/403 = key or model name).
- Accessibility keeps turning off: battery unrestricted, autostart on, lock app in Recents.
