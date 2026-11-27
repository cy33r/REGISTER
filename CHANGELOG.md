# Changelog

## Unreleased

## 1.0.0 — 2026-11-28

اولین نسخهٔ پایدار REGISTER.

- کلمه‌های انگلیسی در هر دو برنامه با حروف بزرگ (WI-FI، VPN، USB و…).
- آیفون: اعداد فارسی و تاریخ شمسی در پیام‌ها، سابقهٔ تماس و مدت تماس.
- آیفون: توضیح‌ها راست‌چین و هم‌تراز (همهٔ خط‌ها هم‌اندازه).
- همهٔ اصلاحات نسخه‌های آزمایشی: بلندگو، ظاهر مرتب اندروید، حالت روشن و تیره، بخش‌های «پیشرفته» و «درباره»، آپدیت بدون حذف برنامه.

## 1.0.0-beta.2 — 2026-11-27

- آیفون: دکمهٔ بلندگو حالا درست خاموش و روشن می‌شود.
- اندروید: صفحهٔ اصلی مرتب‌تر؛ متن‌ها راست‌چین و هم‌تراز، دکمه‌ها زیر متن، کارت «اتصال آیفون» جمع‌وجور.
- اندروید: حالت روشن و تیره همراه با تنظیم گوشی.
- هر دو: بخش «پیشرفته» برای گزینه‌های فنی، بخش «درباره» با نسخهٔ برنامه و دکمهٔ «بررسی آپدیت».
- متن‌ها کوتاه‌تر، اعداد فارسی، رفع غلط‌های املایی و انگلیسی‌های باقی‌مانده.
- آپدیت‌های بعدی بدون حذف برنامه نصب می‌شوند (با کلید امضای ثابت).

## 1.0.0-beta.1 — 2026-11-27

FIRST REGISTER RELEASE.

- iPhone: the speaker stays on when iOS resets the call's audio category mid-call (it fell back to the earpiece by
  itself after a few seconds).

- The app is called REGISTER on both phones, with its own icon (a SIM card with signal waves).
- iPhone and Android: every text in Persian, right to left, in the Vazirmatn typeface (SIL Open Font License),
  whatever the phone's language, scaled to line up with the system text.
- iPhone: it follows the iPhone's light or dark mode
  and uses the iPhone's blue, like the Phone and Messages apps. The call screen is dark grey like the iPhone's own.
- Release files are named REGISTER-Android, REGISTER-iPhone and REGISTER-Client.
- Android phone, Samsung with a Qualcomm chip (Galaxy A73): during a call taken on the iPhone, the earpiece and the
  microphone are muted with Qualcomm's own device mutes (mixer controls; the iPhone's voice is mixed in after them),
  put back every 150 ms because One UI undoes them when the screen turns off. Before, the far end was heard from the
  Android phone and its microphone stayed in the call.

- Android phone: a call taken on the phone itself turns the screen off when the phone is held to the ear (proximity
  sensor), as the stock dialer does. Not while the speaker, a headset or Bluetooth is on, or the call is on the iPhone.
