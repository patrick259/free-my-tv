# Fire TV to-dos

Open items for the Insignia Fire TV build (branch `wip/fire-home-redirect`). Newest first.

## Inputs and power-on
- [x] **Sound on inputs opened from FreeMyTV.** MediaTek's HDMI session holds the volume at 0 until the player sets one;
      our player now takes audio focus and sets 1.0, as Amazon's does. Confirmed on Vsee.
- [x] **"Return to last input" on power-on.** Fire OS records the input for power-on only when its own player had it.
      FreeMyTV now remembers the input its player had at power-off and reopens it, and holds the Home redirect while
      Amazon resumes one of its own (`PowerOn`). Tested with sleep/wake on Vsee.
- [ ] **Fire TV remote doesn't control the Apple TV** (and opening it doesn't wake it) in FreeMyTV's input player.
      HDMI-CEC commands need `android.permission.HDMI_CEC` (system apps only); Amazon's player and input-switch service
      both refuse us. Options: use the Apple TV remote, or find another exported Amazon entry point.

## Scores page
- [x] **F1 tab redesign:** weekend card (round, circuit map, countdown, sessions with winners and flags), drivers with
      team stripes, flags and gaps, constructors as bars. Driver seats are a 2026 table in `ScoresActivity.SEATS`.

## Hero
- [x] **F1: circuit outline + F1 logo** on F1 red (F1's white track outlines, `Sports.TRACKS`; news photo as fallback).
- [ ] Watch on a game opens the channel in YouTube TV (needs the `watch/<code>` for Fox, ABC, ESPN, CBS, NBC,
      NFL Network from tv.youtube.com/live).
- [ ] Netflix title links get lost at its "Who's watching" screen on a cold start (works when Netflix is open).
      Netflix draws its own UI (no accessibility text), so we can't see the profile screen; a resend-on-first-OK
      would interrupt playback when Netflix was already open. Needs a decision.

## Remote and Home button
- [ ] Remap remote app buttons: DirecTV → YouTube TV, Peacock → Paramount+ (need to press each while watching
      the log; also identify the "TV button" that reloads the current app).
- [ ] Cover the ~5 s Home-button wait with a snapshot of the home screen (accessibility overlay, as Home on Fire
      does), swallowing presses while it's up.
- [ ] Sometimes the first ←/→ after ↑ doesn't register (home screen and Scores page). Works with adb key presses
      (which skip the accessibility key filter), so it needs a real-remote repro: note when it happens.
- [ ] Right after an update, Home on Amazon's home screen does nothing (no window change to react to) and a second
      press opens Amazon's guide. `tools/firetv-install.sh` restarts the service; maybe redirect on service connect.

## Housekeeping
- [ ] Merge `wip/fire-home-redirect` into `main` on the fork and tag a version.
- [ ] Consider moving the project out of Dropbox (it makes "conflicted copy" files of build output).
- [ ] Smart home (Home Assistant: front door lock, Ecobee, Ring) in the drawer or a second screen.
