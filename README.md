# Origin TV

A PatchWall-style home screen for Android TV, with live Indian TV channels built in through **Joshi TV** and your own
Jio account.

- **Origin TV** is the home screen. It has tabs (Home, Live TV, Movies, Shows, Sports, Kids, Music, News), a large
  picture of what is on now, rows of live channels and films starting soon, your streaming apps as branded tiles,
  and a *Continue watching* row from the apps that share it. Your TV's inputs (HDMI, AV) are one press away, and so
  is the classic launcher.
- **Joshi TV** plays the live channels your Jio plan includes, with a programme guide, posters, catch-up of films
  from the last day and favourites. You sign in with your Jio number and the OTP Jio sends you.

Both apps are free, have no ads and no tracking, and keep your Jio sign-in on the TV.

> **Please read [Before you use it](#before-you-use-it).** Joshi TV signs in to Jio the way the JioTV app does,
> which JioTV's terms do not allow.

## What you need

- An **Android TV or Google TV** running Android 8.0 or newer, with an ARM processor (nearly all TVs and sticks).
- A **Jio number whose plan includes JioTV** (Jio mobile, JioFiber or JioAirFiber). You see the channels your plan
  includes, no more.
- Internet on the TV.
- For the home screen: a Mac or Linux computer on the same Wi-Fi as the TV, once, for the install (Windows: use
  WSL).

## Install

Download from the [latest release](../../releases/latest):

| File | What it is |
|---|---|
| `OriginTV-installer.zip` | Everything: both apps and the installer. Use this for the full setup. |
| `JoshiTV.apk` | The live TV app on its own. |
| `OriginTV.apk` | The home screen app on its own. |

### Everything, with the Origin TV home screen

Most Android TVs only let a new home screen take over through a one-time setup from a computer. The installer
does that for you in one command.

1. **On the TV, turn on debugging.** Settings > Device Preferences > About (Google TV: Settings > System > About):
   select *Build* (Google TV: *Android TV OS build*) seven times until it says you are a developer. Then open
   *Developer options* in the same menu and turn on **USB debugging** (some TVs also have *ADB debugging* or
   *Network debugging*; turn that on too).
2. **Find the TV's IP address:** Settings > Network & Internet > your Wi-Fi network, for example `192.168.1.50`.
3. **On the computer:** unzip `OriginTV-installer.zip`, open a terminal in the `OriginTV-installer` folder, and run:

   ```bash
   TV_HOST=192.168.1.50 ./install.sh
   ```

   Use your TV's address. The first run sets up a small helper for talking to the TV (Python 3 is needed).
4. **On the TV,** when it asks *Allow USB debugging?*, tick *Always allow from this computer* and press OK.
5. When the script says *Done*, open **Joshi TV** on the TV and sign in. Then press Home: the live channels and
   films fill the home screen within a few minutes.

You can switch USB debugging off again afterwards.

What the installer changes: it installs both apps and makes Origin TV the home screen. On TVs whose own launcher
would keep taking Home back, it switches that launcher off, but only after checking that Origin TV opens. To put
everything back, run `TV_HOST=192.168.1.50 scripts/uninstall.sh` from a clone of this repository (add `JOSHI=1` to
remove Joshi TV too).

Options: `ONLY=joshitv` installs Joshi TV alone; `PHONE_REMOTE=1` also turns on the [phone remote](#phone-remote).

### Joshi TV only, with no computer

1. Install **Downloader** (by AFTVnews) from the Play Store on the TV.
2. Let it install apps: Settings > Device Preferences (Google TV: Apps) > Security & restrictions > Unknown
   sources > Downloader.
3. In Downloader, open this address and install:
   `https://github.com/rjtheorigin/origin-tv/releases/latest/download/JoshiTV.apk`
4. Open Joshi TV and sign in.

`OriginTV.apk` installs the same way. Some TVs then offer it when you press Home. Most, Xiaomi and Google TV
included, need the installer above to make it the home screen.

## Signing in

Open Joshi TV, enter your 10-digit Jio number and then the OTP that Jio sends to that phone. The sign-in is kept in
Joshi TV's private storage on the TV and goes nowhere except to Jio. Jio limits how many devices can be signed in at
once: if you sign in somewhere else and Joshi TV shows the sign-in screen again, just sign in again.

## Using it

- **Home screen:** move with the arrows; resting on a tab opens it. OK on a live card plays that channel in Joshi
  TV, OK on an app opens it, and Home always brings you back. *All apps* (end of the app row) shows the full app
  list; Back returns.
- **Continue watching** shows what you were watching in apps that share it with Android's *Watch Next* list:
  YouTube, JioHotstar, SonyLIV, ZEE5 and Xiaomi TV+ do once you have watched something. Netflix and Prime Video
  keep theirs to themselves, so they appear as tiles that open the app.
- **Live channels on the home screen** come from a copy Joshi TV makes into Android's TV listings a few minutes
  after you sign in, refreshed twice a day. It holds your favourites, the channels you watch, and the channels of
  your two main languages, which follow what you watch (Hindi and English until then). The same channels show up
  as a source in the TV's *Live Channels* app.
- **Classic launcher:** the gear (top right) > *Switch to classic Home*. To come back: gear > Launcher settings >
  *Home style* (the first row).
- **Xiaomi TVs:** the MI button on the remote opens Joshi TV's guide.

### Phone remote

Installed with `PHONE_REMOTE=1`, the home screen also serves a remote for your phone on your home Wi-Fi: gear >
*Phone remote* shows the address and a pairing code. Its arrows and OK work in every app.

## Privacy

- No analytics, no ads, no tracking in either app.
- Joshi TV talks only to Jio (sign-in, channel list, guide, streams and DRM licences) and loads logos and posters
  from Jio's image server. Its stream server listens on `127.0.0.1` only, so nothing else on your network can reach
  it, and it writes no log.
- The home screen loads posters from Jio's image server and from the posters other apps share with Android. The
  phone remote takes commands only from phones you have paired.

## Before you use it

Joshi TV uses [JioTV Go](https://github.com/JioTV-Go/jiotv_go) to sign in to Jio the way the JioTV app does.
**JioTV's terms of use do not allow this.** Jio can block it at any time and could suspend the JioTV sign-in of an
account that uses it. Use it at your own risk.

It does not unlock anything. You see only the channels your plan includes (others answer *No eligible plans
found*), protected channels play with Jio's own DRM licences on your account, and nothing is recorded or
downloaded. Protected channels need a TV with Widevine DRM, which almost all Android TVs have.

Origin TV and Joshi TV are independent and not affiliated with or endorsed by Jio Platforms, Reliance or any channel
or streaming service.

## Building from source

Everything builds in Docker, so the computer needs no JDK or Android SDK.

```bash
server/build-jiotv-go.sh        # the stream server, from JioTV Go's source (once)
scripts/make-release-key.sh     # optional: a release key of your own (back up signing/ afterwards)
scripts/package-release.sh      # both APKs and the installer zip, in dist/
```

Or one app at a time: `tools/docker-build.sh home` or `tools/docker-build.sh live`, with the APK in
`<app>/app/build/outputs/apk/release/`. Both apps must be signed with the same key: Joshi TV lets only an app signed
like it start its stream server.

| Folder | What is in it |
|---|---|
| `home/` | Origin TV, the home screen (plain Java). The PatchWall-style screen is `PatchHome.java`, `PatchData.java` and `PatchImages.java`; the classic launcher it is built on stays underneath. |
| `live/` | Joshi TV (Java, Media3 player). Sign-in, guide, player, and `LiveSync.java`, the copy into Android's TV listings. |
| `server/` | How the JioTV Go stream server is built and bundled. |
| `tools/` | Docker build, the Python ADB client the installer uses, and `brand/MakeBrand.java`, which draws the icons and banners. |
| `scripts/` | Release packaging, the release key, uninstall. |

## Credits and licence

MIT, see [LICENSE](LICENSE). The home screen and the tools are built on
[CleanYourTV](https://github.com/Gathin23/cleanyourtv) by Gathin (MIT). The stream server is
[JioTV Go](https://github.com/JioTV-Go/jiotv_go) by Mohammed Rabil and contributors (CC BY 4.0). Full list in
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
