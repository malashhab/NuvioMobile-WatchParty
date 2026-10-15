<div align="center">

  <img src=".github/assets/nuvio-wp-logo.svg" alt="Nuvio WP logo: two screens with friends watching together" width="160" />

  <h1>Nuvio WP</h1>

  <p>
    An unofficial community fork of <a href="https://github.com/NuvioMedia/NuvioMobile">Nuvio Mobile</a> that adds <b>Watch Party</b>:
    <br />
    watch together with friends, perfectly in sync, on Android and iPhone.
  </p>

  [Releases](https://github.com/malashhab/NuvioMobile-WatchParty/releases) · [Official Nuvio](https://nuvio.tv) · [Support Nuvio](https://nuvio.tv/support)

</div>

> **Unofficial.** Nuvio WP is not made or supported by the Nuvio team. Please report problems with this fork
> [here](https://github.com/malashhab/NuvioMobile-WatchParty/issues), not to Nuvio.

## What's different from Nuvio

Nuvio WP is the regular Nuvio app with Watch Party added on top. On Android it also includes the extras from
[Nuvio Reshaped](https://github.com/DavidVamaiotu/NuvioMobile-Reshaped): subtitle AutoSync (including syncing to the
audio), custom subtitle fonts, seek previews, a seek buffer, volume boost and Live TV. They're in their own Settings
section and off until you turn them on.

### Watch Party

- **Synced playback.** Play, pause, seek and episode changes are mirrored for everyone in the room, with
  network-delay compensation and gentle speed correction to keep phones within a fraction of a second.
- **Everyone starts together.** After a seek or when someone's video is still loading, the room waits until
  everyone is ready, then plays at the same moment. Nobody holds the room up for more than 30 seconds.
- **Rooms and invites.** Create a room from the player and share the invite link. Friends join with one tap or
  by pasting the invite.
- **Pass the remote.** The host controls playback, can hand control to a member who asks, and can take it back
  at any time.
- **Same source, automatically.** When the host picks a stream, each member's app looks for the same release
  among their own add-ons.
- **Chat.** A see-through chat panel and message pop-ups, without leaving the video.
- **Android and iPhone** in the same room.

### Privacy

- Rooms share only *what* is playing and *where* in it: never stream links, add-on addresses or debrid keys.
  Every member plays from their own add-ons.
- Chat is relayed live and never stored. People who join later don't see earlier messages.
- Rooms run on a server the host chooses (see below), not on a shared service run by this project.

## Get it

Downloads are **coming soon** on the [Releases](https://github.com/malashhab/NuvioMobile-WatchParty/releases) page.

- **Android:** install the APK. It installs as **Nuvio WP**, next to official Nuvio, without replacing it.
- **iPhone:** install the IPA with AltStore or SideStore. It also installs as a separate **Nuvio WP** app.

## Using Watch Party

1. Open a movie or episode and start playing it.
2. Tap the **Watch Party** button in the player and choose **Create room**.
3. Tap **Share invite** and send it to your friends.
4. Friends open the invite (or paste it into Watch Party → **Join**). Their app opens the same title and finds
   the same source.
5. Press play. Everyone starts together.

Everyone needs their own add-ons that can play the title. For the smoothest experience, use the same
debrid service or add-ons as the host.

## Setting up your own Watch Party server

Watch Party uses a free [Supabase](https://supabase.com) project to connect the phones in a room. Only the
person **creating** rooms needs one; guests use the host's server automatically through the invite.

1. Create a free account and a new project at [supabase.com](https://supabase.com).
2. In your project, open **Connect** (or **Project Settings → Data API**) and copy the **Project URL**
   (`https://xxxx.supabase.co`).
3. Open **Project Settings → API Keys** and copy the **publishable key** (starts with `sb_publishable_`).
4. In Nuvio WP, open **Settings → Watch Party**, paste both, tap **Save**, then **Test connection**.

No database tables or other setup are needed: rooms use Supabase Realtime only, and nothing is stored.

> **Never** use the secret key (`sb_secret_…` or `service_role`). The app refuses it. The publishable key is
> designed to be public, which is why invites can include it. Only join servers from people you trust.

**Keeping the server awake:** free Supabase projects pause after about a week without database activity.
You can restore a paused project from the Supabase dashboard, or keep it awake automatically (see
[`.github/workflows/supabase-keepalive.yml`](.github/workflows/supabase-keepalive.yml)).

## Build from source

The main branch of this fork is `feature/watch-party`.

```bash
git clone https://github.com/malashhab/NuvioMobile-WatchParty.git
cd NuvioMobile-WatchParty
git checkout feature/watch-party
```

### Android

Requires Android Studio and the Android SDK.

```bash
./gradlew :androidApp:assembleFullDebug
```

To build with a built-in Watch Party server, add these lines to `local.properties` (never commit this file):

```properties
WATCH_PARTY_SUPABASE_URL=https://xxxx.supabase.co
WATCH_PARTY_SUPABASE_KEY=sb_publishable_your-key
```

Without them, users set up their own server in Settings → Watch Party.

### iOS

Requires macOS and Xcode. See the upstream [Nuvio README](https://github.com/NuvioMedia/NuvioMobile#build-from-source)
for the build command. The app is built with Kotlin Multiplatform and Compose Multiplatform.

## Credits

Nuvio WP is built on [Nuvio](https://github.com/NuvioMedia/NuvioMobile) by the NuvioMedia team. All of the app
except Watch Party is their work. If you enjoy it, please [support Nuvio](https://nuvio.tv/support).

The Android extras come from [Nuvio Reshaped](https://github.com/DavidVamaiotu/NuvioMobile-Reshaped) by
[DavidVamaiotu](https://github.com/DavidVamaiotu), also under the GPL-3.0. Audio sync uses [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (Apache-2.0).

## License

[GNU General Public License v3.0](./LICENSE), the same as Nuvio.
