package mas;

import arc.Core;
import arc.graphics.Color;
import arc.scene.actions.Actions;
import arc.scene.event.Touchable;
import arc.scene.style.Drawable;
import arc.scene.ui.Image;
import arc.scene.ui.Label;
import arc.scene.ui.layout.Table;
import arc.util.Align;
import arc.util.Time;
import mindustry.gen.Icon;
import mindustry.gen.Tex;

/**
 * Small popup in the bottom right corner, with a spinner and the time elapsed since it was shown.
 * Doesn't block the clicks on the rest of the UI. Must be used from the main thread.
 */
public class SyncPopup {
    private final Table root = new Table();
    private final Image spinner = new Image(Icon.refresh);
    private final Label title = new Label("");
    private final Label hint = new Label("");
    private final Label elapsed = new Label("");
    private long start;
    private boolean running;

    public SyncPopup() {
        root.setFillParent(true);
        root.touchable = Touchable.disabled;
        root.bottom().right();

        Table panel = new Table(Tex.pane);
        panel.margin(12f);
        panel.add(spinner).size(32f).padRight(12f);
        Table text = new Table();
        text.defaults().left();
        text.add(title).row();
        hint.setColor(Color.lightGray);
        text.add(hint).row();
        elapsed.setColor(Color.lightGray);
        text.add(elapsed);
        panel.add(text);
        root.add(panel).pad(10f);

        root.update(() -> {
            if (running) {
                spinner.setOrigin(Align.center);
                spinner.setRotation(-Time.globalTime * 6f);
                elapsed.setText(formatElapsed());
            }
            // Stay above the menus and dialogs opened after the popup
            root.toFront();
        });
    }

    /** Shows the popup with a spinning icon. Texts are bundle keys. */
    public void show(String titleKey, String hintKey) {
        root.clearActions();
        root.color.a = 1f;
        title.setText(Core.bundle.get(titleKey));
        hint.setText(hintKey == null ? "" : Core.bundle.get(hintKey));
        spinner.setDrawable(Icon.refresh);
        spinner.setColor(Color.white);
        start = Time.millis();
        running = true;
        if (root.parent == null) Core.scene.add(root);
    }

    /** Stops the spinner, shows a final message and hides the popup after a few seconds. */
    public void finish(String titleKey, boolean success) {
        running = false;
        Drawable icon = success ? Icon.ok : Icon.warning;
        spinner.setDrawable(icon);
        spinner.setRotation(0f);
        spinner.setColor(success ? Color.green : Color.scarlet);
        title.setText(Core.bundle.get(titleKey));
        hint.setText("");
        root.clearActions();
        root.actions(Actions.delay(3f), Actions.fadeOut(0.5f), Actions.remove());
    }

    public void hide() {
        running = false;
        root.clearActions();
        root.remove();
    }

    private String formatElapsed() {
        long seconds = Time.timeSinceMillis(start) / 1000;
        return String.format("%d:%02d", seconds / 60, seconds % 60);
    }
}
