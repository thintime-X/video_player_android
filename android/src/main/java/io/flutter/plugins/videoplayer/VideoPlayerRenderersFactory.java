// Copyright 2013 The Flutter Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.videoplayer;

import android.content.Context;
import android.os.Handler;
import androidx.annotation.NonNull;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.video.VideoRendererEventListener;
import java.util.ArrayList;
import java.util.List;

/** 根据创建参数为视频轨道选择硬件或软件解码器的 RenderersFactory。 */
@UnstableApi
public final class VideoPlayerRenderersFactory extends DefaultRenderersFactory {
  private final PlatformVideoDecoderMode decoderMode;

  /** 创建指定视频解码模式的 RenderersFactory。 */
  public VideoPlayerRenderersFactory(
      @NonNull Context context, @NonNull PlatformVideoDecoderMode decoderMode) {
    super(context);
    this.decoderMode = decoderMode;
  }

  /** 只限制视频轨道解码器，音频轨道继续使用 Media3 默认选择逻辑。 */
  @Override
  protected void buildVideoRenderers(
      Context context,
      int extensionRendererMode,
      MediaCodecSelector mediaCodecSelector,
      boolean enableDecoderFallback,
      Handler eventHandler,
      VideoRendererEventListener eventListener,
      long allowedVideoJoiningTimeMs,
      ArrayList<Renderer> out) {
    final MediaCodecSelector videoSelector =
        decoderMode == PlatformVideoDecoderMode.SOFTWARE_ONLY
            ? VideoPlayerRenderersFactory::softwareDecoderInfos
            : mediaCodecSelector;
    final boolean videoDecoderFallback =
        decoderMode == PlatformVideoDecoderMode.SOFTWARE_ONLY || enableDecoderFallback;
    super.buildVideoRenderers(
        context,
        extensionRendererMode,
        videoSelector,
        videoDecoderFallback,
        eventHandler,
        eventListener,
        allowedVideoJoiningTimeMs,
        out);
  }

  /** 过滤出系统明确标记为纯软件实现的视频解码器。 */
  @NonNull
  private static List<MediaCodecInfo> softwareDecoderInfos(
      String mimeType, boolean requiresSecureDecoder, boolean requiresTunnelingDecoder)
      throws androidx.media3.exoplayer.mediacodec.MediaCodecUtil.DecoderQueryException {
    final List<MediaCodecInfo> candidates =
        MediaCodecSelector.DEFAULT.getDecoderInfos(
            mimeType, requiresSecureDecoder, requiresTunnelingDecoder);
    return filterSoftwareDecoders(candidates);
  }

  /** 从候选列表中保留系统明确标记为纯软件实现的解码器。 */
  @NonNull
  static List<MediaCodecInfo> filterSoftwareDecoders(
      @NonNull List<MediaCodecInfo> candidates) {
    final List<MediaCodecInfo> softwareDecoders = new ArrayList<>();
    for (MediaCodecInfo candidate : candidates) {
      if (candidate.softwareOnly) {
        softwareDecoders.add(candidate);
      }
    }
    return softwareDecoders;
  }
}
