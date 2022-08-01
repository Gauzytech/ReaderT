import 'dart:ui' as ui;

import 'package:flutter/cupertino.dart';
import 'package:flutter_lib/interface/i_bitmap_manager.dart';
import 'package:flutter_lib/modal/page_index.dart';

/// Bitmap管理（绘制后的图）的实现
class BitmapManagerImpl extends IBitmapManager {
  /// 缓存Bitmap大小
  static const int cacheSize = 4;
  final List<ui.Image?> _imageCache = List.filled(cacheSize, null, growable: false);

  // 缓存4个pageIndex
  // pageIndex: PREV_2, PREV, CURRENT, NEXT, NEXT_2;
  List<PageIndex?> cachedPageIndexes =
      List.filled(cacheSize, null, growable: false);
  int contentWidth = 0;
  int contentHeight = 0;

  /// 设置绘制Bitmap的宽高（即阅读器内容区域）
  ///
  /// @param w 宽
  /// @param h 高
  void setSize(int width, int height) {
    if (contentWidth != width || contentHeight != height) {
      contentWidth = width;
      contentHeight = height;
      // clear();
    }
  }

  @override
  void clear() {
    for (int i = 0; i < cacheSize; ++i) {
      _imageCache[i]?.dispose();
      _imageCache[i] = null;
      cachedPageIndexes[i] = null;
    }
  }

  /// 获取阅读器内容Bitmap
  ///
  /// @param index 页索引
  /// @return 阅读器内容Bitmap
  @override
  ImageSrc getBitmap(PageIndex index) {
    for (int i = 0; i < cacheSize; ++i) {
      if (cachedPageIndexes[i] == index) {
        ui.Image? image = _imageCache[i];
        return ImageSrc(img: image, processing: image == null);
      }
    }
    return ImageSrc(img: null, processing: false);
  }

  @override
  int findInternalCacheIndex(PageIndex pageIndex) {
    final int internalCacheIndex = getInternalIndex(pageIndex);
    // 找到内部index先把位置占住
    cachedPageIndexes[internalCacheIndex] = pageIndex;

    if(_imageCache[internalCacheIndex] == null) {
      return internalCacheIndex;
    } else {
      // 如果已经存在一个image, 直接清掉
      _imageCache[internalCacheIndex]!.dispose();
      _imageCache[internalCacheIndex] = null;
      return internalCacheIndex;
    }
  }

  void cacheBitmap(int internalCacheIndex, ui.Image image) {
    print("flutter内容绘制流程, 收到了图片并缓存[${image.width}, ${image.height}], idx = $internalCacheIndex");
    _imageCache[internalCacheIndex] = image;
  }

  @override
  void drawBitmap(Canvas canvas, int x, int y, PageIndex index, Paint paint) {

  }

  @override
  void drawPreviewBitmap(
      Canvas canvas, int x, int y, PageIndex index, Paint paint) {
    // TODO: implement drawPreviewBitmap
  }

  /// 获取一个内部索引位置，用于存储Bitmap（原则是：先寻找空的，再寻找非当前使用的）
  ///
  /// @return 索引位置
  int getInternalIndex(PageIndex index) {
    // 寻找没有存储内容的位置
    for (int i = 0; i < cacheSize; ++i) {
      if (cachedPageIndexes[i] == null) {
        return i;
      }
    }
    // 如果没有，找一个不是当前的位置
    for (int i = 0; i < cacheSize; ++i) {
      if (cachedPageIndexes[i] != PageIndex.current &&
          cachedPageIndexes[i] != PageIndex.prev &&
          cachedPageIndexes[i] != PageIndex.next) {
        return i;
      }
    }
    throw UnsupportedError("That's impossible");
  }

  /// 重置索引缓存
  /// TODO: 需要精确rest（避免不必要的缓存失效）
  void reset() {
    for (int i = 0; i < cacheSize; ++i) {
      cachedPageIndexes[i] = null;
    }
  }

  /// 位移操作（所有索引位移至下一状态）
  ///
  /// @param forward 是否向前
  /// 
  /// current, prev, next, null
  ///
  /// shift forward
  /// prev, prev2, current, null
  ///
  /// shift backward
  /// next, current, next2, null
  void shift(bool forward) {
    for (int i = 0; i < cacheSize; ++i) {
      if (cachedPageIndexes[i] == null) {
        continue;
      }
      if(forward) {
        cachedPageIndexes[i] = cachedPageIndexes[i]!.getPrevious();
      } else {
        cachedPageIndexes[i] = cachedPageIndexes[i]!.getNext();
      }
    }
  }

  List<double> getContentSize() {
    return [contentWidth.toDouble(), contentHeight.toDouble()];
  }
}

class ImageSrc {
  ui.Image? img;
  bool processing;
  ImageSrc({required this.img, required this.processing});
}
