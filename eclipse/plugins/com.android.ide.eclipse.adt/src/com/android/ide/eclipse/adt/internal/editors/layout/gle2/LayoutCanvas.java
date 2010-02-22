/*
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Eclipse Public License, Version 1.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.eclipse.org/org/documents/epl-v10.php
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.ide.eclipse.adt.internal.editors.layout.gle2;

import com.android.ide.eclipse.adt.editors.layout.gscripts.INode;
import com.android.ide.eclipse.adt.internal.editors.layout.gre.NodeFactory;
import com.android.ide.eclipse.adt.internal.editors.layout.gre.RulesEngine;
import com.android.ide.eclipse.adt.internal.editors.layout.uimodel.UiViewElementNode;
import com.android.layoutlib.api.ILayoutResult;

import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.DND;
import org.eclipse.swt.dnd.DropTarget;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.MouseListener;
import org.eclipse.swt.events.MouseMoveListener;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.events.PaintListener;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.PaletteData;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.awt.image.Raster;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;

/**
 * Displays the image rendered by the {@link GraphicalEditorPart} and handles
 * the interaction with the widgets.
 * <p/>
 *
 * @since GLE2
 *
 * TODO list:
 * - gray on error, keep select but disable d'n'd.
 * - make sure it is scrollable (Canvas derives from Scrollable, so prolly just setting bounds.)
 * - handle drop target (from palette).
 * - handle drag'n'drop (internal, for moving/duplicating).
 * - handle context menu (depending on selection).
 * - selection synchronization with the outline (both ways).
 */
/* package */  class LayoutCanvas extends Canvas {

    /**
     * Margin around the rendered image. Should be enough space to display the layout
     * width and height pseudo widgets.
     */
    static final int IMAGE_MARGIN = 25;


    /** The Groovy Rules Engine, associated with the current project. */
    private RulesEngine mRulesEngine;

    /*
     * The last valid ILayoutResult passed to {@link #setResult(ILayoutResult)}.
     * This can be null.
     * When non null, {@link #mLastValidViewInfoRoot} is guaranteed to be non-null too.
    */
    private ILayoutResult mLastValidResult;

    /**
     * The CanvasViewInfo root created for the last update of {@link #mLastValidResult}.
     * This is null when {@link #mLastValidResult} is null.
     * When non null, {@link #mLastValidResult} is guaranteed to be non-null too.
     */
    private CanvasViewInfo mLastValidViewInfoRoot;

    /**
     * True when the last {@link #setResult(ILayoutResult)} provided a valid {@link ILayoutResult}
     * in which case it is also available in {@link #mLastValidResult}.
     * When false this means the canvas is displaying an out-dated result image & bounds and some
     * features should be disabled accordingly such a drag'n'drop.
     * <p/>
     * When this is false, {@link #mLastValidResult} can be non-null and points to an older
     * layout result.
     */
    private boolean mIsResultValid;

    /** Current background image. Null when there's no image. */
    private Image mImage;

    /** The current selection list. The list is never null, however it can be empty. */
    private final LinkedList<CanvasSelection> mSelections = new LinkedList<CanvasSelection>();

    /** CanvasSelection border color. Do not dispose, it's a system color. */
    private Color mSelectionFgColor;

    /** GC wrapper given to the IViewRule methods. The GC itself is only defined in the
     *  context of {@link #onPaint(PaintEvent)}; otherwise it is null. */
    private GCWrapper mGCWrapper;

    /** Default font used on the canvas. Do not dispose, it's a system font. */
    private Font mFont;

    /** Current hover view info. Null when no mouse hover. */
    private CanvasViewInfo mHoverViewInfo;

    /** Current mouse hover border rectangle. Null when there's no mouse hover. */
    private Rectangle mHoverRect;

    /** Hover border color. Must be disposed, it's NOT a system color. */
    private Color mHoverFgColor;

    /** Outline color. Do not dispose, it's a system color. */
    private Color mOutlineColor;

    /**
     * The <em>current</em> alternate selection, if any, which changes when the Alt key is
     * used during a selection. Can be null.
     */
    private CanvasAlternateSelection mAltSelection;

    /** When true, always display the outline of all views. */
    private boolean mShowOutline;

    /** Drop target associated with this composite. */
    private DropTarget mDropTarget;

    /** Drop listener, with feedback from current drop */
    private CanvasDropListener mDropListener;

    /** Factory that can create {@link INode} proxies. */
    private final NodeFactory mNodeFactory = new NodeFactory();


    public LayoutCanvas(RulesEngine rulesEngine, Composite parent, int style) {
        super(parent, style | SWT.DOUBLE_BUFFERED);
        mRulesEngine = rulesEngine;

        mGCWrapper = new GCWrapper(IMAGE_MARGIN, IMAGE_MARGIN);

        Display d = getDisplay();
        mSelectionFgColor = d.getSystemColor(SWT.COLOR_RED);
        mHoverFgColor     = new Color(d, 0xFF, 0x99, 0x00); // orange
        mOutlineColor     = d.getSystemColor(SWT.COLOR_GREEN);

        mFont = d.getSystemFont();

        addPaintListener(new PaintListener() {
            public void paintControl(PaintEvent e) {
                onPaint(e);
            }
        });

        addMouseMoveListener(new MouseMoveListener() {
            public void mouseMove(MouseEvent e) {
                onMouseMove(e);
            }
        });

        addMouseListener(new MouseListener() {
            public void mouseUp(MouseEvent e) {
                onMouseUp(e);
            }

            public void mouseDown(MouseEvent e) {
                onMouseDown(e);
            }

            public void mouseDoubleClick(MouseEvent e) {
                onDoubleClick(e);
            }
        });

        mDropTarget = new DropTarget(this, DND.DROP_COPY | DND.DROP_MOVE | DND.DROP_DEFAULT);
        mDropTarget.setTransfer(new Transfer[] { ElementDescTransfer.getInstance() });
        mDropListener = new CanvasDropListener(this);
        mDropTarget.addDropListener(mDropListener);
    }

    @Override
    public void dispose() {
        super.dispose();

        if (mHoverFgColor != null) {
            mHoverFgColor.dispose();
            mHoverFgColor = null;
        }

        if (mDropTarget != null) {
            mDropTarget.dispose();
            mDropTarget = null;
        }

        if (mRulesEngine != null) {
            mRulesEngine.dispose();
            mRulesEngine = null;
        }
    }

    /**
     * Returns true when the last {@link #setResult(ILayoutResult)} provided a valid
     * {@link ILayoutResult} in which case it is also available in {@link #mLastValidResult}.
     * When false this means the canvas is displaying an out-dated result image & bounds and some
     * features should be disabled accordingly such a drag'n'drop.
     * <p/>
     * When this is false, {@link #mLastValidResult} can be non-null and points to an older
     * layout result.
     */
    /* package */ boolean isResultValid() {
        return mIsResultValid;
    }

    /** Returns the Groovy Rules Engine, associated with the current project. */
    /* package */ RulesEngine getRulesEngine() {
        return mRulesEngine;
    }

    /** Sets the Groovy Rules Engine, associated with the current project. */
    /* package */ void setRulesEngine(RulesEngine rulesEngine) {
        mRulesEngine = rulesEngine;
    }

    /**
     * Returns the factory to use to convert from {@link CanvasViewInfo} or from
     * {@link UiViewElementNode} to {@link INode} proxies.
     */
    public NodeFactory getNodeFactory() {
        return mNodeFactory;
    }

    /**
     * Sets the result of the layout rendering. The result object indicates if the layout
     * rendering succeeded. If it did, it contains a bitmap and the objects rectangles.
     *
     * Implementation detail: the bridge's computeLayout() method already returns a newly
     * allocated ILayourResult. That means we can keep this result and hold on to it
     * when it is valid.
     *
     * @param result The new rendering result, either valid or not.
     */
    public void setResult(ILayoutResult result) {
        // disable any hover
        mHoverRect = null;

        mIsResultValid = (result != null && result.getSuccess() == ILayoutResult.SUCCESS);

        if (mIsResultValid && result != null) {
            mLastValidResult = result;
            mLastValidViewInfoRoot = new CanvasViewInfo(result.getRootView());
            setImage(result.getImage());

            updateNodeProxies(mLastValidViewInfoRoot);

            // Check if the selection is still the same (based on the object keys)
            // and eventually recompute their bounds.
            for (ListIterator<CanvasSelection> it = mSelections.listIterator(); it.hasNext(); ) {
                CanvasSelection s = it.next();

                // Check if the selected object still exists
                Object key = s.getViewInfo().getUiViewKey();
                CanvasViewInfo vi = findViewInfoKey(key, mLastValidViewInfoRoot);

                // Remove the previous selection -- if the selected object still exists
                // we need to recompute its bounds in case it moved so we'll insert a new one
                // at the same place.
                it.remove();
                if (vi != null) {
                    it.add(new CanvasSelection(vi, mRulesEngine, mNodeFactory));
                }
            }

            // remove the current alternate selection views
            mAltSelection = null;
        }

        redraw();
    }

    public void setShowOutline(boolean newState) {
        mShowOutline = newState;
        redraw();
    }

    /**
     * Called by the {@link GraphicalEditorPart} when the Copy action is requested.
     *
     * @param clipboard The shared clipboard. Must not be disposed.
     */
    public void onCopy(Clipboard clipboard) {
        // TODO implement copy to clipbard. Also will need to provide feedback to enable
        // copy only when there's a selection.
    }

    /**
     * Called by the {@link GraphicalEditorPart} when the Cut action is requested.
     *
     * @param clipboard The shared clipboard. Must not be disposed.
     */
    public void onCut(Clipboard clipboard) {
        // TODO implement copy to clipbard. Also will need to provide feedback to enable
        // cut only when there's a selection.
    }

    /**
     * Called by the {@link GraphicalEditorPart} when the Paste action is requested.
     *
     * @param clipboard The shared clipboard. Must not be disposed.
     */
    public void onPaste(Clipboard clipboard) {

    }

    /**
     * Called by the {@link GraphicalEditorPart} when the Select All action is requested.
     */
    public void onSelectAll() {
        // First clear the current selection, if any.
        mSelections.clear();
        mAltSelection = null;

        // Now select everything if there's a valid layout
        if (mIsResultValid && mLastValidResult != null) {
            selectAllViewInfos(mLastValidViewInfoRoot);
            redraw();
        }
    }

    /**
     * Delete action
     */
    public void onDelete() {
        // TODO not implemented yet, not even hooked in yet!
    }

    //---


    /**
     * Creates or updates the node proxy for this canvas view info.
     * <p/>
     * Since proxies are reused, this will update the bounds of an existing proxy when the
     * canvas is refreshed and a view changes position or size.
     * <p/>
     * This is a recursive call that updates the whole hierarchy starting at the given
     * view info.
     */
    private void updateNodeProxies(CanvasViewInfo vi) {

        if (vi == null) {
            return;
        }

        UiViewElementNode key = vi.getUiViewKey();

        if (key != null) {
            mNodeFactory.create(vi);
        }

        for (CanvasViewInfo child : vi.getChildren()) {
            updateNodeProxies(child);
        }
    }

    /**
     * Sets the image of the last *successful* rendering.
     * Converts the AWT image into an SWT image.
     */
    private void setImage(BufferedImage awtImage) {
        int width = awtImage.getWidth();
        int height = awtImage.getHeight();

        Raster raster = awtImage.getData(new java.awt.Rectangle(width, height));
        int[] imageDataBuffer = ((DataBufferInt)raster.getDataBuffer()).getData();

        ImageData imageData = new ImageData(width, height, 32,
                new PaletteData(0x00FF0000, 0x0000FF00, 0x000000FF));

        imageData.setPixels(0, 0, imageDataBuffer.length, imageDataBuffer, 0);

        mImage = new Image(getDisplay(), imageData);
    }

    /**
     * Sets the alpha for the given GC.
     * <p/>
     * Alpha may not work on all platforms and may fail with an exception.
     *
     * @param gc the GC to change
     * @param alpha the new alpha, 0 for transparent, 255 for opaque.
     * @return True if the operation worked, false if it failed with an exception.
     *
     * @see GC#setAlpha(int)
     */
    private boolean gc_setAlpha(GC gc, int alpha) {
        try {
            gc.setAlpha(alpha);
            return true;
        } catch (SWTException e) {
            return false;
        }
    }

    /**
     * Paints the canvas in response to paint events.
     */
    private void onPaint(PaintEvent e) {
        GC gc = e.gc;
        gc.setFont(mFont);
        mGCWrapper.setGC(gc);
        try {

            if (mImage != null) {
                if (!mIsResultValid) {
                    gc_setAlpha(gc, 128);  // half-transparent
                }

                gc.drawImage(mImage, IMAGE_MARGIN, IMAGE_MARGIN);

                if (!mIsResultValid) {
                    gc_setAlpha(gc, 255);  // opaque
                }
            }

            if (mShowOutline) {
                gc.setForeground(mOutlineColor);
                gc.setLineStyle(SWT.LINE_DOT);
                drawOutline(gc, mLastValidViewInfoRoot);
            }

            if (mHoverRect != null) {
                gc.setForeground(mHoverFgColor);
                gc.setLineStyle(SWT.LINE_DOT);
                gc.drawRectangle(mHoverRect);
            }

            int n = mSelections.size();
            if (n > 0) {
                boolean isMultipleSelection = n > 1;

                if (n == 1) {
                    gc.setForeground(mSelectionFgColor);
                    mSelections.get(0).paintParentSelection(mRulesEngine, mGCWrapper);
                }

                for (CanvasSelection s : mSelections) {
                    gc.setForeground(mSelectionFgColor);
                    s.paintSelection(mRulesEngine, mGCWrapper, isMultipleSelection);
                }
            }

            if (mDropListener != null) {
                mDropListener.paintFeedback(mGCWrapper);
            }

        } finally {
            mGCWrapper.setGC(null);
        }
    }

    private void drawOutline(GC gc, CanvasViewInfo info) {

        Rectangle r = info.getAbsRect();
        gc.drawRectangle(r.x + IMAGE_MARGIN, r.y + IMAGE_MARGIN, r.width, r.height);

        for (CanvasViewInfo vi : info.getChildren()) {
            drawOutline(gc, vi);
        }
    }

    /**
     * Hover on top of a known child.
     */
    private void onMouseMove(MouseEvent e) {
        if (mLastValidResult != null) {
            CanvasViewInfo root = mLastValidViewInfoRoot;
            CanvasViewInfo vi = findViewInfoAt(e.x - IMAGE_MARGIN, e.y - IMAGE_MARGIN);

            // We don't hover on the root since it's not a widget per see and it is always there.
            if (vi == root) {
                vi = null;
            }

            boolean needsUpdate = vi != mHoverViewInfo;
            mHoverViewInfo = vi;

            if (vi == null) {
                mHoverRect = null;
            } else {
                Rectangle r = vi.getSelectionRect();
                mHoverRect = new Rectangle(r.x + IMAGE_MARGIN, r.y + IMAGE_MARGIN,
                                           r.width, r.height);
            }

            if (needsUpdate) {
                redraw();
            }
        }
    }

    private void onMouseDown(MouseEvent e) {
        // pass, not used yet.
    }

    /**
     * Performs selection on mouse up (not mouse down).
     * <p/>
     * Shift key is used to toggle in multi-selection.
     * Alt key is used to cycle selection through objects at the same level than the one
     * pointed at (i.e. click on an object then alt-click to cycle).
     */
    private void onMouseUp(MouseEvent e) {
        if (mLastValidResult != null) {

            boolean isShift = (e.stateMask & SWT.SHIFT) != 0;
            boolean isAlt   = (e.stateMask & SWT.ALT)   != 0;

            int x = e.x - IMAGE_MARGIN;
            int y = e.y - IMAGE_MARGIN;
            CanvasViewInfo vi = findViewInfoAt(x, y);

            if (isShift && !isAlt) {
                // Case where shift is pressed: pointed object is toggled.

                // reset alternate selection if any
                mAltSelection = null;

                // If nothing has been found at the cursor, assume it might be a user error
                // and avoid clearing the existing selection.

                if (vi != null) {
                    // toggle this selection on-off: remove it if already selected
                    if (deselect(vi)) {
                        redraw();
                        return;
                    }

                    // otherwise add it.
                    mSelections.add(new CanvasSelection(vi, mRulesEngine, mNodeFactory));
                    redraw();
                }

            } else if (isAlt) {
                // Case where alt is pressed: select or cycle the object pointed at.

                // Note: if shift and alt are pressed, shift is ignored. The alternate selection
                // mechanism does not reset the current multiple selection unless they intersect.

                // We need to remember the "origin" of the alternate selection, to be
                // able to continue cycling through it later. If there's no alternate selection,
                // create one. If there's one but not for the same origin object, create a new
                // one too.
                if (mAltSelection == null || mAltSelection.getOriginatingView() != vi) {
                    mAltSelection = new CanvasAlternateSelection(vi, findAltViewInfoAt(
                                                    x, y, mLastValidViewInfoRoot, null));

                    // deselect them all, in case they were partially selected
                    deselectAll(mAltSelection.getAltViews());

                    // select the current one
                    CanvasViewInfo vi2 = mAltSelection.getCurrent();
                    if (vi2 != null) {
                        mSelections.addFirst(new CanvasSelection(vi2, mRulesEngine, mNodeFactory));
                    }
                } else {
                    // We're trying to cycle through the current alternate selection.
                    // First remove the current object.
                    CanvasViewInfo vi2 = mAltSelection.getCurrent();
                    deselect(vi2);

                    // Now select the next one.
                    vi2 = mAltSelection.getNext();
                    if (vi2 != null) {
                        mSelections.addFirst(new CanvasSelection(vi2, mRulesEngine, mNodeFactory));
                    }
                }
                redraw();

            } else {
                // Case where no modifier is pressed: either select or reset the selection.

                // reset alternate selection if any
                mAltSelection = null;

                // reset (multi)selection if any
                if (mSelections.size() > 0) {
                    if (mSelections.size() == 1 && mSelections.getFirst().getViewInfo() == vi) {
                        // CanvasSelection remains the same, don't touch it.
                        return;
                    }
                    mSelections.clear();
                }

                if (vi != null) {
                    mSelections.add(new CanvasSelection(vi, mRulesEngine, mNodeFactory));
                }
                redraw();
            }
        }
    }

    /** Deselects a view info. Returns true if the object was actually selected. */
    private boolean deselect(CanvasViewInfo canvasViewInfo) {
        if (canvasViewInfo == null) {
            return false;
        }

        for (ListIterator<CanvasSelection> it = mSelections.listIterator(); it.hasNext(); ) {
            CanvasSelection s = it.next();
            if (canvasViewInfo == s.getViewInfo()) {
                it.remove();
                return true;
            }
        }

        return false;
    }

    /** Deselects multiple view infos, */
    private void deselectAll(List<CanvasViewInfo> canvasViewInfos) {
        for (ListIterator<CanvasSelection> it = mSelections.listIterator(); it.hasNext(); ) {
            CanvasSelection s = it.next();
            if (canvasViewInfos.contains(s.getViewInfo())) {
                it.remove();
            }
        }
    }

    private void onDoubleClick(MouseEvent e) {
        // pass, not used yet.
    }

    /**
     * Tries to find a child with the same view key in the view info sub-tree.
     * Returns null if not found.
     */
    private CanvasViewInfo findViewInfoKey(Object viewKey, CanvasViewInfo canvasViewInfo) {
        if (canvasViewInfo.getUiViewKey() == viewKey) {
            return canvasViewInfo;
        }

        // try to find a matching child
        for (CanvasViewInfo child : canvasViewInfo.getChildren()) {
            CanvasViewInfo v = findViewInfoKey(viewKey, child);
            if (v != null) {
                return v;
            }
        }

        return null;
    }


    /**
     * Tries to find the inner most child matching the given x,y coordinates in the view
     * info sub-tree, starting at the last know view info root.
     * This uses the potentially-expanded selection bounds.
     *
     * Returns null if not found or if there's view info root.
     */
    /* package */ CanvasViewInfo findViewInfoAt(int x, int y) {
        if (mLastValidViewInfoRoot == null) {
            return null;
        } else {
            return findViewInfoAt(x, y, mLastValidViewInfoRoot);
        }
    }

    /**
     * Tries to find the inner most child matching the given x,y coordinates in the view
     * info sub-tree. This uses the potentially-expanded selection bounds.
     *
     * Returns null if not found.
     */
    private CanvasViewInfo findViewInfoAt(int x, int y, CanvasViewInfo canvasViewInfo) {
        Rectangle r = canvasViewInfo.getSelectionRect();
        if (r.contains(x, y)) {

            // try to find a matching child first
            for (CanvasViewInfo child : canvasViewInfo.getChildren()) {
                CanvasViewInfo v = findViewInfoAt(x, y, child);
                if (v != null) {
                    return v;
                }
            }

            // if no children matched, this is the view that we're looking for
            return canvasViewInfo;
        }

        return null;
    }

    private ArrayList<CanvasViewInfo> findAltViewInfoAt(
            int x, int y, CanvasViewInfo parent, ArrayList<CanvasViewInfo> outList) {
        Rectangle r;

        if (outList == null) {
            outList = new ArrayList<CanvasViewInfo>();

            // add the parent root only once
            r = parent.getSelectionRect();
            if (r.contains(x, y)) {
                outList.add(parent);
            }
        }

        if (parent.getChildren().size() > 0) {
            // then add all children that match the position
            for (CanvasViewInfo child : parent.getChildren()) {
                r = child.getSelectionRect();
                if (r.contains(x, y)) {
                    outList.add(child);
                }
            }

            // finally recurse in the children
            for (CanvasViewInfo child : parent.getChildren()) {
                r = child.getSelectionRect();
                if (r.contains(x, y)) {
                    findAltViewInfoAt(x, y, child, outList);
                }
            }
        }

        return outList;
    }

    /**
     * Used by {@link #onSelectAll()} to add all current view infos to the selection list.
     *
     * @param canvasViewInfo The root to add. This info and all its children will be added to the
     *                 selection list.
     */
    private void selectAllViewInfos(CanvasViewInfo canvasViewInfo) {
        mSelections.add(new CanvasSelection(canvasViewInfo, mRulesEngine, mNodeFactory));
        for (CanvasViewInfo vi : canvasViewInfo.getChildren()) {
            selectAllViewInfos(vi);
        }
    }
}
