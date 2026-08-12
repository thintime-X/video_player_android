# video\_player\_android

The Android implementation of [`video_player`][1].

## Usage

This package is [endorsed][2], which means you can simply use `video_player`
normally. This package will be automatically included in your app when you do,
so you do not need to add it to your `pubspec.yaml`.

However, if you `import` this package to use any of its APIs directly, you
should add it to your `pubspec.yaml` as usual.

### Android video decoder mode

This fork lets applications select the decoder mode used by newly created
Android video players:

```dart
AndroidVideoPlayer.setDecoderMode(
  AndroidVideoDecoderMode.softwareOnly,
);
```

`hardwarePreferred` is the default. `softwareOnly` filters the video decoder
list to codecs Android reports as software-only, while audio decoder selection
keeps the Media3 default behavior. The selected mode is process-local and is
not persisted by this plugin. Existing players must be disposed and recreated
after changing the mode.

## Known issues

Using `VideoViewType.platformView` is not currently recommended on Android due to a known [issue][3] affecting platform views on Android.

[1]: https://pub.dev/packages/video_player
[2]: https://flutter.dev/to/endorsed-federated-plugin
[3]: https://github.com/flutter/flutter/issues/164899
