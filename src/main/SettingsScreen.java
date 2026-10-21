package com.example.sandseller;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

/** Menu cai dat, mo bang phim O. */
public class SettingsScreen extends Screen {
    private final SandSellerClient mod;

    public SettingsScreen(SandSellerClient mod) {
        super(Text.literal("SandSeller"));
        this.mod = mod;
    }

    @Override
    protected void init() { build(); }

    private void build() {
        this.clearChildren();
        Config c = Config.I;
        int cx = this.width / 2;
        int y = Math.max(4, this.height / 2 - 160);
        final int STEP = 22;

        addBtn(cx - 130, y, 260, (mod.isRunning() ? "§aĐANG CHẠY §7(bấm để dừng)" : "§cĐANG TẮT §7(bấm để chạy)"),
                () -> { mod.toggle(); build(); });
        y += STEP;
        stepper(cx, y, "Bán khi đủ: §e" + c.sandLimit + " cát",
                () -> { c.sandLimit = Math.max(100, c.sandLimit - 100); Config.save(); build(); },
                () -> { c.sandLimit = Math.min(2300, c.sandLimit + 100); Config.save(); build(); });
        y += STEP;
        addBtn(cx - 130, y, 260, "Tự ăn: " + (c.autoEat ? "§aBẬT" : "§cTẮT"),
                () -> { c.autoEat = !c.autoEat; Config.save(); build(); });
        y += STEP;
        addBtn(cx - 130, y, 260, "Tự sửa xẻng bằng EXP: " + (c.autoRepair ? "§aBẬT" : "§cTẮT"),
                () -> { c.autoRepair = !c.autoRepair; Config.save(); build(); });
        y += STEP;
        stepper(cx, y, "Bắt đầu sửa khi xẻng ≤ §e" + c.repairStart + "%",
                () -> { c.repairStart = Math.max(5, c.repairStart - 5); Config.save(); build(); },
                () -> { c.repairStart = Math.min(c.repairStop - 5, c.repairStart + 5); Config.save(); build(); });
        y += STEP;
        stepper(cx, y, "Dừng sửa khi xẻng ≥ §e" + c.repairStop + "%",
                () -> { c.repairStop = Math.max(c.repairStart + 5, c.repairStop - 5); Config.save(); build(); },
                () -> { c.repairStop = Math.min(100, c.repairStop + 5); Config.save(); build(); });
        y += STEP;
        addBtn(cx - 130, y, 260, "Tự đánh quái: " + (c.attackMobs ? "§aBẬT" : "§cTẮT"),
                () -> { c.attackMobs = !c.attackMobs; Config.save(); build(); });
        y += STEP;
        stepper(cx, y, "Tầm đánh: §e" + c.attackRange + " block",
                () -> { c.attackRange = Math.max(2.0, c.attackRange - 0.5); Config.save(); build(); },
                () -> { c.attackRange = Math.min(4.5, c.attackRange + 0.5); Config.save(); build(); });
        y += STEP;
        stepper(cx, y, "Chờ màn hình ổn định: §e" + c.settleTicks + " tick",
                () -> { c.settleTicks = Math.max(2, c.settleTicks - 2); Config.save(); build(); },
                () -> { c.settleTicks = Math.min(60, c.settleTicks + 2); Config.save(); build(); });
        y += STEP;
        addBtn(cx - 130, y, 260, "Hiện thông tin trên màn hình: " + (c.hud ? "§aBẬT" : "§cTẮT"),
                () -> { c.hud = !c.hud; Config.save(); build(); });
        y += STEP;
        addBtn(cx - 130, y, 260, "Báo chi tiết (debug) trong chat: " + (c.debug ? "§aBẬT" : "§cTẮT"),
                () -> { c.debug = !c.debug; Config.save(); build(); });
        y += STEP;
        addLabel(cx - 130, y, 90, "Lệnh bán:");
        TextFieldWidget sell = new TextFieldWidget(this.textRenderer, cx - 35, y, 165, 20, Text.empty());
        sell.setMaxLength(40);
        sell.setText(c.sellCommand);
        sell.setChangedListener(s -> { c.sellCommand = s; Config.save(); });
        addDrawableChild(sell);
        y += STEP;
        addLabel(cx - 130, y, 90, "Lệnh shop:");
        TextFieldWidget shop = new TextFieldWidget(this.textRenderer, cx - 35, y, 165, 20, Text.empty());
        shop.setMaxLength(40);
        shop.setText(c.shopCommand);
        shop.setChangedListener(s -> { c.shopCommand = s; Config.save(); });
        addDrawableChild(shop);
        y += STEP + 4;
        addBtn(cx - 50, y, 100, "§cĐóng", this::close);
    }

    private void addBtn(int x, int y, int w, String text, Runnable r) {
        addDrawableChild(ButtonWidget.builder(Text.literal(text), b -> r.run()).dimensions(x, y, w, 20).build());
    }

    private void addLabel(int x, int y, int w, String text) {
        ButtonWidget b = ButtonWidget.builder(Text.literal("§7" + text), x2 -> { }).dimensions(x, y, w, 20).build();
        b.active = false;
        addDrawableChild(b);
    }

    private void stepper(int cx, int y, String label, Runnable minus, Runnable plus) {
        ButtonWidget l = ButtonWidget.builder(Text.literal("§7" + label), b -> { }).dimensions(cx - 130, y, 204, 20).build();
        l.active = false;
        addDrawableChild(l);
        addBtn(cx + 78, y, 24, "§c-", minus);
        addBtn(cx + 106, y, 24, "§a+", plus);
    }
}
