// Copyright 2013 The Flutter Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.videoplayer;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.graphics.Bitmap;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.PixelCopy;
import android.view.SurfaceView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaLibraryInfo;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DecoderCounters;
import androidx.media3.exoplayer.DecoderReuseEvaluation;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/** 采集 Android 播放器诊断数据，并按需截取 PlatformView 视频画面。 */
@SuppressLint("SyntheticAccessor")
@OptIn(markerClass = UnstableApi.class)
public final class VideoPlaybackDiagnosticCollector implements MethodChannel.MethodCallHandler {
  /** Flutter 侧调用诊断能力使用的 MethodChannel。 */
  public static final String CHANNEL_NAME =
      "plugins.flutter.dev/video_player_android/diagnostics";

  private static final String PLUGIN_VERSION = "2.9.5+issue184241+diagnostics.1";
  private static final int MAX_RECENT_EVENTS = 120;
  private static final long RECENT_EVENT_WINDOW_MS = 30_000L;

  @NonNull private final Context context;
  @NonNull private final MethodChannel channel;
  @NonNull private final Handler mainHandler = new Handler(Looper.getMainLooper());
  @NonNull private final ExecutorService fileExecutor = Executors.newSingleThreadExecutor();
  @NonNull private final Map<Long, PlayerSession> sessions = new LinkedHashMap<>();
  @NonNull private final File captureDirectory;

  /** 创建诊断采集器并注册平台通道。 */
  public VideoPlaybackDiagnosticCollector(
      @NonNull Context applicationContext, @NonNull BinaryMessenger messenger) {
    Context resolvedContext = applicationContext.getApplicationContext();
    context = resolvedContext == null ? applicationContext : resolvedContext;
    channel = new MethodChannel(messenger, CHANNEL_NAME);
    channel.setMethodCallHandler(this);
    captureDirectory = new File(context.getCacheDir(), "video_feedback_diagnostics");
  }

  /** 注册播放器并开始采集 ExoPlayer 运行事件。 */
  public synchronized void registerPlayer(
      long playerId, @NonNull ExoPlayer exoPlayer, @NonNull String viewType) {
    unregisterPlayer(playerId);
    PlayerSession session = new PlayerSession(playerId, exoPlayer, viewType);
    sessions.put(playerId, session);
    exoPlayer.addAnalyticsListener(session);
    session.recordEvent("playerRegistered", null);
  }

  /** 解除播放器诊断监听并释放持有的视图引用。 */
  public synchronized void unregisterPlayer(long playerId) {
    PlayerSession session = sessions.remove(playerId);
    if (session == null) {
      return;
    }
    session.exoPlayer.removeAnalyticsListener(session);
    session.surfaceView = null;
  }

  /** 关联 PlatformView 的视频 SurfaceView，用于截图和尺寸诊断。 */
  public synchronized void registerSurfaceView(
      long playerId, @NonNull SurfaceView surfaceView) {
    PlayerSession session = sessions.get(playerId);
    if (session == null) {
      return;
    }
    session.surfaceView = surfaceView;
    session.recordSurfaceEvent("surfaceViewRegistered", 0, surfaceView.getWidth(), surfaceView.getHeight());
  }

  /** 仅在引用仍指向当前视图时解除 SurfaceView。 */
  public synchronized void unregisterSurfaceView(
      long playerId, @NonNull SurfaceView surfaceView) {
    PlayerSession session = sessions.get(playerId);
    if (session == null || session.surfaceView != surfaceView) {
      return;
    }
    session.recordSurfaceEvent(
        "surfaceViewUnregistered", 0, surfaceView.getWidth(), surfaceView.getHeight());
    session.surfaceView = null;
  }

  /** 记录 PlatformView 的 Surface 生命周期事件。 */
  public synchronized void recordSurfaceEvent(
      long playerId, @NonNull String event, int format, int width, int height) {
    PlayerSession session = sessions.get(playerId);
    if (session != null) {
      session.recordSurfaceEvent(event, format, width, height);
    }
  }

  /** 停止通道和采集任务，不删除仍可能由反馈页使用的临时图片。 */
  public synchronized void dispose() {
    channel.setMethodCallHandler(null);
    for (PlayerSession session : sessions.values()) {
      session.exoPlayer.removeAnalyticsListener(session);
      session.surfaceView = null;
    }
    sessions.clear();
    fileExecutor.shutdown();
  }

  /** 分发 Flutter 侧诊断请求。 */
  @Override
  public void onMethodCall(@NonNull MethodCall call, @NonNull MethodChannel.Result result) {
    switch (call.method) {
      case "getSnapshot":
        handleGetSnapshot(call, result);
        break;
      case "captureVideoFrame":
        handleCaptureVideoFrame(call, result);
        break;
      case "deleteCaptureFiles":
        handleDeleteCaptureFiles(call, result);
        break;
      default:
        result.notImplemented();
        break;
    }
  }

  /** 返回指定播放器当前诊断快照。 */
  private void handleGetSnapshot(
      @NonNull MethodCall call, @NonNull MethodChannel.Result result) {
    Long playerId = readPlayerId(call);
    if (playerId == null) {
      result.error("invalid_player_id", "缺少有效的 playerId", null);
      return;
    }
    PlayerSession session;
    synchronized (this) {
      session = sessions.get(playerId);
    }
    if (session == null) {
      result.error("player_not_found", "没有找到对应的播放器诊断会话", null);
      return;
    }
    result.success(session.createSnapshot());
  }

  /** 使用 PixelCopy 截取指定播放器的原生视频 SurfaceView。 */
  private void handleCaptureVideoFrame(
      @NonNull MethodCall call, @NonNull MethodChannel.Result result) {
    Long playerId = readPlayerId(call);
    if (playerId == null) {
      result.success(captureResult(false, null, "invalid_player_id", -1));
      return;
    }
    SurfaceView surfaceView;
    synchronized (this) {
      PlayerSession session = sessions.get(playerId);
      surfaceView = session == null ? null : session.surfaceView;
    }
    if (surfaceView == null) {
      result.success(captureResult(false, null, "surface_view_unavailable", -1));
      return;
    }
    int width = surfaceView.getWidth();
    int height = surfaceView.getHeight();
    if (width <= 0 || height <= 0 || !surfaceView.getHolder().getSurface().isValid()) {
      result.success(captureResult(false, null, "surface_not_ready", -1));
      return;
    }

    final Bitmap bitmap;
    try {
      bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    } catch (RuntimeException | OutOfMemoryError error) {
      result.success(captureResult(false, null, "bitmap_create_failed", -1));
      return;
    }

    try {
      PixelCopy.request(
          surfaceView,
          bitmap,
          copyResult -> {
            if (copyResult != PixelCopy.SUCCESS) {
              bitmap.recycle();
              result.success(captureResult(false, null, "pixel_copy_failed", copyResult));
              return;
            }
            writeCaptureFile(playerId, bitmap, copyResult, result);
          },
          mainHandler);
    } catch (RuntimeException error) {
      bitmap.recycle();
      result.success(captureResult(false, null, "pixel_copy_request_failed", -1));
    }
  }

  /** 在后台线程写入无损 PNG，并回到主线程返回结果。 */
  private void writeCaptureFile(
      long playerId,
      @NonNull Bitmap bitmap,
      int pixelCopyResult,
      @NonNull MethodChannel.Result result) {
    try {
      fileExecutor.execute(
          () -> {
            String path = null;
            String errorCode = null;
            try {
              if (!captureDirectory.exists() && !captureDirectory.mkdirs()) {
                throw new IOException("无法创建诊断截图目录");
              }
              File file =
                  new File(
                      captureDirectory,
                      String.format(
                          Locale.US,
                          "video_%d_%s.png",
                          playerId,
                          UUID.randomUUID().toString().replace("-", "")));
              try (FileOutputStream output = new FileOutputStream(file)) {
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                  throw new IOException("诊断截图编码失败");
                }
              }
              path = file.getAbsolutePath();
            } catch (IOException | RuntimeException error) {
              errorCode = "capture_file_write_failed";
            } finally {
              bitmap.recycle();
            }
            final String finalPath = path;
            final String finalErrorCode = errorCode;
            mainHandler.post(
                () ->
                    result.success(
                        captureResult(
                            finalErrorCode == null,
                            finalPath,
                            finalErrorCode,
                            pixelCopyResult)));
          });
    } catch (RejectedExecutionException error) {
      bitmap.recycle();
      result.success(captureResult(false, null, "collector_disposed", pixelCopyResult));
    }
  }

  /** 删除反馈流程不再需要的诊断截图，仅允许处理采集器自己的缓存目录。 */
  private void handleDeleteCaptureFiles(
      @NonNull MethodCall call, @NonNull MethodChannel.Result result) {
    List<?> paths = call.argument("paths");
    if (paths == null || paths.isEmpty()) {
      result.success(0);
      return;
    }
    int deletedCount = 0;
    try {
      String allowedDirectory = captureDirectory.getCanonicalPath() + File.separator;
      for (Object value : paths) {
        if (!(value instanceof String)) {
          continue;
        }
        File file = new File((String) value);
        String canonicalPath = file.getCanonicalPath();
        if (canonicalPath.startsWith(allowedDirectory) && file.isFile() && file.delete()) {
          deletedCount++;
        }
      }
    } catch (IOException ignored) {
      // 返回已成功删除的数量，单个路径失败不影响其他文件。
    }
    result.success(deletedCount);
  }

  /** 从平台调用参数读取播放器 ID。 */
  @Nullable
  private static Long readPlayerId(@NonNull MethodCall call) {
    Number value = call.argument("playerId");
    return value == null ? null : value.longValue();
  }

  /** 构建截图结果，避免截图问题以异常形式干扰反馈流程。 */
  @NonNull
  private static Map<String, Object> captureResult(
      boolean success, @Nullable String path, @Nullable String errorCode, int pixelCopyResult) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("success", success);
    data.put("path", path);
    data.put("errorCode", errorCode);
    data.put("pixelCopyResult", pixelCopyResult);
    return data;
  }

  /** 单个播放器的诊断会话和最近事件缓冲区。 */
  private final class PlayerSession implements AnalyticsListener {
    private final long playerId;
    @NonNull private final ExoPlayer exoPlayer;
    @NonNull private final String viewType;
    private final long registeredElapsedRealtimeMs = SystemClock.elapsedRealtime();
    @NonNull private final ArrayDeque<Map<String, Object>> recentEvents = new ArrayDeque<>();

    @Nullable private SurfaceView surfaceView;
    @Nullable private String decoderName;
    @Nullable private Format videoFormat;
    @Nullable private DecoderCounters decoderCounters;
    @Nullable private Map<String, Object> lastPlaybackError;
    @Nullable private Map<String, Object> lastCodecError;
    private int droppedVideoFrames;
    private long firstFrameElapsedRealtimeMs = -1L;

    /** 创建播放器诊断会话。 */
    private PlayerSession(long playerId, @NonNull ExoPlayer exoPlayer, @NonNull String viewType) {
      this.playerId = playerId;
      this.exoPlayer = exoPlayer;
      this.viewType = viewType;
    }

    /** 生成可由 StandardMessageCodec 传递的结构化诊断数据。 */
    @NonNull
    private synchronized Map<String, Object> createSnapshot() {
      Map<String, Object> snapshot = new LinkedHashMap<>();
      snapshot.put("schemaVersion", 1);
      snapshot.put("capturedAtMs", System.currentTimeMillis());
      snapshot.put("playerId", playerId);
      snapshot.put("viewType", viewType);
      snapshot.put("pluginVersion", PLUGIN_VERSION);
      snapshot.put("media3Version", MediaLibraryInfo.VERSION);
      snapshot.put("device", createDeviceInfo());
      snapshot.put("app", createAppInfo());
      snapshot.put("media", createMediaInfo(exoPlayer.getCurrentMediaItem()));
      snapshot.put("format", createFormatInfo(videoFormat != null ? videoFormat : exoPlayer.getVideoFormat()));
      snapshot.put("decoder", createDecoderInfo(decoderName));
      snapshot.put("playback", createPlaybackInfo());
      snapshot.put("decoderCounters", createDecoderCountersInfo(currentDecoderCounters()));
      snapshot.put("surface", createSurfaceInfo());
      snapshot.put("lastPlaybackError", lastPlaybackError);
      snapshot.put("lastCodecError", lastCodecError);
      snapshot.put("recentEvents", new ArrayList<>(recentEvents));
      return snapshot;
    }

    /** 获取当前最新的视频解码计数器。 */
    @Nullable
    private DecoderCounters currentDecoderCounters() {
      DecoderCounters current = exoPlayer.getVideoDecoderCounters();
      if (current == null) {
        current = decoderCounters;
      }
      if (current != null) {
        current.ensureUpdated();
      }
      return current;
    }

    /** 记录通用播放器事件，并限制在最近 30 秒。 */
    private synchronized void recordEvent(
        @NonNull String event, @Nullable Map<String, Object> detail) {
      long now = SystemClock.elapsedRealtime();
      Map<String, Object> data = new LinkedHashMap<>();
      data.put("event", event);
      data.put("elapsedRealtimeMs", now);
      data.put("sinceRegisteredMs", now - registeredElapsedRealtimeMs);
      if (detail != null && !detail.isEmpty()) {
        data.put("detail", detail);
      }
      recentEvents.addLast(data);
      while (recentEvents.size() > MAX_RECENT_EVENTS) {
        recentEvents.removeFirst();
      }
      while (!recentEvents.isEmpty()) {
        Object value = recentEvents.peekFirst().get("elapsedRealtimeMs");
        if (!(value instanceof Long) || now - (Long) value <= RECENT_EVENT_WINDOW_MS) {
          break;
        }
        recentEvents.removeFirst();
      }
    }

    /** 记录 Surface 生命周期与尺寸。 */
    private void recordSurfaceEvent(
        @NonNull String event, int format, int width, int height) {
      Map<String, Object> detail = new LinkedHashMap<>();
      detail.put("format", format);
      detail.put("width", width);
      detail.put("height", height);
      recordEvent(event, detail);
    }

    /** 记录播放器状态变化。 */
    @Override
    public void onPlaybackStateChanged(@NonNull EventTime eventTime, int state) {
      Map<String, Object> detail = new LinkedHashMap<>();
      detail.put("state", state);
      recordEvent("playbackStateChanged", detail);
    }

    /** 记录实际启用的视频解码器。 */
    @Override
    public void onVideoDecoderInitialized(
        @NonNull EventTime eventTime,
        @NonNull String decoderName,
        long initializedTimestampMs,
        long initializationDurationMs) {
      this.decoderName = decoderName;
      Map<String, Object> detail = new LinkedHashMap<>();
      detail.put("decoderName", decoderName);
      detail.put("initializationDurationMs", initializationDurationMs);
      recordEvent("videoDecoderInitialized", detail);
    }

    /** 记录实际输入给视频解码器的轨道格式。 */
    @Override
    public void onVideoInputFormatChanged(
        @NonNull EventTime eventTime,
        @NonNull Format format,
        @Nullable DecoderReuseEvaluation decoderReuseEvaluation) {
      videoFormat = format;
      Map<String, Object> detail = new LinkedHashMap<>();
      detail.put("format", createFormatInfo(format));
      recordEvent("videoInputFormatChanged", detail);
    }

    /** 累计视频丢帧事件。 */
    @Override
    public void onDroppedVideoFrames(
        @NonNull EventTime eventTime, int droppedFrames, long elapsedMs) {
      droppedVideoFrames += droppedFrames;
      Map<String, Object> detail = new LinkedHashMap<>();
      detail.put("count", droppedFrames);
      detail.put("elapsedMs", elapsedMs);
      detail.put("total", droppedVideoFrames);
      recordEvent("droppedVideoFrames", detail);
    }

    /** 记录首帧实际渲染时间。 */
    @Override
    public void onRenderedFirstFrame(
        @NonNull EventTime eventTime, @NonNull Object output, long renderTimeMs) {
      firstFrameElapsedRealtimeMs = SystemClock.elapsedRealtime();
      recordEvent("renderedFirstFrame", null);
    }

    /** 记录播放器报告的视频尺寸。 */
    @Override
    public void onVideoSizeChanged(@NonNull EventTime eventTime, @NonNull VideoSize videoSize) {
      Map<String, Object> detail = new LinkedHashMap<>();
      detail.put("width", videoSize.width);
      detail.put("height", videoSize.height);
      detail.put("pixelWidthHeightRatio", (double) videoSize.pixelWidthHeightRatio);
      recordEvent("videoSizeChanged", detail);
    }

    /** 记录 ExoPlayer 的 Surface 输出尺寸。 */
    @Override
    public void onSurfaceSizeChanged(
        @NonNull EventTime eventTime, int width, int height) {
      recordSurfaceEvent("exoSurfaceSizeChanged", 0, width, height);
    }

    /** 保存播放器错误及其原因链。 */
    @Override
    public void onPlayerError(
        @NonNull EventTime eventTime, @NonNull PlaybackException error) {
      lastPlaybackError = createPlaybackErrorInfo(error);
      recordEvent("playerError", lastPlaybackError);
    }

    /** 保存未升级为播放器错误的 MediaCodec 异常。 */
    @Override
    public void onVideoCodecError(@NonNull EventTime eventTime, @NonNull Exception error) {
      lastCodecError = createThrowableInfo(error);
      recordEvent("videoCodecError", lastCodecError);
    }

    /** 保存视频解码计数器引用。 */
    @Override
    public void onVideoEnabled(
        @NonNull EventTime eventTime, @NonNull DecoderCounters decoderCounters) {
      this.decoderCounters = decoderCounters;
      recordEvent("videoEnabled", null);
    }

    /** 在视频解码器停用时保留最后一次完整计数。 */
    @Override
    public void onVideoDisabled(
        @NonNull EventTime eventTime, @NonNull DecoderCounters decoderCounters) {
      decoderCounters.ensureUpdated();
      this.decoderCounters = decoderCounters;
      recordEvent("videoDisabled", createDecoderCountersInfo(decoderCounters));
    }

    /** 构建播放器实时状态。 */
    @NonNull
    private Map<String, Object> createPlaybackInfo() {
      Map<String, Object> info = new LinkedHashMap<>();
      info.put("playbackState", exoPlayer.getPlaybackState());
      info.put("playWhenReady", exoPlayer.getPlayWhenReady());
      info.put("isPlaying", exoPlayer.isPlaying());
      info.put("isLoading", exoPlayer.isLoading());
      info.put("positionMs", exoPlayer.getCurrentPosition());
      info.put("bufferedPositionMs", exoPlayer.getBufferedPosition());
      info.put("totalBufferedDurationMs", exoPlayer.getTotalBufferedDuration());
      info.put("durationMs", exoPlayer.getDuration());
      info.put("playbackSpeed", (double) exoPlayer.getPlaybackParameters().speed);
      info.put("volume", (double) exoPlayer.getVolume());
      info.put("droppedVideoFrames", droppedVideoFrames);
      info.put(
          "firstFrameSinceRegisteredMs",
          firstFrameElapsedRealtimeMs < 0
              ? null
              : firstFrameElapsedRealtimeMs - registeredElapsedRealtimeMs);
      return info;
    }

    /** 构建当前 SurfaceView 状态。 */
    @NonNull
    private Map<String, Object> createSurfaceInfo() {
      Map<String, Object> info = new LinkedHashMap<>();
      SurfaceView view = surfaceView;
      info.put("available", view != null);
      if (view != null) {
        info.put("width", view.getWidth());
        info.put("height", view.getHeight());
        info.put("shown", view.isShown());
        info.put("surfaceValid", view.getHolder().getSurface().isValid());
      }
      return info;
    }
  }

  /** 构建设备与系统构建信息。 */
  @NonNull
  private static Map<String, Object> createDeviceInfo() {
    Map<String, Object> info = new LinkedHashMap<>();
    info.put("manufacturer", Build.MANUFACTURER);
    info.put("brand", Build.BRAND);
    info.put("model", Build.MODEL);
    info.put("product", Build.PRODUCT);
    info.put("device", Build.DEVICE);
    info.put("board", Build.BOARD);
    info.put("hardware", Build.HARDWARE);
    info.put("display", Build.DISPLAY);
    info.put("fingerprint", Build.FINGERPRINT);
    info.put("sdkInt", Build.VERSION.SDK_INT);
    info.put("release", Build.VERSION.RELEASE);
    info.put("incremental", Build.VERSION.INCREMENTAL);
    info.put("securityPatch", Build.VERSION.SECURITY_PATCH);
    info.put("supportedAbis", Arrays.asList(Build.SUPPORTED_ABIS));
    return info;
  }

  /** 构建宿主应用版本信息。 */
  @NonNull
  @SuppressWarnings("deprecation")
  private Map<String, Object> createAppInfo() {
    Map<String, Object> info = new LinkedHashMap<>();
    info.put("packageName", context.getPackageName());
    try {
      PackageInfo packageInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
      info.put("versionName", packageInfo.versionName);
      info.put("versionCode", Build.VERSION.SDK_INT >= 28 ? packageInfo.getLongVersionCode() : packageInfo.versionCode);
    } catch (Exception error) {
      info.put("packageInfoError", error.getClass().getSimpleName());
    }
    return info;
  }

  /** 构建不包含签名参数和完整路径的媒体来源信息。 */
  @NonNull
  private static Map<String, Object> createMediaInfo(@Nullable MediaItem mediaItem) {
    Map<String, Object> info = new LinkedHashMap<>();
    if (mediaItem == null || mediaItem.localConfiguration == null) {
      return info;
    }
    android.net.Uri uri = mediaItem.localConfiguration.uri;
    info.put("scheme", uri.getScheme());
    info.put("host", uri.getHost());
    info.put("pathHash", sha256(uri.getPath()));
    info.put("mimeType", mediaItem.localConfiguration.mimeType);
    info.put("drm", mediaItem.localConfiguration.drmConfiguration != null);
    return info;
  }

  /** 构建视频轨道格式信息。 */
  @NonNull
  private static Map<String, Object> createFormatInfo(@Nullable Format format) {
    Map<String, Object> info = new LinkedHashMap<>();
    if (format == null) {
      return info;
    }
    info.put("id", format.id);
    info.put("containerMimeType", format.containerMimeType);
    info.put("sampleMimeType", format.sampleMimeType);
    info.put("codecs", format.codecs);
    info.put("averageBitrate", valueOrNull(format.averageBitrate));
    info.put("peakBitrate", valueOrNull(format.peakBitrate));
    info.put("width", valueOrNull(format.width));
    info.put("height", valueOrNull(format.height));
    info.put("decodedWidth", valueOrNull(format.decodedWidth));
    info.put("decodedHeight", valueOrNull(format.decodedHeight));
    info.put("frameRate", format.frameRate == Format.NO_VALUE ? null : (double) format.frameRate);
    info.put("rotationDegrees", valueOrNull(format.rotationDegrees));
    info.put("pixelWidthHeightRatio", (double) format.pixelWidthHeightRatio);
    info.put("maxInputSize", valueOrNull(format.maxInputSize));
    info.put("drm", format.drmInitData != null);
    info.put("colorInfo", createColorInfo(format.colorInfo));
    return info;
  }

  /** 构建视频色彩空间与位深信息。 */
  @NonNull
  private static Map<String, Object> createColorInfo(@Nullable ColorInfo colorInfo) {
    Map<String, Object> info = new LinkedHashMap<>();
    if (colorInfo == null) {
      return info;
    }
    info.put("colorSpace", colorInfo.colorSpace);
    info.put("colorRange", colorInfo.colorRange);
    info.put("colorTransfer", colorInfo.colorTransfer);
    info.put("lumaBitdepth", colorInfo.lumaBitdepth);
    info.put("chromaBitdepth", colorInfo.chromaBitdepth);
    return info;
  }

  /** 构建实际解码器及其厂商属性。 */
  @NonNull
  private static Map<String, Object> createDecoderInfo(@Nullable String decoderName) {
    Map<String, Object> info = new LinkedHashMap<>();
    info.put("name", decoderName);
    if (decoderName == null) {
      return info;
    }
    MediaCodecInfo codecInfo = findCodecInfo(decoderName);
    if (codecInfo == null) {
      return info;
    }
    info.put("canonicalName", Build.VERSION.SDK_INT >= 29 ? codecInfo.getCanonicalName() : codecInfo.getName());
    if (Build.VERSION.SDK_INT >= 29) {
      info.put("hardwareAccelerated", codecInfo.isHardwareAccelerated());
      info.put("softwareOnly", codecInfo.isSoftwareOnly());
      info.put("vendor", codecInfo.isVendor());
    }
    info.put("supportedTypes", Arrays.asList(codecInfo.getSupportedTypes()));
    return info;
  }

  /** 按名称查找系统 MediaCodecInfo。 */
  @Nullable
  private static MediaCodecInfo findCodecInfo(@NonNull String decoderName) {
    try {
      for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos()) {
        if (info.getName().equalsIgnoreCase(decoderName)) {
          return info;
        }
      }
    } catch (RuntimeException ignored) {
      // 某些厂商系统查询 Codec 列表会抛出运行时异常，诊断快照仍可继续返回。
    }
    return null;
  }

  /** 构建视频解码计数器。 */
  @NonNull
  private static Map<String, Object> createDecoderCountersInfo(@Nullable DecoderCounters counters) {
    Map<String, Object> info = new LinkedHashMap<>();
    if (counters == null) {
      return info;
    }
    info.put("decoderInitCount", counters.decoderInitCount);
    info.put("decoderReleaseCount", counters.decoderReleaseCount);
    info.put("queuedInputBufferCount", counters.queuedInputBufferCount);
    info.put("skippedInputBufferCount", counters.skippedInputBufferCount);
    info.put("renderedOutputBufferCount", counters.renderedOutputBufferCount);
    info.put("skippedOutputBufferCount", counters.skippedOutputBufferCount);
    info.put("droppedBufferCount", counters.droppedBufferCount);
    info.put("droppedInputBufferCount", counters.droppedInputBufferCount);
    info.put("maxConsecutiveDroppedBufferCount", counters.maxConsecutiveDroppedBufferCount);
    info.put("droppedToKeyframeCount", counters.droppedToKeyframeCount);
    info.put("totalVideoFrameProcessingOffsetUs", counters.totalVideoFrameProcessingOffsetUs);
    info.put("videoFrameProcessingOffsetCount", counters.videoFrameProcessingOffsetCount);
    return info;
  }

  /** 构建 ExoPlayer 播放异常信息。 */
  @NonNull
  private static Map<String, Object> createPlaybackErrorInfo(@NonNull PlaybackException error) {
    Map<String, Object> info = createThrowableInfo(error);
    info.put("errorCode", error.errorCode);
    info.put("errorCodeName", error.getErrorCodeName());
    info.put("timestampMs", error.timestampMs);
    if (error instanceof ExoPlaybackException) {
      ExoPlaybackException exoError = (ExoPlaybackException) error;
      info.put("type", exoError.type);
      info.put("rendererName", exoError.rendererName);
      info.put("rendererIndex", exoError.rendererIndex);
      info.put("rendererFormat", createFormatInfo(exoError.rendererFormat));
      info.put("rendererFormatSupport", exoError.rendererFormatSupport);
    }
    return info;
  }

  /** 构建异常类型、消息和有限原因链。 */
  @NonNull
  private static Map<String, Object> createThrowableInfo(@NonNull Throwable error) {
    Map<String, Object> info = new LinkedHashMap<>();
    info.put("type", error.getClass().getName());
    info.put("message", error.getMessage());
    if (error instanceof MediaCodec.CodecException) {
      MediaCodec.CodecException codecException = (MediaCodec.CodecException) error;
      info.put("diagnosticInfo", codecException.getDiagnosticInfo());
      info.put("recoverable", codecException.isRecoverable());
      info.put("transient", codecException.isTransient());
      info.put("codecErrorCode", codecException.getErrorCode());
    }
    List<Map<String, Object>> causes = new ArrayList<>();
    Throwable cause = error.getCause();
    int depth = 0;
    while (cause != null && cause != error && depth < 5) {
      Map<String, Object> causeInfo = new LinkedHashMap<>();
      causeInfo.put("type", cause.getClass().getName());
      causeInfo.put("message", cause.getMessage());
      if (cause instanceof MediaCodec.CodecException) {
        MediaCodec.CodecException codecException = (MediaCodec.CodecException) cause;
        causeInfo.put("diagnosticInfo", codecException.getDiagnosticInfo());
        causeInfo.put("recoverable", codecException.isRecoverable());
        causeInfo.put("transient", codecException.isTransient());
        causeInfo.put("codecErrorCode", codecException.getErrorCode());
      }
      causes.add(causeInfo);
      cause = cause.getCause();
      depth++;
    }
    info.put("causes", causes);
    return info;
  }

  /** 将 Media3 的无效整数常量归一为 null。 */
  @Nullable
  private static Integer valueOrNull(int value) {
    return value == Format.NO_VALUE ? null : value;
  }

  /** 对媒体路径做不可逆摘要，避免上传完整地址。 */
  @Nullable
  private static String sha256(@Nullable String value) {
    if (value == null || value.isEmpty()) {
      return null;
    }
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder builder = new StringBuilder();
      for (int index = 0; index < Math.min(digest.length, 12); index++) {
        builder.append(String.format(Locale.US, "%02x", digest[index]));
      }
      return builder.toString();
    } catch (NoSuchAlgorithmException ignored) {
      return null;
    }
  }
}
