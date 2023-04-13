/*
 * Copyright (C) 2007-2015 FBReader.ORG Limited <contact@fbreader.org>
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA
 * 02110-1301, USA.
 */

package org.geometerplus.zlibrary.ui.android.view;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.CornerPathEffect;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.Typeface;

import androidx.annotation.Nullable;

import org.geometerplus.DebugHelper;
import org.geometerplus.zlibrary.core.filesystem.ZLFile;
import org.geometerplus.zlibrary.core.fonts.FontEntry;
import org.geometerplus.zlibrary.core.image.ZLImageData;
import org.geometerplus.zlibrary.core.options.ZLBooleanOption;
import org.geometerplus.zlibrary.core.util.SystemInfo;
import org.geometerplus.zlibrary.core.util.ZLColor;
import org.geometerplus.zlibrary.core.view.ZLPaintContext;
import org.geometerplus.zlibrary.ui.android.image.ZLAndroidImageData;
import org.geometerplus.zlibrary.ui.android.util.ZLAndroidColorUtil;
import org.geometerplus.zlibrary.ui.android.view.bookrender.model.TextBlock;

import java.util.List;

import timber.log.Timber;

/**
 * Android画笔上下文（绘制相关）
 */
public final class ZLAndroidPaintContext extends ZLPaintContext {
    private final String TAG = "PaintContext[" + System.identityHashCode(this) + "]";

    /**
     * 字体抗锯齿
     */
    public static ZLBooleanOption AntiAliasOption = new ZLBooleanOption("Fonts", "AntiAlias", true);
    /**
     * 设备字距微调
     */
    public static ZLBooleanOption DeviceKerningOption = new ZLBooleanOption("Fonts", "DeviceKerning", false);
    /**
     * 抖动
     */
    public static ZLBooleanOption DitheringOption = new ZLBooleanOption("Fonts", "Dithering", false);
    /**
     * 亚像素
     */
    public static ZLBooleanOption SubpixelOption = new ZLBooleanOption("Fonts", "Subpixel", false);

    /**
     * 画布
     */
    private final Canvas myCanvas;
    /**
     * 文字画笔
     */
    private final Paint myTextPaint = new Paint();
    /**
     * 线画笔
     */
    private final Paint myLinePaint = new Paint();
    /**
     * 填充画笔
     */
    private final Paint myFillPaint = new Paint();
    /**
     * 轮廓线画笔
     */
    private final Paint myOutlinePaint = new Paint();

    private final Paint myExtraPaint = new Paint();
    private final Path myPath = new Path();

    private final Paint transparentPaint = new Paint();

    /**
     * 几何属性
     */
    public static final class Geometry {
        /**
         * 屏幕大小
         */
        final Size ScreenSize;
        /**
         * 区域大小
         */
        final Size AreaSize;
        /**
         * 左边距
         */
        final int LeftMargin;
        /**
         * 顶部边距
         */
        final int TopMargin;

        public Geometry(int screenWidth, int screenHeight, int width, int height, int leftMargin, int topMargin) {
            ScreenSize = new Size(screenWidth, screenHeight);
            AreaSize = new Size(width, height);
            LeftMargin = leftMargin;
            TopMargin = topMargin;
        }
    }

    private final Geometry myGeometry;
    private final int myScrollbarWidth;

    private ZLColor myBackgroundColor = new ZLColor(0, 0, 0);

    public ZLAndroidPaintContext(SystemInfo systemInfo, @Nullable Canvas canvas, Geometry geometry, int scrollbarWidth) {
        super(systemInfo);

        myCanvas = canvas;
        myGeometry = geometry;
        myScrollbarWidth = scrollbarWidth;
        Timber.v("可绘制区域, setMainHeight, %s", geometry.AreaSize.Height);
        // 设置文字的画笔
        myTextPaint.setLinearText(false);
        myTextPaint.setAntiAlias(AntiAliasOption.getValue());
        if (DeviceKerningOption.getValue()) {
            myTextPaint.setFlags(myTextPaint.getFlags() | Paint.DEV_KERN_TEXT_FLAG);
        } else {
            myTextPaint.setFlags(myTextPaint.getFlags() & ~Paint.DEV_KERN_TEXT_FLAG);
        }
        myTextPaint.setDither(DitheringOption.getValue());
        myTextPaint.setSubpixelText(SubpixelOption.getValue());

        myLinePaint.setStyle(Paint.Style.STROKE);

        // 设置填充的画笔，比如：长按选中高亮
        myFillPaint.setAntiAlias(AntiAliasOption.getValue());

        // 设置轮廓画笔, 比如: 长按选中图片或者超链接
        myOutlinePaint.setAntiAlias(true);
        myOutlinePaint.setDither(true);
        myOutlinePaint.setStrokeWidth(4);
        myOutlinePaint.setStyle(Paint.Style.STROKE);
//        myOutlinePaint.setStyle(Paint.Style.FILL);
        // 将path所有拐角变成圆角, 见https://blog.csdn.net/weixin_47623364/article/details/121597433
        myOutlinePaint.setPathEffect(new CornerPathEffect(5));
        // 遮罩, 浮雕效果, 没啥用, 需要硬件加速支持, 见https://blog.csdn.net/lyz_zyx/article/details/78783956
//        myOutlinePaint.setMaskFilter(new EmbossMaskFilter(new float[]{1, 1, 1}, .4f, 6f, 3.5f));

        myExtraPaint.setAntiAlias(true);

        transparentPaint.setColor(systemInfo.getContext().getResources().getColor(android.R.color.transparent));
        transparentPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        transparentPaint.setAntiAlias(true);
    }

    private static ZLFile ourWallpaperFile;
    private static Bitmap ourWallpaper;
    private static FillMode ourFillMode;

    @Override
    public void clear(ZLFile wallpaperFile, FillMode mode) {
        Timber.v("%s, clear", TAG);
        if (myCanvas == null) return;

        if (!wallpaperFile.equals(ourWallpaperFile) || mode != ourFillMode) {
            ourWallpaperFile = wallpaperFile;
            ourFillMode = mode;
            ourWallpaper = null;
            try {
                final Bitmap fileBitmap =
                        BitmapFactory.decodeStream(wallpaperFile.getInputStream());
                switch (mode) {
                    default:
                        ourWallpaper = fileBitmap;
                        break;
                    case tileMirror: {
                        final int w = fileBitmap.getWidth();
                        final int h = fileBitmap.getHeight();
                        final Bitmap wallpaper = Bitmap.createBitmap(2 * w, 2 * h, fileBitmap.getConfig());
                        final Canvas wallpaperCanvas = new Canvas(wallpaper);
                        final Paint wallpaperPaint = new Paint();

                        Matrix m = new Matrix();
                        wallpaperCanvas.drawBitmap(fileBitmap, m, wallpaperPaint);
                        m.preScale(-1, 1);
                        m.postTranslate(2 * w, 0);
                        wallpaperCanvas.drawBitmap(fileBitmap, m, wallpaperPaint);
                        m.preScale(1, -1);
                        m.postTranslate(0, 2 * h);
                        wallpaperCanvas.drawBitmap(fileBitmap, m, wallpaperPaint);
                        m.preScale(-1, 1);
                        m.postTranslate(-2 * w, 0);
                        wallpaperCanvas.drawBitmap(fileBitmap, m, wallpaperPaint);
                        ourWallpaper = wallpaper;
                        break;
                    }
                }
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }
        if (ourWallpaper != null) {
            myBackgroundColor = ZLAndroidColorUtil.getAverageColor(ourWallpaper);
            final int w = ourWallpaper.getWidth();
            final int h = ourWallpaper.getHeight();
            final Geometry g = myGeometry;
            switch (mode) {
                case fullscreen: {
                    final Matrix m = new Matrix();
                    m.preScale(1f * g.ScreenSize.Width / w, 1f * g.ScreenSize.Height / h);
                    m.postTranslate(-g.LeftMargin, -g.TopMargin);
                    myCanvas.drawBitmap(ourWallpaper, m, myFillPaint);
                    break;
                }
                case stretch: {
                    final Matrix m = new Matrix();
                    final float sw = 1f * g.ScreenSize.Width / w;
                    final float sh = 1f * g.ScreenSize.Height / h;
                    final float scale;
                    float dx = g.LeftMargin;
                    float dy = g.TopMargin;
                    if (sw < sh) {
                        scale = sh;
                        dx += (scale * w - g.ScreenSize.Width) / 2;
                    } else {
                        scale = sw;
                        dy += (scale * h - g.ScreenSize.Height) / 2;
                    }
                    m.preScale(scale, scale);
                    m.postTranslate(-dx, -dy);
                    myCanvas.drawBitmap(ourWallpaper, m, myFillPaint);
                    break;
                }
                case tileVertically: {
                    final Matrix m = new Matrix();
                    final int dx = g.LeftMargin;
                    final int dy = g.TopMargin % h;
                    m.preScale(1f * g.ScreenSize.Width / w, 1);
                    m.postTranslate(-dx, -dy);
                    for (int ch = g.AreaSize.Height + dy; ch > 0; ch -= h) {
                        myCanvas.drawBitmap(ourWallpaper, m, myFillPaint);
                        m.postTranslate(0, h);
                    }
                    break;
                }
                case tileHorizontally: {
                    final Matrix m = new Matrix();
                    final int dx = g.LeftMargin % w;
                    final int dy = g.TopMargin;
                    m.preScale(1, 1f * g.ScreenSize.Height / h);
                    m.postTranslate(-dx, -dy);
                    for (int cw = g.AreaSize.Width + dx; cw > 0; cw -= w) {
                        myCanvas.drawBitmap(ourWallpaper, m, myFillPaint);
                        m.postTranslate(w, 0);
                    }
                    break;
                }
                case tile:
                case tileMirror: {
                    final int dx = g.LeftMargin % w;
                    final int dy = g.TopMargin % h;
                    final int fullw = g.AreaSize.Width + dx;
                    final int fullh = g.AreaSize.Height + dy;
                    for (int cw = 0; cw < fullw; cw += w) {
                        for (int ch = 0; ch < fullh; ch += h) {
                            myCanvas.drawBitmap(ourWallpaper, cw - dx, ch - dy, myFillPaint);
                        }
                    }
                    break;
                }
            }
        } else {
            clear(new ZLColor(128, 128, 128));
        }
    }

    @Override
    public void clear(ZLColor color) {
        Timber.v("%s, clear", TAG);
        myBackgroundColor = color;
        myFillPaint.setColor(ZLAndroidColorUtil.rgb(color));
        if (DebugHelper.ENABLE_FLUTTER) {
            if (myCanvas != null) {
                myCanvas.drawRect(0, 0, myGeometry.AreaSize.Width, myGeometry.AreaSize.Height, transparentPaint);
            }
        } else {
            myCanvas.drawRect(0, 0, myGeometry.AreaSize.Width, myGeometry.AreaSize.Height, myFillPaint);
        }
    }

    @Override
    public ZLColor getBackgroundColor() {
        Timber.v("%s, getBackgroundColor", TAG);
        return myBackgroundColor;
    }

    public void fillPolygon(int[] xs, int[] ys) {
        Timber.v("%s, fillPolygon", TAG);
        if (myCanvas == null) return;
        final Path path = new Path();
        final int last = xs.length - 1;
        path.moveTo(xs[last], ys[last]);
        for (int i = 0; i <= last; ++i) {
            path.lineTo(xs[i], ys[i]);
        }
        myCanvas.drawPath(path, myFillPaint);
    }

    public void drawPolygonalLine(int[] xs, int[] ys) {
        Timber.v("%s, drawPolygonalLine", TAG);
        if (myCanvas == null) return;
        final Path path = new Path();
        final int last = xs.length - 1;
        path.moveTo(xs[last], ys[last]);
        for (int i = 0; i <= last; ++i) {
            path.lineTo(xs[i], ys[i]);
        }
        myCanvas.drawPath(path, myLinePaint);
    }

    public void drawOutline(int[] xs, int[] ys) {
        Timber.v("%s", TAG);
        if (myCanvas == null) return;
        final int last = xs.length - 1;
        int xStart = (xs[0] + xs[last]) / 2;
        int yStart = (ys[0] + ys[last]) / 2;
        int xEnd = xStart;
        int yEnd = yStart;
        int offset = 5;
        if (xs[0] != xs[last]) {
            if (xs[0] > xs[last]) {
                xStart -= offset;
                xEnd += offset;
            } else {
                xStart += offset;
                xEnd -= offset;
            }
        } else {
            if (ys[0] > ys[last]) {
                yStart -= offset;
                yEnd += offset;
            } else {
                yStart += offset;
                yEnd -= offset;
            }
        }

        final Path path = new Path();
        path.moveTo(xStart, yStart);
        for (int i = 0; i <= last; ++i) {
            path.lineTo(xs[i], ys[i]);
        }
        path.lineTo(xEnd, yEnd);
//        myOutlinePaint.setAlpha(150);
        myCanvas.drawPath(path, myOutlinePaint);
    }

    @Override
    protected void setFontInternal(List<FontEntry> entries, int size, boolean bold, boolean italic, boolean underline, boolean strikeThrough) {
        Timber.v("%s, setFontInternal", TAG);
        Typeface typeface = null;
        for (FontEntry e : entries) {
            Timber.v("字体测试， %s", e);
            typeface = AndroidFontUtil.typeface(getSystemInfo(), e, bold, italic);
            if (typeface != null) {
                break;
            }
        }
        myTextPaint.setTypeface(typeface);
        myTextPaint.setTextSize(size);
        myTextPaint.setUnderlineText(underline);
        myTextPaint.setStrikeThruText(strikeThrough);
    }

    @Override
    public void setTextColor(ZLColor color) {
        Timber.v("%s, setTextColor", TAG);
        if (color != null) {
            myTextPaint.setColor(ZLAndroidColorUtil.rgb(color));
        }
    }

    @Override
    public void setLineColor(ZLColor color) {
        Timber.v("长按选中流程[绘制],  LineColor = %s", color);
        if (color != null) {
            myLinePaint.setColor(ZLAndroidColorUtil.rgb(color));
            myOutlinePaint.setColor(ZLAndroidColorUtil.rgb(color));
        }
    }

    @Override
    public void setLineWidth(int width) {
        Timber.v("%s, setLineWidth", TAG);
        myLinePaint.setStrokeWidth(width);
    }

    @Override
    public void setFillColor(ZLColor color, int alpha) {
        if (color != null) {
            myFillPaint.setColor(ZLAndroidColorUtil.rgba(color, alpha));
        }
    }

    @Override
    public Geometry getGeometry() {
        return myGeometry;
    }

    public int getWidth() {
        Timber.v("%s, getWidth", TAG);
        return myGeometry.AreaSize.Width - myScrollbarWidth;
    }

    public int getHeight() {
        Timber.v("%s, getHeight", TAG);
        return myGeometry.AreaSize.Height;
    }

    @Override
    public int getStringWidth(char[] string, int offset, int length) {
        Timber.v("%s, getStringWidth", TAG);
        boolean containsSoftHyphen = false;
        for (int i = offset; i < offset + length; ++i) {
            if (string[i] == (char) 0xAD) {
                containsSoftHyphen = true;
                break;
            }
        }
        if (!containsSoftHyphen) {
            return (int) (myTextPaint.measureText(new String(string, offset, length)) + 0.5f);
        } else {
            final char[] corrected = new char[length];
            int len = 0;
            for (int o = offset; o < offset + length; ++o) {
                final char chr = string[o];
                if (chr != (char) 0xAD) {
                    corrected[len++] = chr;
                }
            }
            return (int) (myTextPaint.measureText(corrected, 0, len) + 0.5f);
        }
    }

    @Override
    public int getExtraStringWidth(char[] string, int offset, int length) {
        Timber.v("%s, getExtraStringWidth", TAG);
        boolean containsSoftHyphen = false;
        for (int i = offset; i < offset + length; ++i) {
            if (string[i] == (char) 0xAD) {
                containsSoftHyphen = true;
                break;
            }
        }
        if (!containsSoftHyphen) {
            return (int) (myExtraPaint.measureText(new String(string, offset, length)) + 0.5f);
        } else {
            final char[] corrected = new char[length];
            int len = 0;
            for (int o = offset; o < offset + length; ++o) {
                final char chr = string[o];
                if (chr != (char) 0xAD) {
                    corrected[len++] = chr;
                }
            }
            return (int) (myExtraPaint.measureText(corrected, 0, len) + 0.5f);
        }
    }

    @Override
    protected int getSpaceWidthInternal() {
        Timber.v("%s, getSpaceWidthInternal", TAG);
        return (int) (myTextPaint.measureText(" ", 0, 1) + 0.5f);
    }

    @Override
    protected int getCharHeightInternal(char chr) {
        Timber.v("%s, getCharHeightInternal", TAG);
        final Rect r = new Rect();
        final char[] txt = new char[]{chr};
        myTextPaint.getTextBounds(txt, 0, 1, r);
        return r.bottom - r.top;
    }

    @Override
    protected int getStringHeightInternal() {
        Timber.v("%s, getStringHeightInternal", TAG);
        return (int) (myTextPaint.getTextSize() + 0.5f);
    }

    @Override
    protected int getDescentInternal() {
        Timber.v("%s, getDescentInternal", TAG);
        return (int) (myTextPaint.descent() + 0.5f);
    }

    @Override
    public void drawString(int x, int y, char[] string, int offset, int length) {
        Timber.v("%s, drawString", TAG);
        if (myCanvas == null) return;
        boolean containsSoftHyphen = false;
        for (int i = offset; i < offset + length; ++i) {
            if (string[i] == (char) 0xAD) {
                containsSoftHyphen = true;
                break;
            }
        }
        if (!containsSoftHyphen) {

            printDebugStr(string, offset, length, x, y);
            myCanvas.drawText(string, offset, length, x, y, myTextPaint);
        } else {
            final char[] corrected = new char[length];
            int len = 0;
            for (int o = offset; o < offset + length; ++o) {
                final char chr = string[o];
                if (chr != (char) 0xAD) {
                    corrected[len++] = chr;
                }
            }

            printDebugStr(corrected, 0, len, x, y);
            myCanvas.drawText(corrected, 0, len, x, y, myTextPaint);
        }
    }

    @Override
    public TextBlock getDrawStringData(int x, int y, char[] string, int offset, int length) {
        boolean containsSoftHyphen = false;
        for (int i = offset; i <= offset + length; ++i) {
            if (string[i] == (char) 0xAD) {
                containsSoftHyphen = true;
                break;
            }
        }
        if (!containsSoftHyphen) {
            myCanvas.drawText(string, offset, length, x, y, myTextPaint);
            return TextBlock.create(string, offset, length, x, y);
        } else {
            final char[] corrected = new char[length];
            int len = 0;
            for (int o = offset; o < offset + length; ++o) {
                final char chr = string[o];
                if (chr != (char) 0xAD) {
                    corrected[len++] = chr;
                }
            }
            myCanvas.drawText(corrected, 0, len, x, y, myTextPaint);
            return TextBlock.create(corrected, 0, len, x, y);
        }
    }

    @Override
    public Size imageSize(ZLImageData imageData, Size maxSize, ScalingType scaling) {
        Timber.v("%s, imageSize", TAG);
        final Bitmap bitmap = ((ZLAndroidImageData) imageData).getBitmap(maxSize, scaling);
        return (bitmap != null && !bitmap.isRecycled())
                ? new Size(bitmap.getWidth(), bitmap.getHeight()) : null;
    }

    @Override
    public void drawImage(int x, int y, ZLImageData imageData, Size maxSize, ScalingType scaling, ColorAdjustingMode adjustingMode) {
        Timber.v("%s, drawImage", TAG);
        if (myCanvas == null) return;
        final Bitmap bitmap = ((ZLAndroidImageData) imageData).getBitmap(maxSize, scaling);
        if (bitmap != null && !bitmap.isRecycled()) {
            switch (adjustingMode) {
                case LIGHTEN_TO_BACKGROUND:
                    myFillPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.LIGHTEN));
                    break;
                case DARKEN_TO_BACKGROUND:
                    myFillPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DARKEN));
                    break;
                case NONE:
                    break;
            }
            myCanvas.drawBitmap(bitmap, x, y - bitmap.getHeight(), myFillPaint);
            myFillPaint.setXfermode(null);
        }
    }

    @Override
    public void drawLine(int x0, int y0, int x1, int y1) {
        Timber.v("%s, drawLine", TAG);
        if (myCanvas == null) return;
        final Canvas canvas = myCanvas;
        final Paint paint = myLinePaint;
        paint.setAntiAlias(false);
        canvas.drawLine(x0, y0, x1, y1, paint);
        canvas.drawPoint(x0, y0, paint);
        canvas.drawPoint(x1, y1, paint);
        paint.setAntiAlias(true);
    }

    @Override
    public void fillRectangle(int x0, int y0, int x1, int y1) {
        Timber.v("%s, fillRectangle", TAG);
        if (myCanvas == null) return;
        if (x1 < x0) {
            int swap = x1;
            x1 = x0;
            x0 = swap;
        }
        if (y1 < y0) {
            int swap = y1;
            y1 = y0;
            y0 = swap;
        }
        myCanvas.drawRect(x0, y0, x1 + 1, y1 + 1, myFillPaint);
    }

    @Override
    public void drawHeader(int x, int y, String title) {
        Timber.v("%s, drawHeader", TAG);
        if (myCanvas == null) return;
        myCanvas.drawText(title, x, y, myExtraPaint);
    }

    @Override
    public void drawFooter(int x, int y, String progress) {
        Timber.v("%s, drawFooter", TAG);
        if (myCanvas == null) return;
        myCanvas.drawText(progress, x, y, myExtraPaint);
    }

    @Override
    public void fillCircle(int x, int y, int radius) {
        Timber.v("%s, fillCircle", TAG);
        if (myCanvas == null) return;
        myCanvas.drawCircle(x, y, radius, myFillPaint);
    }

    @Override
    public void drawBookMark(int x0, int y0, int x1, int y1) {
        Timber.v("%s, drawBookMark", TAG);
        if (myCanvas == null) return;
        myPath.reset();
        myPath.moveTo(x0, y0);
        myPath.lineTo(x1, y0);
        myPath.lineTo(x1, y1);
        myPath.lineTo((x1 + x0) / 2f, y1 - (y1 - y0) / 5f);
        myPath.lineTo(x0, y1);
        myPath.close();
        myCanvas.drawPath(myPath, myFillPaint);
    }

    @Override
    public void setExtraFoot(int textSize, ZLColor color) {
        Timber.v("%s, setExtraFoot", TAG);
        myExtraPaint.setTextSize(textSize);
        myExtraPaint.setARGB(255, color.Red, color.Green, color.Blue);
    }

    private void printDebugStr(char[] chars, int start, int len, int x, int y) {
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < start + len; i++) {
            sb.append(chars[i]);
        }
        Timber.v("ceshi123, draw: %s, [%s, %s], total = %s", sb, x, y, chars.length);
    }
}