// Copyright 2013 The Flutter Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.videoplayer;

import static org.junit.Assert.assertEquals;

import androidx.media3.common.MimeTypes;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/** 验证软解模式不会把硬件视频解码器保留在候选列表中。 */
@RunWith(RobolectricTestRunner.class)
public final class VideoPlayerRenderersFactoryTest {
  /** 只保留系统标记为 softwareOnly 的解码器，并保持候选顺序。 */
  @Test
  public void filterSoftwareDecodersOnlyKeepsSoftwareCodecs() {
    final MediaCodecInfo hardwareCodec = codec("c2.vendor.avc.decoder", false);
    final MediaCodecInfo softwareCodec = codec("c2.android.avc.decoder", true);

    final List<MediaCodecInfo> result =
        VideoPlayerRenderersFactory.filterSoftwareDecoders(
            Arrays.asList(hardwareCodec, softwareCodec));

    assertEquals(1, result.size());
    assertEquals(softwareCodec, result.get(0));
  }

  /** 创建测试使用的 AVC 解码器信息。 */
  private static MediaCodecInfo codec(String name, boolean softwareOnly) {
    return MediaCodecInfo.newInstance(
        name,
        MimeTypes.VIDEO_H264,
        MimeTypes.VIDEO_H264,
        null,
        !softwareOnly,
        softwareOnly,
        !softwareOnly,
        false,
        false);
  }
}
