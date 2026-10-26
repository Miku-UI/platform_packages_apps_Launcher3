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

package com.android.launcher3.widget.resize;

import static com.android.launcher3.AbstractFloatingView.TYPE_WIDGET_RESIZE_FRAME;

import android.appwidget.AppWidgetProviderInfo;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import com.android.launcher3.AbstractFloatingView;
import com.android.launcher3.Launcher;
import com.android.launcher3.R;
import com.android.launcher3.Utilities;
import com.android.launcher3.WorkspaceFreeMode;
import com.android.launcher3.model.data.ItemInfo;
import com.android.launcher3.util.Themes;
import com.android.launcher3.views.BaseDragLayer;
import com.android.launcher3.widget.LauncherAppWidgetHostView;

/**
 * Top-left restore and bottom-right rotate handles for a free-layout widget. They sit on the
 * widget's visual corners. A drag that starts on the widget body moves it, the same way an icon
 * edit frame does. Resize edges stay with the resize frame.
 */
public class FreeWidgetRotateHandles extends AbstractFloatingView {

    private static final int HANDLE_NONE = -1;
    private static final int HANDLE_RESET = 0;
    private static final int HANDLE_ROTATE = 1;

    private final Launcher mLauncher;
    private final AppWidgetResizeFrameCompose mFrame;
    private final LauncherAppWidgetHostView mWidget;
    private final Paint mHandleFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mHandleStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mResetPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mResetArrow = new Path();
    private final RectF mResetArc = new RectF();
    private final float[] mMapped = new float[2];
    private final float[] mWidgetPoint = new float[2];

    private final float mHandleRadius;
    private final float mHandleTouchRadius;
    private final float mResizeInsetX;
    private final float mResizeInsetY;

    private float mCenterX;
    private float mCenterY;
    private float mResetX;
    private float mResetY;
    private float mRotateX;
    private float mRotateY;
    private boolean mGeometryValid;

    private int mActivePointer = -1;
    private int mActiveHandle = HANDLE_NONE;
    private boolean mResetArmed;
    private boolean mMoving;
    private boolean mPositionDirty;
    private float mMoveLastX;
    private float mMoveLastY;
    private float mGrabAngle;
    private float mGrabRotation;
    private boolean mDirty;
    private boolean mDismissPosted;

    private final ViewTreeObserver.OnPreDrawListener mFollow = this::followWidget;

    private FreeWidgetRotateHandles(Context context, AppWidgetResizeFrameCompose frame,
            LauncherAppWidgetHostView widget) {
        super(context, null);
        mLauncher = Launcher.getLauncher(context);
        mFrame = frame;
        mWidget = widget;
        mHandleRadius = Utilities.dpToPx(9);
        mHandleTouchRadius = Utilities.dpToPx(28);
        // The resize strip hangs halfway inside the widget. That band stays a resize, not a move.
        float resizeInset = context.getResources().getDimensionPixelSize(
                R.dimen.resize_frame_touch_target_size) / 2f;
        int resizeMode = 0;
        AppWidgetProviderInfo provider = widget.getAppWidgetInfo();
        if (provider != null) {
            resizeMode = provider.resizeMode;
        }
        mResizeInsetX = (resizeMode & AppWidgetProviderInfo.RESIZE_HORIZONTAL) != 0
                ? resizeInset : 0f;
        mResizeInsetY = (resizeMode & AppWidgetProviderInfo.RESIZE_VERTICAL) != 0
                ? resizeInset : 0f;

        int accent = Themes.getAttrColor(context, R.attr.workspaceAccentColor);
        float stroke = Utilities.dpToPx(2);
        mHandleFill.setStyle(Paint.Style.FILL);
        mHandleFill.setColor(0xFFFFFFFF);
        mHandleStroke.setStyle(Paint.Style.STROKE);
        mHandleStroke.setStrokeWidth(stroke);
        mHandleStroke.setColor(accent);
        mResetPaint.setStyle(Paint.Style.STROKE);
        mResetPaint.setStrokeWidth(Utilities.dpToPx(1.7f));
        mResetPaint.setStrokeCap(Paint.Cap.ROUND);
        mResetPaint.setStrokeJoin(Paint.Join.ROUND);
        mResetPaint.setColor(0xFF1B1B1F);

        setWillNotDraw(false);
        setClickable(false);
        widget.getViewTreeObserver().addOnPreDrawListener(mFollow);
    }

    static FreeWidgetRotateHandles show(AppWidgetResizeFrameCompose frame,
            LauncherAppWidgetHostView widget) {
        Launcher launcher = Launcher.getLauncher(frame.getContext());
        FreeWidgetRotateHandles handles = new FreeWidgetRotateHandles(
                frame.getContext(), frame, widget);
        BaseDragLayer.LayoutParams lp = new BaseDragLayer.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        lp.ignoreInsets = true;
        handles.setElevation(Utilities.dpToPx(2));
        launcher.getDragLayer().addView(handles, lp);
        handles.mIsOpen = true;
        return handles;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        // Unclaimed touches belong to the resize frame underneath.
        return false;
    }

    @Override
    protected void handleClose(boolean animate) {
        if (mWidget != null) {
            mWidget.getViewTreeObserver().removeOnPreDrawListener(mFollow);
        }
        commit();
        if (getParent() instanceof ViewGroup parent) {
            parent.removeView(this);
        }
    }

    @Override
    protected boolean isOfType(int type) {
        return (type & TYPE_WIDGET_RESIZE_FRAME) != 0;
    }

    @Override
    public boolean canInterceptEventsInSystemGestureRegion() {
        return true;
    }

    @Override
    public boolean onControllerInterceptTouchEvent(MotionEvent ev) {
        if (ev.getActionMasked() != MotionEvent.ACTION_DOWN) {
            return false;
        }
        if (!toLocal(ev, 0)) {
            return false;
        }
        float x = mMapped[0];
        float y = mMapped[1];
        if (handleAt(x, y) != HANDLE_NONE) {
            return true;
        }
        if (insideWidgetBody(ev.getX(), ev.getY())) {
            return true;
        }
        if (!mFrame.containsControllerTouch(ev)) {
            return mFrame.handleOutsideControllerTouch(ev);
        }
        return false;
    }

    @Override
    public boolean onControllerTouchEvent(MotionEvent ev) {
        if (!mIsOpen) {
            return true;
        }
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                mActivePointer = ev.getPointerId(0);
                if (!toLocal(ev, 0)) {
                    return true;
                }
                float downX = mMapped[0];
                float downY = mMapped[1];
                mActiveHandle = handleAt(downX, downY);
                mResetArmed = mActiveHandle == HANDLE_RESET;
                mMoving = false;
                if (mActiveHandle == HANDLE_ROTATE) {
                    beginRotate(downX, downY);
                } else if (mActiveHandle == HANDLE_NONE
                        && insideWidgetBody(ev.getX(), ev.getY())) {
                    mMoving = true;
                    mMoveLastX = ev.getX();
                    mMoveLastY = ev.getY();
                }
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                int index = ev.findPointerIndex(mActivePointer);
                if (index < 0) {
                    return true;
                }
                if (mMoving) {
                    float x = ev.getX(index);
                    float y = ev.getY(index);
                    float dx = x - mMoveLastX;
                    float dy = y - mMoveLastY;
                    if (dx != 0f || dy != 0f) {
                        mFrame.dismissPopupMenu();
                        WorkspaceFreeMode.offsetFreeIcon(mWidget, dx, dy);
                        mMoveLastX = x;
                        mMoveLastY = y;
                        mPositionDirty = true;
                        mFrame.followMovedWidget();
                    }
                    return true;
                }
                if (!toLocal(ev, index)) {
                    return true;
                }
                float x = mMapped[0];
                float y = mMapped[1];
                if (mResetArmed && handleAt(x, y) != HANDLE_RESET) {
                    mResetArmed = false;
                    mActiveHandle = HANDLE_NONE;
                } else if (mActiveHandle == HANDLE_ROTATE) {
                    updateRotate(x, y);
                }
                return true;
            }
            case MotionEvent.ACTION_POINTER_UP:
                if (ev.getPointerId(ev.getActionIndex()) == mActivePointer) {
                    finishGesture();
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (mResetArmed) {
                    resetRotation();
                } else {
                    finishGesture();
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                finishGesture();
                return true;
            default:
                return true;
        }
    }

    private boolean followWidget() {
        if (mDismissPosted) {
            return true;
        }
        if (!mWidget.isAttachedToWindow() || !WorkspaceFreeMode.isEnabled(getContext())) {
            mDismissPosted = true;
            post(() -> mFrame.close(false));
            return true;
        }
        if (computeGeometry()) {
            invalidate();
        }
        return true;
    }

    private boolean computeGeometry() {
        int width = mWidget.getWidth();
        int height = mWidget.getHeight();
        if (width <= 0 || height <= 0) {
            mGeometryValid = false;
            return false;
        }
        if (!mapWidgetPoint(width / 2f, height / 2f)) {
            mGeometryValid = false;
            return false;
        }
        mCenterX = mMapped[0];
        mCenterY = mMapped[1];
        if (!mapWidgetPoint(0f, 0f)) {
            mGeometryValid = false;
            return false;
        }
        mResetX = mMapped[0];
        mResetY = mMapped[1];
        if (!mapWidgetPoint(width, height)) {
            mGeometryValid = false;
            return false;
        }
        mRotateX = mMapped[0];
        mRotateY = mMapped[1];
        mGeometryValid = true;
        return true;
    }

    private boolean mapWidgetPoint(float x, float y) {
        mMapped[0] = x;
        mMapped[1] = y;
        mLauncher.getDragLayer().getDescendantCoordRelativeToSelf(mWidget, mMapped);
        mMapped[0] -= getX();
        mMapped[1] -= getY();
        return true;
    }

    private boolean toLocal(MotionEvent ev, int index) {
        mMapped[0] = ev.getX(index) - getX();
        mMapped[1] = ev.getY(index) - getY();
        return true;
    }

    private int handleAt(float x, float y) {
        if (!mGeometryValid && !computeGeometry()) {
            return HANDLE_NONE;
        }
        if (dist(x, y, mResetX, mResetY) <= mHandleTouchRadius) {
            return HANDLE_RESET;
        }
        if (dist(x, y, mRotateX, mRotateY) <= mHandleTouchRadius) {
            return HANDLE_ROTATE;
        }
        return HANDLE_NONE;
    }

    private static float dist(float x, float y, float cx, float cy) {
        return (float) Math.hypot(x - cx, y - cy);
    }

    private void beginRotate(float x, float y) {
        if (!(mWidget.getTag() instanceof ItemInfo)) {
            mActiveHandle = HANDLE_NONE;
            return;
        }
        mGrabAngle = (float) Math.atan2(y - mCenterY, x - mCenterX);
        mGrabRotation = ((ItemInfo) mWidget.getTag()).freeRotation;
    }

    private void updateRotate(float x, float y) {
        if (!(mWidget.getTag() instanceof ItemInfo info)) {
            return;
        }
        float angle = (float) Math.atan2(y - mCenterY, x - mCenterX);
        float delta = (float) Math.toDegrees(angle - mGrabAngle);
        if (delta > 180f) {
            delta -= 360f;
        } else if (delta < -180f) {
            delta += 360f;
        }
        info.freeRotation = WorkspaceFreeMode.sanitizeRotation(mGrabRotation + delta);
        WorkspaceFreeMode.applyWidgetRotation(mWidget);
        mDirty = true;
        computeGeometry();
        invalidate();
    }

    private void resetRotation() {
        mResetArmed = false;
        mActiveHandle = HANDLE_NONE;
        mActivePointer = -1;
        if (!(mWidget.getTag() instanceof ItemInfo info)) {
            return;
        }
        if (Math.abs(info.freeRotation) <= 0.1f) {
            return;
        }
        info.freeRotation = WorkspaceFreeMode.DEFAULT_ICON_ROTATION;
        WorkspaceFreeMode.applyWidgetRotation(mWidget);
        mDirty = true;
        computeGeometry();
        invalidate();
        commit();
    }

    private void finishGesture() {
        mMoving = false;
        mResetArmed = false;
        mActiveHandle = HANDLE_NONE;
        mActivePointer = -1;
        if (mPositionDirty) {
            WorkspaceFreeMode.commitIconTranslation(mWidget);
            mFrame.followMovedWidget();
            mPositionDirty = false;
            mDirty = true;
        }
        commit();
    }

    /** Widget body, inside the resize strips. Coordinates are drag-layer. */
    private boolean insideWidgetBody(float dragLayerX, float dragLayerY) {
        int width = mWidget.getWidth();
        int height = mWidget.getHeight();
        if (width <= 0 || height <= 0) {
            return false;
        }
        if (width <= mResizeInsetX * 2f || height <= mResizeInsetY * 2f) {
            return false;
        }
        mWidgetPoint[0] = dragLayerX;
        mWidgetPoint[1] = dragLayerY;
        mLauncher.getDragLayer().mapCoordInSelfToDescendant(mWidget, mWidgetPoint);
        return mWidgetPoint[0] >= mResizeInsetX
                && mWidgetPoint[1] >= mResizeInsetY
                && mWidgetPoint[0] <= width - mResizeInsetX
                && mWidgetPoint[1] <= height - mResizeInsetY;
    }

    private void commit() {
        if (!mDirty || !(mWidget.getTag() instanceof ItemInfo info)) {
            mDirty = false;
            return;
        }
        info.freeRotation = WorkspaceFreeMode.sanitizeRotation(info.freeRotation);
        WorkspaceFreeMode.applyWidgetRotation(mWidget);
        mLauncher.getModelWriter().updateItemInDatabase(info);
        mDirty = false;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!mGeometryValid) {
            return;
        }
        drawHandle(canvas, mResetX, mResetY, true);
        drawHandle(canvas, mRotateX, mRotateY, false);
    }

    private void drawHandle(Canvas canvas, float x, float y, boolean reset) {
        canvas.drawCircle(x, y, mHandleRadius, mHandleFill);
        canvas.drawCircle(x, y, mHandleRadius, mHandleStroke);
        if (reset) {
            drawResetGlyph(canvas, x, y);
        }
    }

    /** Refresh mark, kept upright while the widget is rotated. */
    private void drawResetGlyph(Canvas canvas, float x, float y) {
        canvas.save();
        canvas.translate(x, y);
        canvas.rotate(-mWidget.getRotation());
        float radius = mHandleRadius * 0.52f;
        mResetArc.set(-radius, -radius, radius, radius);
        canvas.drawArc(mResetArc, 40f, 280f, false, mResetPaint);
        double end = Math.toRadians(40f + 280f);
        float ex = radius * (float) Math.cos(end);
        float ey = radius * (float) Math.sin(end);
        float tx = -(float) Math.sin(end);
        float ty = (float) Math.cos(end);
        float head = mHandleRadius * 0.34f;
        mResetArrow.rewind();
        mResetArrow.moveTo(ex + tx * head, ey + ty * head);
        mResetArrow.lineTo(ex - ty * head * 0.72f, ey + tx * head * 0.72f);
        mResetArrow.lineTo(ex + ty * head * 0.72f, ey - tx * head * 0.72f);
        mResetArrow.close();
        mResetPaint.setStyle(Paint.Style.FILL);
        canvas.drawPath(mResetArrow, mResetPaint);
        mResetPaint.setStyle(Paint.Style.STROKE);
        canvas.restore();
    }
}
