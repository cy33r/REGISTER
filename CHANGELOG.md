# Changelog

## Unreleased

## 1.0.0-beta.1 — 2026-11-27

FIRST REGISTER RELEASE.

- The app is called REGISTER on both phones, with its own icon (a SIM card with signal waves).
- iPhone and Android: every text in Persian, right to left, in the Vazirmatn typeface (SIL Open Font License),
  whatever the phone's language.
- iPhone: it follows the iPhone's light or dark mode
  and uses the iPhone's blue, like the Phone and Messages apps. The call screen is dark grey like the iPhone's own.
- Release files are named REGISTER-Android, REGISTER-iPhone and REGISTER-Client.
- Android phone, Qualcomm: the earpiece stays silent when the screen turns off during a call taken on the iPhone
  (One UI put the call's volume back; REGISTER now mutes it again every 400 ms while the call is bridged).

- Android phone: a call taken on the phone itself turns the screen off when the phone is held to the ear (proximity
  sensor), as the stock dialer does. Not while the speaker, a headset or Bluetooth is on, or the call is on the iPhone.
- Android phone, Qualcomm (Galaxy A73 and others): while a call is bridged to the iPhone, the HAL voice volume is set
  to 0 as well, so the far end is no longer heard from the Android phone's earpiece (One UI ignores the stream mute).
