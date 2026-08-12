// Copyright 2013 The Flutter Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.videoplayer.texture;

import android.content.Context;
import android.view.Surface;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;
import androidx.annotation.VisibleForTesting;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import io.flutter.plugins.videoplayer.ExoPlayerEventListener;
import io.flutter.plugins.videoplayer.VideoAsset;
import io.flutter.plugins.videoplayer.VideoPlaybackDiagnosticCollector;
import io.flutter.plugins.videoplayer.VideoPlayer;
import io.flutter.plugins.videoplayer.VideoPlayerCallbacks;
import io.flutter.plugins.videoplayer.VideoPlayerOptions;
import io.flutter.plugins.videoplayer.PlatformVideoDecoderMode;
import io.flutter.plugins.videoplayer.VideoPlayerRenderersFactory;
import io.flutter.view.TextureRegistry.SurfaceProducer;

/**
 * A subclass of {@link VideoPlayer} that adds functionality related to texture view as a way of
 * displaying the video in the app.
 *
 * <p>It manages the lifecycle of the texture and ensures that the video is properly displayed on
 * the texture.
 */
public final class TextureVideoPlayer extends VideoPlayer implements SurfaceProducer.Callback {
  // True when the ExoPlayer instance has a null surface.
  private boolean needsSurface = true;
  private final long playerId;
  @Nullable private final VideoPlaybackDiagnosticCollector diagnosticCollector;

  /**
   * Creates a texture video player.
   *
   * @param context application context.
   * @param events event callbacks.
   * @param surfaceProducer produces a texture to render to.
   * @param asset asset to play.
   * @param options options for playback.
   * @param playerId player id used by diagnostics.
   * @param diagnosticCollector optional diagnostics collector.
   * @return a video player instance.
   */
  // TODO: Migrate to stable API, see https://github.com/flutter/flutter/issues/147039.
  @UnstableApi
  @NonNull
  public static TextureVideoPlayer create(
      @NonNull Context context,
      @NonNull VideoPlayerCallbacks events,
      @NonNull SurfaceProducer surfaceProducer,
      @NonNull VideoAsset asset,
      @NonNull VideoPlayerOptions options,
      @NonNull PlatformVideoDecoderMode decoderMode,
      long playerId,
      @Nullable VideoPlaybackDiagnosticCollector diagnosticCollector) {
    return new TextureVideoPlayer(
        events,
        surfaceProducer,
        asset.getMediaItem(),
        options,
        playerId,
        diagnosticCollector,
        () -> {
          androidx.media3.exoplayer.trackselection.DefaultTrackSelector trackSelector =
              new androidx.media3.exoplayer.trackselection.DefaultTrackSelector(context);
          ExoPlayer.Builder builder =
              new ExoPlayer.Builder(
                      context, new VideoPlayerRenderersFactory(context, decoderMode))
                  .setTrackSelector(trackSelector)
                  .setMediaSourceFactory(asset.getMediaSourceFactory(context));
          return builder.build();
        });
  }

  // TODO: Migrate to stable API, see https://github.com/flutter/flutter/issues/147039.
  @UnstableApi
  @VisibleForTesting
  public TextureVideoPlayer(
      @NonNull VideoPlayerCallbacks events,
      @NonNull SurfaceProducer surfaceProducer,
      @NonNull MediaItem mediaItem,
      @NonNull VideoPlayerOptions options,
      long playerId,
      @Nullable VideoPlaybackDiagnosticCollector diagnosticCollector,
      @NonNull ExoPlayerProvider exoPlayerProvider) {
    super(events, mediaItem, options, surfaceProducer, exoPlayerProvider);
    this.playerId = playerId;
    this.diagnosticCollector = diagnosticCollector;

    surfaceProducer.setCallback(this);

    Surface surface = surfaceProducer.getSurface();
    this.exoPlayer.setVideoSurface(surface);
    needsSurface = surface == null;
  }

  /** 注册当前 Texture Surface，供反馈流程截取视频帧。 */
  public void registerCurrentSurfaceForDiagnostics() {
    Surface surface = currentSurface();
    if (surface != null && diagnosticCollector != null) {
      diagnosticCollector.registerTextureSurface(playerId, surface);
    }
  }

  @NonNull
  @Override
  protected ExoPlayerEventListener createExoPlayerEventListener(
      @NonNull ExoPlayer exoPlayer, @Nullable SurfaceProducer surfaceProducer) {
    if (surfaceProducer == null) {
      throw new IllegalArgumentException(
          "surfaceProducer cannot be null to create an ExoPlayerEventListener for TextureVideoPlayer.");
    }
    boolean surfaceProducerHandlesCropAndRotation = surfaceProducer.handlesCropAndRotation();
    return new TextureExoPlayerEventListener(
        exoPlayer, videoPlayerEvents, surfaceProducerHandlesCropAndRotation);
  }

  @RestrictTo(RestrictTo.Scope.LIBRARY)
  public void onSurfaceAvailable() {
    if (needsSurface) {
      // TextureVideoPlayer must always set a surfaceProducer.
      assert surfaceProducer != null;
      Surface surface = surfaceProducer.getSurface();
      exoPlayer.setVideoSurface(surface);
      needsSurface = false;
      if (surface != null && diagnosticCollector != null) {
        diagnosticCollector.registerTextureSurface(playerId, surface);
      }
    }
  }

  @RestrictTo(RestrictTo.Scope.LIBRARY)
  public void onSurfaceCleanup() {
    Surface surface = currentSurface();
    if (surface != null && diagnosticCollector != null) {
      diagnosticCollector.unregisterTextureSurface(playerId, surface);
    }
    exoPlayer.setVideoSurface(null);
    needsSurface = true;
  }

  public void dispose() {
    Surface surface = currentSurface();
    if (surface != null && diagnosticCollector != null) {
      diagnosticCollector.unregisterTextureSurface(playerId, surface);
    }
    // 先释放播放器，再释放 SurfaceProducer 持有的 Surface。
    super.dispose();

    // TextureVideoPlayer must always set a surfaceProducer.
    assert surfaceProducer != null;
    surfaceProducer.release();
  }

  /** 获取当前 SurfaceProducer 持有的 Surface。 */
  @Nullable
  private Surface currentSurface() {
    // TextureVideoPlayer must always set a surfaceProducer.
    assert surfaceProducer != null;
    return surfaceProducer.getSurface();
  }
}
