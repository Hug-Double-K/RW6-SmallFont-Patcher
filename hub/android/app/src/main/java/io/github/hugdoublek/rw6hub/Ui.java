package io.github.hugdoublek.rw6hub;

/** 화면 공통 도우미 */
public final class Ui {
    private Ui() {}

    /** 버튼 위아래 여백 (붙어 보이지 않게). 부모가 정한 크기는 그대로 두고 여백만 더함 */
    public static void spaced(android.view.View v) {
        v.addOnAttachStateChangeListener(new android.view.View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(android.view.View x) {
                android.view.ViewGroup.LayoutParams lp = x.getLayoutParams();
                if (lp instanceof android.view.ViewGroup.MarginLayoutParams) {
                    int m = Math.round(5 * x.getResources().getDisplayMetrics().density);
                    android.view.ViewGroup.MarginLayoutParams mp = (android.view.ViewGroup.MarginLayoutParams) lp;
                    if (mp.topMargin < m || mp.bottomMargin < m) {
                        mp.topMargin = Math.max(mp.topMargin, m);
                        mp.bottomMargin = Math.max(mp.bottomMargin, m);
                        x.setLayoutParams(mp);
                    }
                }
                x.removeOnAttachStateChangeListener(this);
            }
            @Override public void onViewDetachedFromWindow(android.view.View x) {}
        });
    }
}
