# Changelog

## 1.3

- Packs are offered to WhatsApp in a fixed order. WhatsApp keeps its own order of the sticker
  tray (see "Pack order in WhatsApp" in the README).
- `scripts/sign-release.sh` asks for the keystore password once, checks it with keytool and
  then signs, instead of using apksigner's own prompt.

## 1.2

- Import folders of images: pick a folder and each subfolder becomes a pack named after it
  ("01 BAD" becomes "BAD", the number sets the order), with its images in file name order.
- Animated GIFs become animated stickers instead of stills.
- Import several `.wastickers` files at once. Files named "00 Name", "01 Name", ... keep their
  order in the pack list.
- Packs imported from other sticker apps keep that app's order.

## 1.1

- Import from other sticker apps (Sticker Maker and any app that offers packs to WhatsApp):
  pick the packs, and they arrive with names, authors, emojis and animations, fully editable.
- Still stickers that other apps stored as two identical animation frames are recognised and
  kept as stills.
- Merge two packs into one from the pack menu.
- Packs larger than 30 stickers go to WhatsApp as "Name 1", "Name 2" instead of "Name 1/2".

## 1.0

First release.

- Sticker packs stored as plain images; WhatsApp's format is built only when a pack is added,
  shared or exported.
- Add to WhatsApp, WhatsApp Business or both; shows whether each pack is added and when
  WhatsApp has an older version.
- Packs of any size, split into parts of up to 30 stickers in pack order.
- Still and animated stickers in the same pack (still stickers are sent as two identical frames
  in animated parts).
- Animated stickers outside WhatsApp's limits are re-encoded to fit.
- Editor: move, zoom, rotate, square, circle and freehand cut, caption, outline.
- Stickers from WhatsApp and WhatsApp Business chats and built-in packs.
- Import `.wastickers` and shared images; export as a folder of images or `.wastickers`;
  share as `.wastickers`.
- Backup and restore of all packs as a `.zip` with the original images.
