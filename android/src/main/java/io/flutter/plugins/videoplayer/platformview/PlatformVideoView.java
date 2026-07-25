// Copyright 2013 The Flutter Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.videoplayer.platformview;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Build;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import io.flutter.plugin.platform.PlatformView;
import io.flutter.plugins.videoplayer.VideoPlaybackDiagnosticCollector;

/**
 * A class used to create a native video view that can be embedded in a Flutter app. It wraps an
 * {@link ExoPlayer} instance and displays its video content.
 */
@SuppressLint("SyntheticAccessor")
public final class PlatformVideoView implements PlatformView {
  @NonNull private final SurfaceView surfaceView;
  private final long playerId;
  @Nullable private final VideoPlaybackDiagnosticCollector diagnosticCollector;

  /**
   * Constructs a new PlatformVideoView.
   *
   * @param context The context in which the view is running.
   * @param exoPlayer The ExoPlayer instance used to play the video.
   */
  @OptIn(markerClass = UnstableApi.class)
  public PlatformVideoView(@NonNull Context context, @NonNull ExoPlayer exoPlayer) {
    this(context, exoPlayer, -1L, null);
  }

  /** 创建可关联播放器诊断会话的原生视频视图。 */
  @OptIn(markerClass = UnstableApi.class)
  public PlatformVideoView(
      @NonNull Context context,
      @NonNull ExoPlayer exoPlayer,
      long playerId,
      @Nullable VideoPlaybackDiagnosticCollector diagnosticCollector) {
    this.playerId = playerId;
    this.diagnosticCollector = diagnosticCollector;
    surfaceView = new VideoSurfaceView(context, exoPlayer);
    if (diagnosticCollector != null) {
      diagnosticCollector.registerSurfaceView(playerId, surfaceView);
    }

    setupSurfaceWithCallback(exoPlayer);

    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.N_MR1) {
      // Avoid blank space instead of a video on Android versions below 8 by adjusting video's
      // z-layer within the Android view hierarchy:
      surfaceView.setZOrderMediaOverlay(true);
    }
  }

  private void setupSurfaceWithCallback(@NonNull ExoPlayer exoPlayer) {
    surfaceView
        .getHolder()
        .addCallback(
            new SurfaceHolder.Callback() {
              @Override
              public void surfaceCreated(@NonNull SurfaceHolder holder) {
                recordSurfaceEvent("surfaceCreated", 0, surfaceView.getWidth(), surfaceView.getHeight());
                bindPlayerToSurface(exoPlayer, holder.getSurface());
                forceFirstFrameForAndroid9(exoPlayer);
              }

              @Override
              public void surfaceChanged(
                  @NonNull SurfaceHolder holder, int format, int width, int height) {
                recordSurfaceEvent("surfaceChanged", format, width, height);
              }

              @Override
              public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
                recordSurfaceEvent("surfaceDestroyed", 0, surfaceView.getWidth(), surfaceView.getHeight());
                // Use clearVideoSurface to ensure we only unbind if this surface is currently
                // active.
                exoPlayer.clearVideoSurface(holder.getSurface());
              }
            });
  }

  /** 将 Surface 生命周期事件写入对应播放器诊断会话。 */
  private void recordSurfaceEvent(@NonNull String event, int format, int width, int height) {
    if (diagnosticCollector != null) {
      diagnosticCollector.recordSurfaceEvent(playerId, event, format, width, height);
    }
  }

  /** Binds the ExoPlayer to the provided surface. */
  static void bindPlayerToSurface(@NonNull ExoPlayer exoPlayer, @NonNull Surface surface) {
    if (surface.isValid()) {
      exoPlayer.setVideoSurface(surface);
    }
  }

  /**
   * Workaround for a rendering bug on Android 9 (API 28) where the decoder does not flush its
   * output buffer when a new surface is attached while the player is paused.
   */
  static void forceFirstFrameForAndroid9(@NonNull ExoPlayer exoPlayer) {
    if (Build.VERSION.SDK_INT == Build.VERSION_CODES.P && !exoPlayer.getPlayWhenReady()) {
      long position = exoPlayer.getCurrentPosition();
      exoPlayer.seekTo(position == 0 ? 1 : position);
    }
  }

  /**
   * A custom SurfaceView that re-attaches the player surface when the view becomes visible again,
   * such as after returning from a full-screen route transition.
   */
  private static class VideoSurfaceView extends SurfaceView {
    private final ExoPlayer exoPlayer;

    VideoSurfaceView(Context context, ExoPlayer exoPlayer) {
      super(context);
      this.exoPlayer = exoPlayer;
    }

    @Override
    protected void onVisibilityChanged(@NonNull View changedView, int visibility) {
      super.onVisibilityChanged(changedView, visibility);
      // When the view becomes visible again, re-attach the current surface.
      if (visibility == View.VISIBLE && isShown()) {
        bindPlayerToSurface(exoPlayer, getHolder().getSurface());
      }
    }
  }

  /**
   * Returns the view associated with this PlatformView.
   *
   * @return The SurfaceView used to display the video.
   */
  @NonNull
  @Override
  public View getView() {
    return surfaceView;
  }

  /** Disposes of the resources used by this PlatformView. */
  @Override
  public void dispose() {
    if (diagnosticCollector != null) {
      diagnosticCollector.unregisterSurfaceView(playerId, surfaceView);
    }
    Surface surface = surfaceView.getHolder().getSurface();
    if (surface != null) {
      surface.release();
    }
  }
}
