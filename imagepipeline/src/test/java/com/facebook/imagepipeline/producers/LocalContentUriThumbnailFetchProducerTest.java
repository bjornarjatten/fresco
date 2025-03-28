/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.imagepipeline.producers;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.ContentResolver;
import android.database.Cursor;
import android.media.ExifInterface;
import android.net.Uri;
import android.provider.MediaStore;
import com.facebook.common.memory.PooledByteBuffer;
import com.facebook.common.memory.PooledByteBufferFactory;
import com.facebook.imagepipeline.common.Priority;
import com.facebook.imagepipeline.common.ResizeOptions;
import com.facebook.imagepipeline.core.ImagePipelineConfig;
import com.facebook.imagepipeline.image.EncodedImage;
import com.facebook.imagepipeline.request.ImageRequest;
import com.facebook.imagepipeline.testing.FakeClock;
import com.facebook.imagepipeline.testing.TestExecutorService;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import org.junit.*;
import org.junit.After;
import org.junit.runner.*;
import org.mockito.*;
import org.mockito.MockedStatic;
import org.mockito.stubbing.Answer;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.*;

/** Basic tests for LocalContentUriThumbnailFetchProducer */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class LocalContentUriThumbnailFetchProducerTest {
  private static final String PRODUCER_NAME = LocalContentUriThumbnailFetchProducer.PRODUCER_NAME;
  private static final String THUMBNAIL_FILE_NAME = "////sdcard/thumb.jpg";
  private static final long THUMBNAIL_FILE_SIZE = 1374;

  @Mock public PooledByteBufferFactory mPooledByteBufferFactory;
  @Mock public ContentResolver mContentResolver;
  @Mock public Consumer<EncodedImage> mConsumer;
  @Mock public ImageRequest mImageRequest;
  @Mock public ProducerListener2 mProducerListener;
  @Mock public Exception mException;
  @Mock public Cursor mCursor;
  @Mock public File mThumbnailFile;
  @Mock public ImagePipelineConfig mConfig;

  private TestExecutorService mExecutor;
  private SettableProducerContext mProducerContext;
  private final String mRequestId = "mRequestId";
  private Uri mContentUri;
  private LocalContentUriThumbnailFetchProducer mLocalContentUriThumbnailFetchProducer;
  private MockedStatic<MediaStore.Images.Thumbnails> mockedMediaStoreImagesThumbnails;
  private MockedStatic<LocalContentUriThumbnailFetchProducer.FileUtil> mockedFileUtil;
  private MockedConstruction<FileInputStream> mockedFileInputStream;
  private MockedConstruction<ExifInterface> mockedExifInterface;
  private MockedConstruction<EncodedImage> mockedEncodedImage;

  @Before
  public void setUp() throws Exception {
    mockedMediaStoreImagesThumbnails = mockStatic(MediaStore.Images.Thumbnails.class);
    MockitoAnnotations.initMocks(this);

    mExecutor = new TestExecutorService(new FakeClock());
    mLocalContentUriThumbnailFetchProducer =
        new LocalContentUriThumbnailFetchProducer(
            mExecutor, mPooledByteBufferFactory, mContentResolver);
    mContentUri = Uri.parse("content://media/external/images/media/1");

    mProducerContext =
        new SettableProducerContext(
            mImageRequest,
            mRequestId,
            mProducerListener,
            mock(Object.class),
            ImageRequest.RequestLevel.FULL_FETCH,
            false,
            true,
            Priority.MEDIUM,
            mConfig);
    when(mImageRequest.getSourceUri()).thenReturn(mContentUri);

    mockMediaStoreCursor();
    mockContentResolver();
    mockThumbnailFile();
  }

  @After
  public void tearDown() {
    mockedMediaStoreImagesThumbnails.close();
    mockedFileUtil.close();
    mockedFileInputStream.close();
    mockedExifInterface.close();
    mockedEncodedImage.close();
  }

  private void mockMediaStoreCursor() {
    when(MediaStore.Images.Thumbnails.queryMiniThumbnail(
            any(ContentResolver.class), anyLong(), anyInt(), any(String[].class)))
        .thenAnswer((Answer<Cursor>) invocation -> mCursor);
    final int dataColumnIndex = 5;
    when(mCursor.getColumnIndex(MediaStore.Images.Thumbnails.DATA)).thenReturn(dataColumnIndex);
    when(mCursor.getString(dataColumnIndex)).thenReturn(THUMBNAIL_FILE_NAME);
    when(mCursor.moveToFirst()).thenReturn(true);
  }

  private void mockThumbnailFile() throws Exception {
    mockedFileUtil = mockStatic(LocalContentUriThumbnailFetchProducer.FileUtil.class);
    when(LocalContentUriThumbnailFetchProducer.FileUtil.exists(any())).thenReturn(true);
    when(LocalContentUriThumbnailFetchProducer.FileUtil.length(any()))
        .thenReturn(THUMBNAIL_FILE_SIZE);
    mockedFileInputStream = mockConstruction(FileInputStream.class);
    mockedExifInterface = mockConstruction(ExifInterface.class);
    mockedEncodedImage =
        mockConstruction(
            EncodedImage.class,
            (mock, context) -> {
              when(mock.getSize()).thenReturn((int) THUMBNAIL_FILE_SIZE);
            });
  }

  private void mockContentResolver() throws Exception {
    when(mContentResolver.query(
            eq(mContentUri),
            nullable(String[].class),
            nullable(String.class),
            nullable(String[].class),
            nullable(String.class)))
        .thenReturn(mCursor);
    when(mContentResolver.openInputStream(mContentUri)).thenReturn(mock(InputStream.class));
  }

  @Test
  public void testLocalContentUriFetchCancelled() {
    mockResizeOptions(512, 384);

    produceResults();

    mProducerContext.cancel();
    verify(mProducerListener).onProducerStart(mProducerContext, PRODUCER_NAME);
    verify(mProducerListener)
        .onProducerFinishWithCancellation(mProducerContext, PRODUCER_NAME, null);
    verify(mConsumer).onCancellation();
    mExecutor.runUntilIdle();
    verifyZeroInteractions(mPooledByteBufferFactory);
  }

  @Test
  public void testFetchLocalContentUri() throws Exception {
    mockResizeOptions(512, 384);

    PooledByteBuffer pooledByteBuffer = mock(PooledByteBuffer.class);
    when(mPooledByteBufferFactory.newByteBuffer(any(InputStream.class)))
        .thenReturn(pooledByteBuffer);

    produceResultsAndRunUntilIdle();

    assertConsumerReceivesImage();
    verify(mProducerListener).onProducerStart(mProducerContext, PRODUCER_NAME);
    verify(mProducerListener).onProducerFinishWithSuccess(mProducerContext, PRODUCER_NAME, null);
    verify(mProducerListener).onUltimateProducerReached(mProducerContext, PRODUCER_NAME, true);
  }

  @Test(expected = RuntimeException.class)
  public void testFetchLocalContentUriFailsByThrowing() throws Exception {
    mockResizeOptions(512, 384);

    when(mPooledByteBufferFactory.newByteBuffer(any(InputStream.class))).thenThrow(mException);
    verify(mConsumer).onFailure(mException);
    verify(mProducerListener).onProducerStart(mProducerContext, PRODUCER_NAME);
    verify(mProducerListener)
        .onProducerFinishWithFailure(mProducerContext, PRODUCER_NAME, mException, null);
    verify(mProducerListener).onUltimateProducerReached(mProducerContext, PRODUCER_NAME, false);
  }

  @Test
  public void testIsLargerThanThumbnailMaxSize() {
    mockResizeOptions(1000, 384);

    produceResultsAndRunUntilIdle();

    assertConsumerReceivesNull();
  }

  @Test
  public void testWithoutResizeOptions() {
    produceResultsAndRunUntilIdle();

    assertConsumerReceivesNull();
  }

  private void mockResizeOptions(int width, int height) {
    ResizeOptions resizeOptions = new ResizeOptions(width, height);
    when(mImageRequest.getResizeOptions()).thenReturn(resizeOptions);
  }

  private void produceResults() {
    mLocalContentUriThumbnailFetchProducer.produceResults(mConsumer, mProducerContext);
  }

  private void produceResultsAndRunUntilIdle() {
    mLocalContentUriThumbnailFetchProducer.produceResults(mConsumer, mProducerContext);
    mExecutor.runUntilIdle();
  }

  private void assertConsumerReceivesNull() {
    verify(mConsumer).onNewResult(null, Consumer.IS_LAST);
    verifyNoMoreInteractions(mConsumer);

    verifyZeroInteractions(mPooledByteBufferFactory);
  }

  private void assertConsumerReceivesImage() {
    ArgumentCaptor<EncodedImage> resultCaptor = ArgumentCaptor.forClass(EncodedImage.class);
    verify(mConsumer).onNewResult(resultCaptor.capture(), eq(Consumer.IS_LAST));

    assertNotNull(resultCaptor.getValue());
    assertEquals(THUMBNAIL_FILE_SIZE, resultCaptor.getValue().getSize());

    verifyNoMoreInteractions(mConsumer);
  }
}
