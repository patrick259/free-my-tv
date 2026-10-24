# Fire TV to-dos

Open items for the Insignia Fire TV build (branch `wip/fire-home-redirect`). Newest first.

## Inputs and power-on
- [ ] **No sound on the Apple TV input when opened from FreeMyTV.** Picture works, sound doesn't; from Amazon's
      own inputs menu both work. FreeMyTV shows the input in its own player (`InputPlayerActivity`, a `TvView`),
      because Amazon's player needs a permission only Amazon apps get. Likely the audio routing for the HDMI port
      isn't switched (Amazon's player does that, plus CEC). Look at how the TvView session sets audio, and at
      Amazon's `NAVIGATE_TO_INPUTS` launcher action as a way to switch through Amazon instead.
- [ ] **"Return to last input" on power-on no longer works.** Before: turn the TV on while Apple TV (HDMI 1) was
      last used, and it switched to HDMI 1. Now FreeMyTV comes up every time. Probably the Home redirect firing on
      Amazon's launcher at boot, before the TV switches input (and/or our input player not counting as "last input").
      Fix: don't redirect at power-on when the TV is about to resume an input; remember the last input ourselves
      and switch back to it.
- [ ] **Fire TV remote doesn't control the Apple TV** in FreeMyTV's input player (CEC routing doesn't follow).
      Same root cause as the sound issue, so the same fix may solve it.

## Scores page
- [ ] **Make the F1 tab look better.** It's plain lists today: add team colors and driver photos or numbers,
      a car/team stripe per row, the circuit map, the next session as a hero with a countdown, and the last
      race's podium.

## Hero
- [ ] **F1: a track image for the current race plus the F1 logo**, instead of the news photo. Options: an aerial
      or circuit-map image per Grand Prix (ESPN circuit data, Wikimedia Commons circuit maps, or a bundled set of
      the season's circuits), with the F1 logo on top.
- [ ] Watch on a game opens the channel in YouTube TV (needs the `watch/<code>` for Fox, ABC, ESPN, CBS, NBC,
      NFL Network from tv.youtube.com/live).
- [ ] Netflix title links get lost at its "Who's watching" screen on a cold start (works when Netflix is open).

## Remote and Home button
- [ ] Remap remote app buttons: DirecTV → YouTube TV, Peacock → Paramount+ (need to press each while watching
      the log; also identify the "TV button" that reloads the current app).
- [ ] Cover the ~5 s Home-button wait with a snapshot of the home screen (accessibility overlay, as Home on Fire
      does), swallowing presses while it's up.
- [ ] Sometimes the first ←/→ after ↑ doesn't register (home screen and Scores page).

## Housekeeping
- [ ] Merge `wip/fire-home-redirect` into `main` on the fork and tag a version.
- [ ] Consider moving the project out of Dropbox (it makes "conflicted copy" files of build output).
- [ ] Smart home (Home Assistant: front door lock, Ecobee, Ring) in the drawer or a second screen.
