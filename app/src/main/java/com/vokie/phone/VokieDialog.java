package com.vokie.phone;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.PathInterpolator;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

final class VokieDialog extends Dialog {
    private static final int INK = Color.rgb(29, 33, 41);
    private static final int MUTED = Color.rgb(105, 115, 134);
    private static final int GREEN = Color.rgb(24, 160, 88);
    private static final int GREEN_DARK = Color.rgb(18, 128, 76);
    private static final int GREEN_PALE = Color.rgb(231, 248, 238);
    private static final int SURFACE = Color.rgb(244, 246, 249);
    private static final int CAUTION = Color.rgb(132, 90, 22);
    private static final int CAUTION_PALE = Color.rgb(255, 246, 224);
    private static final PathInterpolator MOTION =
            new PathInterpolator(0.2f, 0f, 0f, 1f);

    enum ActionTone {
        PRIMARY,
        CAUTION
    }

    static final class Builder {
        private final Context context;
        private String title;
        private String message;
        private String verificationCode;
        private String verificationHint;
        private String[] choices;
        private int selectedChoice = -1;
        private IntConsumer choiceListener;
        private String positiveLabel;
        private Runnable positiveAction;
        private ActionTone positiveTone = ActionTone.PRIMARY;
        private String negativeLabel;
        private Runnable negativeAction;
        private boolean cancelable = true;

        Builder(Context context) {
            this.context = context;
        }

        Builder setTitle(String title) {
            this.title = title;
            return this;
        }

        Builder setMessage(String message) {
            this.message = message;
            return this;
        }

        Builder setVerificationCode(String code, String hint) {
            verificationCode = code;
            verificationHint = hint;
            return this;
        }

        Builder setChoices(String[] choices, int selectedChoice, IntConsumer listener) {
            this.choices = choices;
            this.selectedChoice = selectedChoice;
            choiceListener = listener;
            return this;
        }

        Builder setPositiveButton(String label, Runnable action) {
            positiveLabel = label;
            positiveAction = action;
            return this;
        }

        Builder setPositiveButton(String label, ActionTone tone, Runnable action) {
            positiveLabel = label;
            positiveTone = tone;
            positiveAction = action;
            return this;
        }

        Builder setNegativeButton(String label, Runnable action) {
            negativeLabel = label;
            negativeAction = action;
            return this;
        }

        Builder setCancelable(boolean cancelable) {
            this.cancelable = cancelable;
            return this;
        }

        VokieDialog show() {
            VokieDialog dialog = new VokieDialog(context);
            dialog.configure(this);
            dialog.show();
            return dialog;
        }
    }

    private LinearLayout panel;

    private VokieDialog(Context context) {
        super(context);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
    }

    private void configure(Builder builder) {
        setCancelable(builder.cancelable);
        setCanceledOnTouchOutside(builder.cancelable);

        panel = new LinearLayout(getContext());
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(24), dp(24), dp(24), dp(20));
        panel.setBackground(roundRect(Color.WHITE, dp(24), 0, Color.TRANSPARENT));
        panel.setElevation(dp(18));

        TextView title = text(builder.title, 20, INK, Typeface.BOLD);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setLineSpacing(0, 1.08f);
        panel.addView(title, matchWrap());

        if (!TextUtils.isEmpty(builder.message)) {
            TextView message = text(builder.message, 14, MUTED, Typeface.NORMAL);
            message.setLineSpacing(dp(3), 1f);
            LinearLayout.LayoutParams messageParams = matchWrap();
            messageParams.topMargin = dp(10);
            panel.addView(message, messageParams);
        }

        if (!TextUtils.isEmpty(builder.verificationCode)) {
            addVerificationCode(builder.verificationCode, builder.verificationHint);
        }

        if (builder.choices != null) {
            addChoices(builder);
        }

        if (!TextUtils.isEmpty(builder.positiveLabel) ||
                !TextUtils.isEmpty(builder.negativeLabel)) {
            addActions(builder);
        }

        setContentView(panel);
    }

    private void addVerificationCode(String code, String hint) {
        LinearLayout codePanel = new LinearLayout(getContext());
        codePanel.setOrientation(LinearLayout.VERTICAL);
        codePanel.setGravity(Gravity.CENTER);
        codePanel.setPadding(dp(16), dp(18), dp(16), dp(16));
        codePanel.setBackground(roundRect(GREEN_PALE, dp(16), 0, Color.TRANSPARENT));

        TextView codeLabel = text("验证码", 12, GREEN_DARK, Typeface.BOLD);
        codeLabel.setGravity(Gravity.CENTER);
        codePanel.addView(codeLabel, matchWrap());

        TextView codeView = text(code, 28, INK, Typeface.BOLD);
        codeView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        codeView.setGravity(Gravity.CENTER);
        codeView.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams codeParams = matchWrap();
        codeParams.topMargin = dp(8);
        codePanel.addView(codeView, codeParams);

        if (!TextUtils.isEmpty(hint)) {
            TextView hintView = text(hint, 13, MUTED, Typeface.NORMAL);
            hintView.setGravity(Gravity.CENTER);
            hintView.setLineSpacing(dp(2), 1f);
            LinearLayout.LayoutParams hintParams = matchWrap();
            hintParams.topMargin = dp(10);
            codePanel.addView(hintView, hintParams);
        }

        LinearLayout.LayoutParams panelParams = matchWrap();
        panelParams.topMargin = dp(18);
        panel.addView(codePanel, panelParams);
    }

    private void addChoices(Builder builder) {
        LinearLayout choiceList = new LinearLayout(getContext());
        choiceList.setOrientation(LinearLayout.VERTICAL);
        List<ChoiceRow> rows = new ArrayList<>();

        for (int index = 0; index < builder.choices.length; index++) {
            int choiceIndex = index;
            ChoiceRow row = new ChoiceRow(getContext(), builder.choices[index]);
            row.setSelectedChoice(index == builder.selectedChoice);
            row.setOnClickListener(view -> {
                builder.selectedChoice = choiceIndex;
                for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
                    rows.get(rowIndex).setSelectedChoice(rowIndex == choiceIndex);
                }
                if (builder.choiceListener != null) {
                    builder.choiceListener.accept(choiceIndex);
                }
            });
            addPressFeedback(row);
            rows.add(row);
            LinearLayout.LayoutParams rowParams =
                    new LinearLayout.LayoutParams(-1, dp(54));
            if (index > 0) rowParams.topMargin = dp(6);
            choiceList.addView(row, rowParams);
        }

        ScrollView scroller = new ScrollView(getContext());
        scroller.setFillViewport(true);
        scroller.setVerticalScrollBarEnabled(false);
        scroller.addView(choiceList, matchWrap());

        int visibleHeight = Math.min(dp(234), builder.choices.length * dp(60));
        LinearLayout.LayoutParams listParams =
                new LinearLayout.LayoutParams(-1, visibleHeight);
        listParams.topMargin = dp(18);
        panel.addView(scroller, listParams);
    }

    private void addActions(Builder builder) {
        LinearLayout actions = new LinearLayout(getContext());
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);

        if (!TextUtils.isEmpty(builder.negativeLabel)) {
            Button negative = actionButton(builder.negativeLabel, false, ActionTone.PRIMARY);
            negative.setOnClickListener(view -> {
                dismiss();
                if (builder.negativeAction != null) builder.negativeAction.run();
            });
            LinearLayout.LayoutParams negativeParams =
                    new LinearLayout.LayoutParams(0, dp(48), 1f);
            actions.addView(negative, negativeParams);
        }

        if (!TextUtils.isEmpty(builder.positiveLabel)) {
            Button positive = actionButton(
                    builder.positiveLabel, true, builder.positiveTone);
            positive.setOnClickListener(view -> {
                dismiss();
                if (builder.positiveAction != null) builder.positiveAction.run();
            });
            LinearLayout.LayoutParams positiveParams =
                    new LinearLayout.LayoutParams(0, dp(48), 1f);
            if (actions.getChildCount() > 0) positiveParams.leftMargin = dp(10);
            actions.addView(positive, positiveParams);
        }

        LinearLayout.LayoutParams actionsParams = matchWrap();
        actionsParams.topMargin = dp(22);
        panel.addView(actions, actionsParams);
    }

    @Override
    public void show() {
        super.show();
        Window window = getWindow();
        if (window == null) return;
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.setDimAmount(0.3f);
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        WindowManager.LayoutParams attributes = window.getAttributes();
        attributes.gravity = Gravity.CENTER;
        attributes.width = Math.min(
                dp(360), getContext().getResources().getDisplayMetrics().widthPixels - dp(32));
        attributes.height = WindowManager.LayoutParams.WRAP_CONTENT;
        window.setAttributes(attributes);

        panel.setAlpha(0f);
        panel.setScaleX(0.98f);
        panel.setScaleY(0.98f);
        panel.setTranslationY(dp(8));
        panel.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .translationY(0f)
                .setDuration(190)
                .setInterpolator(MOTION)
                .start();
    }

    private Button actionButton(String label, boolean primary, ActionTone tone) {
        Button button = new Button(getContext());
        button.setText(label);
        button.setTextSize(15);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setAllCaps(false);
        button.setIncludeFontPadding(false);
        button.setMinWidth(0);
        button.setMinHeight(0);
        button.setStateListAnimator(null);
        button.setGravity(Gravity.CENTER);
        if (!primary) {
            button.setTextColor(INK);
            button.setBackground(roundRect(SURFACE, dp(12), 0, Color.TRANSPARENT));
        } else if (tone == ActionTone.CAUTION) {
            button.setTextColor(CAUTION);
            button.setBackground(roundRect(
                    CAUTION_PALE, dp(12), 0, Color.TRANSPARENT));
        } else {
            button.setTextColor(Color.WHITE);
            button.setBackground(roundRect(
                    GREEN_DARK, dp(12), 0, Color.TRANSPARENT));
        }
        addPressFeedback(button);
        return button;
    }

    private static void addPressFeedback(View view) {
        view.setOnTouchListener((pressedView, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                pressedView.animate().cancel();
                pressedView.animate()
                        .scaleX(0.96f)
                        .scaleY(0.96f)
                        .alpha(0.86f)
                        .setDuration(80)
                        .setInterpolator(MOTION)
                        .start();
            } else if (event.getActionMasked() == MotionEvent.ACTION_UP ||
                    event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                pressedView.animate().cancel();
                pressedView.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .alpha(1f)
                        .setDuration(140)
                        .setInterpolator(MOTION)
                        .start();
            }
            return false;
        });
    }

    private TextView text(String value, int size, int color, int style) {
        TextView view = new TextView(getContext());
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.DEFAULT, style);
        return view;
    }

    private GradientDrawable roundRect(int color, int radius, int strokeWidth, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        if (strokeWidth > 0) drawable.setStroke(dp(strokeWidth), stroke);
        return drawable;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(-1, -2);
    }

    private int dp(int value) {
        return Math.round(value * getContext().getResources().getDisplayMetrics().density);
    }

    private final class ChoiceRow extends LinearLayout {
        private final TextView label;
        private final TextView indicator;

        ChoiceRow(Context context, String value) {
            super(context);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(14), 0, dp(12), 0);
            setClickable(true);
            setFocusable(true);

            label = text(value, 15, INK, Typeface.BOLD);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            addView(label, new LinearLayout.LayoutParams(0, -2, 1f));

            indicator = text("", 13, Color.WHITE, Typeface.BOLD);
            indicator.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams indicatorParams =
                    new LinearLayout.LayoutParams(dp(24), dp(24));
            indicatorParams.leftMargin = dp(10);
            addView(indicator, indicatorParams);
        }

        void setSelectedChoice(boolean selected) {
            setBackground(roundRect(
                    selected ? GREEN_PALE : SURFACE,
                    dp(12), 0, Color.TRANSPARENT));
            indicator.setText(selected ? "\u2713" : "");
            indicator.setBackground(roundRect(
                    selected ? GREEN : Color.TRANSPARENT,
                    dp(12), 1, selected ? GREEN : Color.rgb(196, 202, 212)));
        }
    }
}
