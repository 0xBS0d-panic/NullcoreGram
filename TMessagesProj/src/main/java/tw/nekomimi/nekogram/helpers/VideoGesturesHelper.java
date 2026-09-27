package tw.nekomimi.nekogram.helpers;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.media.AudioManager;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.VideoPlayer;
import org.telegram.ui.PhotoViewer;

import tw.nekomimi.nekogram.NekoConfig;

/**
 * Обработка жестов видеоплеера (яркость, громкость, перемотка) в PhotoViewer.
 *
 * Архитектура (гибридный подход):
 *
 * 1. dispatchTouchEvent → onDispatchTouchEvent()
 *    - Вызывается ВСЕГДА для каждого touch event (даже если child не обработал DOWN).
 *    - На ACTION_DOWN: сохраняет начальные координаты, возвращает false.
 *    - На ACTION_MOVE (порог не пройден): возвращает false, event идёт в children / onTouchEvent.
 *    - На ACTION_MOVE (порог пройден): устанавливает gestureActive=true, ОБРАБАТЫВАЕТ жест, возвращает true.
 *    - Когда gestureActive=true: обрабатывает жест и возвращает true (event НЕ уходит в super.dispatchTouchEvent).
 *
 * 2. onInterceptTouchEvent → isGestureActive()
 *    - Если жест уже активен, перехватывает touch у children (на случай если child обработал DOWN).
 *
 * 3. onTouchEvent → onTouchEvent()
 *    - Если жест активен, обрабатывает его (для случая когда onInterceptTouchEvent перехватил).
 */
public class VideoGesturesHelper {

    private static final String TAG = "VideoGestures";

    private static final int GESTURE_NONE = 0;
    private static final int GESTURE_BRIGHTNESS = 1;
    private static final int GESTURE_VOLUME = 2;
    private static final int GESTURE_SEEK = 3;

    private static int currentGesture = GESTURE_NONE;
    private static boolean gestureActive = false;
    private static boolean isDownInVideo = false;

    private static float downX;
    private static float downY;
    private static float initialBrightness = 0.5f;
    private static int initialVolume = 0;
    private static int maxVolume = 15;
    private static long initialPosition = 0;
    private static long targetSeekPosition = -1;

    private static GesturesOverlayView overlayView;

    public static boolean isVideoGesturesAvailable(PhotoViewer photoViewer, boolean isCurrentVideo) {
        return photoViewer != null && NekoConfig.videoPlayerGestures.Bool() && isCurrentVideo;
    }

    public static boolean isGestureActive() {
        return gestureActive;
    }

    /**
     * Вызывается из windowView.dispatchTouchEvent() — ПЕРВЫМ в цепочке обработки.
     * dispatchTouchEvent вызывается ВСЕГДА, даже если mFirstTouchTarget == null.
     *
     * Возвращает true → event полностью обработан, super.dispatchTouchEvent не вызывается.
     * Возвращает false → event проходит стандартную маршрутизацию.
     */
    public static boolean onDispatchTouchEvent(PhotoViewer photoViewer, FrameLayout windowView, MotionEvent ev, boolean isCurrentVideo) {
        if (!isVideoGesturesAvailable(photoViewer, isCurrentVideo)) {
            return false;
        }

        VideoPlayer player = photoViewer.getVideoPlayer();
        int action = ev.getActionMasked();

        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                float rx = ev.getRawX();
                float ry = ev.getRawY();

                if (isHitTopBar(photoViewer, ry) || isHitBottomBar(photoViewer, windowView, ry)) {
                    isDownInVideo = false;
                    currentGesture = GESTURE_NONE;
                    gestureActive = false;
                    return false;
                }

                downX = rx;
                downY = ry;
                isDownInVideo = true;
                currentGesture = GESTURE_NONE;
                gestureActive = false;
                targetSeekPosition = -1;

                initBrightnessAndVolume(photoViewer);
                initialPosition = (player != null) ? player.getCurrentPosition() : 0;

                Log.d(TAG, "DOWN: rx=" + rx + " ry=" + ry);
                return false; // Не перехватываем DOWN — даём стандартной обработке пройти
            }

            case MotionEvent.ACTION_MOVE: {
                if (!isDownInVideo) {
                    return false;
                }

                float dx = ev.getRawX() - downX;
                float dy = ev.getRawY() - downY;
                float absDx = Math.abs(dx);
                float absDy = Math.abs(dy);

                if (!gestureActive) {
                    int threshold = AndroidUtilities.dp(16);
                    if (absDx > threshold || absDy > threshold) {
                        if (absDx > absDy) {
                            if (player != null && player.getDuration() > 0) {
                                currentGesture = GESTURE_SEEK;
                                gestureActive = true;
                            }
                        } else {
                            int screenWidth = windowView.getWidth() > 0 ? windowView.getWidth() : AndroidUtilities.displaySize.x;
                            if (downX < screenWidth * 0.5f) {
                                currentGesture = GESTURE_BRIGHTNESS;
                            } else {
                                currentGesture = GESTURE_VOLUME;
                            }
                            gestureActive = true;
                        }

                        if (gestureActive) {
                            Log.d(TAG, "GESTURE DETECTED: type=" + currentGesture);
                            try {
                                windowView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                            } catch (Exception ignored) {}

                            // Отменяем все стандартные жесты PhotoViewer
                            photoViewer.cancelVideoGestures();

                            // Отправляем CANCEL всем children чтобы они сбросили своё состояние
                            cancelChildrenTouch(windowView);
                        }
                    }
                }

                if (!gestureActive) {
                    return false;
                }

                // Жест активен — обрабатываем и поглощаем event
                processGestureMove(photoViewer, windowView, ev, dx, dy);
                return true;
            }

            case MotionEvent.ACTION_UP: {
                Log.d(TAG, "UP: gestureActive=" + gestureActive);
                isDownInVideo = false;
                if (gestureActive) {
                    if (currentGesture == GESTURE_SEEK && targetSeekPosition >= 0 && player != null) {
                        player.seekTo(targetSeekPosition);
                    }
                    if (overlayView != null) {
                        overlayView.dismiss();
                    }
                    gestureActive = false;
                    currentGesture = GESTURE_NONE;
                    targetSeekPosition = -1;
                    return true;
                }
                currentGesture = GESTURE_NONE;
                break;
            }

            case MotionEvent.ACTION_CANCEL: {
                isDownInVideo = false;
                gestureActive = false;
                currentGesture = GESTURE_NONE;
                targetSeekPosition = -1;
                if (overlayView != null) {
                    overlayView.dismiss();
                }
                break;
            }
        }

        return false;
    }

    /**
     * Вызывается из windowView.onTouchEvent() когда gestureActive == true.
     * Это для случая, когда onInterceptTouchEvent перехватил touch у children.
     */
    public static boolean onTouchEvent(PhotoViewer photoViewer, FrameLayout windowView, MotionEvent ev, boolean isCurrentVideo) {
        if (!gestureActive || !isVideoGesturesAvailable(photoViewer, isCurrentVideo)) {
            return false;
        }

        int action = ev.getActionMasked();

        if (action == MotionEvent.ACTION_MOVE) {
            float dx = ev.getRawX() - downX;
            float dy = ev.getRawY() - downY;
            processGestureMove(photoViewer, windowView, ev, dx, dy);
            return true;
        }

        if (action == MotionEvent.ACTION_UP) {
            VideoPlayer player = photoViewer.getVideoPlayer();
            if (currentGesture == GESTURE_SEEK && targetSeekPosition >= 0 && player != null) {
                player.seekTo(targetSeekPosition);
            }
            if (overlayView != null) {
                overlayView.dismiss();
            }
            gestureActive = false;
            currentGesture = GESTURE_NONE;
            isDownInVideo = false;
            targetSeekPosition = -1;
            return true;
        }

        if (action == MotionEvent.ACTION_CANCEL) {
            gestureActive = false;
            currentGesture = GESTURE_NONE;
            isDownInVideo = false;
            targetSeekPosition = -1;
            if (overlayView != null) {
                overlayView.dismiss();
            }
            return true;
        }

        return true;
    }

    /**
     * Обработка движения пальца — общая для onDispatchTouchEvent и onTouchEvent.
     */
    private static void processGestureMove(PhotoViewer photoViewer, FrameLayout windowView, MotionEvent ev, float dx, float dy) {
        ensureOverlay(windowView);
        if (overlayView == null) {
            return;
        }

        int screenHeight = windowView.getHeight() > 0 ? windowView.getHeight() : AndroidUtilities.displaySize.y;
        int screenWidth = windowView.getWidth() > 0 ? windowView.getWidth() : AndroidUtilities.displaySize.x;

        if (currentGesture == GESTURE_BRIGHTNESS) {
            Activity activity = photoViewer.getParentActivity();
            if (activity != null) {
                Window window = activity.getWindow();
                WindowManager.LayoutParams lp = window.getAttributes();
                float delta = (downY - ev.getRawY()) / (float) (screenHeight * 0.85f);
                float newBrightness = Math.max(0.01f, Math.min(1.0f, initialBrightness + delta));
                lp.screenBrightness = newBrightness;
                window.setAttributes(lp);
                overlayView.showBrightness((int) (newBrightness * 100));
            }
        } else if (currentGesture == GESTURE_VOLUME) {
            Activity activity = photoViewer.getParentActivity();
            if (activity != null) {
                AudioManager am = (AudioManager) activity.getSystemService(Context.AUDIO_SERVICE);
                if (am != null) {
                    float delta = (downY - ev.getRawY()) / (float) (screenHeight * 0.85f);
                    int newVol = Math.max(0, Math.min(maxVolume, Math.round(initialVolume + delta * maxVolume)));
                    try {
                        am.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0);
                    } catch (Exception ignored) {}
                    int percent = maxVolume > 0 ? (int) ((newVol / (float) maxVolume) * 100) : 0;
                    overlayView.showVolume(percent, newVol == 0);
                }
            }
        } else if (currentGesture == GESTURE_SEEK) {
            VideoPlayer player = photoViewer.getVideoPlayer();
            if (player != null) {
                long duration = player.getDuration();
                if (duration > 0) {
                    long deltaMs = (long) ((dx / (float) screenWidth) * 90000L);
                    targetSeekPosition = Math.max(0, Math.min(duration, initialPosition + deltaMs));
                    long diffSec = (targetSeekPosition - initialPosition) / 1000;
                    String sign = diffSec >= 0 ? "+" : "";
                    String diffStr = sign + diffSec + "s";
                    String timeStr = AndroidUtilities.formatShortDuration((int) (targetSeekPosition / 1000)) +
                            " / " + AndroidUtilities.formatShortDuration((int) (duration / 1000));
                    overlayView.showSeek(diffStr, timeStr, diffSec >= 0);
                }
            }
        }
    }

    private static void initBrightnessAndVolume(PhotoViewer photoViewer) {
        Activity activity = photoViewer.getParentActivity();
        if (activity != null) {
            WindowManager.LayoutParams lp = activity.getWindow().getAttributes();
            if (lp.screenBrightness >= 0) {
                initialBrightness = lp.screenBrightness;
            } else {
                try {
                    int sys = Settings.System.getInt(activity.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS);
                    initialBrightness = Math.max(0.01f, sys / 255.0f);
                } catch (Exception e) {
                    initialBrightness = 0.5f;
                }
            }

            AudioManager am = (AudioManager) activity.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                initialVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                maxVolume = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            }
        }
    }

    private static boolean isHitTopBar(PhotoViewer photoViewer, float ry) {
        if (photoViewer != null && photoViewer.isActionBarVisible()) {
            return ry < (AndroidUtilities.statusBarHeight + AndroidUtilities.dp(56));
        }
        return false;
    }

    private static boolean isHitBottomBar(PhotoViewer photoViewer, FrameLayout windowView, float ry) {
        if (photoViewer != null && photoViewer.isVideoPlayerControlVisible()) {
            int h = windowView != null && windowView.getHeight() > 0 ? windowView.getHeight() : AndroidUtilities.displaySize.y;
            return ry > (h - AndroidUtilities.dp(96));
        }
        return false;
    }

    private static void cancelChildrenTouch(ViewGroup viewGroup) {
        long now = System.currentTimeMillis();
        MotionEvent cancelEvent = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0, 0, 0);
        for (int i = 0; i < viewGroup.getChildCount(); i++) {
            View child = viewGroup.getChildAt(i);
            if (child != overlayView) {
                try {
                    child.dispatchTouchEvent(cancelEvent);
                } catch (Exception ignored) {}
            }
        }
        cancelEvent.recycle();
    }

    public static void onReset() {
        currentGesture = GESTURE_NONE;
        gestureActive = false;
        isDownInVideo = false;
        if (overlayView != null) {
            overlayView.animate().cancel();
            overlayView.setVisibility(View.GONE);
            if (overlayView.dimView != null) {
                overlayView.dimView.setAlpha(0.0f);
            }
        }
    }

    private static void ensureOverlay(FrameLayout windowView) {
        if (windowView == null) {
            return;
        }
        if (overlayView == null || overlayView.getParent() != windowView) {
            if (overlayView != null && overlayView.getParent() != null) {
                ((ViewGroup) overlayView.getParent()).removeView(overlayView);
            }
            overlayView = new GesturesOverlayView(windowView.getContext());
            windowView.addView(overlayView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        }
    }

    private static class GesturesOverlayView extends FrameLayout {
        private final View dimView;

        private final LinearLayout brightnessCard;
        private final ImageView brightnessIcon;
        private final FrameLayout brightnessTrack;
        private final View brightnessFill;
        private final TextView brightnessText;

        private final LinearLayout volumeCard;
        private final ImageView volumeIcon;
        private final FrameLayout volumeTrack;
        private final View volumeFill;
        private final TextView volumeText;

        private final LinearLayout seekCard;
        private final ImageView seekIcon;
        private final TextView seekDiffText;
        private final TextView seekTimeText;

        private static final int TRACK_HEIGHT_DP = 130;

        public GesturesOverlayView(Context context) {
            super(context);
            setVisibility(View.GONE);
            setClickable(false);
            setFocusable(false);

            dimView = new View(context);
            dimView.setBackgroundColor(Color.BLACK);
            dimView.setAlpha(0.0f);
            dimView.setClickable(false);
            dimView.setFocusable(false);
            addView(dimView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

            // Brightness Card (Left)
            brightnessCard = new LinearLayout(context);
            brightnessCard.setOrientation(LinearLayout.VERTICAL);
            brightnessCard.setGravity(Gravity.CENTER_HORIZONTAL);
            brightnessCard.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(18), 0xCC1A1A1A));
            brightnessCard.setPadding(AndroidUtilities.dp(10), AndroidUtilities.dp(14), AndroidUtilities.dp(10), AndroidUtilities.dp(14));
            addView(brightnessCard, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.START | Gravity.CENTER_VERTICAL, 24, 0, 0, 0));

            brightnessIcon = new ImageView(context);
            brightnessIcon.setColorFilter(Color.WHITE);
            brightnessIcon.setImageResource(R.drawable.msg_brightness_high);
            brightnessCard.addView(brightnessIcon, LayoutHelper.createLinear(22, 22, Gravity.CENTER_HORIZONTAL));

            brightnessTrack = new FrameLayout(context);
            brightnessTrack.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(4), 0x44FFFFFF));
            brightnessCard.addView(brightnessTrack, LayoutHelper.createLinear(8, TRACK_HEIGHT_DP, Gravity.CENTER_HORIZONTAL, 0, 10, 0, 0));

            brightnessFill = new View(context);
            brightnessFill.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(4), Color.WHITE));
            brightnessTrack.addView(brightnessFill, LayoutHelper.createFrame(8, 0, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));

            brightnessText = new TextView(context);
            brightnessText.setTextColor(Color.WHITE);
            brightnessText.setTextSize(12);
            brightnessText.setTypeface(AndroidUtilities.bold());
            brightnessText.setGravity(Gravity.CENTER);
            brightnessCard.addView(brightnessText, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 8, 0, 0));

            // Volume Card (Right)
            volumeCard = new LinearLayout(context);
            volumeCard.setOrientation(LinearLayout.VERTICAL);
            volumeCard.setGravity(Gravity.CENTER_HORIZONTAL);
            volumeCard.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(18), 0xCC1A1A1A));
            volumeCard.setPadding(AndroidUtilities.dp(10), AndroidUtilities.dp(14), AndroidUtilities.dp(10), AndroidUtilities.dp(14));
            addView(volumeCard, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.END | Gravity.CENTER_VERTICAL, 0, 0, 24, 0));

            volumeIcon = new ImageView(context);
            volumeIcon.setColorFilter(Color.WHITE);
            volumeIcon.setImageResource(R.drawable.volume_on);
            volumeCard.addView(volumeIcon, LayoutHelper.createLinear(22, 22, Gravity.CENTER_HORIZONTAL));

            volumeTrack = new FrameLayout(context);
            volumeTrack.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(4), 0x44FFFFFF));
            volumeCard.addView(volumeTrack, LayoutHelper.createLinear(8, TRACK_HEIGHT_DP, Gravity.CENTER_HORIZONTAL, 0, 10, 0, 0));

            volumeFill = new View(context);
            volumeFill.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(4), Color.WHITE));
            volumeTrack.addView(volumeFill, LayoutHelper.createFrame(8, 0, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));

            volumeText = new TextView(context);
            volumeText.setTextColor(Color.WHITE);
            volumeText.setTextSize(12);
            volumeText.setTypeface(AndroidUtilities.bold());
            volumeText.setGravity(Gravity.CENTER);
            volumeCard.addView(volumeText, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 8, 0, 0));

            // Seek Card (Center)
            seekCard = new LinearLayout(context);
            seekCard.setOrientation(LinearLayout.VERTICAL);
            seekCard.setGravity(Gravity.CENTER);
            seekCard.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(16), 0xDD1A1A1A));
            seekCard.setPadding(AndroidUtilities.dp(22), AndroidUtilities.dp(14), AndroidUtilities.dp(22), AndroidUtilities.dp(14));
            addView(seekCard, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));

            LinearLayout seekTopRow = new LinearLayout(context);
            seekTopRow.setOrientation(LinearLayout.HORIZONTAL);
            seekTopRow.setGravity(Gravity.CENTER_VERTICAL);
            seekCard.addView(seekTopRow, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));

            seekIcon = new ImageView(context);
            seekIcon.setColorFilter(Color.WHITE);
            seekIcon.setImageResource(R.drawable.forwardvideo);
            seekTopRow.addView(seekIcon, LayoutHelper.createLinear(28, 28, Gravity.CENTER_VERTICAL));

            seekDiffText = new TextView(context);
            seekDiffText.setTextColor(Color.WHITE);
            seekDiffText.setTextSize(20);
            seekDiffText.setTypeface(AndroidUtilities.bold());
            seekDiffText.setGravity(Gravity.CENTER_VERTICAL);
            seekTopRow.addView(seekDiffText, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 8, 0, 0, 0));

            seekTimeText = new TextView(context);
            seekTimeText.setTextColor(0xAAFFFFFF);
            seekTimeText.setTextSize(14);
            seekTimeText.setGravity(Gravity.CENTER);
            seekCard.addView(seekTimeText, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 6, 0, 0));
        }

        public void showBrightness(int percent) {
            animate().cancel();
            setAlpha(1.0f);
            setVisibility(View.VISIBLE);

            brightnessCard.setVisibility(View.VISIBLE);
            volumeCard.setVisibility(View.GONE);
            seekCard.setVisibility(View.GONE);

            brightnessIcon.setImageResource(percent < 40 ? R.drawable.msg_brightness_low : R.drawable.msg_brightness_high);
            brightnessText.setText(percent + "%");

            int maxPx = AndroidUtilities.dp(TRACK_HEIGHT_DP);
            int fillHeight = Math.max(AndroidUtilities.dp(6), Math.min(maxPx, (int) (maxPx * (percent / 100.0f))));
            brightnessFill.getLayoutParams().height = fillHeight;
            brightnessFill.requestLayout();

            if (percent < 50) {
                float dimAlpha = ((50 - percent) / 50.0f) * 0.4f;
                dimView.setAlpha(dimAlpha);
            } else {
                dimView.setAlpha(0.0f);
            }
        }

        public void showVolume(int percent, boolean isMute) {
            animate().cancel();
            setAlpha(1.0f);
            setVisibility(View.VISIBLE);

            volumeCard.setVisibility(View.VISIBLE);
            brightnessCard.setVisibility(View.GONE);
            seekCard.setVisibility(View.GONE);
            dimView.setAlpha(0.0f);

            volumeIcon.setImageResource(isMute ? R.drawable.volume_off : R.drawable.volume_on);
            volumeText.setText(percent + "%");

            int maxPx = AndroidUtilities.dp(TRACK_HEIGHT_DP);
            int fillHeight = Math.max(AndroidUtilities.dp(6), Math.min(maxPx, (int) (maxPx * (percent / 100.0f))));
            volumeFill.getLayoutParams().height = fillHeight;
            volumeFill.requestLayout();
        }

        public void showSeek(String diffText, String timeText, boolean forward) {
            animate().cancel();
            setAlpha(1.0f);
            setVisibility(View.VISIBLE);

            seekCard.setVisibility(View.VISIBLE);
            brightnessCard.setVisibility(View.GONE);
            volumeCard.setVisibility(View.GONE);
            dimView.setAlpha(0.0f);

            seekIcon.setScaleX(forward ? 1.0f : -1.0f);
            seekDiffText.setText(diffText);
            seekTimeText.setText(timeText);
        }

        public void dismiss() {
            animate().cancel();
            animate()
                    .alpha(0.0f)
                    .setDuration(250)
                    .setStartDelay(650)
                    .withEndAction(() -> {
                        setVisibility(View.GONE);
                        dimView.setAlpha(0.0f);
                    })
                    .start();
        }
    }
}
