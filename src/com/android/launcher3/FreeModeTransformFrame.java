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

import static com.android.launcher3.AbstractFloatingView.TYPE_FREE_ICON_TRANSFORM;
import static com.android.launcher3.LauncherState.EDIT_MODE;
import static com.android.launcher3.LauncherState.NORMAL;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import com.android.launcher3.model.data.ItemInfo;
import com.android.launcher3.util.Themes;
import com.android.launcher3.views.BaseDragLayer;

/**
 * Square frame drawn around a free-layout icon. The top-left handle restores scale and rotation.
 * The other corners scale and rotate around the icon center. A drag that starts inside the frame,
 * off the handles, moves the icon.
 */
public class FreeModeTransformFrame extends AbstractFloatingView {

    private static final int HANDLE_COUNT = 4;
    /** Top-left corner of the frame, in the frame's own coordinates. */
    private static final int HANDLE_RESET = 0;

    private final Launcher mLauncher;
    private final Rect mIconBounds = new Rect();
    private final RectF mFrameRect = new RectF();
    private final float[] mCorner = new float[2];
    private final Paint mFramePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mHandleFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mHandleStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mResetPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mResetArrow = new Path();
    private final RectF mResetArc = new RectF();

    private final int mOutset;
    private final int mCornerRadius;
    private final float mHandleRadius;
    private final float mHandleTouchRadius;

    private View mIcon;
    private float mCenterX;
    private float mCenterY;
    private float mHalf;
    private float mRotation;
    private boolean mGeometryValid;

    private final float[] mTouch = new float[2];
    private int mActivePointer = -1;
    private int mActiveHandle = -1;
    private boolean mResetArmed;
    private boolean mMoving;
    private float mMoveLastX;
    private float mMoveLastY;
    private boolean mDownOutside;
    private float mGrabDist;
    private float mGrabAngle;
    private float mGrabHalf;
    private float mGrabScale;
    private float mGrabRotation;
    private boolean mDirty;
    private boolean mDismissPosted;
    private ObjectAnimator mFadeIn;

    private final ViewTreeObserver.OnPreDrawListener mFollow = this::followIcon;

    public FreeModeTransformFrame(Context context, View icon) {
        super(context, null);
        mLauncher = Launcher.getLauncher(context);
        mIcon = icon;
        mOutset = Utilities.dpToPx(10);
        mCornerRadius = Utilities.dpToPx(8);
        mHandleRadius = Utilities.dpToPx(9);
        // Big enough to grab, small enough that the frame interior still moves the icon.
        mHandleTouchRadius = Utilities.dpToPx(28);

        int accent = Themes.getAttrColor(context, R.attr.workspaceAccentColor);
        float stroke = Utilities.dpToPx(2);
        mFramePaint.setStyle(Paint.Style.STROKE);
        mFramePaint.setStrokeWidth(stroke);
        mFramePaint.setColor(accent);
        mHandleFill.setStyle(Paint.Style.FILL);
        mHandleFill.setColor(0xFFFFFFFF);
        mHandleStroke.setStyle(Paint.Style.STROKE);
        mHandleStroke.setStrokeWidth(stroke);
        mHandleStroke.setColor(accent);
        mResetPaint.setStyle(Paint.Style.STROKE);
        mResetPaint.setStrokeWidth(Utilities.dpToPx(1.7f));
        mResetPaint.setStrokeCap(Paint.Cap.ROUND);
        mResetPaint.setStrokeJoin(Paint.Join.ROUND);
        // Accent on the white handle is too faint to read as a refresh mark.
        mResetPaint.setColor(0xFF1B1B1F);

        setWillNotDraw(false);
        setClipChildren(false);
        setElevation(Utilities.dpToPx(2));
        icon.getViewTreeObserver().addOnPreDrawListener(mFollow);
        setAlpha(0f);
        mFadeIn = ObjectAnimator.ofFloat(this, ALPHA, 0f, 1f);
        mFadeIn.setDuration(150);
        mFadeIn.start();
    }

    /** Shows the frame for {@code icon} and closes any frame already open. */
    public static void show(Launcher launcher, View icon) {
        dismiss(launcher);
        if (!(icon instanceof FreeIconTransformHost) || !icon.isAttachedToWindow()) {
            return;
        }
        FreeModeTransformFrame frame = new FreeModeTransformFrame(launcher, icon);
        BaseDragLayer.LayoutParams lp = new BaseDragLayer.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        // Insets would shift this view while touch events stay in DragLayer space.
        lp.ignoreInsets = true;
        launcher.getDragLayer().addView(frame, lp);
        frame.mIsOpen = true;
    }

    public static void dismiss(Launcher launcher) {
        AbstractFloatingView.closeOpenViews(launcher, false, TYPE_FREE_ICON_TRANSFORM);
    }

    @Override
    protected void handleClose(boolean animate) {
        if (mFadeIn != null) {
            mFadeIn.cancel();
            mFadeIn = null;
        }
        commit();
        if (mIcon != null) {
            mIcon.getViewTreeObserver().removeOnPreDrawListener(mFollow);
            mIcon = null;
        }
        if (getParent() instanceof ViewGroup parent) {
            parent.removeView(this);
        }
    }

    @Override
    protected boolean isOfType(int type) {
        return (type & TYPE_FREE_ICON_TRANSFORM) != 0;
    }

    @Override
    public boolean canInterceptEventsInSystemGestureRegion() {
        return true;
    }

    @Override
    public boolean onControllerInterceptTouchEvent(MotionEvent ev) {
        return mIsOpen;
    }

    @Override
    public boolean onControllerTouchEvent(MotionEvent ev) {
        if (mIcon == null) {
            return true;
        }
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mActivePointer = ev.getPointerId(0);
                if (!eventLocal(ev, 0)) {
                    return true;
                }
                mActiveHandle = handleAt(mTouch[0], mTouch[1]);
                mResetArmed = mActiveHandle == HANDLE_RESET;
                mDownOutside = isOutsideFrame(mTouch[0], mTouch[1]);
                mMoving = false;
                if (mResetArmed) {
                    // A tap restores the icon. Dragging this corner does not scale it.
                } else if (mActiveHandle >= 0) {
                    beginGrab(mTouch[0], mTouch[1]);
                } else if (!mDownOutside) {
                    mMoving = true;
                    mMoveLastX = mTouch[0];
                    mMoveLastY = mTouch[1];
                }
                return true;
            case MotionEvent.ACTION_MOVE: {
                int index = ev.findPointerIndex(mActivePointer);
                if (index < 0 || !eventLocal(ev, index)) {
                    return true;
                }
                if (mResetArmed) {
                    if (handleAt(mTouch[0], mTouch[1]) != HANDLE_RESET) {
                        mResetArmed = false;
                        mActiveHandle = -1;
                    }
                } else if (mMoving) {
                    WorkspaceFreeMode.offsetFreeIcon(mIcon, mTouch[0] - mMoveLastX,
                            mTouch[1] - mMoveLastY);
                    mMoveLastX = mTouch[0];
                    mMoveLastY = mTouch[1];
                    mDirty = true;
                } else if (mActiveHandle >= 0) {
                    updateGrab(mTouch[0], mTouch[1]);
                }
                return true;
            }
            case MotionEvent.ACTION_POINTER_UP:
                if (ev.getPointerId(ev.getActionIndex()) == mActivePointer) {
                    if (mResetArmed) {
                        resetIconTransform();
                    } else {
                        finishGrab();
                    }
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (mResetArmed) {
                    resetIconTransform();
                } else if (!mMoving && mActiveHandle < 0 && mDownOutside) {
                    close(false);
                } else {
                    finishGrab();
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                finishGrab();
                return true;
            default:
                return true;
        }
    }

    /** Controller events are in DragLayer coordinates. Corners are drawn in this view. */
    private boolean eventLocal(MotionEvent ev, int index) {
        mTouch[0] = ev.getX(index) - getX();
        mTouch[1] = ev.getY(index) - getY();
        return true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!mGeometryValid) {
            return;
        }
        canvas.save();
        canvas.translate(mCenterX, mCenterY);
        canvas.rotate(mRotation);
        float radius = Math.min(mCornerRadius, mHalf);
        mFrameRect.set(-mHalf, -mHalf, mHalf, mHalf);
        canvas.drawRoundRect(mFrameRect, radius, radius, mFramePaint);
        for (int i = 0; i < HANDLE_COUNT; i++) {
            cornerLocal(i, mCorner);
            canvas.drawCircle(mCorner[0], mCorner[1], mHandleRadius, mHandleFill);
            canvas.drawCircle(mCorner[0], mCorner[1], mHandleRadius, mHandleStroke);
            if (i == HANDLE_RESET) {
                drawResetGlyph(canvas, mCorner[0], mCorner[1]);
            }
        }
        canvas.restore();
    }

    /** Refresh mark, kept upright on screen while the frame itself is rotated. */
    private void drawResetGlyph(Canvas canvas, float x, float y) {
        canvas.save();
        canvas.translate(x, y);
        canvas.rotate(-mRotation);
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

    private boolean followIcon() {
        if (mDismissPosted) {
            return true;
        }
        if (mIcon == null || !mIcon.isAttachedToWindow()
                || !WorkspaceFreeMode.isEnabled(getContext())
                || (!mLauncher.isInState(NORMAL) && !mLauncher.isInState(EDIT_MODE))) {
            mDismissPosted = true;
            post(() -> close(false));
            return true;
        }
        float prevX = mCenterX;
        float prevY = mCenterY;
        float prevHalf = mHalf;
        float prevRotation = mRotation;
        if (computeGeometry() && (Math.abs(prevX - mCenterX) > 0.5f
                || Math.abs(prevY - mCenterY) > 0.5f
                || Math.abs(prevHalf - mHalf) > 0.5f
                || Math.abs(prevRotation - mRotation) > 0.1f)) {
            invalidate();
        }
        return true;
    }

    private boolean computeGeometry() {
        if (!(mIcon instanceof FreeIconTransformHost host)
                || !(mIcon.getTag() instanceof ItemInfo info)) {
            mGeometryValid = false;
            return false;
        }
        host.getFreeIconBounds(mIconBounds);
        if (mIconBounds.width() <= 0 || mIconBounds.height() <= 0) {
            mGeometryValid = false;
            return false;
        }
        float[] center = new float[] {
                mIconBounds.exactCenterX(), mIconBounds.exactCenterY()};
        float descScale = mLauncher.getDragLayer().getDescendantCoordRelativeToSelf(mIcon, center);
        center[0] -= getX();
        center[1] -= getY();
        // descScale already includes the view scale, which is the free-mode scale.
        float half = Math.max(mIconBounds.width(), mIconBounds.height()) / 2f
                * descScale + mOutset;
        mCenterX = center[0];
        mCenterY = center[1];
        mHalf = half;
        mRotation = info.freeRotation;
        mGeometryValid = true;
        return true;
    }

    private void cornerLocal(int index, float[] out) {
        float sx = (index == 0 || index == 3) ? -1f : 1f;
        float sy = (index == 0 || index == 1) ? -1f : 1f;
        out[0] = sx * mHalf;
        out[1] = sy * mHalf;
    }

    /** Corner center in this view's coordinates. Canvas.rotate is clockwise. */
    private void cornerOnScreen(int index, float[] out) {
        cornerLocal(index, out);
        double rad = Math.toRadians(mRotation);
        float cos = (float) Math.cos(rad);
        float sin = (float) Math.sin(rad);
        // Same matrix as Canvas.rotate: positive degrees turn the top edge to the right.
        float x = out[0] * cos - out[1] * sin;
        float y = out[0] * sin + out[1] * cos;
        out[0] = mCenterX + x;
        out[1] = mCenterY + y;
    }

    private boolean isOutsideFrame(float x, float y) {
        if (!mGeometryValid && !computeGeometry()) {
            return true;
        }
        double rad = Math.toRadians(mRotation);
        float cos = (float) Math.cos(rad);
        float sin = (float) Math.sin(rad);
        float dx = x - mCenterX;
        float dy = y - mCenterY;
        float lx = dx * cos + dy * sin;
        float ly = -dx * sin + dy * cos;
        float limit = mHalf + mHandleTouchRadius;
        return Math.abs(lx) > limit || Math.abs(ly) > limit;
    }

    private int handleAt(float x, float y) {
        if (!computeGeometry()) {
            return -1;
        }
        int best = -1;
        float bestDist = mHandleTouchRadius;
        for (int i = 0; i < HANDLE_COUNT; i++) {
            cornerOnScreen(i, mCorner);
            float dist = (float) Math.hypot(x - mCorner[0], y - mCorner[1]);
            if (dist <= bestDist) {
                bestDist = dist;
                best = i;
            }
        }
        return best;
    }

    private void beginGrab(float x, float y) {
        if (!(mIcon.getTag() instanceof ItemInfo info)) {
            mActiveHandle = -1;
            return;
        }
        float dx = x - mCenterX;
        float dy = y - mCenterY;
        mGrabDist = (float) Math.hypot(dx, dy);
        mGrabAngle = (float) Math.atan2(dy, dx);
        mGrabHalf = mHalf;
        mGrabScale = info.freeScale;
        mGrabRotation = info.freeRotation;
        if (mGrabDist < 1f) {
            mActiveHandle = -1;
        }
    }

    private void updateGrab(float x, float y) {
        if (mActiveHandle < 0 || mGrabDist < 1f
                || !(mIcon instanceof FreeIconTransformHost host)
                || !(mIcon.getTag() instanceof ItemInfo info)) {
            return;
        }
        float dx = x - mCenterX;
        float dy = y - mCenterY;
        float dist = (float) Math.hypot(dx, dy);
        float angle = (float) Math.atan2(dy, dx);
        float delta = (float) Math.toDegrees(angle - mGrabAngle);
        if (delta > 180f) {
            delta -= 360f;
        } else if (delta < -180f) {
            delta += 360f;
        }
        // Keep the grabbed corner on the finger. The outset stays a fixed gap, so scale is solved
        // from the frame size rather than from icon size alone.
        float iconHalf = Math.max(1f, mGrabHalf - mOutset);
        float newHalf = mGrabHalf * (dist / mGrabDist);
        float scale = Utilities.boundToRange(
                mGrabScale * Math.max(0f, newHalf - mOutset) / iconHalf,
                WorkspaceFreeMode.MIN_ICON_SCALE, WorkspaceFreeMode.MAX_ICON_SCALE);
        float rotation = mGrabRotation + delta;
        info.freeScale = scale;
        info.freeRotation = rotation;
        host.setFreeIconTransform(scale, rotation);
        mDirty = true;
        computeGeometry();
        invalidate();
    }

    private void resetIconTransform() {
        mResetArmed = false;
        mActiveHandle = -1;
        mActivePointer = -1;
        if (!(mIcon instanceof FreeIconTransformHost host)
                || !(mIcon.getTag() instanceof ItemInfo info)) {
            return;
        }
        if (!WorkspaceFreeMode.hasIconTransform(info)) {
            return;
        }
        info.freeScale = WorkspaceFreeMode.DEFAULT_ICON_SCALE;
        info.freeRotation = WorkspaceFreeMode.DEFAULT_ICON_ROTATION;
        host.setFreeIconTransform(info.freeScale, info.freeRotation);
        mDirty = true;
        computeGeometry();
        invalidate();
        commit();
    }

    private void finishGrab() {
        if (mMoving && mIcon != null) {
            WorkspaceFreeMode.commitIconTranslation(mIcon);
        }
        mMoving = false;
        mResetArmed = false;
        mActiveHandle = -1;
        mActivePointer = -1;
        commit();
    }

    private void commit() {
        if (!mDirty || mIcon == null || !(mIcon.getTag() instanceof ItemInfo info)) {
            mDirty = false;
            return;
        }
        info.freeScale = WorkspaceFreeMode.sanitizeScale(info.freeScale);
        info.freeRotation = WorkspaceFreeMode.sanitizeRotation(info.freeRotation);
        if (mIcon instanceof FreeIconTransformHost host) {
            host.setFreeIconTransform(info.freeScale, info.freeRotation);
        }
        mLauncher.getModelWriter().updateItemInDatabase(info);
        mDirty = false;
    }
}
