/*
 * Copyright (C) 2026 Miku UI
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.launcher3;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Rect;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import com.android.launcher3.LauncherSettings.Favorites;
import com.android.launcher3.views.ActivityContext;
import com.android.launcher3.views.BaseDragLayer;
import com.android.launcher3.popup.IconViewController;
import com.android.launcher3.celllayout.CellLayoutLayoutParams;
import com.android.launcher3.dragndrop.DraggableView;
import com.android.launcher3.folder.PreviewBackground;
import com.android.launcher3.logging.FileLog;
import com.android.launcher3.model.data.ItemInfo;
import com.android.launcher3.util.IntSet;

import java.io.File;

/**
 * Pixel placement for workspace icons and widgets when free layout is enabled.
 *
 * Free layout lives in a sibling SQLite file ({@code <grid>_free.db}) so it never writes the
 * original grid store. The first time the mode is turned on, the current original database is
 * copied into that file.
 *
 * Positions are stored as 0–10000 fractions of the {@link ShortcutAndWidgetContainer}.
 */
public final class WorkspaceFreeMode {

    public static final String PREF_KEY = "pref_workspace_free_mode";
    public static final int UNSET = -1;
    public static final float DEFAULT_ICON_SCALE = 1f;
    public static final float DEFAULT_ICON_ROTATION = 0f;
    public static final float MIN_ICON_SCALE = 0.5f;
    public static final float MAX_ICON_SCALE = 3f;
    private static final int NORM = 10000;
    /** Center-to-center distance as a fraction of the icon's visual size. */
    private static final float FOLDER_OVERLAP = 0.2f;
    private static final String TAG = "WorkspaceFreeMode";

    /** The icon currently following the finger, hosted on the drag layer. */
    private static View sDragView;
    private static ShortcutAndWidgetContainer sParent;
    private static int sIndex;
    private static CellLayoutLayoutParams sLp;
    private static float sBaseTx;
    private static float sBaseTy;
    private static int sOriginX;
    private static int sOriginY;
    private static float sScreenX;
    private static float sScreenY;

    private WorkspaceFreeMode() {}

    /** Scale or rotation is on the view, so a bitmap stand-in would clip it. */
    public static boolean viewIsTransformed(View view) {
        return view != null
                && (Math.abs(view.getScaleX() - 1f) > 0.001f
                || Math.abs(view.getScaleY() - 1f) > 0.001f
                || Math.abs(view.getRotation()) > 0.1f);
    }

    public static boolean isEnabled(Context context) {
        return LauncherPrefs.get(context).get(LauncherPrefs.WORKSPACE_FREE_MODE);
    }

    public static boolean isActive(CellLayout layout) {
        return layout != null && layout.acceptsWidget() && isEnabled(layout.getContext());
    }

    public static boolean isActive(Context context, int container) {
        return container == LauncherSettings.Favorites.CONTAINER_DESKTOP && isEnabled(context);
    }

    public static void applyToLayoutParams(View child, CellLayoutLayoutParams lp,
            ShortcutAndWidgetContainer container) {
        if (!(child.getTag() instanceof ItemInfo info) || !info.hasFreePosition()) {
            return;
        }
        if (!isEnabled(container.getContext())) {
            return;
        }
        if (child.getId() == R.id.search_container_workspace) {
            return;
        }
        int layoutW = container.getMeasuredWidth();
        int layoutH = container.getMeasuredHeight();
        if (layoutW <= 0 || layoutH <= 0) {
            return;
        }
        lp.x = Utilities.boundToRange(fromNorm(info.freeX, layoutW), 0,
                Math.max(0, layoutW - lp.width));
        lp.y = Utilities.boundToRange(fromNorm(info.freeY, layoutH), 0,
                Math.max(0, layoutH - lp.height));
    }

    /**
     * Sets {@link ItemInfo#freeX}/{@code freeY} from a drop's visual center in CellLayout space,
     * and updates {@code cellX}/{@code cellY} to the nearest cell.
     *
     * {@code droppedView} supplies the icon's visual bounds so the view top-left is derived from
     * the icon center, not the full cell center (the label sits below the icon).
     */
    public static void setFromVisualCenter(ItemInfo info, CellLayout layout, float centerX,
            float centerY, int spanX, int spanY, View droppedView) {
        ShortcutAndWidgetContainer container = layout.getShortcutsAndWidgets();
        int itemW = spanWidth(layout, spanX);
        int itemH = spanHeight(layout, spanY);
        float offsetX = itemW / 2f;
        float offsetY = itemH / 2f;
        if (droppedView instanceof DraggableView dv) {
            if (droppedView.getWidth() > 0 && droppedView.getHeight() > 0) {
                Rect visual = new Rect();
                dv.getWorkspaceVisualDragBounds(visual);
                if (visual.width() > 0 && visual.height() > 0) {
                    offsetX = visual.centerX();
                    offsetY = visual.centerY();
                }
            } else if (dv.getViewType() == DraggableView.DRAGGABLE_ICON) {
                int iconSize = layout.mActivity.getDeviceProfile().getWorkspaceProfile()
                        .getIconSizePx();
                int mw = droppedView.getMeasuredWidth();
                offsetX = (mw > 0 ? mw : itemW) / 2f;
                offsetY = droppedView.getPaddingTop() + iconSize / 2f;
            }
            // getVisualCenter is DragView origin + iconSize/2; the icon in the bitmap starts
            // at blur/2, matching animateViewIntoPosition.
            if (dv.getViewType() == DraggableView.DRAGGABLE_ICON) {
                float blurHalf = layout.getResources()
                        .getDimensionPixelSize(R.dimen.blur_size_medium_outline) / 2f;
                centerX += blurHalf;
                centerY += blurHalf;
            }
        }
        int layoutW = Math.max(1, container.getMeasuredWidth());
        int layoutH = Math.max(1, container.getMeasuredHeight());
        int x = Math.round(centerX - container.getLeft() - offsetX);
        int y = Math.round(centerY - container.getTop() - offsetY);
        x = Utilities.boundToRange(x, 0, Math.max(0, layoutW - itemW));
        y = Utilities.boundToRange(y, 0, Math.max(0, layoutH - itemH));
        info.freeX = toNorm(x, layoutW);
        info.freeY = toNorm(y, layoutH);
        int stepX = Math.max(1, layout.getCellWidth() + layout.mBorderSpace.x);
        int stepY = Math.max(1, layout.getCellHeight() + layout.mBorderSpace.y);
        info.cellX = Utilities.boundToRange(x / stepX, 0, Math.max(0, layout.getCountX() - spanX));
        info.cellY = Utilities.boundToRange(y / stepY, 0, Math.max(0, layout.getCountY() - spanY));
    }

    /**
     * Hosts the icon on the drag layer so a page snap cannot scroll it out of view.
     * The drag bitmap stays hidden; this view keeps its scale, rotation, and label.
     */
    public static void beginTransformedDrag(View view, int dragLayerX, int dragLayerY) {
        if (!viewIsTransformed(view) || sDragView != null) {
            return;
        }
        if (!(view.getParent() instanceof ShortcutAndWidgetContainer)
                || !(view.getLayoutParams() instanceof CellLayoutLayoutParams)) {
            return;
        }
        ShortcutAndWidgetContainer parent = (ShortcutAndWidgetContainer) view.getParent();
        CellLayoutLayoutParams lp = (CellLayoutLayoutParams) view.getLayoutParams();
        int width = Math.max(1, view.getWidth() > 0 ? view.getWidth() : view.getMeasuredWidth());
        int height = Math.max(1,
                view.getHeight() > 0 ? view.getHeight() : view.getMeasuredHeight());
        BaseDragLayer<?> layer = ActivityContext.lookupContext(view.getContext()).getDragLayer();
        float[] origin = new float[] {view.getX(), view.getY()};
        layer.getDescendantCoordRelativeToSelf(parent, origin);

        sDragView = view;
        sParent = parent;
        sIndex = parent.indexOfChild(view);
        sLp = lp;
        sBaseTx = view.getTranslationX();
        sBaseTy = view.getTranslationY();
        sOriginX = dragLayerX;
        sOriginY = dragLayerY;
        sScreenX = origin[0];
        sScreenY = origin[1];

        parent.removeView(view);
        view.setTranslationX(0f);
        view.setTranslationY(0f);
        BaseDragLayer.LayoutParams dlp = new BaseDragLayer.LayoutParams(width, height);
        dlp.ignoreInsets = true;
        dlp.customPosition = true;
        dlp.width = width;
        dlp.height = height;
        moveOnDragLayer(view, layer, dlp, Math.round(sScreenX), Math.round(sScreenY));
    }

    public static void followTransformedDrag(View view, int dragLayerX, int dragLayerY) {
        if (view == null || view != sDragView
                || !(view.getLayoutParams() instanceof BaseDragLayer.LayoutParams)) {
            return;
        }
        BaseDragLayer.LayoutParams dlp = (BaseDragLayer.LayoutParams) view.getLayoutParams();
        int x = Math.round(sScreenX + (dragLayerX - sOriginX));
        int y = Math.round(sScreenY + (dragLayerY - sOriginY));
        BaseDragLayer<?> layer = ActivityContext.lookupContext(view.getContext()).getDragLayer();
        moveOnDragLayer(view, layer, dlp, x, y);
    }

    /**
     * Drop landed on {@code target}. Puts the icon back in that page at the position under
     * the finger and writes {@code freeX}/{@code freeY}.
     */
    public static boolean placeTransformedDrag(View view, CellLayout target, ItemInfo info,
            int screenId) {
        if (view == null || view != sDragView || target == null || target instanceof Hotseat) {
            return false;
        }
        ShortcutAndWidgetContainer container = target.getShortcutsAndWidgets();
        BaseDragLayer<?> layer = ActivityContext.lookupContext(view.getContext()).getDragLayer();
        float[] local = new float[] {view.getX(), view.getY()};
        layer.mapCoordInSelfToDescendant(container, local);
        // A snap still in progress leaves the page short of its resting scroll. Keep the
        // icon where it looks now, once that scroll finishes.
        if (target.getParent() instanceof Workspace workspace) {
            int index = workspace.indexOfChild(target);
            if (index >= 0) {
                local[0] += workspace.getScrollForPage(index) - workspace.getScrollX();
            }
        }
        attachToContainer(view, container, -1);
        writeFreePosition(info, view, container, target, Math.round(local[0]), Math.round(local[1]),
                screenId);
        container.measureChild(view);
        container.layoutChild(view);
        clearDragState();
        return true;
    }

    /** Drop landed while the icon is still in its original page. */
    public static boolean commitTransformedDrag(ItemInfo info, View view) {
        if (view == null || view != sDragView
                || !(view.getParent() instanceof ShortcutAndWidgetContainer)) {
            return false;
        }
        ShortcutAndWidgetContainer container = (ShortcutAndWidgetContainer) view.getParent();
        if (!(container.getParent() instanceof CellLayout)) {
            return false;
        }
        CellLayout layout = (CellLayout) container.getParent();
        ItemInfo tag = info;
        if (view.getTag() instanceof ItemInfo item) {
            tag = item;
        }
        if (tag == null) {
            return false;
        }
        int layoutW = Math.max(1, container.getMeasuredWidth());
        int layoutH = Math.max(1, container.getMeasuredHeight());
        int x = Math.round(view.getX());
        int y = Math.round(view.getY());
        int itemW = view.getWidth() > 0 ? view.getWidth() : view.getMeasuredWidth();
        int itemH = view.getHeight() > 0 ? view.getHeight() : view.getMeasuredHeight();
        x = Utilities.boundToRange(x, 0, Math.max(0, layoutW - itemW));
        y = Utilities.boundToRange(y, 0, Math.max(0, layoutH - itemH));
        tag.freeX = toNorm(x, layoutW);
        tag.freeY = toNorm(y, layoutH);
        int stepX = Math.max(1, layout.getCellWidth() + layout.mBorderSpace.x);
        int stepY = Math.max(1, layout.getCellHeight() + layout.mBorderSpace.y);
        int spanX = tag.spanX > 0 ? tag.spanX : 1;
        int spanY = tag.spanY > 0 ? tag.spanY : 1;
        tag.cellX = Utilities.boundToRange(x / stepX, 0, Math.max(0, layout.getCountX() - spanX));
        tag.cellY = Utilities.boundToRange(y / stepY, 0, Math.max(0, layout.getCountY() - spanY));
        if (info != null && info != tag) {
            info.freeX = tag.freeX;
            info.freeY = tag.freeY;
            info.cellX = tag.cellX;
            info.cellY = tag.cellY;
        }
        view.setTranslationX(0f);
        view.setTranslationY(0f);
        container.measureChild(view);
        container.layoutChild(view);
        clearDragState();
        return true;
    }

    /**
     * Slides a desktop icon by a drag-layer delta. The layout box stays inside the page;
     * scale and rotation are unchanged.
     */
    public static void offsetFreeIcon(View view, float dx, float dy) {
        if (!(view.getParent() instanceof ShortcutAndWidgetContainer)) {
            return;
        }
        ShortcutAndWidgetContainer container = (ShortcutAndWidgetContainer) view.getParent();
        int layoutW = container.getMeasuredWidth();
        int layoutH = container.getMeasuredHeight();
        if (layoutW <= 0 || layoutH <= 0) {
            return;
        }
        int itemW = Math.max(1, view.getWidth() > 0 ? view.getWidth() : view.getMeasuredWidth());
        int itemH = Math.max(1, view.getHeight() > 0 ? view.getHeight() : view.getMeasuredHeight());
        float x = Utilities.boundToRange(view.getX() + dx, 0, Math.max(0, layoutW - itemW));
        float y = Utilities.boundToRange(view.getY() + dy, 0, Math.max(0, layoutH - itemH));
        view.setTranslationX(x - view.getLeft());
        view.setTranslationY(y - view.getTop());
    }

    /** Writes a translation from {@link #offsetFreeIcon} into {@code freeX}/{@code freeY}. */
    public static void commitIconTranslation(View view) {
        if (!(view.getParent() instanceof ShortcutAndWidgetContainer)) {
            return;
        }
        ShortcutAndWidgetContainer container = (ShortcutAndWidgetContainer) view.getParent();
        if (!(container.getParent() instanceof CellLayout) || !(view.getTag() instanceof ItemInfo)) {
            return;
        }
        CellLayout layout = (CellLayout) container.getParent();
        ItemInfo info = (ItemInfo) view.getTag();
        if (view.getTranslationX() == 0f && view.getTranslationY() == 0f) {
            return;
        }
        int x = Math.round(view.getX());
        int y = Math.round(view.getY());
        view.setTranslationX(0f);
        view.setTranslationY(0f);
        writeFreePosition(info, view, container, layout, x, y, info.screenId);
        container.measureChild(view);
        container.layoutChild(view);
    }

    /** The icon left the drag layer by some other drop path. Forget the host. */
    public static void abandonTransformedDrag(View view) {
        if (view != null && view == sDragView) {
            clearDragState();
        }
    }

    /** Drag ended without a new position. Put the icon back on its original page. */
    public static void cancelTransformedDrag(View view) {
        if (view == null || view != sDragView || sParent == null) {
            clearDragState();
            return;
        }
        attachToContainer(view, sParent, sIndex);
        view.setTranslationX(sBaseTx);
        view.setTranslationY(sBaseTy);
        sParent.measureChild(view);
        sParent.layoutChild(view);
        clearDragState();
    }

    private static void moveOnDragLayer(View view, BaseDragLayer<?> layer,
            BaseDragLayer.LayoutParams lp, int x, int y) {
        lp.x = x;
        lp.y = y;
        if (view.getParent() != layer) {
            layer.addView(view, lp);
        }
        int width = Math.max(1, lp.width);
        int height = Math.max(1, lp.height);
        view.layout(x, y, x + width, y + height);
    }

    private static void attachToContainer(View view, ShortcutAndWidgetContainer container,
            int index) {
        if (view.getParent() instanceof ViewGroup parent) {
            parent.removeView(view);
        }
        view.setTranslationX(0f);
        view.setTranslationY(0f);
        view.setLayoutParams(sLp);
        int at = index;
        if (at < 0 || at > container.getChildCount()) {
            at = container.getChildCount();
        }
        container.addView(view, at, sLp);
    }

    private static void writeFreePosition(ItemInfo info, View view,
            ShortcutAndWidgetContainer container, CellLayout layout, int x, int y, int screenId) {
        ItemInfo tag = info;
        if (view.getTag() instanceof ItemInfo item) {
            tag = item;
        }
        if (tag == null) {
            return;
        }
        int layoutW = Math.max(1, container.getMeasuredWidth());
        int layoutH = Math.max(1, container.getMeasuredHeight());
        int itemW = view.getWidth() > 0 ? view.getWidth() : view.getMeasuredWidth();
        int itemH = view.getHeight() > 0 ? view.getHeight() : view.getMeasuredHeight();
        x = Utilities.boundToRange(x, 0, Math.max(0, layoutW - Math.max(1, itemW)));
        y = Utilities.boundToRange(y, 0, Math.max(0, layoutH - Math.max(1, itemH)));
        tag.freeX = toNorm(x, layoutW);
        tag.freeY = toNorm(y, layoutH);
        int stepX = Math.max(1, layout.getCellWidth() + layout.mBorderSpace.x);
        int stepY = Math.max(1, layout.getCellHeight() + layout.mBorderSpace.y);
        int spanX = tag.spanX > 0 ? tag.spanX : 1;
        int spanY = tag.spanY > 0 ? tag.spanY : 1;
        tag.cellX = Utilities.boundToRange(x / stepX, 0, Math.max(0, layout.getCountX() - spanX));
        tag.cellY = Utilities.boundToRange(y / stepY, 0, Math.max(0, layout.getCountY() - spanY));
        if (screenId >= 0) {
            tag.screenId = screenId;
        }
        if (info != null && info != tag) {
            info.freeX = tag.freeX;
            info.freeY = tag.freeY;
            info.cellX = tag.cellX;
            info.cellY = tag.cellY;
            info.screenId = tag.screenId;
        }
    }

    private static void clearDragState() {
        sDragView = null;
        sParent = null;
        sLp = null;
        sIndex = -1;
    }

    public static void setFromView(ItemInfo info, View view) {
        if (!(view.getParent() instanceof ShortcutAndWidgetContainer container)) {
            return;
        }
        if (!(container.getParent() instanceof CellLayout layout)) {
            return;
        }
        CellLayoutLayoutParams lp = (CellLayoutLayoutParams) view.getLayoutParams();
        int layoutW = Math.max(1, container.getMeasuredWidth());
        int layoutH = Math.max(1, container.getMeasuredHeight());
        int x = Utilities.boundToRange(lp.x, 0, Math.max(0, layoutW - lp.width));
        int y = Utilities.boundToRange(lp.y, 0, Math.max(0, layoutH - lp.height));
        info.freeX = toNorm(x, layoutW);
        info.freeY = toNorm(y, layoutH);
        info.cellX = lp.getCellX();
        info.cellY = lp.getCellY();
    }

    /**
     * A new folder is created only when neither icon has been scaled or rotated.
     * Overlap of a transformed icon stays an overlap.
     */
    public static boolean canMergeIntoNewFolder(ItemInfo drag, View dropOver) {
        if (dropOver == null || !isEnabled(dropOver.getContext())) {
            return true;
        }
        if (hasIconTransform(drag)) {
            return false;
        }
        if (!(dropOver.getTag() instanceof ItemInfo)) {
            return true;
        }
        return !hasIconTransform((ItemInfo) dropOver.getTag());
    }

    public static View findDropOverView(CellLayout layout, float cellLayoutX, float cellLayoutY,
            View ignore) {
        ShortcutAndWidgetContainer container = layout.getShortcutsAndWidgets();
        View best = null;
        float bestDist = Float.MAX_VALUE;
        float[] center = new float[2];
        for (int i = 0; i < container.getChildCount(); i++) {
            View child = container.getChildAt(i);
            if (child == ignore || child.getId() == R.id.search_container_workspace) {
                continue;
            }
            if (child instanceof DraggableView dv
                    && dv.getViewType() == DraggableView.DRAGGABLE_WIDGET) {
                continue;
            }
            getDropTargetCenter(layout, child, center);
            float dist = (float) Math.hypot(cellLayoutX - center[0], cellLayoutY - center[1]);
            if (dist < folderCreationRadius(layout, child) && dist < bestDist) {
                bestDist = dist;
                best = child;
            }
        }
        return best;
    }

    /** Folder drop only when the drag center is almost on top of the target icon. */
    public static float folderCreationRadius(CellLayout layout, View dropOver) {
        int size = layout.mActivity.getDeviceProfile().getWorkspaceProfile().getIconSizePx();
        if (dropOver instanceof DraggableView dv
                && dv.getViewType() == DraggableView.DRAGGABLE_ICON) {
            Rect icon = new Rect();
            dv.getWorkspaceVisualDragBounds(icon);
            if (icon.width() > 0 && icon.height() > 0) {
                size = Math.min(icon.width(), icon.height());
            }
        }
        return size * FOLDER_OVERLAP;
    }

    public static void getDropTargetCenter(CellLayout layout, View child, float[] out) {
        ShortcutAndWidgetContainer container = layout.getShortcutsAndWidgets();
        float left = container.getLeft() + child.getX();
        float top = container.getTop() + child.getY();
        if (child instanceof DraggableView dv
                && dv.getViewType() == DraggableView.DRAGGABLE_ICON) {
            Rect icon = new Rect();
            dv.getWorkspaceVisualDragBounds(icon);
            out[0] = left + icon.centerX();
            out[1] = top + icon.centerY();
            return;
        }
        out[0] = left + child.getWidth() / 2f;
        out[1] = top + child.getHeight() / 2f;
    }

    public static float distanceToViewCenter(CellLayout layout, View child, float cellLayoutX,
            float cellLayoutY) {
        float[] center = new float[2];
        getDropTargetCenter(layout, child, center);
        return (float) Math.hypot(cellLayoutX - center[0], cellLayoutY - center[1]);
    }

    /** Move a cell-based drop rect so its origin matches the item's free-layout position. */
    public static void shiftDropRectToFreePosition(CellLayout layout, ItemInfo info, Rect r) {
        if (layout == null || info == null || r == null || !info.hasFreePosition()) {
            return;
        }
        ShortcutAndWidgetContainer container = layout.getShortcutsAndWidgets();
        int layoutW = Math.max(1, container.getMeasuredWidth());
        int layoutH = Math.max(1, container.getMeasuredHeight());
        int freeLeft = container.getLeft() + fromNorm(info.freeX, layoutW);
        int freeTop = container.getTop() + fromNorm(info.freeY, layoutH);
        r.offset(freeLeft - r.left, freeTop - r.top);
    }

    /** Move the folder-create preview from the grid cell onto the icon's real position. */
    public static void alignFolderPreview(PreviewBackground bg, CellLayout layout, View view) {
        if (bg == null || layout == null || view == null) {
            return;
        }
        CellLayoutLayoutParams lp = (CellLayoutLayoutParams) view.getLayoutParams();
        int[] cellOrigin = new int[2];
        layout.cellToPoint(lp.getCellX(), lp.getCellY(), cellOrigin);
        ShortcutAndWidgetContainer container = layout.getShortcutsAndWidgets();
        bg.mDelegateShiftX = Math.round(container.getLeft() + view.getX()) - cellOrigin[0];
        bg.mDelegateShiftY = Math.round(container.getTop() + view.getY()) - cellOrigin[1];
        layout.invalidate();
    }

    public static float distanceToViewCenter(CellLayout layout, View child, float[] visualCenter) {
        return distanceToViewCenter(layout, child, visualCenter[0], visualCenter[1]);
    }

    public static int nextDesktopRank(CellLayout layout) {
        int max = -1;
        ShortcutAndWidgetContainer container = layout.getShortcutsAndWidgets();
        for (int i = 0; i < container.getChildCount(); i++) {
            View v = container.getChildAt(i);
            if (v.getId() == R.id.search_container_workspace) {
                continue;
            }
            if (v.getTag() instanceof ItemInfo info
                    && info.container == LauncherSettings.Favorites.CONTAINER_DESKTOP) {
                max = Math.max(max, info.rank);
            }
        }
        return max + 1;
    }

    public static void bringDroppedToFront(CellLayout layout, View child) {
        if (!isActive(layout) || child == null
                || child.getId() == R.id.search_container_workspace) {
            return;
        }
        ShortcutAndWidgetContainer container = layout.getShortcutsAndWidgets();
        if (child.getParent() == container) {
            container.bringChildToFront(child);
        }
        if (child.getTag() instanceof ItemInfo info
                && (info.container == LauncherSettings.Favorites.CONTAINER_DESKTOP
                        || info.container <= 0)) {
            info.rank = nextDesktopRank(layout);
        }
    }

    public static String freeDbName(String baseDb) {
        if (isFreeDb(baseDb)) {
            return baseDb;
        }
        if (baseDb.endsWith(".db")) {
            return baseDb.substring(0, baseDb.length() - 3) + "_free.db";
        }
        return baseDb + "_free";
    }

    public static String baseDbName(String dbName) {
        if (dbName.endsWith("_free.db")) {
            return dbName.substring(0, dbName.length() - "_free.db".length()) + ".db";
        }
        if (dbName.endsWith("_free")) {
            return dbName.substring(0, dbName.length() - "_free".length());
        }
        return dbName;
    }

    public static boolean isFreeDb(String dbName) {
        return dbName.endsWith("_free.db") || dbName.endsWith("_free");
    }

    public static String otherDbName(String dbName) {
        return isFreeDb(dbName) ? baseDbName(dbName) : freeDbName(dbName);
    }

    public static boolean storeExists(Context context, String dbName) {
        File file = context.getDatabasePath(dbName);
        // A non-empty file is a store even if it cannot be opened right now. Returning false here
        // would copy the original DB over it and drop free-layout items.
        return file.exists() && file.length() > 0;
    }

    /** Snapshot {@code sourceDb} into {@code destName} only when dest does not already exist. */
    public static void copyStore(Context context, SQLiteDatabase sourceDb, String destName) {
        File dest = context.getDatabasePath(destName);
        if (dest.exists() && dest.length() > 0) {
            FileLog.d(TAG, "copyStore skipped, already exists: " + destName);
            return;
        }
        if (dest.exists()) {
            context.deleteDatabase(destName);
        }
        File parent = dest.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("Cannot create databases dir for " + destName);
        }
        String path = dest.getAbsolutePath().replace("'", "''");
        sourceDb.execSQL("VACUUM INTO '" + path + "'");
        FileLog.d(TAG, "Copied launcher store to " + destName);
    }

    public static IntSet widgetIdsInStore(Context context, String dbName) {
        IntSet ids = new IntSet();
        if (!storeExists(context, dbName)) {
            return ids;
        }
        File file = context.getDatabasePath(dbName);
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(file.getPath(), null,
                SQLiteDatabase.OPEN_READONLY);
             Cursor c = db.query(Favorites.TABLE_NAME, new String[]{Favorites.APPWIDGET_ID},
                     Favorites.ITEM_TYPE + "=" + Favorites.ITEM_TYPE_APPWIDGET,
                     null, null, null, null)) {
            while (c.moveToNext()) {
                ids.add(c.getInt(0));
            }
        } catch (Exception e) {
            Log.w(TAG, "widgetIdsInStore failed for " + dbName, e);
        }
        return ids;
    }

    private static int spanWidth(CellLayout layout, int spanX) {
        return spanX * layout.getCellWidth() + Math.max(0, spanX - 1) * layout.mBorderSpace.x;
    }

    private static int spanHeight(CellLayout layout, int spanY) {
        return spanY * layout.getCellHeight() + Math.max(0, spanY - 1) * layout.mBorderSpace.y;
    }

    private static int toNorm(int px, int size) {
        if (size <= 0) {
            return UNSET;
        }
        return Utilities.boundToRange(Math.round(px * (float) NORM / size), 0, NORM);
    }

    private static int fromNorm(int norm, int size) {
        return Math.round(norm * (float) size / NORM);
    }

    /** Desktop icons and folders in free layout. Widgets keep the cell resize frame. */
    public static boolean canTransform(View view) {
        if (view == null || !view.isAttachedToWindow() || view.getWidth() <= 0
                || view.getHeight() <= 0 || !isEnabled(view.getContext())) {
            return false;
        }
        if (!(view instanceof FreeIconTransformHost)) {
            return false;
        }
        return view.getTag() instanceof ItemInfo info
                && info.id != ItemInfo.NO_ID
                && info.container == Favorites.CONTAINER_DESKTOP;
    }

    public static boolean hasIconTransform(ItemInfo info) {
        return info != null && (Math.abs(info.freeScale - DEFAULT_ICON_SCALE) > 0.001f
                || Math.abs(info.freeRotation) > 0.1f);
    }

    public static float sanitizeScale(float scale) {
        if (!(scale > 0f) || Float.isNaN(scale) || Float.isInfinite(scale)) {
            return DEFAULT_ICON_SCALE;
        }
        return Utilities.boundToRange(scale, MIN_ICON_SCALE, MAX_ICON_SCALE);
    }

    public static float sanitizeRotation(float degrees) {
        if (Float.isNaN(degrees) || Float.isInfinite(degrees)) {
            return DEFAULT_ICON_ROTATION;
        }
        degrees %= 360f;
        if (degrees > 180f) {
            degrees -= 360f;
        } else if (degrees <= -180f) {
            degrees += 360f;
        }
        return degrees;
    }

    /** Applies the stored glyph transform, or clears it when free layout is off. */
    public static void applyIconTransform(View child) {
        if (!(child instanceof FreeIconTransformHost host)) {
            return;
        }
        float scale = DEFAULT_ICON_SCALE;
        float rotation = DEFAULT_ICON_ROTATION;
        if (isEnabled(child.getContext())
                && child.getTag() instanceof ItemInfo info
                && info.container == Favorites.CONTAINER_DESKTOP) {
            scale = sanitizeScale(info.freeScale);
            rotation = Float.isNaN(info.freeRotation) ? DEFAULT_ICON_ROTATION : info.freeRotation;
        }
        host.setFreeIconTransform(scale, rotation);
    }

    /**
     * Scales and rotates the whole item, name included, around the icon center.
     * Pivot is applied while the transform is identity so the center does not jump.
     */
    public static void applyTransformToView(View view, Rect pivotBounds, float scale,
            float rotation) {
        if (view == null || pivotBounds.width() <= 0 || pivotBounds.height() <= 0) {
            return;
        }
        float pivotX = pivotBounds.exactCenterX();
        float pivotY = pivotBounds.exactCenterY();
        if (Float.compare(view.getScaleX(), scale) == 0
                && Float.compare(view.getScaleY(), scale) == 0
                && Float.compare(view.getRotation(), rotation) == 0
                && Float.compare(view.getPivotX(), pivotX) == 0
                && Float.compare(view.getPivotY(), pivotY) == 0) {
            return;
        }
        if (view.getScaleX() != 1f || view.getScaleY() != 1f || view.getRotation() != 0f) {
            view.setScaleX(1f);
            view.setScaleY(1f);
            view.setRotation(0f);
        }
        view.setPivotX(pivotX);
        view.setPivotY(pivotY);
        view.setScaleX(scale);
        view.setScaleY(scale);
        view.setRotation(rotation);
    }

    /**
     * Popup pre-drag hides the glyph and may leave the label at alpha 0. Put the icon back after
     * that pre-drag is cancelled so the transform frame has something to draw around.
     */
    public static void restoreIconPresentation(View icon) {
        if (icon == null) {
            return;
        }
        icon.setVisibility(View.VISIBLE);
        if (icon instanceof IconViewController controller) {
            controller.setIconVisible(true);
            controller.setForceHideDot(false);
            if (controller.getFloatingViewTextAlpha() != null) {
                controller.getFloatingViewTextAlpha().setValue(1f);
            }
        }
    }

}
