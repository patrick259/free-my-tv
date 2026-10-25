# Fire TV to-dos

Open items for the Insignia Fire TV build (branch `wip/fire-home-redirect`). Newest first.

## Inputs and power-on
- [x] **Sound on inputs opened from FreeMyTV.** MediaTek's HDMI session holds the volume at 0 until the player sets one;
      our player now takes audio focus and sets 1.0, as Amazon's does. Confirmed on Vsee.
- [x] **"Return to last input" on power-on.** Fire OS records the input for power-on only when its own player had it.
      FreeMyTV now remembers the input its player had at power-off and reopens it, and holds the Home redirect while
      Amazon resumes one of its own (`PowerOn`). Tested with sleep/wake on Vsee.

## Scores page
- [x] **F1 tab redesign:** weekend card (round, circuit map, countdown, sessions with winners and flags), drivers with
      team stripes, flags and gaps, constructors as bars. Driver seats are a 2026 table in `ScoresActivity.SEATS`.

## Hero
- [x] **F1: circuit outline, country flag and F1 logo** on F1 red (F1's white track outlines, `Sports.TRACKS`; news photo as fallback).
- [ ] Watch on a game opens the channel in YouTube TV (needs the `watch/<code>` for Fox, ABC, ESPN, CBS, NBC,
      NFL Network from tv.youtube.com/live).
- [ ] Netflix title links get lost at its "Who's watching" screen on a cold start (works when Netflix is open).
      Netflix draws its own UI (no accessibility text), so we can't see the profile screen; a resend-on-first-OK
      would interrupt playback when Netflix was already open. Needs a decision.

## Remote and Home button
- [x] Remote app buttons: Peacock → Paramount+, DirecTV → YouTube TV (`FireButtons`, from the system log). The
      "TV" button sends Home.
- [ ] Cover the ~5 s Home-button wait with a snapshot of the home screen (accessibility overlay, as Home on Fire
      does), swallowing presses while it's up.
- [x] The first arrow press sometimes didn't register (home screen, Scores): Fire OS switches windows to touch mode,
      and leaving it ate the press when the focused view wasn't focusable in touch mode. Now they all are.
- [x] Home on Amazon's own home screen (after an update, or in Fire TV's settings, which are a page of it) did
      nothing. `HomeWatcher` now sees every Home press in the system log (READ_LOGS, granted by the install script).
- [x] TV Settings opens Fire TV's settings and stays there until Home (`FireSettings`).

## Housekeeping
- [ ] Merge `wip/fire-home-redirect` into `main` on the fork and tag a version.
- [ ] Consider moving the project out of Dropbox (it makes "conflicted copy" files of build output).
- [x] Ring cameras live (Home Assistant, WebRTC): Cameras button and drawer shortcut; ha_cameras picks which.
- [ ] Smart home: front door lock and Ecobee, once they are in Home Assistant.
