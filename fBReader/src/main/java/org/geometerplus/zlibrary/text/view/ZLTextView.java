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

package org.geometerplus.zlibrary.text.view;

import org.geometerplus.DebugHelper;
import org.geometerplus.fbreader.book.Bookmark;
import org.geometerplus.fbreader.fbreader.BookmarkHighlighting;
import org.geometerplus.zlibrary.core.application.ZLApplication;
import org.geometerplus.zlibrary.core.filesystem.ZLFile;
import org.geometerplus.zlibrary.core.util.RationalNumber;
import org.geometerplus.zlibrary.core.util.ZLColor;
import org.geometerplus.zlibrary.core.view.Hull;
import org.geometerplus.zlibrary.core.view.SelectionCursor;
import org.geometerplus.zlibrary.core.view.ZLPaintContext;
import org.geometerplus.zlibrary.text.hyphenation.ZLTextHyphenationInfo;
import org.geometerplus.zlibrary.text.hyphenation.ZLTextHyphenator;
import org.geometerplus.zlibrary.text.model.ZLTextAlignmentType;
import org.geometerplus.zlibrary.text.model.ZLTextMark;
import org.geometerplus.zlibrary.text.model.ZLTextModel;
import org.geometerplus.zlibrary.text.model.ZLTextParagraph;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import timber.log.Timber;

public abstract class ZLTextView extends ZLTextViewBase {

    public static final int SCROLLBAR_HIDE = 0;
    public static final int SCROLLBAR_SHOW = 1;
    public static final int SCROLLBAR_SHOW_AS_PROGRESS = 2;
    private static final char[] ourDefaultLetters = "System developers have used modeling languages for decades to specify, visualize, construct, and document systems. The Unified Modeling Language (UML) is one of those languages. UML makes it possible for team members to collaborate by providing a common language that applies to a multitude of different systems. Essentially, it enables you to communicate solutions in a consistent, tool-supported language.".toCharArray();
    private static final char[] SPACE = new char[]{' '};
    private final HashMap<ZLTextLineInfo, ZLTextLineInfo> myLineInfoCache = new HashMap<ZLTextLineInfo, ZLTextLineInfo>();
    public final ZLTextSelection mySelection = new ZLTextSelection(this);
    private final Set<ZLTextHighlighting> myHighlightingList = Collections.synchronizedSet(new TreeSet<>());
    private final Set<ZLTextHighlighting> myBookMarkList = Collections.synchronizedSet(new TreeSet<>());
    private final char[] myLettersBuffer = new char[512];
    private ZLTextModel myTextModel;
    private int myScrollingMode;
    private int myOverlappingValue;
    private ZLTextPage myPreviousPage = new ZLTextPage();
    private ZLTextPage myCurrentPage = new ZLTextPage();
    private ZLTextPage myNextPage = new ZLTextPage();
    private ZLTextRegion.Soul myOutlinedRegionSoul;
    private boolean myShowOutline = true;
    private CursorManager myCursorManager; // LRU缓存
    private int myLettersBufferLength = 0;
    private ZLTextModel myLettersModel = null;
    private float myCharWidth = -1f;
    private volatile ZLTextWord myCachedWord;
    private volatile ZLTextHyphenationInfo myCachedInfo;

    public ZLTextView(ZLApplication application) {
        super(application);
    }

    public final ZLTextModel getModel() {
        return myTextModel;
    }

    /**
     * 初始化textModel, 并通过{@link #getParagraphCursor(int)}获得图书第一个paragraph的对象,
     * 并将myCurrentPage设为该对象
     *
     * @param textModel 通过cpp解析出来的包含图书解析信息的model
     */
    public synchronized void setTextModel(ZLTextModel textModel) {
        // 这是个LRU, 缓存ZLTextParagraphCursor
        this.myCursorManager = textModel != null ? new CursorManager(textModel, getExtensionManager()) : null;

        mySelection.clear();
        myHighlightingList.clear();

        this.myTextModel = textModel;
        this.myCurrentPage.reset();
        this.myPreviousPage.reset();
        this.myNextPage.reset();
        // 负责定位到指定的段落
        // 定位的过程主要是维护ZLTextPage类中的StartCursor属性指向的ZLTextWordCursor类
        // ZLTextWordCursor类中的三个属性myParagraphCursor、myElementIndex、myCharIndex结合起来就完成来了定位到指定段落的流程。
        // 这三个属性中，myParagraphCursor属性指向的就是代表指定段落的ZLTextParagraphCursor类
        if (myTextModel != null) {
            final int paragraphsNumber = myTextModel.getParagraphsNumber();
            if (paragraphsNumber > 0) {
                // 先将cursor定位到第一页
                // 从cursorManager LRU中获得第一个解析缓存文件的cursor, 因为此时是cursorManager是空的, 所以缓存解析文件肯定包括了第一章解析内容
                ZLTextParagraphCursor start = getParagraphCursor(0);
                Timber.v("打开图书:渲染流程, 初始化startCursor: %s", start.toString());
                // 重置currentPage的信息
                myCurrentPage.moveStartCursor(start);
            }
        }
        // 重置所有缓存, 我们会使用BitmapManager缓存4个bitmap对象
        Application.getViewWidget().reset("setTextModel");
    }

    protected abstract ExtensionElementManager getExtensionManager();

    public ZLTextWordCursor getStartCursor() {
        if (myCurrentPage.startCursor.isNull()) {
            preparePaintInfo(myCurrentPage, "getStartCursor");
        }
        return myCurrentPage.startCursor;
    }

    public ZLTextWordCursor getEndCursor() {
        if (myCurrentPage.endCursor.isNull()) {
            preparePaintInfo(myCurrentPage, "getStartCursor");
        }
        return myCurrentPage.endCursor;
    }

    private synchronized void gotoMark(ZLTextMark mark) {
        if (mark == null) {
            return;
        }

        myPreviousPage.reset();
        myNextPage.reset();
        boolean doRepaint = false;
        if (myCurrentPage.startCursor.isNull()) {
            doRepaint = true;
            preparePaintInfo(myCurrentPage, "getStartCursor");
        }
        if (myCurrentPage.startCursor.isNull()) {
            return;
        }
        if (myCurrentPage.startCursor.getParagraphIndex() != mark.ParagraphIndex ||
                myCurrentPage.startCursor.getMark().compareTo(mark) > 0) {
            doRepaint = true;
            gotoPosition(mark.ParagraphIndex, 0, 0);
            preparePaintInfo(myCurrentPage, "getStartCursor");
        }
        if (myCurrentPage.endCursor.isNull()) {
            preparePaintInfo(myCurrentPage, "getStartCursor");
        }
        while (mark.compareTo(myCurrentPage.endCursor.getMark()) > 0) {
            doRepaint = true;
            turnPage(true, ScrollingMode.NO_OVERLAPPING, 0);
            preparePaintInfo(myCurrentPage, "getStartCursor");
        }
        if (doRepaint) {
            if (myCurrentPage.startCursor.isNull()) {
                preparePaintInfo(myCurrentPage, "getStartCursor");
            }
            repaint("gotoMark");
        }
    }

    public synchronized void gotoHighlighting(ZLTextHighlighting highlighting) {
        myPreviousPage.reset();
        myNextPage.reset();
        boolean doRepaint = false;
        if (myCurrentPage.startCursor.isNull()) {
            doRepaint = true;
            preparePaintInfo(myCurrentPage, "gotoHighlighting");
        }
        if (myCurrentPage.startCursor.isNull()) {
            return;
        }
        if (!highlighting.intersects(myCurrentPage)) {
            gotoPosition(highlighting.getStartPosition().getParagraphIndex(), 0, 0);
            preparePaintInfo(myCurrentPage, "gotoHighlighting");
        }
        if (myCurrentPage.endCursor.isNull()) {
            preparePaintInfo(myCurrentPage, "gotoHighlighting");
        }
        while (!highlighting.intersects(myCurrentPage)) {
            doRepaint = true;
            turnPage(true, ScrollingMode.NO_OVERLAPPING, 0);
            preparePaintInfo(myCurrentPage, "gotoHighlighting");
        }
        if (doRepaint) {
            if (myCurrentPage.startCursor.isNull()) {
                preparePaintInfo(myCurrentPage, "gotoHighlighting");
            }
            repaint("gotoHighlighting");
        }
    }

    public synchronized int search(final String text, boolean ignoreCase, boolean wholeText, boolean backward, boolean thisSectionOnly) {
        if (myTextModel == null || text.length() == 0) {
            return 0;
        }
        int startIndex = 0;
        int endIndex = myTextModel.getParagraphsNumber();
        if (thisSectionOnly) {
            // TODO: implement
        }
        int count = myTextModel.search(text, startIndex, endIndex, ignoreCase);
        myPreviousPage.reset();
        myNextPage.reset();
        if (!myCurrentPage.startCursor.isNull()) {
            rebuildPaintInfo();
            if (count > 0) {
                ZLTextMark mark = myCurrentPage.startCursor.getMark();
                gotoMark(wholeText ?
                        (backward ? myTextModel.getLastMark() : myTextModel.getFirstMark()) :
                        (backward ? myTextModel.getPreviousMark(mark) : myTextModel.getNextMark(mark)));
            }
            repaint("search");
        }
        return count;
    }

    public boolean canFindNext() {
        final ZLTextWordCursor end = myCurrentPage.endCursor;
        return !end.isNull() && (myTextModel != null) && (myTextModel.getNextMark(end.getMark()) != null);
    }

    public synchronized void findNext() {
        final ZLTextWordCursor end = myCurrentPage.endCursor;
        if (!end.isNull()) {
            gotoMark(myTextModel.getNextMark(end.getMark()));
        }
    }

    public boolean canFindPrevious() {
        final ZLTextWordCursor start = myCurrentPage.startCursor;
        return !start.isNull() && (myTextModel != null) && (myTextModel.getPreviousMark(start.getMark()) != null);
    }

    public synchronized void findPrevious() {
        final ZLTextWordCursor start = myCurrentPage.startCursor;
        if (!start.isNull()) {
            gotoMark(myTextModel.getPreviousMark(start.getMark()));
        }
    }

    public void clearFindResults() {
        if (!findResultsAreEmpty()) {
            myTextModel.removeAllMarks();
            rebuildPaintInfo();
            repaint("clearFindResults");
        }
    }

    public boolean findResultsAreEmpty() {
        return myTextModel == null || myTextModel.getMarks().isEmpty();
    }

    protected synchronized void rebuildPaintInfo() {
        myPreviousPage.reset();
        myNextPage.reset();
        if (myCursorManager != null) {
            myCursorManager.evictAll();
        }

        if (!myCurrentPage.isClearPaintState()) {
            myCurrentPage.lineInfos.clear();
            if (!myCurrentPage.startCursor.isNull()) {
                myCurrentPage.startCursor.rebuild();
                myCurrentPage.endCursor.reset();
                myCurrentPage.paintState = PaintStateEnum.START_IS_KNOWN;
            } else if (!myCurrentPage.endCursor.isNull()) {
                myCurrentPage.endCursor.rebuild();
                myCurrentPage.startCursor.reset();
                myCurrentPage.paintState = PaintStateEnum.END_IS_KNOWN;
            }
        }

        myLineInfoCache.clear();
    }

    public void highlight(ZLTextPosition start, ZLTextPosition end) {
        removeHighlightings(ZLTextManualHighlighting.class);
        addHighlighting(new ZLTextManualHighlighting(this, start, end));
    }

    public boolean removeHighlightings(Class<? extends ZLTextHighlighting> type) {
        boolean result = false;
        synchronized (myHighlightingList) {
            for (Iterator<ZLTextHighlighting> it = myHighlightingList.iterator(); it.hasNext(); ) {
                final ZLTextHighlighting h = it.next();
                if (type.isInstance(h)) {
                    it.remove();
                    result = true;
                }
            }
        }
        return result;
    }

    public final void addHighlighting(ZLTextHighlighting h) {
        myHighlightingList.add(h);
        repaint("addHighlighting");
    }

    public final void addBookMark(ZLTextHighlighting h) {
        myBookMarkList.add(h);
        repaint("addBookMark");
    }

    /**
     * 重绘
     */
    public void repaint(String from) {
        Application.getViewWidget().reset(from);
        Application.getViewWidget().repaint(from);
    }

    public boolean removeMarkHighlight(Class<? extends ZLTextHighlighting> type) {
        boolean result = false;
        synchronized (myBookMarkList) {
            for (Iterator<ZLTextHighlighting> it = myBookMarkList.iterator(); it.hasNext(); ) {
                final ZLTextHighlighting h = it.next();
                if (type.isInstance(h)) {
                    it.remove();
                    result = true;
                }
            }
        }
        return result;
    }

    public final void addHighlightings(Collection<ZLTextHighlighting> hilites) {
        myHighlightingList.addAll(hilites);
        repaint("addHighlightings");
    }

    public void clearHighlighting() {
        if (removeHighlightings(ZLTextManualHighlighting.class)) {
            repaint("clearHighlighting");
        }
    }

    protected void moveSelectionCursorTo(SelectionCursor.Which which, int x, int y, String from) {
        Timber.v("长按选中流程[%s], moveSelectionCursorTo: %s", from, getSelectionDebug());
        y -= getTextStyleCollection().getBaseStyle().getFontSize() / 2;
        mySelection.setCursorInMovement(which, x, y);
        mySelection.expandTo(myCurrentPage, x, y);
        repaint("moveSelectionCursorTo");
    }

    protected boolean moveSelectionCursorToFlutter(SelectionCursor.Which which, int x, int y) {
        Timber.v("长按选中流程, %s", getSelectionDebug());
        y -= getTextStyleCollection().getBaseStyle().getFontSize() / 2;
        mySelection.setCursorInMovement(which, x, y);
        return mySelection.expandToFlutter(myCurrentPage, x, y);
    }

    protected void releaseSelectionCursor() {
        mySelection.stop();
        repaint("releaseSelectionCursor");
    }

    protected void releaseSelectionCursorFlutter() {
        mySelection.stop();
    }

    protected SelectionCursor.Which getSelectionCursorInMovement() {
        return mySelection.getCursorInMovement();
    }

    protected SelectionCursor.Which findSelectionCursor(int x, int y) {
        return findSelectionCursor(x, y, Float.MAX_VALUE);
    }

    protected SelectionCursor.Which findSelectionCursor(int x, int y, float maxDistance2) {
        if (mySelection.isEmpty()) {
            return null;
        }

        final float leftDistance2 = distance2ToCursor(x, y, SelectionCursor.Which.Left);
        final float rightDistance2 = distance2ToCursor(x, y, SelectionCursor.Which.Right);

        if (rightDistance2 < leftDistance2) {
            return rightDistance2 <= maxDistance2 ? SelectionCursor.Which.Right : null;
        } else {
            return leftDistance2 <= maxDistance2 ? SelectionCursor.Which.Left : null;
        }
    }

    private float distance2ToCursor(int x, int y, SelectionCursor.Which which) {
        final ZLTextSelection.Point point = getSelectionCursorPoint(myCurrentPage, which);
        if (point == null) {
            return Float.MAX_VALUE;
        }
        final float dX = x - point.X;
        final float dY = y - point.Y;
        return dX * dX + dY * dY;
    }

    private ZLTextSelection.Point getSelectionCursorPoint(ZLTextPage page, SelectionCursor.Which which) {
        if (which == null) {
            return null;
        }

//        if (which == mySelection.getCursorInMovement()) {
//            Timber.v("小耳朵， 1");
//            return mySelection.getCursorInMovementPoint();
//        }

        // 左侧小耳朵
        if (which == SelectionCursor.Which.Left) {
            if (mySelection.hasPartBeforePage(page)) {
                return null;
            }
            final ZLTextElementArea area = mySelection.getStartArea(page);
            if (area != null) {
                return new ZLTextSelection.Point(area.XStart, (area.YStart + area.YEnd) / 2);
            }
        } else {
            // 右侧小耳朵
            if (mySelection.hasPartAfterPage(page)) {
                return null;
            }
            final ZLTextElementArea area = mySelection.getEndArea(page);
            if (area != null) {
                return new ZLTextSelection.Point(area.XEnd, (area.YStart + area.YEnd) / 2);
            }
        }
        return null;
    }

    private void drawSelectionCursor(ZLPaintContext context, ZLTextPage page, SelectionCursor.Which which) {
        final ZLTextSelection.Point pt = getSelectionCursorPoint(page, which);
        if (pt != null) {
            SelectionCursor.draw(context, which, pt.X, pt.Y, getSelectionCursorColor());
        }
    }

    @Override
    public synchronized void preparePage(ZLPaintContext context, PageIndex pageIndex) {
        setContext(context);
        ZLTextPage page = getPage(pageIndex);
        Timber.v("渲染流程[preparePage], %s: %s, %s, %s", pageIndex.name(), page.startCursor, page.endCursor, DebugHelper.getPatinStateStr(page.paintState));
        preparePaintInfo(page, "preparePage");
    }

    @Override
    public synchronized void paint(ZLPaintContext paintContext, PageIndex pageIndex) {
        Timber.v("渲染流程:Bitmap绘制, ================================ 开始paint: %s================================", pageIndex.name());
        setContext(paintContext);
        // 绘制背景
        final ZLFile wallpaper = getWallpaperFile();
        if (wallpaper != null) {
            paintContext.clear(wallpaper, getFillMode());
        } else {
            paintContext.clear(getBackgroundColor());
        }

        // 还没有图书数据就不绘制
        if (myTextModel == null || myTextModel.getParagraphsNumber() == 0) {
            Timber.v("渲染流程:Bitmap绘制, myTextModel不存在, 图书没解析, paint结束");
            return;
        }

        Timber.v("渲染流程:Bitmap绘制, myTextModel存在, draw %s, 总paragraphs = %s", pageIndex.name(), myTextModel.getParagraphsNumber());

        // 先根据pageIndex选择page
        ZLTextPage page;
        switch (pageIndex) {
            default:
            case CURRENT:
                page = myCurrentPage;
                break;
            case PREV:
                page = myPreviousPage;
                if (myPreviousPage.isClearPaintState()) {
                    Timber.v("渲染流程:Bitmap绘制, 更新myPreviousPage, currentState= %s", DebugHelper.getPatinStateStr(myCurrentPage.paintState));
                    preparePaintInfo(myCurrentPage, "paint.PREV");
                    myPreviousPage.endCursor.setCursor(myCurrentPage.startCursor);
                    myPreviousPage.paintState = PaintStateEnum.END_IS_KNOWN;
                }
                break;
            case NEXT:
                page = myNextPage;
                if (myNextPage.isClearPaintState()) {
                    Timber.v("渲染流程:Bitmap绘制, 更新myNextPage, currentState= %s", DebugHelper.getPatinStateStr(myCurrentPage.paintState));
                    preparePaintInfo(myCurrentPage, "paint.NEXT");
                    myNextPage.startCursor.setCursor(myCurrentPage.endCursor);
                    myNextPage.paintState = PaintStateEnum.START_IS_KNOWN;
                }
        }

        page.TextElementMap.clear();

        // 从定位指定段落后得到的ZLTextPage类中取出
        // 代表段落中每个字的ZLTextElement子类，计算出每个字应该在屏幕上的哪一行
        preparePaintInfo(page, "paint." + pageIndex.name());

        if (page.startCursor.isNull() || page.endCursor.isNull()) {
            return;
        }

        Timber.v("渲染流程:Bitmap绘制[%s]], ----------------------------- preparePaintInfo完成, 本次需要绘制lineInfoSize = %d, 接下来就是把lineInfo画到bitmap上 -----------------------------", pageIndex.name(), page.lineInfos.size());
        /*
         * 内容 + 高亮
         */
        final List<ZLTextLineInfo> lineInfoList = page.lineInfos;
        final int[] labels = new int[lineInfoList.size() + 1];
        int x = getLeftMargin();
        int y = getTopMargin();
        int columnIndex = 0;
        ZLTextLineInfo prevLineInfo = null;
        for (int i = 0; i < lineInfoList.size(); i++) {
            ZLTextLineInfo info = lineInfoList.get(i);
            info.adjust(prevLineInfo);
            // 进一步计算出每一行中的每一个字在屏幕上的绝对位置
            // 每个字的绝对位置以及显示格式等信息会用y一个ZLTextElementArea类表示
            prepareTextLine(page, info, x, y, columnIndex);
            // 累加每行的行高，以获取下一行的y坐标
            y += info.height + info.descent + info.VSpaceAfter;
            // labels指向的int数组将被用于迭代ZLTextPage类TextElementMap属性
            labels[i + 1] = page.TextElementMap.size();
            if (i + 1 == page.column0Height) {
                // 获取顶部页边距, 作为屏幕上第一行的y坐标
                y = getTopMargin();
                x += page.getTextWidth() + getSpaceBetweenColumns();
                columnIndex = 1;
            }
            prevLineInfo = info;
        }

        // 笔记效果: 计算需要高亮的文字
        // 长按选中效果见findHighlightingList()
        final List<ZLTextHighlighting> highlightingList = findHighlightingList(page, pageIndex.name());

        for (ZLTextHighlighting h : highlightingList) {
            int mode = Hull.DrawMode.None;

            final ZLColor bgColor = h.getBackgroundColor();
            if (bgColor != null) {
                paintContext.setFillColor(bgColor);
                mode |= Hull.DrawMode.Fill;
            }

            final ZLColor outlineColor = h.getOutlineColor();
            if (outlineColor != null) {
                paintContext.setLineColor(outlineColor);
                mode |= Hull.DrawMode.Outline;
            }

            if (mode != Hull.DrawMode.None) {
                h.hull(page).draw(getContext(), mode);
            }
        }

        x = getLeftMargin();
        y = getTopMargin();
        for (int i = 0; i < lineInfoList.size(); i++) {
            ZLTextLineInfo info = lineInfoList.get(i);
            // 利用ZLTextElementArea类中的信息最终将字一个一个画到画布上去
            // 将文字内容和高亮一起绘制
            drawTextLine(page, highlightingList, info, labels[i], labels[i + 1]);
            y += info.height + info.descent + info.VSpaceAfter;
            if (i + 1 == page.column0Height) {
                y = getTopMargin();
                x += page.getTextWidth() + getSpaceBetweenColumns();
            }
        }

        // 绘制outline: 长按图片或者超链接, 会有一个描边效果
        final ZLTextRegion outlinedElementRegion = getOutlinedRegion(page);
        if (outlinedElementRegion != null && myShowOutline) {
            Timber.v("长按选中流程[绘制],  绘制outline, %s", getSelectionBackgroundColor());
            paintContext.setLineColor(getSelectionBackgroundColor());
            outlinedElementRegion.hull().draw(paintContext, Hull.DrawMode.Outline);
        }

        // 绘制选中的左右光标
        drawSelectionCursor(paintContext, page, SelectionCursor.Which.Left);
        drawSelectionCursor(paintContext, page, SelectionCursor.Which.Right);

        // 左上角标题效果: 绘制头部（章节标题）
        paintContext.setExtraFoot((int) (getTopMargin() * 0.375), getExtraColor());
        paintContext.drawHeader(getLeftMargin(), (int) (getTopMargin() / 1.6), getTocText(page.startCursor));

        // 右下角总页码效果: 绘制底部（总页码）
        // TODO: 总页码计算不准确
        if (DebugHelper.FOOTER_PAGE_COUNT_ENABLE) {
            String progressText = getPageProgress();
            int footerX = getContextWidth() - getRightMargin() - paintContext.getExtraStringWidth(progressText);
            int footerY = (int) (getTopMargin() + getTextAreaHeight() + getBottomMargin() / 1.3);
            paintContext.drawFooter(footerX, footerY, progressText);
        }

        // 书签效果: 绘制书签
        final List<ZLTextHighlighting> bookMarkList = findBookMarkList(page);
        paintContext.setFillColor(getBookMarkColor());
        if (!bookMarkList.isEmpty()) {
            paintContext.drawBookMark(getContextWidth() - 100, 0, getContextWidth() - 60, 90);
        }
        Timber.v("渲染流程:Bitmap绘制, ================================ %s paint完成 ================================", pageIndex.name());
    }

    @Override
    public synchronized void onScrollingFinished(PageIndex pageIndex) {
        Timber.v("渲染流程:Bitmap绘制, onScrollingFinished: %s", pageIndex.name());
        switch (pageIndex) {
            case CURRENT:
                break;
            case PREV: {
                final ZLTextPage swap = myNextPage;
                myNextPage = myCurrentPage;
                myCurrentPage = myPreviousPage;
                myPreviousPage = swap;
                myPreviousPage.reset();
                switch (myCurrentPage.paintState) {
                    case PaintStateEnum.NOTHING_TO_PAINT:
                        preparePaintInfo(myNextPage, "onScrollingFinished");
                        myCurrentPage.endCursor.setCursor(myNextPage.startCursor);
                        myCurrentPage.paintState = PaintStateEnum.END_IS_KNOWN;
                        break;
                    case PaintStateEnum.READY:
                        myCurrentPage.endCursor.setCursor(myNextPage.startCursor);
                        myCurrentPage.paintState = PaintStateEnum.END_IS_KNOWN;
                        break;
                }
                break;
            }
            case NEXT: {
                final ZLTextPage swap = myPreviousPage;
                myPreviousPage = myCurrentPage;
                myCurrentPage = myNextPage;
                myNextPage = swap;
                myNextPage.reset();
                switch (myCurrentPage.paintState) {
                    case PaintStateEnum.NOTHING_TO_PAINT:
                        preparePaintInfo(myPreviousPage, "onScrollingFinished");
                        myCurrentPage.startCursor.setCursor(myPreviousPage.endCursor);
                        myCurrentPage.paintState = PaintStateEnum.START_IS_KNOWN;
                        break;
                    case PaintStateEnum.READY:
                        myNextPage.startCursor.setCursor(myCurrentPage.endCursor);
                        myNextPage.paintState = PaintStateEnum.START_IS_KNOWN;
                        break;
                }
                break;
            }
        }
    }

    @Override
    public final boolean isScrollbarShown() {
        return scrollbarType() == SCROLLBAR_SHOW || scrollbarType() == SCROLLBAR_SHOW_AS_PROGRESS;
    }

    public abstract int scrollbarType();

    @Override
    public final synchronized int getScrollbarFullSize() {
        return sizeOfFullText();
    }

    protected final synchronized int sizeOfFullText() {
        if (myTextModel == null || myTextModel.getParagraphsNumber() == 0) {
            return 1;
        }
        return myTextModel.getTextLength(myTextModel.getParagraphsNumber() - 1);
    }

    @Override
    public final synchronized int getScrollbarThumbPosition(PageIndex pageIndex) {
        return scrollbarType() == SCROLLBAR_SHOW_AS_PROGRESS ? 0 : getCurrentCharNumber(pageIndex, true, "getScrollbarThumbPosition");
    }

    @Override
    public final synchronized int getScrollbarThumbLength(PageIndex pageIndex) {
        int start = scrollbarType() == SCROLLBAR_SHOW_AS_PROGRESS
                ? 0 : getCurrentCharNumber(pageIndex, true, "getScrollbarThumbLength");
        int end = getCurrentCharNumber(pageIndex, false, "getScrollbarThumbLength");
        return Math.max(1, end - start);
    }

    @Override
    public boolean canScroll(PageIndex index) {
        switch (index) {
            default:
                return true;
            case NEXT: {
                final ZLTextWordCursor cursor = getEndCursor();
                return cursor != null && !cursor.isNull() && !cursor.isEndOfText();
            }
            case PREV: {
                final ZLTextWordCursor cursor = getStartCursor();
                return cursor != null && !cursor.isNull() && !cursor.isStartOfText();
            }
        }
    }

    /**
     * @return 当前页是否有书签
     */
    public List<Bookmark> getBookMarks() {
        List<Bookmark> bookmarks = new ArrayList<>();
        List<ZLTextHighlighting> bookMarkList = findBookMarkList(myCurrentPage);
        for (ZLTextHighlighting highlighting : bookMarkList) {
            if (highlighting instanceof BookmarkHighlighting) {
                bookmarks.add(((BookmarkHighlighting) highlighting).Bookmark);
            }
        }
        return bookmarks;
    }

    private List<ZLTextHighlighting> findBookMarkList(ZLTextPage page) {
        final LinkedList<ZLTextHighlighting> bookMarkList = new LinkedList<>();
        synchronized (myBookMarkList) {
            for (ZLTextHighlighting h : myBookMarkList) {
                if (h.intersects(page)) {
                    bookMarkList.add(h);
                }
            }
        }
        return bookMarkList;
    }

    /**
     * @return 当前页是否有书签
     */
    public boolean hasBookMark() {
        List<ZLTextHighlighting> bookMarkList = findBookMarkList(myCurrentPage);
        return !bookMarkList.isEmpty();
    }

    protected abstract String getPageProgress();

    private ZLTextPage getPage(PageIndex pageIndex) {
        switch (pageIndex) {
            default:
            case CURRENT:
                return myCurrentPage;
            case PREV:
                return myPreviousPage;
            case NEXT:
                return myNextPage;
        }
    }

    protected final synchronized int sizeOfTextBeforeParagraph(int paragraphIndex) {
        return myTextModel != null ? myTextModel.getTextLength(paragraphIndex - 1) : 0;
    }

    private synchronized int getCurrentCharNumber(PageIndex pageIndex, boolean startNotEndOfPage, String form) {
        if (myTextModel == null || myTextModel.getParagraphsNumber() == 0) {
            return 0;
        }
        final ZLTextPage page = getPage(pageIndex);
        preparePaintInfo(page, form);
        if (startNotEndOfPage) {
            return Math.max(0, sizeOfTextBeforeCursor(page.startCursor));
        } else {
            int end = sizeOfTextBeforeCursor(page.endCursor);
            if (end == -1) {
                end = myTextModel.getTextLength(myTextModel.getParagraphsNumber() - 1) - 1;
            }
            return Math.max(1, end);
        }
    }

    private int sizeOfTextBeforeCursor(ZLTextWordCursor wordCursor) {
        final ZLTextParagraphCursor paragraphCursor = wordCursor.getParagraphCursor();
        if (paragraphCursor == null) {
            return -1;
        }
        final int paragraphIndex = paragraphCursor.paragraphIdx;
        int sizeOfText = myTextModel.getTextLength(paragraphIndex - 1);
        final int paragraphLength = paragraphCursor.getParagraphLength();
        if (paragraphLength > 0) {
            sizeOfText +=
                    (myTextModel.getTextLength(paragraphIndex) - sizeOfText)
                            * wordCursor.getElementIndex()
                            / paragraphLength;
        }
        return sizeOfText;
    }

    /**
     * TODO 方法不精确, 需要重写
     * 计算一页的字符数
     *
     * @return 一页的字符数
     */
    private synchronized float computeCharsPerPage() {
        setTextStyle(getTextStyleCollection().getBaseStyle());

        final int textWidth = getTextColumnWidth();
        final int textHeight = getTextAreaHeight();

        final int num = myTextModel.getParagraphsNumber();
        final int totalTextSize = myTextModel.getTextLength(num - 1);
        final float charsPerParagraph = ((float) totalTextSize) / num;

        final float charWidth = computeCharWidth();

        final int indentWidth = getElementWidth(ZLTextElement.Indent, 0);
        final float effectiveWidth = textWidth - (indentWidth + 0.5f * textWidth) / charsPerParagraph;
        float charsPerLine = Math.min(effectiveWidth / charWidth,
                charsPerParagraph * 1.2f);

        final int strHeight = getWordHeight() + getContext().getDescent();
        final int effectiveHeight = (int)
                (textHeight -
                        (getTextStyle().getSpaceBefore(metrics())
                                + getTextStyle().getSpaceAfter(metrics()) / 2) / charsPerParagraph);
        final int linesPerPage = effectiveHeight / strHeight;

        return charsPerLine * linesPerPage;
    }

    /**
     * TODO 方法不精确, 需要重写
     * 计算页数
     *
     * @return 页数
     */
    private synchronized int computeTextPageNumber(int textSize) {
        if (myTextModel == null || myTextModel.getParagraphsNumber() == 0) {
            return 1;
        }

        final float factor = 1.0f / computeCharsPerPage();
        final float pages = textSize * factor;
        return Math.max((int) (pages + 1.0f - 0.5f * factor), 1);
    }

    private float computeCharWidth() {
        if (myLettersModel != myTextModel) {
            myLettersModel = myTextModel;
            myLettersBufferLength = 0;
            myCharWidth = -1f;

            int paragraph = 0;
            final int textSize = myTextModel.getTextLength(myTextModel.getParagraphsNumber() - 1);
            if (textSize > myLettersBuffer.length) {
                paragraph = myTextModel.findParagraphByTextLength((textSize - myLettersBuffer.length) / 2);
            }
            while (paragraph < myTextModel.getParagraphsNumber()
                    && myLettersBufferLength < myLettersBuffer.length) {
                final ZLTextParagraph.EntryIterator it = myTextModel.getParagraph(paragraph++).iterator();
                while (myLettersBufferLength < myLettersBuffer.length && it.next()) {
                    if (it.getType() == ZLTextParagraph.Entry.TEXT) {
                        final int len = Math.min(it.getTextLength(),
                                myLettersBuffer.length - myLettersBufferLength);
                        System.arraycopy(it.getTextData(), it.getTextOffset(),
                                myLettersBuffer, myLettersBufferLength, len);
                        myLettersBufferLength += len;
                    }
                }
            }

            if (myLettersBufferLength == 0) {
                myLettersBufferLength = Math.min(myLettersBuffer.length, ourDefaultLetters.length);
                System.arraycopy(ourDefaultLetters, 0, myLettersBuffer, 0, myLettersBufferLength);
            }
        }

        if (myCharWidth < 0f) {
            myCharWidth = computeCharWidth(myLettersBuffer, myLettersBufferLength);
        }
        return myCharWidth;
    }

    private float computeCharWidth(char[] pattern, int length) {
        return getContext().getStringWidth(pattern, 0, length) / ((float) length);
    }

    public final synchronized PagePosition pagePosition() {
        int current = computeTextPageNumber(getCurrentCharNumber(PageIndex.CURRENT, false, "pagePosition"));
        int total = computeTextPageNumber(sizeOfFullText());

        if (total > 3) {
            return new PagePosition(current, total);
        }

        preparePaintInfo(myCurrentPage, "pagePosition");
        ZLTextWordCursor cursor = myCurrentPage.startCursor;
        if (cursor.isNull()) {
            return new PagePosition(current, total);
        }

        if (cursor.isStartOfText()) {
            current = 1;
        } else {
            ZLTextWordCursor prevCursor = myPreviousPage.startCursor;
            if (prevCursor.isNull()) {
                preparePaintInfo(myPreviousPage, "pagePosition");
                prevCursor = myPreviousPage.startCursor;
            }
            if (!prevCursor.isNull()) {
                current = prevCursor.isStartOfText() ? 2 : 3;
            }
        }

        total = current;
        cursor = myCurrentPage.endCursor;
        if (cursor.isNull()) {
            return new PagePosition(current, total);
        }
        if (!cursor.isEndOfText()) {
            ZLTextWordCursor nextCursor = myNextPage.endCursor;
            if (nextCursor.isNull()) {
                preparePaintInfo(myNextPage, "pagePosition");
                nextCursor = myNextPage.endCursor;
            }
            total += nextCursor.isEndOfText() ? 1 : 2;
        }

        return new PagePosition(current, total);
    }

    public final RationalNumber getProgress() {
        final PagePosition position = pagePosition();
        return RationalNumber.create(position.Current, position.Total);
    }

    public final synchronized void gotoPage(int page) {
        if (myTextModel == null || myTextModel.getParagraphsNumber() == 0) {
            return;
        }

        final float factor = computeCharsPerPage();
        final float textSize = page * factor;

        int intTextSize = (int) textSize;
        int paragraphIndex = myTextModel.findParagraphByTextLength(intTextSize);

        if (paragraphIndex > 0 && myTextModel.getTextLength(paragraphIndex) > intTextSize) {
            --paragraphIndex;
        }
        intTextSize = myTextModel.getTextLength(paragraphIndex);

        int sizeOfTextBefore = myTextModel.getTextLength(paragraphIndex - 1);
        while (paragraphIndex > 0 && intTextSize == sizeOfTextBefore) {
            --paragraphIndex;
            intTextSize = sizeOfTextBefore;
            sizeOfTextBefore = myTextModel.getTextLength(paragraphIndex - 1);
        }

        final int paragraphLength = intTextSize - sizeOfTextBefore;

        final int wordIndex;
        if (paragraphLength == 0) {
            wordIndex = 0;
        } else {
            preparePaintInfo(myCurrentPage, "gotoPage");
            final ZLTextWordCursor cursor = new ZLTextWordCursor(myCurrentPage.endCursor);
            cursor.moveToParagraph(paragraphIndex);
            wordIndex = cursor.getParagraphCursor().getParagraphLength();
        }

        gotoPositionByEnd(paragraphIndex, wordIndex, 0);
    }

    public void gotoHome() {
        final ZLTextWordCursor cursor = getStartCursor();
        if (!cursor.isNull() && cursor.isStartOfParagraph() && cursor.getParagraphIndex() == 0) {
            return;
        }
        gotoPosition(0, 0, 0);
        preparePaintInfo();
    }

    /** 长按选中效果 */
    private List<ZLTextHighlighting> findHighlightingList(ZLTextPage page, String from) {
        Timber.v("长按选中流程: 匹配选中区域, %s, %s", from, getSelectionDebug());
        final LinkedList<ZLTextHighlighting> highlightingList = new LinkedList<>();
        // 长按选中高亮效果
        if (mySelection.intersects(page)) {
            highlightingList.add(mySelection);
        }
        // 笔记高亮效果
        synchronized (myHighlightingList) {
            for (ZLTextHighlighting h : myHighlightingList) {
                if (h.intersects(page)) {
                    highlightingList.add(h);
                }
            }
        }
        return highlightingList;
    }

    protected abstract ZLPaintContext.ColorAdjustingMode getAdjustingModeForImages();

    /**
     * 绘制文本行
     *
     * @param page             页面
     * @param highlightingList 高亮列表
     * @param info             文本行信息
     * @param from             起始索引
     * @param to               结束索引
     */
    private void drawTextLine(ZLTextPage page, List<ZLTextHighlighting> highlightingList, ZLTextLineInfo info, int from, int to) {
        final ZLPaintContext context = getContext();
        final ZLTextParagraphCursor paragraph = info.paragraphCursor;
        int index = from;
        final int endElementIndex = info.endElementIndex;
        int charIndex = info.realStartCharIndex;
        final List<ZLTextElementArea> pageAreas = page.TextElementMap.areas();
        if (to > pageAreas.size()) {
            return;
        }
        for (int wordIndex = info.realStartElementIndex; wordIndex != endElementIndex && index < to; ++wordIndex, charIndex = 0) {
            // 获取对应当前字的ZLTextWord类
            final ZLTextElement element = paragraph.getElement(wordIndex);
            // 获取对应当前字的ZLTextElementArea类
            final ZLTextElementArea area = pageAreas.get(index);
            // 当ZLTextWord类与ZLTextElementArea类对应时进行操作
            // 保证跳过代表标签的ZLTextControlElement类
            if (element == area.Element) {
                ++index;
                if (area.ChangeStyle) {
                    setTextStyle(area.Style);
                }
                // 起始X坐标
                final int areaX = area.XStart;
                // 起始Y坐标
                final int areaY = area.YEnd - getElementDescent(element) - getTextStyle().getVerticalAlign(metrics());
                // 根据元素类型处理
                if (element instanceof ZLTextWord) { // 文本文字
                    // 文本位置信息
                    final ZLTextPosition pos = new ZLTextFixedPosition(info.paragraphCursor.paragraphIdx, wordIndex, 0);
                    // 文本高亮信息
                    final ZLTextHighlighting hl = getWordHighlighting(pos, highlightingList);
                    // 高亮前景色
                    final ZLColor hlColor = hl != null ? hl.getForegroundColor() : null;
                    // 绘制文字
                    drawWord(
                            areaX, areaY, (ZLTextWord) element, charIndex, -1, false,
                            hlColor != null ? hlColor : getTextColor(getTextStyle().Hyperlink)
                    );
                } else if (element instanceof ZLTextImageElement) {
                    final ZLTextImageElement imageElement = (ZLTextImageElement) element;
                    context.drawImage(
                            areaX, areaY,
                            imageElement.ImageData,
                            getTextAreaSize(),
                            getScalingType(imageElement),
                            getAdjustingModeForImages()
                    );
                } else if (element instanceof ZLTextVideoElement) {
                    context.setLineColor(getTextColor(ZLTextHyperlink.NO_LINK));
                    context.setFillColor(new ZLColor(127, 127, 127));
                    final int xStart = area.XStart + 10;
                    final int xEnd = area.XEnd - 10;
                    final int yStart = area.YStart + 10;
                    final int yEnd = area.YEnd - 10;
                    context.fillRectangle(xStart, yStart, xEnd, yEnd);
                    context.drawLine(xStart, yStart, xStart, yEnd);
                    context.drawLine(xStart, yEnd, xEnd, yEnd);
                    context.drawLine(xEnd, yEnd, xEnd, yStart);
                    context.drawLine(xEnd, yStart, xStart, yStart);
                    final int l = xStart + (xEnd - xStart) * 7 / 16;
                    final int r = xStart + (xEnd - xStart) * 10 / 16;
                    final int t = yStart + (yEnd - yStart) * 2 / 6;
                    final int b = yStart + (yEnd - yStart) * 4 / 6;
                    final int c = yStart + (yEnd - yStart) / 2;
                    context.setFillColor(new ZLColor(196, 196, 196));
                    context.fillPolygon(new int[]{l, l, r}, new int[]{t, b, c});
                } else if (element instanceof ExtensionElement) {
                    ((ExtensionElement) element).draw(context, area);
                } else if (element == ZLTextElement.HSpace || element == ZLTextElement.NBSpace) {
                    final int cw = context.getSpaceWidth();
                    for (int len = 0; len < area.XEnd - area.XStart; len += cw) {
                        context.drawString(areaX + len, areaY, SPACE, 0, 1);
                    }
                }
            }
        }
        if (index != to) {
            ZLTextElementArea area = pageAreas.get(index++);
            if (area.ChangeStyle) {
                setTextStyle(area.Style);
            }
            final int start = info.startElementIndex == info.endElementIndex
                    ? info.startCharIndex : 0;
            final int len = info.endCharIndex - start;
            final ZLTextWord word = (ZLTextWord) paragraph.getElement(info.endElementIndex);
            final ZLTextPosition pos =
                    new ZLTextFixedPosition(info.paragraphCursor.paragraphIdx, info.endElementIndex, 0);
            final ZLTextHighlighting hl = getWordHighlighting(pos, highlightingList);
            final ZLColor hlColor = hl != null ? hl.getForegroundColor() : null;
            drawWord(
                    area.XStart, area.YEnd - context.getDescent() - getTextStyle().getVerticalAlign(metrics()),
                    word, start, len, area.AddHyphenationSign,
                    hlColor != null ? hlColor : getTextColor(getTextStyle().Hyperlink)
            );
        }
    }

    /**
     * 获取文字高亮
     *
     * @param pos              位置信息
     * @param highlightingList 高亮列表
     * @return 文字高亮信息
     */
    private ZLTextHighlighting getWordHighlighting(ZLTextPosition pos, List<ZLTextHighlighting> highlightingList) {
        for (ZLTextHighlighting h : highlightingList) {
            // 如果文本位置在高亮列表的位置里，返回该高亮信息
            if (h.getStartPosition() == null) return  null;
            if (h.getStartPosition().compareToIgnoreChar(pos) <= 0 && pos.compareToIgnoreChar(h.getEndPosition()) <= 0) {
                return h;
            }
        }
        return null;
    }

    // 这个是分页算法: 根据可绘制区域高度往里填充textLine,
    // startCursor/endCursor确定填充几个段落
    private void buildInfos(ZLTextPage page, ZLTextWordCursor startCursor, ZLTextWordCursor endCursor, String from) {
        if (DebugHelper.filterTag(from, "paint")){
            Timber.v("渲染流程:分页[%s], 开始分页 -> start = %s, \nend = %s", from, startCursor, endCursor);
        }
        endCursor.setCursor(startCursor);
        // 屏幕能显示的总高度
        int textAreaHeight = page.getTextHeight();
        if (DebugHelper.filterTag(from, "paint")) {
            Timber.v("渲染流程:分页, 0. 可渲染高度, %d", textAreaHeight);
        }
        page.lineInfos.clear();
        page.column0Height = 0;
        boolean nextParagraph;
        ZLTextLineInfo currentLineInfo = null;
        do {
            final ZLTextLineInfo previousLineInfo = currentLineInfo;
            // 恢复到基本样式
            resetTextStyle();
            // 要处理的paragraph数据
            final ZLTextParagraphCursor endParagraphCursor = endCursor.getParagraphCursor();
            final int wordIndex = endCursor.getElementIndex();
            applyStyleChanges(endParagraphCursor, 0, wordIndex, from);
            currentLineInfo = new ZLTextLineInfo(endParagraphCursor, wordIndex, endCursor.getCharIndex(), getTextStyle());
            if (DebugHelper.filterTag(from, "paint")) {
                Timber.v("渲染流程:分页, 1. 准备配置style, %s, wordIndex = %d",
                        endParagraphCursor,
                        wordIndex);
            }
            // endIdx就是elementSize
            final int lastElementIndex = currentLineInfo.paragraphCursorLength;
            while (currentLineInfo.endElementIndex != lastElementIndex) {
                // 获取该行的信息，包括行高，左右缩进，包含哪些字等信息

                int debugElementIdx = currentLineInfo.endElementIndex;
                int debugCharIdx = currentLineInfo.endCharIndex;
                currentLineInfo = processTextLine(page, endParagraphCursor,
                        currentLineInfo.endElementIndex, currentLineInfo.endCharIndex, lastElementIndex, previousLineInfo, from);
//                if (DebugHelper.filterTag(from, "paint")) {
                    Timber.v("渲染流程:分页[%s], 2. element in textLine processed {endElementIndex = %d -> %d, endCharIndex = %d -> %d}",
                            from,
                            debugElementIdx, currentLineInfo.endElementIndex,
                            debugCharIdx, currentLineInfo.endCharIndex);
//                }
                // textAreaHeight递减，代表屏幕上能显示的总高度在不断减小
                textAreaHeight -= currentLineInfo.height + currentLineInfo.descent;
                // 当textAreaHeight < 0, 就代表屏幕y已经被填充满了
                if (textAreaHeight < 0 && page.lineInfos.size() > page.column0Height) {
                    // 处理双列的情况
                    if (page.column0Height == 0 && page.twoColumnView()) {
                        textAreaHeight = page.getTextHeight();
                        textAreaHeight -= currentLineInfo.height + currentLineInfo.descent;
                        page.column0Height = page.lineInfos.size();
                    } else {
                        break;
                    }
                }
                textAreaHeight -= currentLineInfo.VSpaceAfter;
                // 每一行的字就是下一行的第一个字
                endCursor.moveTo(currentLineInfo.endElementIndex, currentLineInfo.endCharIndex);
                // 保存每一行的ZLTextLineInfo类将被加入到ZLTextPage类的LineInfos属性中去
                page.lineInfos.add(currentLineInfo);
                if (textAreaHeight < 0) {
                    if (page.column0Height == 0 && page.twoColumnView()) {
                        textAreaHeight = page.getTextHeight();
                        page.column0Height = page.lineInfos.size();
                    } else {
                        break;
                    }
                }
            }
            // 如果当前段落的内容被全部读完时，代码就自动定位到下一个段落
            nextParagraph = endCursor.isEndOfParagraph() && endCursor.nextParagraph();
            if (nextParagraph && endCursor.getParagraphCursor().isEndOfSection()) {
                if (page.column0Height == 0 && page.twoColumnView() && !page.lineInfos.isEmpty()) {
                    textAreaHeight = page.getTextHeight();
                    page.column0Height = page.lineInfos.size();
                }
            }
        } while (nextParagraph && textAreaHeight >= 0 &&
                (!endCursor.getParagraphCursor().isEndOfSection() ||
                        page.lineInfos.size() == page.column0Height)
        );
        resetTextStyle();
    }

    private boolean isHyphenationPossible() {
        return getTextStyleCollection().getBaseStyle().AutoHyphenationOption.getValue()
                && getTextStyle().allowHyphenations();
    }

    private synchronized ZLTextHyphenationInfo getHyphenationInfo(ZLTextWord word) {
        if (myCachedWord != word) {
            myCachedWord = word;
            myCachedInfo = ZLTextHyphenator.Instance().getInfo(word);
        }
        return myCachedInfo;
    }

    private ZLTextLineInfo processTextLine(
            ZLTextPage page,
            ZLTextParagraphCursor paragraphCursor,
            final int startIndex,
            final int startCharIndex,
            final int endIndex,
            ZLTextLineInfo previousInfo,
            String from
    ) {
        // 新建一个ZLTextLineInfo类，代表这一行的信息
        // 当前在ArrayList中的偏移量startIndex会被存储到RealStartElementIndex属性中去
        // 用来代表这一行的第一个字位置
        final ZLTextLineInfo info = processTextLineInternal(
                page, paragraphCursor, startIndex, startCharIndex, endIndex, previousInfo, from
        );
        if (info.endElementIndex == startIndex && info.endCharIndex == startCharIndex) {
            info.endElementIndex = paragraphCursor.getParagraphLength();
            info.endCharIndex = 0;
            // TODO: add error element
        }
        return info;
    }

    private ZLTextLineInfo processTextLineInternal(
            ZLTextPage page,
            ZLTextParagraphCursor paragraphCursor,
            final int startIndex,
            final int startCharIndex,
            final int endIndex,
            ZLTextLineInfo previousInfo,
            String from
    ) {
        final ZLTextLineInfo info = new ZLTextLineInfo(paragraphCursor, startIndex, startCharIndex, getTextStyle());
        final ZLTextLineInfo cachedInfo = myLineInfoCache.get(info);

        if (cachedInfo != null) {
            cachedInfo.adjust(previousInfo);
            applyStyleChanges(paragraphCursor, startIndex, cachedInfo.endElementIndex, "processTextLineInternal");
            return cachedInfo;

        } else {

            int currentElementIndex = startIndex;
            int currentCharIndex = startCharIndex;
            final boolean isFirstLine = startIndex == 0 && startCharIndex == 0;
            // 当startIndex为0时，判断当前位置处于要显示的段落的起始位置
            // 如果textLine开头
            if (isFirstLine) {
                // 先配置所有的style,
                // 跳过代表标签信息的ZLTextControlElement类
                ZLTextElement element = paragraphCursor.getElement(currentElementIndex);
                while (isStyleChangeElement(element)) {
                    // 读取位于段落开头代表标签信息的ZLTextControlElement类，
                    // 获得标签对应样式
                    applyStyleChangeElement(element);
                    currentElementIndex++;
                    currentCharIndex = 0;
                    if (currentElementIndex == endIndex) {
                        break;
                    }
                    element = paragraphCursor.getElement(currentElementIndex);
                }
                info.startStyle = getTextStyle();
                // 获取改行的代表第一个字的ZLTextWord类
                // ZLTextParagraphCursor类中代表当前段落的ArrayList中的偏移量
                info.realStartElementIndex = currentElementIndex;
                info.realStartCharIndex = currentCharIndex;
            }

            ZLTextStyle storedStyle = getTextStyle();
            // getTextWidth: 获取屏幕总宽度
            // getRightIndent: 获取该行右缩进信息
            // maxWidth: 每行能显示的最大宽度
            final int maxWidth = page.getTextWidth() - storedStyle.getRightIndent(metrics());
            // 获取首行的左缩进信息
            info.leftIndent = storedStyle.getLeftIndent(metrics());
            if (isFirstLine && storedStyle.getAlignment() != ZLTextAlignmentType.ALIGN_CENTER) {
                info.leftIndent += storedStyle.getFirstLineIndent(metrics());
            }
            if (info.leftIndent > maxWidth - 20) {
                info.leftIndent = maxWidth * 3 / 4;
            }

            info.width = info.leftIndent;

            // 已经到末尾了, 更新endElementIndex和endCharIndex
            if (info.realStartElementIndex == endIndex) {
                info.endElementIndex = info.realStartElementIndex;
                info.endCharIndex = info.realStartCharIndex;
                return info;
            }

            int newWidth = info.width;
            int newHeight = info.height;
            int newDescent = info.descent;
            boolean wordOccurred = false;
            boolean isVisible = false;
            int lastSpaceWidth = 0;
            int internalSpaceCounter = 0;
            boolean removeLastSpace = false;

            do {
                // 利用不断递增的currentElementIndex, 从ZLTextParagraphCursor类中的myElements Arraylist中依次读取元素
                ZLTextElement element = paragraphCursor.getElement(currentElementIndex);
                // 获取每个字将占的宽度, 并将每个字的宽度累加
                newWidth += getElementWidth(element, currentCharIndex);
                newHeight = Math.max(newHeight, getElementHeight(element));
                newDescent = Math.max(newDescent, getElementDescent(element));
                if (element == ZLTextElement.HSpace) {
                    if (wordOccurred) {
                        wordOccurred = false;
                        internalSpaceCounter++;
                        lastSpaceWidth = getContext().getSpaceWidth();
                        newWidth += lastSpaceWidth;
                    }
                } else if (element == ZLTextElement.NBSpace) {
                    wordOccurred = true;
                } else if (element instanceof ZLTextWord) {
                    wordOccurred = true;
                    isVisible = true;
                } else if (element instanceof ZLTextImageElement) {
                    wordOccurred = true;
                    isVisible = true;
                } else if (element instanceof ZLTextVideoElement) {
                    wordOccurred = true;
                    isVisible = true;
                } else if (element instanceof ExtensionElement) {
                    wordOccurred = true;
                    isVisible = true;
                } else if (isStyleChangeElement(element)) {
                    applyStyleChangeElement(element);
                }
                // 当累加的字符长度大于屏幕能显示的宽度时，就代表这一行被填充满了
                if (newWidth > maxWidth) {
                    if (info.endElementIndex != startIndex || element instanceof ZLTextWord) {
                        break;
                    }
                }
                ZLTextElement previousElement = element;
                currentElementIndex++;
                currentCharIndex = 0;
                boolean allowBreak = currentElementIndex == endIndex;
                if (!allowBreak) {
                    element = paragraphCursor.getElement(currentElementIndex);
                    allowBreak =
                            previousElement != ZLTextElement.NBSpace &&
                                    element != ZLTextElement.NBSpace &&
                                    (!(element instanceof ZLTextWord) || previousElement instanceof ZLTextWord) &&
                                    !(element instanceof ZLTextImageElement) &&
                                    !(element instanceof ZLTextControlElement);
                }

                if (allowBreak) {
                    info.isVisible = isVisible;
                    info.width = newWidth;
                    if (info.height < newHeight) {
                        info.height = newHeight;
                    }
                    if (info.descent < newDescent) {
                        info.descent = newDescent;
                    }
                    // 获取这一行的最后一个字
                    info.endElementIndex = currentElementIndex;
                    info.endCharIndex = currentCharIndex;
                    info.spaceCounter = internalSpaceCounter;
                    storedStyle = getTextStyle();
                    removeLastSpace = !wordOccurred && (internalSpaceCounter > 0);
                }
            } while (currentElementIndex != endIndex);

            if (currentElementIndex != endIndex &&
                    (isHyphenationPossible() || info.endElementIndex == startIndex)) {
                ZLTextElement element = paragraphCursor.getElement(currentElementIndex);
                if (element instanceof ZLTextWord) {
                    final ZLTextWord word = (ZLTextWord) element;
                    newWidth -= getWordWidth(word, currentCharIndex);
                    int spaceLeft = maxWidth - newWidth;
                    if ((word.Length > 3 && spaceLeft > 2 * getContext().getSpaceWidth())
                            || info.endElementIndex == startIndex) {
                        ZLTextHyphenationInfo hyphenationInfo = getHyphenationInfo(word);
                        int hyphenationPosition = currentCharIndex;
                        int subwordWidth = 0;
                        for (int right = word.Length - 1, left = currentCharIndex; right > left; ) {
                            final int mid = (right + left + 1) / 2;
                            int m1 = mid;
                            while (m1 > left && !hyphenationInfo.isHyphenationPossible(m1)) {
                                --m1;
                            }
                            if (m1 > left) {
                                final int w = getWordWidth(
                                        word,
                                        currentCharIndex,
                                        m1 - currentCharIndex,
                                        word.Data[word.Offset + m1 - 1] != '-'
                                );
                                if (w < spaceLeft) {
                                    left = mid;
                                    hyphenationPosition = m1;
                                    subwordWidth = w;
                                } else {
                                    right = mid - 1;
                                }
                            } else {
                                left = mid;
                            }
                        }
                        if (hyphenationPosition == currentCharIndex && info.endElementIndex == startIndex) {
                            subwordWidth = getWordWidth(word, currentCharIndex, 1, false);
                            int right = word.Length == currentCharIndex + 1 ? word.Length : word.Length - 1;
                            int left = currentCharIndex + 1;
                            while (right > left) {
                                final int mid = (right + left + 1) / 2;
                                final int w = getWordWidth(
                                        word,
                                        currentCharIndex,
                                        mid - currentCharIndex,
                                        word.Data[word.Offset + mid - 1] != '-'
                                );
                                if (w <= spaceLeft) {
                                    left = mid;
                                    subwordWidth = w;
                                } else {
                                    right = mid - 1;
                                }
                            }
                            hyphenationPosition = right;
                        }
                        if (hyphenationPosition > currentCharIndex) {
                            info.isVisible = true;
                            info.width = newWidth + subwordWidth;
                            if (info.height < newHeight) {
                                info.height = newHeight;
                            }
                            if (info.descent < newDescent) {
                                info.descent = newDescent;
                            }
                            info.endElementIndex = currentElementIndex;
                            info.endCharIndex = hyphenationPosition;
                            info.spaceCounter = internalSpaceCounter;
                            storedStyle = getTextStyle();
                            removeLastSpace = false;
                        }
                    }
                }
            }

            if (removeLastSpace) {
                info.width -= lastSpaceWidth;
                info.spaceCounter--;
            }

            setTextStyle(storedStyle);

            if (isFirstLine) {
                info.VSpaceBefore = info.startStyle.getSpaceBefore(metrics());
                if (previousInfo != null) {
                    info.previousInfoUsed = true;
                    info.height += Math.max(0, info.VSpaceBefore - previousInfo.VSpaceAfter);
                } else {
                    info.previousInfoUsed = false;
                    info.height += info.VSpaceBefore;
                }
            }
            if (info.isEndOfParagraph()) {
                info.VSpaceAfter = getTextStyle().getSpaceAfter(metrics());
            }

            if (info.endElementIndex != endIndex || endIndex == info.paragraphCursorLength) {
                myLineInfoCache.put(info, info);
            }

            return info;
        }
    }

    /**
     * 进一步计算出每一行中的每一个字在屏幕上的绝对位置
     * 每个字的绝对位置以及显示格式等信息会用y一个ZLTextElementArea类表示
     */
    private void prepareTextLine(ZLTextPage page, ZLTextLineInfo info, int x, int y, int columnIndex) {
        // 获取当前行的y坐标, 当前行的每个字都适用这个y坐标
        y = Math.min(y + info.height, getTopMargin() + page.getTextHeight() - 1);

        final ZLPaintContext context = getContext();
        final ZLTextParagraphCursor paragraphCursor = info.paragraphCursor;

        setTextStyle(info.startStyle);
        int spaceCounter = info.spaceCounter;
        float elementCorrection = 0;
        final boolean endOfParagraph = info.isEndOfParagraph();
        boolean wordOccurred = false;
        boolean changeStyle = true;

        // 为了精度不丢失,以float代之
        float fx = x;
        fx += info.leftIndent;

        final ZLTextParagraphCursor paragraph = info.paragraphCursor;
        final int paragraphIndex = paragraph.paragraphIdx;
        // 获取当前行最后一个字的位置
        final int endElementIndex = info.endElementIndex;
        int charIndex = info.realStartCharIndex;
        ZLTextElementArea spaceElement = null;

        final int maxWidth = page.getTextWidth();
        // 非段尾采用，排齐模式
        if (!endOfParagraph && (paragraphCursor.getElement(info.endElementIndex) != ZLTextElement.AfterParagraph)) {
            float gapCount = endElementIndex - info.realStartElementIndex;
            elementCorrection = (maxWidth - getTextStyle().getRightIndent(metrics()) - info.width) / (gapCount - 1);
        } else {
            switch (getTextStyle().getAlignment()) {
                case ZLTextAlignmentType.ALIGN_RIGHT:
                    fx += maxWidth - getTextStyle().getRightIndent(metrics()) - info.width;
                    break;
                case ZLTextAlignmentType.ALIGN_CENTER:
                    fx += (maxWidth - getTextStyle().getRightIndent(metrics()) - info.width) / 2f;
                    break;
                case ZLTextAlignmentType.ALIGN_JUSTIFY:
                case ZLTextAlignmentType.ALIGN_LEFT:
                case ZLTextAlignmentType.ALIGN_UNDEFINED:
                    break;
            }
        }

        // 利用RealStartElementIndex属性获取当前行第一个字的位置，利用for循环读取当前行第一个字到最后一个字之间的内容
        for (int wordIndex = info.realStartElementIndex; wordIndex != endElementIndex; ++wordIndex, charIndex = 0) {
            final ZLTextElement element = paragraph.getElement(wordIndex);
            final int width = getElementWidth(element, charIndex);
            if (element == ZLTextElement.HSpace) {
                if (wordOccurred && spaceCounter > 0) {
                    final int spaceLength = context.getSpaceWidth();
                    if (getTextStyle().isUnderline()) {
                        spaceElement = new ZLTextElementArea(
                                paragraphIndex, wordIndex, 0,
                                0, // length
                                true, // is last in element
                                false, // add hyphenation sign
                                false, // changed style
                                getTextStyle(), element, (int) fx, (int) fx + spaceLength, y, y, columnIndex
                        );
                    } else {
                        spaceElement = null;
                    }
                    fx += spaceLength;
                    wordOccurred = false;
                    --spaceCounter;
                }
            } else if (element instanceof ZLTextWord || element instanceof ZLTextImageElement || element instanceof ZLTextVideoElement || element instanceof ExtensionElement) {
                final int height = getElementHeight(element);
                final int descent = getElementDescent(element);
                final int length = element instanceof ZLTextWord ? ((ZLTextWord) element).Length : 0;
                if (spaceElement != null) {
                    page.TextElementMap.add(spaceElement);
                    spaceElement = null;
                }
                page.TextElementMap.add(new ZLTextElementArea(
                        paragraphIndex, wordIndex, charIndex,
                        length - charIndex,
                        true, // is last in element
                        false, // add hyphenation sign
                        changeStyle, getTextStyle(), element,
                        (int) fx, (int) fx + width - 1, y - height + 1, y + descent, columnIndex
                ));
                changeStyle = false;
                wordOccurred = true;
            } else if (isStyleChangeElement(element)) {
                applyStyleChangeElement(element);
                changeStyle = true;
            }

            // 最后一行不用均匀分布
            if (endOfParagraph) {
                fx += width;
                continue;
            }
            // 累加每个字的宽度，以获取下一个字的x坐标
            fx += width + elementCorrection;
        }
        if (!endOfParagraph) {
            final int len = info.endCharIndex;
            if (len > 0) {
                final int wordIndex = info.endElementIndex;
                final ZLTextWord word = (ZLTextWord) paragraph.getElement(wordIndex);
                final boolean addHyphenationSign = word.Data[word.Offset + len - 1] != '-';
                final int width = getWordWidth(word, 0, len, addHyphenationSign);
                final int height = getElementHeight(word);
                final int descent = context.getDescent();
                // 将新建的ZLTextElementArea类加入ZLTextPage类的TextElementMap属性中
                page.TextElementMap.add(
                        // 根据当前字的x坐标与y坐标以及使用的样式生成一个ZLTextElementArea类
                        new ZLTextElementArea(
                                paragraphIndex, wordIndex, 0,
                                len,
                                false, // is last in element
                                addHyphenationSign,
                                changeStyle, getTextStyle(), word,
                                (int) fx, (int) fx + width - 1, y - height + 1, y + descent, columnIndex
                        )
                );
            }
        }
    }

    /**
     * 翻页
     * @param forward 是否向前
     * @param scrollingMode 滚动模式
     * @param value
     */
    public synchronized final void turnPage(boolean forward, int scrollingMode, int value) {
        preparePaintInfo(myCurrentPage, "turnPage");
        myPreviousPage.reset();
        myNextPage.reset();
        if (myCurrentPage.paintState == PaintStateEnum.READY) {
            myCurrentPage.paintState = forward ? PaintStateEnum.TO_SCROLL_FORWARD : PaintStateEnum.TO_SCROLL_BACKWARD;
            myScrollingMode = scrollingMode;
            myOverlappingValue = value;
        }
    }

    public final synchronized void gotoPosition(ZLTextPosition position) {
        if (position != null) {
            gotoPosition(position.getParagraphIndex(), position.getElementIndex(), position.getCharIndex());
        }
    }

    /**
     * 跳到一个阅读位置
     */
    public final synchronized void gotoPosition(int paragraphIndex, int wordIndex, int charIndex) {
        if (myTextModel != null && myTextModel.getParagraphsNumber() > 0) {
            Application.getViewWidget().reset("gotoPosition");
            myCurrentPage.moveStartCursor(paragraphIndex, wordIndex, charIndex);
            myPreviousPage.reset();
            myNextPage.reset();
            preparePaintInfo(myCurrentPage, "gotoPosition");
            if (myCurrentPage.isEmptyPage()) {
                turnPage(true, ScrollingMode.NO_OVERLAPPING, 0);
            }
        }
    }

    private synchronized void gotoPositionByEnd(int paragraphIndex, int wordIndex, int charIndex) {
        if (myTextModel != null && myTextModel.getParagraphsNumber() > 0) {
            myCurrentPage.moveEndCursor(paragraphIndex, wordIndex, charIndex);
            myPreviousPage.reset();
            myNextPage.reset();
            preparePaintInfo(myCurrentPage, "gotoPositionByEnd");
            if (myCurrentPage.isEmptyPage()) {
                turnPage(false, ScrollingMode.NO_OVERLAPPING, 0);
            }
        }
    }

    protected synchronized void preparePaintInfo() {
        myPreviousPage.reset();
        myNextPage.reset();
        preparePaintInfo(myCurrentPage, "preparePaintInfo");
    }

    /**
     * 准备绘制信息
     *
     * @param page 页面
     */
    private synchronized void preparePaintInfo(ZLTextPage page, String from) {
        if (from.contains("paint")) {
            Timber.v("渲染流程:lineInfo, from -> " + from +
                            ", \n{startCursor= " + page.startCursor +
                            ", \nendCursor= " + page.endCursor +
                            ", \nlineInfoSize= " + page.lineInfos.size() +
                            ", \npaintState= " + DebugHelper.getPatinStateStr(page.paintState) +
                            '}'
                    );
        }

        // 设置图书内容绘制区域跨高
        page.setSize(getTextColumnWidth(), getTextAreaHeight(), isTwoColumnView(), page == myPreviousPage);

        if (page.isClearPaintState() || page.paintState == PaintStateEnum.READY) {
            Timber.v("渲染流程:lineInfo[%s], NO_PAINT/READY ignore", from);
            return;
        }

        final int oldState = page.paintState;

        for (ZLTextLineInfo info : page.lineInfos) {
            myLineInfoCache.put(info, info);
        }

        // 根据paint state计算page的startCursor和endCursor
        switch (page.paintState) {
            default:
                break;
            case PaintStateEnum.TO_SCROLL_FORWARD:
                // 滑动到下一页
                if (!page.endCursor.isEndOfText()) {
                    // 新建一个起始游标
                    final ZLTextWordCursor startCursor = new ZLTextWordCursor();
                    // 1. 根据滑动类型设置startCursor数据
                    switch (myScrollingMode) {
                        case ScrollingMode.NO_OVERLAPPING:
                            break;
                        case ScrollingMode.KEEP_LINES:
                            page.findLineFromEnd(startCursor, myOverlappingValue);
                            break;
                        case ScrollingMode.SCROLL_LINES:
                            page.findLineFromStart(startCursor, myOverlappingValue);
                            if (startCursor.isEndOfParagraph()) {
                                startCursor.nextParagraph();
                            }
                            break;
                        case ScrollingMode.SCROLL_PERCENTAGE:
                            page.findPercentFromStart(startCursor, myOverlappingValue);
                            break;
                    }

                    // 2. 获得目标line
                    if (!startCursor.isNull() && startCursor.samePositionAs(page.startCursor)) {
                        page.findLineFromStart(startCursor, 1);
                    }

                    // 3. 根据startCursor进行分页
                    if (!startCursor.isNull()) {
                        final ZLTextWordCursor endCursor = new ZLTextWordCursor();
                        buildInfos(page, startCursor, endCursor, from);
                        if (!page.isEmptyPage() && (myScrollingMode != ScrollingMode.KEEP_LINES || !endCursor.samePositionAs(page.endCursor))) {
                            page.startCursor.setCursor(startCursor);
                            page.endCursor.setCursor(endCursor);
                            break;
                        }
                    }

                    page.startCursor.setCursor(page.endCursor);
                    buildInfos(page, page.startCursor, page.endCursor, from);
                }
                break;
            case PaintStateEnum.TO_SCROLL_BACKWARD:
                // 滑动到上一页
                if (!page.startCursor.isStartOfText()) {
                    switch (myScrollingMode) {
                        case ScrollingMode.NO_OVERLAPPING:
                            page.startCursor.setCursor(findStartOfPreviousPage(page, page.startCursor));
                            break;
                        case ScrollingMode.KEEP_LINES: {
                            ZLTextWordCursor endCursor = new ZLTextWordCursor();
                            page.findLineFromStart(endCursor, myOverlappingValue);
                            if (!endCursor.isNull() && endCursor.samePositionAs(page.endCursor)) {
                                page.findLineFromEnd(endCursor, 1);
                            }
                            if (!endCursor.isNull()) {
                                ZLTextWordCursor startCursor = findStartOfPreviousPage(page, endCursor);
                                if (startCursor.samePositionAs(page.startCursor)) {
                                    page.startCursor.setCursor(findStartOfPreviousPage(page, page.startCursor));
                                } else {
                                    page.startCursor.setCursor(startCursor);
                                }
                            } else {
                                page.startCursor.setCursor(findStartOfPreviousPage(page, page.startCursor));
                            }
                            break;
                        }
                        case ScrollingMode.SCROLL_LINES:
                            page.startCursor.setCursor(findStart(page, page.startCursor, SizeUnit.LINE_UNIT, myOverlappingValue));
                            break;
                        case ScrollingMode.SCROLL_PERCENTAGE:
                            page.startCursor.setCursor(findStart(page, page.startCursor, SizeUnit.PIXEL_UNIT, page.getTextHeight() * myOverlappingValue / 100));
                            break;
                    }
                    buildInfos(page, page.startCursor, page.endCursor, from);
                    if (page.isEmptyPage()) {
                        page.startCursor.setCursor(findStart(page, page.startCursor, SizeUnit.LINE_UNIT, 1));
                        buildInfos(page, page.startCursor, page.endCursor, from);
                    }
                }
                break;
            case PaintStateEnum.START_IS_KNOWN:
                // 开始cursor已经知道, 从前往后渲染
                if (!page.startCursor.isNull()) {
                    buildInfos(page, page.startCursor, page.endCursor, from);
                }
                break;
            case PaintStateEnum.END_IS_KNOWN:
                // 结束cursor已经知道,
                if (!page.endCursor.isNull()) {
                    // TODO findStartOfPreviousPage()无法判断从前往后无法判断后面的page是否需要留白
                    //  最后会导致从后往前，从前往后效果不一样. 所以我们永远需要从前往后进行分页操作:
                    //  每次加载一个xhtml文件，就从文件第一页开始进行分页确定startCursor, endCursor.
                    //  最后缓存startCursor/endCursor到数据库
                    //  这也就是计算总页码的方法
                    page.startCursor.setCursor(findStartOfPreviousPage(page, page.endCursor));
                    buildInfos(page, page.startCursor, page.endCursor, from);
                }
                break;
        }
        if (from.contains("paint")) {
            Timber.v("渲染流程:lineInfo[%s], 分页完成 for [%s, %s]",
                    DebugHelper.getPatinStateStr(page.paintState),
                    page.startCursor.getParagraphIndex(),
                    page.endCursor.getParagraphIndex());
        }

        page.paintState = PaintStateEnum.READY;

        myLineInfoCache.clear();

        if (page == myCurrentPage) {
            if (oldState != PaintStateEnum.START_IS_KNOWN) {
                myPreviousPage.reset();
            }
            if (oldState != PaintStateEnum.END_IS_KNOWN) {
                myNextPage.reset();
            }
        }
    }

    public void clearCaches() {
        resetMetrics();
        rebuildPaintInfo();
        Application.getViewWidget().reset("clearCaches");
        myCharWidth = -1;
    }

    private int infoSize(ZLTextLineInfo info, int unit) {
        return (unit == SizeUnit.PIXEL_UNIT) ? (info.height + info.descent + info.VSpaceAfter) : (info.isVisible ? 1 : 0);
    }

    private ParagraphSize paragraphSize(ZLTextPage page, ZLTextWordCursor cursor, boolean beforeCurrentPosition, int unit) {
        final ParagraphSize size = new ParagraphSize();

        final ZLTextParagraphCursor paragraphCursor = cursor.getParagraphCursor();
        if (paragraphCursor == null) {
            return size;
        }
        final int endElementIndex =
                beforeCurrentPosition ? cursor.getElementIndex() : paragraphCursor.getParagraphLength();

        resetTextStyle();

        int wordIndex = 0;
        int charIndex = 0;
        ZLTextLineInfo info = null;
        while (wordIndex != endElementIndex) {
            final ZLTextLineInfo prev = info;
            info = processTextLine(page, paragraphCursor, wordIndex, charIndex, endElementIndex, prev, "paragraphSize");
            wordIndex = info.endElementIndex;
            charIndex = info.endCharIndex;
            size.Height += infoSize(info, unit);
            if (prev == null) {
                size.TopMargin = info.VSpaceBefore;
            }
            size.BottomMargin = info.VSpaceAfter;
        }

        return size;
    }

    private void skip(ZLTextPage page, ZLTextWordCursor cursor, int unit, int size) {
        final ZLTextParagraphCursor paragraphCursor = cursor.getParagraphCursor();
        if (paragraphCursor == null) {
            return;
        }
        final int endElementIndex = paragraphCursor.getParagraphLength();

        resetTextStyle();
        applyStyleChanges(paragraphCursor, 0, cursor.getElementIndex(), "skip");

        ZLTextLineInfo info = null;
        while (!cursor.isEndOfParagraph() && size > 0) {
            info = processTextLine(page, paragraphCursor, cursor.getElementIndex(), cursor.getCharIndex(), endElementIndex, info, "skip");
            cursor.moveTo(info.endElementIndex, info.endCharIndex);
            size -= infoSize(info, unit);
        }
    }

    private ZLTextWordCursor findStartOfPreviousPage(ZLTextPage page, ZLTextWordCursor end) {
        if (isTwoColumnView()) {
            end = findStart(page, end, SizeUnit.PIXEL_UNIT, page.getTextHeight());
        }
        end = findStart(page, end, SizeUnit.PIXEL_UNIT, page.getTextHeight());
        return end;
    }

    private ZLTextWordCursor findStart(ZLTextPage page, ZLTextWordCursor end, int unit, int height) {
        final ZLTextWordCursor start = new ZLTextWordCursor(end);
        ParagraphSize size = paragraphSize(page, start, true, unit);
        height -= size.Height;
        boolean positionChanged = !start.isStartOfParagraph();
        start.moveToParagraphStart();
        while (height > 0) {
            final ParagraphSize previousSize = size;
            if (positionChanged && start.getParagraphCursor().isEndOfSection()) {
                break;
            }
            if (!start.previousParagraph()) {
                break;
            }
            if (!start.getParagraphCursor().isEndOfSection()) {
                positionChanged = true;
            }
            size = paragraphSize(page, start, false, unit);
            height -= size.Height;
            if (previousSize != null) {
                height += Math.min(size.BottomMargin, previousSize.TopMargin);
            }
        }
        skip(page, start, unit, -height);

        if (unit == SizeUnit.PIXEL_UNIT) {
            boolean sameStart = start.samePositionAs(end);
            if (!sameStart && start.isEndOfParagraph() && end.isStartOfParagraph()) {
                ZLTextWordCursor startCopy = new ZLTextWordCursor(start);
                startCopy.nextParagraph();
                sameStart = startCopy.samePositionAs(end);
            }
            if (sameStart) {
                start.setCursor(findStart(page, end, SizeUnit.LINE_UNIT, 1));
            }
        }

        return start;
    }

    protected ZLTextElementArea getElementByCoordinates(int x, int y) {
        return myCurrentPage.TextElementMap.binarySearch(x, y);
    }

    public final void outlineRegion(ZLTextRegion region) {
        outlineRegion(region != null ? region.getSoul() : null);
    }

    public final void outlineRegion(ZLTextRegion.Soul soul) {
        myShowOutline = true;
        myOutlinedRegionSoul = soul;
    }

    public void hideOutline() {
        myShowOutline = false;
        Application.getViewWidget().reset("hideOutline");
    }

    protected ZLTextHighlighting findHighlighting(int x, int y, int maxDistance) {
        final ZLTextRegion region = findRegion(x, y, maxDistance, ZLTextRegion.AnyRegionFilter);
        if (region == null) {
            return null;
        }
        synchronized (myHighlightingList) {
            for (ZLTextHighlighting h : myHighlightingList) {
                if (h.getBackgroundColor() != null && h.intersects(region)) {
                    return h;
                }
            }
        }
        return null;
    }

    protected ZLTextRegion findRegion(int x, int y, int maxDistance, ZLTextRegion.Filter filter) {
        return myCurrentPage.TextElementMap.findRegion(x, y, maxDistance, filter);
    }

    protected ZLTextRegion findRegion(int x, int y, ZLTextRegion.Filter filter) {
        return findRegion(x, y, Integer.MAX_VALUE - 1, filter);
    }

    protected ZLTextElementAreaVector.RegionPair findRegionsPair(int x, int y, ZLTextRegion.Filter filter) {
        return myCurrentPage.TextElementMap.findRegionsPair(x, y, getColumnIndex(x), filter);
    }

/*
	public void resetRegionPointer() {
		myOutlinedRegionSoul = null;
		myShowOutline = true;
	}
*/

    protected void setSelectedRegion(ZLTextRegion region) {
        mySelection.start(region);
    }
    /**
     * 在这里设定选中的区域, 选定的文字区域一般在触摸坐标的上一行
     */
    protected boolean setSelectionRegionWithTextSize(int x, int y) {
        // 在这里初始化选中的区域,
        Timber.v("长按选中流程, y = %d, fontSize = %s", y, getTextStyleCollection().getBaseStyle().getFontSize());
        // y坐标减去字体高度，因为选中区域永远在触摸坐标的上面
        y -= getTextStyleCollection().getBaseStyle().getFontSize() / 2;
        Timber.v("长按选中流程, y = %s", y);
        return mySelection.start(x, y);
    }

//    protected boolean drawSelection(int x, int y) {
//        // 在这里初始化选中的区域,
//        y -= getTextStyleCollection().getBaseStyle().getFontSize() / 2;
//        if (!mySelection.start(x, y)) {
//            return false;
//        }
//        Timber.v("长按选中流程, 设置选中区域: %s", getSelectionDebug());
//        repaint("drawSelection");
//        return true;
//    }

    public void clearSelection() {
        if (mySelection.clear() && !DebugHelper.ENABLE_FLUTTER) {
            repaint("clearSelection");
        }
    }

    public ZLTextHighlighting getSelectionHighlighting() {
        return mySelection;
    }

    public int getSelectionStartY() {
        if (mySelection.isEmpty()) {
            return 0;
        }
        final ZLTextElementArea selectionStartArea = mySelection.getStartArea(myCurrentPage);
        if (selectionStartArea != null) {
            return selectionStartArea.YStart;
        }
        if (mySelection.hasPartBeforePage(myCurrentPage)) {
            final ZLTextElementArea firstArea = myCurrentPage.TextElementMap.getFirstArea();
            return firstArea != null ? firstArea.YStart : 0;
        } else {
            final ZLTextElementArea lastArea = myCurrentPage.TextElementMap.getLastArea();
            return lastArea != null ? lastArea.YEnd : 0;
        }
    }

    public int getSelectionEndY() {
        if (mySelection.isEmpty()) {
            return 0;
        }
        final ZLTextElementArea selectionEndArea = mySelection.getEndArea(myCurrentPage);
        if (selectionEndArea != null) {
            return selectionEndArea.YEnd;
        }
        if (mySelection.hasPartAfterPage(myCurrentPage)) {
            final ZLTextElementArea lastArea = myCurrentPage.TextElementMap.getLastArea();
            return lastArea != null ? lastArea.YEnd : 0;
        } else {
            final ZLTextElementArea firstArea = myCurrentPage.TextElementMap.getFirstArea();
            return firstArea != null ? firstArea.YStart : 0;
        }
    }

    public ZLTextPosition getSelectionStartPosition() {
        return mySelection.getStartPosition();
    }

    public ZLTextPosition getSelectionEndPosition() {
        return mySelection.getEndPosition();
    }

    public boolean isSelectionEmpty() {
        return mySelection.isEmpty();
    }

    public ZLTextRegion nextRegion(Direction direction, ZLTextRegion.Filter filter) {
        return myCurrentPage.TextElementMap.nextRegion(getOutlinedRegion(), direction, filter);
    }

    public ZLTextRegion getOutlinedRegion() {
        return getOutlinedRegion(myCurrentPage);
    }

    private ZLTextRegion getOutlinedRegion(ZLTextPage page) {
        return page.TextElementMap.getRegion(myOutlinedRegionSoul);
    }

    public ZLTextParagraphCursor getParagraphCursor(int index) {
        return myCursorManager.get(index);
    }

    /**
     * 获取该页章节名
     *
     * @param cursor 该页的StartCursor
     * @return 该页章节名
     */
    protected abstract String getTocText(ZLTextWordCursor cursor);

    protected abstract ZLColor getExtraColor();

    public interface ScrollingMode {
        int NO_OVERLAPPING = 0;
        int KEEP_LINES = 1;
        int SCROLL_LINES = 2;
        int SCROLL_PERCENTAGE = 3;
    }

    private interface SizeUnit {
        int PIXEL_UNIT = 0;
        int LINE_UNIT = 1;
    }

    /**
     * 页面位置
     */
    public static class PagePosition {
        /**
         * 当前页数
         */
        public final int Current;
        /**
         * 总页数
         */
        public final int Total;

        PagePosition(int current, int total) {
            Current = current;
            Total = total;
        }
    }

    private static class ParagraphSize {
        public int Height;
        public int TopMargin;
        public int BottomMargin;
    }

    public String getSelectionDebug() {
        return mySelection.getStartPosition() + " " + mySelection.getEndPosition();
    }
}
