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

import com.android.launcher3.LauncherSettings.Favorites;
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
    private static final int NORM = 10000;
    /** Center-to-center distance as a fraction of the icon's visual size. */
    private static final float FOLDER_OVERLAP = 0.2f;
    private static final String TAG = "WorkspaceFreeMode";

    private WorkspaceFreeMode() {}

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
}
