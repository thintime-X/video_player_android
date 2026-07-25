// Copyright 2013 The Flutter Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.videoplayer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.test.core.app.ApplicationProvider;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** 验证 Android 播放诊断快照、截图失败结果和缓存文件删除边界。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class VideoPlaybackDiagnosticCollectorTest {
  private Context context;
  private ExoPlayer exoPlayer;
  private VideoPlaybackDiagnosticCollector collector;

  /** 创建使用模拟播放器的诊断采集器。 */
  @Before
  public void setUp() {
    context = ApplicationProvider.getApplicationContext();
    exoPlayer = mock(ExoPlayer.class);
    when(exoPlayer.getPlaybackParameters()).thenReturn(PlaybackParameters.DEFAULT);
    collector =
        new VideoPlaybackDiagnosticCollector(context, mock(BinaryMessenger.class));
  }

  /** 释放诊断采集器持有的执行器和监听器。 */
  @After
  public void tearDown() {
    collector.dispose();
  }

  /** 诊断快照包含播放器、设备和事件等基础字段。 */
  @Test
  public void getSnapshotReturnsStructuredData() {
    collector.registerPlayer(7L, exoPlayer, "platformView");
    CapturingResult result = new CapturingResult();

    collector.onMethodCall(
        new MethodCall("getSnapshot", Collections.singletonMap("playerId", 7L)), result);

    assertNotNull(result.successValue);
    Map<?, ?> snapshot = (Map<?, ?>) result.successValue;
    assertEquals(1, snapshot.get("schemaVersion"));
    assertEquals(7L, snapshot.get("playerId"));
    assertEquals("platformView", snapshot.get("viewType"));
    assertTrue(snapshot.get("device") instanceof Map);
    assertTrue(snapshot.get("playback") instanceof Map);
    assertTrue(snapshot.get("recentEvents") instanceof Iterable);
  }

  /** 未注册 SurfaceView 时截图返回结构化失败，不抛出平台异常。 */
  @Test
  public void captureWithoutSurfaceReturnsFailureResult() {
    collector.registerPlayer(8L, exoPlayer, "platformView");
    CapturingResult result = new CapturingResult();

    collector.onMethodCall(
        new MethodCall("captureVideoFrame", Collections.singletonMap("playerId", 8L)),
        result);

    Map<?, ?> capture = (Map<?, ?>) result.successValue;
    assertEquals(false, capture.get("success"));
    assertEquals("surface_view_unavailable", capture.get("errorCode"));
  }

  /** 删除接口仅处理诊断缓存目录内的文件。 */
  @Test
  public void deleteCaptureFilesRejectsPathsOutsideCaptureDirectory() throws Exception {
    File allowedDirectory = new File(context.getCacheDir(), "video_feedback_diagnostics");
    assertTrue(allowedDirectory.mkdirs() || allowedDirectory.isDirectory());
    File allowedFile = new File(allowedDirectory, "allowed.png");
    try (FileOutputStream output = new FileOutputStream(allowedFile)) {
      output.write(1);
    }
    File outsideFile = new File(context.getCacheDir(), "outside.png");
    try (FileOutputStream output = new FileOutputStream(outsideFile)) {
      output.write(1);
    }
    CapturingResult result = new CapturingResult();

    collector.onMethodCall(
        new MethodCall(
            "deleteCaptureFiles",
            Collections.singletonMap(
                "paths", Arrays.asList(allowedFile.getAbsolutePath(), outsideFile.getAbsolutePath()))),
        result);

    assertEquals(1, result.successValue);
    assertFalse(allowedFile.exists());
    assertTrue(outsideFile.exists());
    assertTrue(outsideFile.delete());
  }

  /** 保存平台通道返回值，便于验证同步诊断方法。 */
  private static final class CapturingResult implements MethodChannel.Result {
    private Object successValue;

    /** 保存成功返回值。 */
    @Override
    public void success(Object result) {
      successValue = result;
    }

    /** 测试中的调用不应返回错误。 */
    @Override
    public void error(String errorCode, String errorMessage, Object errorDetails) {
      throw new AssertionError(errorCode + ": " + errorMessage);
    }

    /** 测试中的调用都应已实现。 */
    @Override
    public void notImplemented() {
      throw new AssertionError("方法未实现");
    }
  }
}
