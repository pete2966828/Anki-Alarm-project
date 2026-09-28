# Anki Alarm

An Android alarm clock that only stops ringing once you've reviewed your Anki flashcards.

Cards come from **AnkiDroid**, and your answers (Again / Hard / Good / Easy) are saved in your
AnkiDroid collection like any normal review. When AnkiDroid syncs, they go to AnkiWeb and then to
Anki on your computer.

## How it works

1. At alarm time the phone rings and vibrates, and your next due card appears over the lock screen.
2. Tap **Show answer**, then grade yourself. Each graded card counts toward the number you chose (1–30).
3. After the last card the alarm stops. If **Sync after waking up** is on, unlock your phone and AnkiDroid syncs with AnkiWeb.

The back button doesn't close the alarm screen. If AnkiDroid isn't installed, isn't allowed, or has no
cards due, you get simple math problems instead, so you can always turn the alarm off.

Each alarm has its own time, repeat days, label, deck (or "current deck in AnkiDroid"), number of cards, sound, and snooze (off / 5 / 10 min, up to 3 times).

**Shakes before each card:** Off, or 5–50 in steps of 5. Each card is hidden behind a "Shake your phone!" counter until you've
shaken the phone that many times, which gets you moving before you have to think. A firm shake or a quick flip of the phone counts.
If no shake registers for 90 seconds (for example, a broken sensor), a "Shaking not working?" link appears so you can never get stuck.
Phones without a motion sensor skip the shaking.

**Scan your wake-up code first:** on the main screen, under **Wake-up code**, scan any barcode or QR code once. A product barcode
(toothpaste, coffee jar) or a QR code printed and stuck in the bathroom both work. Alarms with this switch on show a camera
view instead of cards until you walk over and scan that exact code. The camera needs permission, and codes are read on the phone
without internet. If scanning isn't possible after 3 minutes, a "Can't scan it?" link appears.

**Volume:** with **Gentle start**, the alarm begins quietly and reaches full volume after 30 seconds. While you're answering
(touching the screen, typing, shaking), it drops to 30% so you can think. Stop for 30 seconds and it's back at full volume.

**Sound:** use the default alarm sound, pick one of the phone's built-in sounds, or tap **Add from file…** and choose any audio file
(MP3, M4A, OGG, WAV… up to 30 MB). Added files are copied into the app, so the alarm keeps working if you move or delete the original.
They stay in the list for your other alarms. Tap **Play** to preview a sound at alarm volume. If a sound can't be played when the alarm rings,
the default alarm sound plays instead.

**Language:** English or Thai (ไทย). By default the app follows the phone's language. To change it, scroll to
**Language · ภาษา** at the bottom of the main screen.

## Install on your phone

You don't need a computer. GitHub Actions builds the app on every push.

1. On your phone, open this repository on GitHub → **Releases** → **Latest build** → download `AnkiAlarm.apk`.
   (Or: **Actions** → latest run → **Artifacts** → `AnkiAlarm-apk`, which downloads as a zip.)
2. Open the file. Android asks you to allow installs from your browser or file manager. Allow it, then tap **Install**.
3. Updates install over the old version and keep your alarms. All builds are signed with the same key in `keystore/`.

## First-time setup

1. Install [AnkiDroid](https://play.google.com/store/apps/details?id=com.ichi2.anki) and sign in to AnkiWeb in it
   (AnkiDroid → Settings → Sync), so your desktop decks are on the phone.
2. Open Anki Alarm. Any item under **Finish setup** has a button. Tap each one:
   - **Allow access to AnkiDroid**: lets the app read due cards and save answers.
   - **Allow notifications**: the ringing alarm is a notification.
   - **Alarms & reminders** / **full-screen alarms**: only shown if your Android version needs them.
3. Tap **+**, set a time, pick a deck, and tap **Save & test** to try it right away.

### Getting reviews onto your computer

Your answers go into AnkiDroid right away. They reach desktop Anki the usual way: AnkiDroid ↔ AnkiWeb ↔ desktop Anki.
- Keep **Sync after waking up** on (the default), or
- turn on AnkiDroid → Settings → Sync → **Automatic synchronization**, and
- sync desktop Anki when you open it (it does this by default when you're signed in).

## Troubleshooting

- **The alarm is late or doesn't ring.** Some phones (Xiaomi, Huawei, Samsung, OnePlus…) stop background apps.
  Go to Settings → Apps → Anki Alarm → Battery and choose **Unrestricted** / "Don't optimize".
- **Always math, never cards.** Check that AnkiDroid access is allowed. In AnkiDroid, check that Settings → Advanced → **Enable AnkiDroid API** is on.
  Also check that the chosen deck has cards due today.
- **Pictures on cards.** AnkiDroid doesn't let other apps read your card pictures, so add them once: in AnkiDroid open the deck menu →
  **Export** → **Anki deck package (.apkg)** with **Include media** ticked, then in Anki Alarm tap **Card pictures → Add deck file**.
  Exports from desktop Anki work too. Add the file again after adding new pictures. Card sounds aren't played.
- **Silent alarm.** The app uses the alarm volume and your default alarm sound. If the alarm volume is at zero, it raises it to 60%.

## Building locally

Open the folder in Android Studio, or run `./gradlew assembleDebug` with the Android SDK installed (JDK 17).
The APK ends up in `app/build/outputs/apk/debug/`.

## How it talks to AnkiDroid

It uses AnkiDroid's public content provider (`com.ichi2.anki.flashcards`, permission
`com.ichi2.anki.permission.READ_WRITE_DATABASE`):
- `content://com.ichi2.anki.flashcards/schedule`: query with `limit=?,deckID=?` for the next due card, update to answer it
- `content://com.ichi2.anki.flashcards/notes/{noteId}/cards/{ord}`: the card's question and answer HTML
- `content://com.ichi2.anki.flashcards/decks`: the deck list
- the `com.ichi2.anki.DO_SYNC` intent asks AnkiDroid to sync

See `app/src/main/java/app/ankialarm/AnkiDroid.kt`.

## About

Made by Pete. Free for anyone to use, for any exam. Not for resale.
