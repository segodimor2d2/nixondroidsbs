package com.termux.app.terminal;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.media.AudioManager;
import android.os.Environment;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Toast;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.file.FileUtils;
import com.termux.shared.interact.MessageDialogUtils;
import com.termux.shared.interact.ShareUtils;
import com.termux.shared.shell.ShellUtils;
import com.termux.shared.termux.TermuxBootstrap;
import com.termux.shared.termux.terminal.TermuxTerminalViewClientBase;
import com.termux.shared.termux.extrakeys.SpecialButton;
import com.termux.shared.android.AndroidUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.activities.ReportActivity;
import com.termux.shared.models.ReportInfo;
import com.termux.app.models.UserAction;
import com.termux.app.terminal.io.KeyboardShortcut;
import com.termux.shared.termux.settings.properties.TermuxPropertyConstants;
import com.termux.shared.data.DataUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.markdown.MarkdownUtils;
import com.termux.shared.termux.TermuxUtils;
import com.termux.shared.termux.data.TermuxUrlUtils;
import com.termux.shared.view.KeyboardUtils;
import com.termux.shared.view.ViewUtils;
import com.termux.terminal.KeyHandler;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import androidx.drawerlayout.widget.DrawerLayout;

public class TermuxTerminalViewClient extends TermuxTerminalViewClientBase {

    final TermuxActivity mActivity;

    final TermuxTerminalSessionActivityClient mTermuxTerminalSessionActivityClient;

    /** Keeping track of the special keys acting as Ctrl and Fn for the soft keyboard and other hardware keys. */
    boolean mVirtualControlKeyDown, mVirtualFnKeyDown;

    private boolean leadKB;

    private void setLeaderActive(boolean active) {
        leadKB = active;
        mActivity.getTerminalView().setLeaderActive(active);
        if (active) mActivity.getTerminalView().requestFocus();
    }

    private boolean handleLeaderCodePoint(int codePoint, boolean shiftDown) {
        if (codePoint == '@' && shiftDown) {
            setLeaderActive(true);
            return true;
        }
        if (!leadKB) return false;
        if (codePoint == 27) {
            setLeaderActive(false);
            return true;
        }
        if (codePoint == 'w') {
            mActivity.toggleTerminalToolbar();
            // The toolbar may focus its text input; keep leader input on the terminal.
            mActivity.getTerminalView().requestFocus();
            return true;
        }
        if (codePoint == 'c') {
            mActivity.getTerminalView().centerViewport();
            return true;
        }
        if (codePoint == 'U' || codePoint == 'I') {
            mActivity.changeTerminalFontSize(codePoint == 'U');
            return true;
        }
        // Reuse the menu controls so preferences, sensor registration and labels stay in sync.
        final int viewportControlId;
        switch (codePoint) {
            case 't': viewportControlId = R.id.fixed_viewport_toggle; break;
            case 'g': viewportControlId = R.id.viewport_gyroscope_toggle; break;
            case 'u': viewportControlId = R.id.viewport_zoom_plus; break;
            case 'i': viewportControlId = R.id.viewport_zoom_minus; break;
            case 'y': viewportControlId = R.id.viewport_gyro_sensitivity_plus; break;
            case 'o': viewportControlId = R.id.viewport_gyro_sensitivity_minus; break;
            default: return true; // Leader owns input until Escape, including unknown commands.
        }
        View control = mActivity.findViewById(viewportControlId);
        if (control != null && control.isEnabled()) control.performClick();
        return true;
    }

    private Runnable mShowSoftKeyboardRunnable;

    private boolean mShowSoftKeyboardIgnoreOnce;
    private boolean mShowSoftKeyboardWithDelayOnce;

    private boolean mTerminalCursorBlinkerStateAlreadySet;

    private List<KeyboardShortcut> mSessionShortcuts;

    private static final String LOG_TAG = "TermuxTerminalViewClient";

    public TermuxTerminalViewClient(TermuxActivity activity, TermuxTerminalSessionActivityClient termuxTerminalSessionActivityClient) {
        this.mActivity = activity;
        this.mTermuxTerminalSessionActivityClient = termuxTerminalSessionActivityClient;
    }

    public TermuxActivity getActivity() {
        return mActivity;
    }

    /**
     * Should be called when mActivity.onCreate() is called
     */
    public void onCreate() {
        onReloadProperties();

        mActivity.getTermuxActivityRootView().getViewTreeObserver()
            .addOnGlobalFocusChangeListener((oldFocus, newFocus) -> {
                if (leadKB && newFocus != mActivity.getTerminalView())
                    mActivity.getTerminalView().requestFocus();
            });

        mActivity.applyTerminalFontSize();
        mActivity.getTerminalView().setKeepScreenOn(mActivity.getPreferences().shouldKeepScreenOn());
    }

    /**
     * Should be called when mActivity.onStart() is called
     */
    public void onStart() {
        // Set {@link TerminalView#TERMINAL_VIEW_KEY_LOGGING_ENABLED} value
        // Also required if user changed the preference from {@link TermuxSettings} activity and returns
        boolean isTerminalViewKeyLoggingEnabled = mActivity.getPreferences().isTerminalViewKeyLoggingEnabled();
        mActivity.getTerminalView().setIsTerminalViewKeyLoggingEnabled(isTerminalViewKeyLoggingEnabled);

        // Piggyback on the terminal view key logging toggle for now, should add a separate toggle in future
        mActivity.getTermuxActivityRootView().setIsRootViewLoggingEnabled(isTerminalViewKeyLoggingEnabled);
        ViewUtils.setIsViewUtilsLoggingEnabled(isTerminalViewKeyLoggingEnabled);
    }

    /**
     * Should be called when mActivity.onResume() is called
     */
    public void onResume() {
        // Show the soft keyboard if required
        setSoftKeyboardState(true, mActivity.isActivityRecreated());

        mTerminalCursorBlinkerStateAlreadySet = false;

        if (mActivity.getTerminalView().mEmulator != null) {
            // Start terminal cursor blinking if enabled
            // If emulator is already set, then start blinker now, otherwise wait for onEmulatorSet()
            // event to start it. This is needed since onEmulatorSet() may not be called after
            // TermuxActivity is started after device display timeout with double tap and not power button.
            setTerminalCursorBlinkerState(true);
            mTerminalCursorBlinkerStateAlreadySet = true;
        }
    }

    /**
     * Should be called when mActivity.onStop() is called
     */
    public void onStop() {
        // Stop terminal cursor blinking if enabled
        setTerminalCursorBlinkerState(false);
    }

    /**
     * Should be called when mActivity.reloadProperties() is called
     */
    public void onReloadProperties() {
        setSessionShortcuts();
    }

    /**
     * Should be called when mActivity.reloadActivityStyling() is called
     */
    public void onReloadActivityStyling() {
        // Show the soft keyboard if required
        setSoftKeyboardState(false, true);

        // Start terminal cursor blinking if enabled
        setTerminalCursorBlinkerState(true);
    }

    /**
     * Should be called when {@link com.termux.view.TerminalView#mEmulator} is set
     */
    @Override
    public void onEmulatorSet() {
        if (!mTerminalCursorBlinkerStateAlreadySet) {
            // Start terminal cursor blinking if enabled
            // We need to wait for the first session to be attached that's set in
            // TermuxActivity.onServiceConnected() and then the multiple calls to TerminalView.updateSize()
            // where the final one eventually sets the mEmulator when width/height is not 0. Otherwise
            // blinker will not start again if TermuxActivity is started again after exiting it with
            // double back press. Check TerminalView.setTerminalCursorBlinkerState().
            setTerminalCursorBlinkerState(true);
            mTerminalCursorBlinkerStateAlreadySet = true;
        }
    }



    @Override
    public float onScale(float scale) {
        if (scale < 0.9f || scale > 1.1f) {
            boolean increase = scale > 1.f;
            changeFontSize(increase);
            return 1.0f;
        }
        return scale;
    }



    @Override
    public void onTwoFingerTap() {
        mActivity.toggleTerminalToolbar();
    }

    @Override
    public void onSingleTapUp(MotionEvent e) {
        TerminalEmulator term = mActivity.getCurrentSession().getEmulator();

        if (mActivity.getProperties().shouldOpenTerminalTranscriptURLOnClick()) {
            int[] columnAndRow = mActivity.getTerminalView().getColumnAndRow(e, true);
            String wordAtTap = term.getScreen().getWordAtLocation(columnAndRow[0], columnAndRow[1]);
            LinkedHashSet<CharSequence> urlSet = TermuxUrlUtils.extractUrls(wordAtTap);

            if (!urlSet.isEmpty()) {
                String url = (String) urlSet.iterator().next();
                ShareUtils.openUrl(mActivity, url);
                return;
            }
        }

        if (!term.isMouseTrackingActive() && !e.isFromSource(InputDevice.SOURCE_MOUSE)) {
            if (!KeyboardUtils.areDisableSoftKeyboardFlagsSet(mActivity)) {
                mShowSoftKeyboardIgnoreOnce = false;
                KeyboardUtils.setSoftInputModeAdjustResize(mActivity);
                mActivity.getTerminalView().requestFocus();
                KeyboardUtils.showSoftKeyboard(mActivity, mActivity.getTerminalView());
                mActivity.showTerminalToolbarOnTerminalTap();
            } else
                Logger.logVerbose(LOG_TAG, "Not showing soft keyboard onSingleTapUp since its disabled");
        }
    }

    @Override
    public boolean shouldBackButtonBeMappedToEscape() {
        return mActivity.getProperties().isBackKeyTheEscapeKey();
    }

    @Override
    public boolean shouldEnforceCharBasedInput() {
        return mActivity.getProperties().isEnforcingCharBasedInput();
    }

    @Override
    public boolean shouldUseCtrlSpaceWorkaround() {
        return mActivity.getProperties().isUsingCtrlSpaceWorkaround();
    }

    @Override
    public boolean isTerminalViewSelected() {
        return mActivity.getTerminalToolbarViewPager() == null || mActivity.isTerminalViewSelected() || mActivity.getTerminalView().hasFocus();
    }



    @Override
    public void copyModeChanged(boolean copyMode) {
        // Reapplying LOCKED_CLOSED also closes a drawer opened programmatically.
        // Ending selection from a viewport control must leave that drawer open.
        if (copyMode || mActivity.getDrawer().getDrawerLockMode(Gravity.LEFT)
            != DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
            mActivity.getDrawer().setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED);
    }



    @SuppressLint("RtlHardcoded")
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e, TerminalSession currentSession) {
        if (leadKB) {
            if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
                setLeaderActive(false);
            } else {
                int metaState = e.getMetaState();
                boolean shiftDown = e.isShiftPressed() || readShiftKey();
                if (shiftDown) metaState |= KeyEvent.META_SHIFT_ON;
                handleLeaderCodePoint(e.getUnicodeChar(metaState), shiftDown);
            }
            return true;
        }
        if (handleVirtualKeys(keyCode, e, true)) return true;
        if (keyCode == KeyEvent.KEYCODE_W && !e.isAltPressed() && !mVirtualFnKeyDown
            && (e.isShiftPressed() || readShiftKey())
            && (e.isCtrlPressed() || readControlKey())) {
            if (e.getRepeatCount() == 0) mActivity.toggleTerminalToolbar();
            return true;
        }
        boolean fontSizeKey = keyCode == KeyEvent.KEYCODE_PLUS
            || keyCode == KeyEvent.KEYCODE_EQUALS || keyCode == KeyEvent.KEYCODE_MINUS;
        // Gboard sends key events without Ctrl in their meta state when Ctrl comes
        // from the auxiliary bar. Read that modifier before terminal key mapping.
        if (fontSizeKey && !e.isAltPressed()
            && (e.isShiftPressed() || readShiftKey())
            && (e.isCtrlPressed() || readControlKey())) {
            if (keyCode == KeyEvent.KEYCODE_PLUS || keyCode == KeyEvent.KEYCODE_EQUALS) {
                changeFontSize(true);
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_MINUS) {
                changeFontSize(false);
                return true;
            }
        }

        if (keyCode == KeyEvent.KEYCODE_ENTER && !currentSession.isRunning()) {
            mTermuxTerminalSessionActivityClient.removeFinishedSession(currentSession);
            return true;
        } else if (!mActivity.getProperties().areHardwareKeyboardShortcutsDisabled() &&
            e.isCtrlPressed() && e.isAltPressed()) {
            // Get the unmodified code point:
            int unicodeChar = e.getUnicodeChar(0);

            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN || unicodeChar == 'n'/* next */) {
                mTermuxTerminalSessionActivityClient.switchToSession(true);
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP || unicodeChar == 'p' /* previous */) {
                mTermuxTerminalSessionActivityClient.switchToSession(false);
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                mActivity.getDrawer().openDrawer(Gravity.LEFT);
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                mActivity.getDrawer().closeDrawers();
            } else if (unicodeChar == 'k'/* keyboard */) {
                onToggleSoftKeyboardRequest();
            } else if (keyCode == KeyEvent.KEYCODE_W) {
                if (e.getRepeatCount() == 0) mActivity.toggleTerminalToolbar();
            } else if (unicodeChar == 'm'/* menu */) {
                mActivity.getTerminalView().showContextMenu();
            } else if (unicodeChar == 'r'/* rename */) {
                mTermuxTerminalSessionActivityClient.renameSession(currentSession);
            } else if (unicodeChar == 'c'/* create */) {
                mTermuxTerminalSessionActivityClient.addNewSession(false, null);
            } else if (unicodeChar == 'u' /* urls */) {
                showUrlSelection();
            } else if (unicodeChar == 'v') {
                doPaste();
            } else if (unicodeChar == '+' || e.getUnicodeChar(KeyEvent.META_SHIFT_ON) == '+') {
                // We also check for the shifted char here since shift may be required to produce '+',
                // see https://github.com/termux/termux-api/issues/2
                changeFontSize(true);
            } else if (unicodeChar == '-') {
                changeFontSize(false);
            } else if (unicodeChar >= '1' && unicodeChar <= '9') {
                int index = unicodeChar - '1';
                mTermuxTerminalSessionActivityClient.switchToSession(index);
            }
            return true;
        }

        return false;

    }



    @Override
    public boolean onKeyUp(int keyCode, KeyEvent e) {
        // If emulator is not set, like if bootstrap installation failed and user dismissed the error
        // dialog, then just exit the activity, otherwise they will be stuck in a broken state.
        if (keyCode == KeyEvent.KEYCODE_BACK && mActivity.getTerminalView().mEmulator == null) {
            mActivity.finishActivityIfNotFinishing();
            return true;
        }

        return handleVirtualKeys(keyCode, e, false);
    }

    /** Handle dedicated volume buttons as virtual keys if applicable. */
    private boolean handleVirtualKeys(int keyCode, KeyEvent event, boolean down) {
        InputDevice inputDevice = event.getDevice();
        if (mActivity.getProperties().areVirtualVolumeKeysDisabled()) {
            return false;
        } else if (inputDevice != null && inputDevice.getKeyboardType() == InputDevice.KEYBOARD_TYPE_ALPHABETIC) {
            // Do not steal dedicated buttons from a full external keyboard.
            return false;
        } else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            mVirtualControlKeyDown = down;
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            mVirtualFnKeyDown = down;
            return true;
        }
        return false;
    }



    @Override
    public boolean readControlKey() {
        return readExtraKeysSpecialButton(SpecialButton.CTRL) || mVirtualControlKeyDown;
    }

    @Override
    public boolean readAltKey() {
        return readExtraKeysSpecialButton(SpecialButton.ALT);
    }

    @Override
    public boolean readShiftKey() {
        return readExtraKeysSpecialButton(SpecialButton.SHIFT);
    }

    @Override
    public boolean readFnKey() {
        return readExtraKeysSpecialButton(SpecialButton.FN);
    }

    public boolean readExtraKeysSpecialButton(SpecialButton specialButton) {
        if (mActivity.getExtraKeysView() == null) return false;
        Boolean state = mActivity.getExtraKeysView().readSpecialButton(specialButton, true);
        if (state == null) {
            Logger.logError(LOG_TAG,"Failed to read an unregistered " + specialButton + " special button value from extra keys.");
            return false;
        }
        return state;
    }

    @Override
    public boolean onLongPress(MotionEvent event) {
        return false;
    }



    @Override
    public boolean onCodePoint(int codePoint, boolean ctrlDown, boolean shiftDown, TerminalSession session) {
        if (handleLeaderCodePoint(codePoint, shiftDown)) return true;
        if (ctrlDown && shiftDown && !mVirtualFnKeyDown
            && Character.toLowerCase(codePoint) == 'w') {
            mActivity.toggleTerminalToolbar();
            return true;
        }
        if (ctrlDown && shiftDown && !mVirtualFnKeyDown
            && (codePoint == '+' || codePoint == '=' || codePoint == '-' || codePoint == '_')) {
            changeFontSize(codePoint == '+' || codePoint == '=');
            return true;
        }
        return onCodePoint(codePoint, ctrlDown, session);
    }

    @Override
    public boolean onCodePoint(final int codePoint, boolean ctrlDown, TerminalSession session) {
        if (mVirtualFnKeyDown) {
            int resultingKeyCode = -1;
            int resultingCodePoint = -1;
            boolean altDown = false;
            int lowerCase = Character.toLowerCase(codePoint);
            switch (lowerCase) {
                // Arrow keys.
                case 'w':
                    resultingKeyCode = KeyEvent.KEYCODE_DPAD_UP;
                    break;
                case 'a':
                    resultingKeyCode = KeyEvent.KEYCODE_DPAD_LEFT;
                    break;
                case 's':
                    resultingKeyCode = KeyEvent.KEYCODE_DPAD_DOWN;
                    break;
                case 'd':
                    resultingKeyCode = KeyEvent.KEYCODE_DPAD_RIGHT;
                    break;

                // Page up and down.
                case 'p':
                    resultingKeyCode = KeyEvent.KEYCODE_PAGE_UP;
                    break;
                case 'n':
                    resultingKeyCode = KeyEvent.KEYCODE_PAGE_DOWN;
                    break;

                // Some special keys:
                case 't':
                    resultingKeyCode = KeyEvent.KEYCODE_TAB;
                    break;
                case 'i':
                    resultingKeyCode = KeyEvent.KEYCODE_INSERT;
                    break;
                case 'h':
                    resultingCodePoint = '~';
                    break;

                // Special characters to input.
                case 'u':
                    resultingCodePoint = '_';
                    break;
                case 'l':
                    resultingCodePoint = '|';
                    break;

                // Function keys.
                case '1':
                case '2':
                case '3':
                case '4':
                case '5':
                case '6':
                case '7':
                case '8':
                case '9':
                    resultingKeyCode = (codePoint - '1') + KeyEvent.KEYCODE_F1;
                    break;
                case '0':
                    resultingKeyCode = KeyEvent.KEYCODE_F10;
                    break;

                // Other special keys.
                case 'e':
                    resultingCodePoint = /*Escape*/ 27;
                    break;
                case '.':
                    resultingCodePoint = /*^.*/ 28;
                    break;

                case 'b': // alt+b, jumping backward in readline.
                case 'f': // alf+f, jumping forward in readline.
                case 'x': // alt+x, common in emacs.
                    resultingCodePoint = lowerCase;
                    altDown = true;
                    break;

                // Volume control.
                case 'v':
                    resultingCodePoint = -1;
                    AudioManager audio = (AudioManager) mActivity.getSystemService(Context.AUDIO_SERVICE);
                    audio.adjustSuggestedStreamVolume(AudioManager.ADJUST_SAME, AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI);
                    break;

                // Writing mode:
                case 'q':
                case 'k':
                    mActivity.toggleTerminalToolbar();
                    mVirtualFnKeyDown=false; // force disable fn key down to restore keyboard input into terminal view, fixes termux/termux-app#1420
                    break;
            }

            if (resultingKeyCode != -1) {
                TerminalEmulator term = session.getEmulator();
                session.write(KeyHandler.getCode(resultingKeyCode, 0, term.isCursorKeysApplicationMode(), term.isKeypadApplicationMode()));
            } else if (resultingCodePoint != -1) {
                session.writeCodePoint(altDown, resultingCodePoint);
            }
            return true;
        } else if (ctrlDown) {
            if (codePoint == 106 /* Ctrl+j or \n */ && !session.isRunning()) {
                mTermuxTerminalSessionActivityClient.removeFinishedSession(session);
                return true;
            }

            List<KeyboardShortcut> shortcuts = mSessionShortcuts;
            if (shortcuts != null && !shortcuts.isEmpty()) {
                int codePointLowerCase = Character.toLowerCase(codePoint);
                for (int i = shortcuts.size() - 1; i >= 0; i--) {
                    KeyboardShortcut shortcut = shortcuts.get(i);
                    if (codePointLowerCase == shortcut.codePoint) {
                        switch (shortcut.shortcutAction) {
                            case TermuxPropertyConstants.ACTION_SHORTCUT_CREATE_SESSION:
                                mTermuxTerminalSessionActivityClient.addNewSession(false, null);
                                return true;
                            case TermuxPropertyConstants.ACTION_SHORTCUT_NEXT_SESSION:
                                mTermuxTerminalSessionActivityClient.switchToSession(true);
                                return true;
                            case TermuxPropertyConstants.ACTION_SHORTCUT_PREVIOUS_SESSION:
                                mTermuxTerminalSessionActivityClient.switchToSession(false);
                                return true;
                            case TermuxPropertyConstants.ACTION_SHORTCUT_RENAME_SESSION:
                                mTermuxTerminalSessionActivityClient.renameSession(mActivity.getCurrentSession());
                                return true;
                        }
                    }
                }
            }
        }

        return false;
    }

    /**
     * Set the terminal sessions shortcuts.
     */
    private void setSessionShortcuts() {
        mSessionShortcuts = new ArrayList<>();

        // The {@link TermuxPropertyConstants#MAP_SESSION_SHORTCUTS} stores the session shortcut key and action pair
        for (Map.Entry<String, Integer> entry : TermuxPropertyConstants.MAP_SESSION_SHORTCUTS.entrySet()) {
            // The mMap stores the code points for the session shortcuts while loading properties
            Integer codePoint = (Integer) mActivity.getProperties().getInternalPropertyValue(entry.getKey(), true);
            // If codePoint is null, then session shortcut did not exist in properties or was invalid
            // as parsed by {@link #getCodePointForSessionShortcuts(String,String)}
            // If codePoint is not null, then get the action for the MAP_SESSION_SHORTCUTS key and
            // add the code point to sessionShortcuts
            if (codePoint != null)
                mSessionShortcuts.add(new KeyboardShortcut(codePoint, entry.getValue()));
        }
    }





    public void changeFontSize(boolean increase) {
        mActivity.changeTerminalFontSize(increase);
    }



    /**
     * Called when user requests the soft keyboard to be toggled via "KEYBOARD" toggle button in
     * drawer or extra keys, or with ctrl+alt+k hardware keyboard shortcut.
     */
    public void onToggleSoftKeyboardRequest() {
        // If soft keyboard toggle behaviour is enable/disabled
        if (mActivity.getProperties().shouldEnableDisableSoftKeyboardOnToggle()) {
            // If soft keyboard is visible
            if (!KeyboardUtils.areDisableSoftKeyboardFlagsSet(mActivity)) {
                Logger.logVerbose(LOG_TAG, "Disabling soft keyboard on toggle");
                mActivity.getPreferences().setSoftKeyboardEnabled(false);
                KeyboardUtils.disableSoftKeyboard(mActivity, mActivity.getTerminalView());
            } else {
                // Show with a delay, otherwise pressing keyboard toggle won't show the keyboard after
                // switching back from another app if keyboard was previously disabled by user.
                // Also request focus, since it wouldn't have been requested at startup by
                // setSoftKeyboardState if keyboard was disabled. #2112
                Logger.logVerbose(LOG_TAG, "Enabling soft keyboard on toggle");
                mActivity.getPreferences().setSoftKeyboardEnabled(true);
                KeyboardUtils.clearDisableSoftKeyboardFlags(mActivity);
                if(mShowSoftKeyboardWithDelayOnce) {
                    mShowSoftKeyboardWithDelayOnce = false;
                    mActivity.getTerminalView().postDelayed(getShowSoftKeyboardRunnable(), 500);
                    mActivity.getTerminalView().requestFocus();
                } else
                    KeyboardUtils.showSoftKeyboard(mActivity, mActivity.getTerminalView());
            }
        }
        // If soft keyboard toggle behaviour is show/hide
        else {
            // If soft keyboard is disabled by user for Termux
            if (!mActivity.getPreferences().isSoftKeyboardEnabled()) {
                Logger.logVerbose(LOG_TAG, "Maintaining disabled soft keyboard on toggle");
                KeyboardUtils.disableSoftKeyboard(mActivity, mActivity.getTerminalView());
            } else {
                Logger.logVerbose(LOG_TAG, "Showing/Hiding soft keyboard on toggle");
                KeyboardUtils.clearDisableSoftKeyboardFlags(mActivity);
                KeyboardUtils.toggleSoftKeyboard(mActivity);
            }
        }
    }

    public void setSoftKeyboardState(boolean isStartup, boolean isReloadTermuxProperties) {
        boolean noShowKeyboard = false;

        // Requesting terminal view focus is necessary regardless of if soft keyboard is to be
        // disabled or hidden at startup, otherwise if hardware keyboard is attached and user
        // starts typing on hardware keyboard without tapping on the terminal first, then a colour
        // tint will be added to the terminal as highlight for the focussed view. Test with a light
        // theme. For android 8.+, the "defaultFocusHighlightEnabled" attribute is also set to false
        // in TerminalView layout to fix the issue.

        // If soft keyboard is disabled by user for Termux (check function docs for Termux behaviour info)
        if (KeyboardUtils.shouldSoftKeyboardBeDisabled(mActivity,
            mActivity.getPreferences().isSoftKeyboardEnabled(),
            mActivity.getPreferences().isSoftKeyboardEnabledOnlyIfNoHardware())) {
            Logger.logVerbose(LOG_TAG, "Maintaining disabled soft keyboard");
            KeyboardUtils.disableSoftKeyboard(mActivity, mActivity.getTerminalView());
            mActivity.getTerminalView().requestFocus();
            noShowKeyboard = true;
            // Delay is only required if onCreate() is called like when Termux app is exited with
            // double back press, not when Termux app is switched back from another app and keyboard
            // toggle is pressed to enable keyboard
            if (isStartup && mActivity.isOnResumeAfterOnCreate())
                mShowSoftKeyboardWithDelayOnce = true;
        } else {
            // Set flag to automatically push up TerminalView when keyboard is opened instead of showing over it
            KeyboardUtils.setSoftInputModeAdjustResize(mActivity);

            // Clear any previous flags to disable soft keyboard in case setting updated
            KeyboardUtils.clearDisableSoftKeyboardFlags(mActivity);

            // Start with the keyboard and auxiliary keys hidden until the user opens input.
            if (isStartup && mActivity.isOnResumeAfterOnCreate()) {
                Logger.logVerbose(LOG_TAG, "Hiding soft keyboard on startup");
                // Hide on initial entry without hiding subsequent Gboard input restarts.
                mActivity.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
                    | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

                KeyboardUtils.hideSoftKeyboard(mActivity, mActivity.getTerminalView());
                mActivity.getTerminalView().requestFocus();
                noShowKeyboard = true;
                // Required to keep keyboard hidden on app startup
                mShowSoftKeyboardIgnoreOnce = true;
            }
        }

        mActivity.getTerminalView().setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View view, boolean hasFocus) {
                // Force show soft keyboard if TerminalView or toolbar text input view has
                // focus and close it if they don't
                boolean textInputViewHasFocus = false;
                final EditText textInputView =  mActivity.findViewById(R.id.terminal_toolbar_text_input);
                if (textInputView != null) textInputViewHasFocus = textInputView.hasFocus();

                if (hasFocus || textInputViewHasFocus) {
                    if (mShowSoftKeyboardIgnoreOnce) {
                        mShowSoftKeyboardIgnoreOnce = false; return;
                    }
                    Logger.logVerbose(LOG_TAG, "Showing soft keyboard on focus change");
                } else {
                    // A large IME can briefly shrink the terminal enough to clear its focus.
                    // No other view was selected: restore focus after the resize instead of
                    // closing the IME and starting a show/hide loop (notably with Gboard).
                    if (mActivity.getCurrentFocus() == null) {
                        view.post(() -> {
                            if (mActivity.getCurrentFocus() == null && view.isShown()
                                && view.hasWindowFocus()) view.requestFocus();
                        });
                        return;
                    }
                    Logger.logVerbose(LOG_TAG, "Hiding soft keyboard on focus change");
                }

                KeyboardUtils.setSoftKeyboardVisibility(getShowSoftKeyboardRunnable(), mActivity, mActivity.getTerminalView(), hasFocus || textInputViewHasFocus);
            }
        });

        // Do not force show soft keyboard if termux-reload-settings command was run with hardware keyboard
        // or soft keyboard is to be hidden or is disabled
        if (!isReloadTermuxProperties && !noShowKeyboard) {
            // Request focus for TerminalView
            // Also show the keyboard, since onFocusChange will not be called if TerminalView already
            // had focus on startup to show the keyboard, like when opening url with context menu
            // "Select URL" long press and returning to Termux app with back button. This
            // will also show keyboard even if it was closed before opening url. #2111
            Logger.logVerbose(LOG_TAG, "Requesting TerminalView focus and showing soft keyboard");
            mActivity.getTerminalView().requestFocus();
            mActivity.getTerminalView().postDelayed(getShowSoftKeyboardRunnable(), 300);
        }
    }

    public void hideSoftKeyboard() {
        // Cancel a pending keyboard show so it cannot undo the hide after rotation.
        if (mShowSoftKeyboardRunnable != null)
            mActivity.getTerminalView().removeCallbacks(mShowSoftKeyboardRunnable);
        View focusedView = mActivity.getCurrentFocus();
        KeyboardUtils.hideSoftKeyboard(mActivity,
            focusedView != null ? focusedView : mActivity.getTerminalView());
    }

    private Runnable getShowSoftKeyboardRunnable() {
        if (mShowSoftKeyboardRunnable == null) {
            mShowSoftKeyboardRunnable = () -> {
                KeyboardUtils.showSoftKeyboard(mActivity, mActivity.getTerminalView());
            };
        }
        return mShowSoftKeyboardRunnable;
    }



    public void setTerminalCursorBlinkerState(boolean start) {
        if (start) {
            // If set/update the cursor blinking rate is successful, then enable cursor blinker
            if (mActivity.getTerminalView().setTerminalCursorBlinkerRate(mActivity.getProperties().getTerminalCursorBlinkRate()))
                mActivity.getTerminalView().setTerminalCursorBlinkerState(true, true);
            else
                Logger.logError(LOG_TAG,"Failed to start cursor blinker");
        } else {
            // Disable cursor blinker
            mActivity.getTerminalView().setTerminalCursorBlinkerState(false, true);
        }
    }



    public void shareSessionTranscript() {
        TerminalSession session = mActivity.getCurrentSession();
        if (session == null) return;

        String transcriptText = ShellUtils.getTerminalSessionTranscriptText(session, false, true);
        if (transcriptText == null) return;

        // See https://github.com/termux/termux-app/issues/1166.
        transcriptText = DataUtils.getTruncatedCommandOutput(transcriptText, DataUtils.TRANSACTION_SIZE_LIMIT_IN_BYTES, false, true, false).trim();
        ShareUtils.shareText(mActivity, mActivity.getString(R.string.title_share_transcript),
            transcriptText, mActivity.getString(R.string.title_share_transcript_with));
    }

    public void shareSelectedText() {
        String selectedText = mActivity.getTerminalView().getStoredSelectedText();
        if (DataUtils.isNullOrEmpty(selectedText)) return;
        ShareUtils.shareText(mActivity, mActivity.getString(R.string.title_share_selected_text),
            selectedText, mActivity.getString(R.string.title_share_selected_text_with));
    }

    public void showUrlSelection() {
        TerminalSession session = mActivity.getCurrentSession();
        if (session == null) return;

        String text = ShellUtils.getTerminalSessionTranscriptText(session, true, true);

        LinkedHashSet<CharSequence> urlSet = TermuxUrlUtils.extractUrls(text);
        if (urlSet.isEmpty()) {
            new AlertDialog.Builder(mActivity).setMessage(R.string.title_select_url_none_found).show();
            return;
        }

        final CharSequence[] urls = urlSet.toArray(new CharSequence[0]);
        Collections.reverse(Arrays.asList(urls)); // Latest first.

        // Click to copy url to clipboard:
        final AlertDialog dialog = new AlertDialog.Builder(mActivity).setItems(urls, (di, which) -> {
            String url = (String) urls[which];
            ShareUtils.copyTextToClipboard(mActivity, url, mActivity.getString(R.string.msg_select_url_copied_to_clipboard));
        }).setTitle(R.string.title_select_url_dialog).create();

        // Long press to open URL:
        dialog.setOnShowListener(di -> {
            ListView lv = dialog.getListView(); // this is a ListView with your "buds" in it
            lv.setOnItemLongClickListener((parent, view, position, id) -> {
                dialog.dismiss();
                String url = (String) urls[position];
                ShareUtils.openUrl(mActivity, url);
                return true;
            });
        });

        dialog.show();
    }

    public void reportIssueFromTranscript() {
        TerminalSession session = mActivity.getCurrentSession();
        if (session == null) return;

        final String transcriptText = ShellUtils.getTerminalSessionTranscriptText(session, false, true);
        if (transcriptText == null) return;

        MessageDialogUtils.showMessage(mActivity, TermuxConstants.TERMUX_APP_NAME + " Report Issue",
            mActivity.getString(R.string.msg_add_termux_debug_info),
            mActivity.getString(R.string.action_yes), (dialog, which) -> reportIssueFromTranscript(transcriptText, true),
            mActivity.getString(R.string.action_no), (dialog, which) -> reportIssueFromTranscript(transcriptText, false),
            null);
    }

    private void reportIssueFromTranscript(String transcriptText, boolean addTermuxDebugInfo) {
        Logger.showToast(mActivity, mActivity.getString(R.string.msg_generating_report), true);

        new Thread() {
            @Override
            public void run() {
                StringBuilder reportString = new StringBuilder();

                String title = TermuxConstants.TERMUX_APP_NAME + " Report Issue";

                reportString.append("## Transcript\n");
                reportString.append("\n").append(MarkdownUtils.getMarkdownCodeForString(transcriptText, true));
                reportString.append("\n##\n");

                if (addTermuxDebugInfo) {
                    reportString.append("\n\n").append(TermuxUtils.getAppInfoMarkdownString(mActivity, TermuxUtils.AppInfoMode.TERMUX_AND_PLUGIN_PACKAGES));
                } else {
                    reportString.append("\n\n").append(TermuxUtils.getAppInfoMarkdownString(mActivity, TermuxUtils.AppInfoMode.TERMUX_PACKAGE));
                }

                reportString.append("\n\n").append(AndroidUtils.getDeviceInfoMarkdownString(mActivity, true));

                if (TermuxBootstrap.isAppPackageManagerAPT()) {
                    String termuxAptInfo = TermuxUtils.geAPTInfoMarkdownString(mActivity);
                    if (termuxAptInfo != null)
                        reportString.append("\n\n").append(termuxAptInfo);
                }

                if (addTermuxDebugInfo) {
                    String termuxDebugInfo = TermuxUtils.getTermuxDebugMarkdownString(mActivity);
                    if (termuxDebugInfo != null)
                        reportString.append("\n\n").append(termuxDebugInfo);
                }

                String userActionName = UserAction.REPORT_ISSUE_FROM_TRANSCRIPT.getName();

                ReportInfo reportInfo = new ReportInfo(userActionName,
                    TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY_NAME, title);
                reportInfo.setReportString(reportString.toString());
                reportInfo.setReportStringSuffix("\n\n" + TermuxUtils.getReportIssueMarkdownString(mActivity));
                reportInfo.setReportSaveFileLabelAndPath(userActionName,
                    Environment.getExternalStorageDirectory() + "/" +
                        FileUtils.sanitizeFileName(TermuxConstants.TERMUX_APP_NAME + "-" + userActionName + ".log", true, true));

                ReportActivity.startReportActivity(mActivity, reportInfo);
            }
        }.start();
    }

    public void doPaste() {
        TerminalSession session = mActivity.getCurrentSession();
        if (session == null) return;
        if (!session.isRunning()) return;

        String text = ShareUtils.getTextStringFromClipboardIfSet(mActivity, true);
        if (text != null)
            session.getEmulator().paste(text);
    }

}
